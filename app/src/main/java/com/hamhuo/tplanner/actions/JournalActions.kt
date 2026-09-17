package com.hamhuo.tplanner

import android.content.Context
import android.widget.Toast
import com.hamhuo.tplanner.drafts.DraftCommitResult
import com.hamhuo.tplanner.drafts.DraftConflict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class JournalConflictPrompt(val details: DraftConflict) {
    val date: String get() = details.target.entityId
    val draftText: String get() = details.draftContent
}

class JournalActions(
    private val scope: CoroutineScope,
    private val context: Context,
    private val store: JournalStore,
    private val mutex: Mutex,
    private val getDateKey: () -> String,
    private val getContent: () -> String,
    private val setContent: (String) -> Unit,
    private val getHasDraft: () -> Boolean,
    private val setHasDraft: (Boolean) -> Unit,
    private val getConflict: () -> JournalConflictPrompt?,
    private val setConflict: (JournalConflictPrompt?) -> Unit,
) {
    fun saveDraft(text: String) {
        val dateKey = getDateKey()
        setHasDraft(true)
        store.enqueueDraft(dateKey, text)
    }

    fun commitDraft(text: String) {
        val dateKey = getDateKey()
        setHasDraft(true)
        store.enqueueDraft(dateKey, text)
        scope.launch {
            try {
                val result = mutex.withLock {
                    store.commitDraft(dateKey, text)
                }
                when (result) {
                    DraftCommitResult.Saved,
                    DraftCommitResult.AlreadySaved,
                    -> if (getDateKey() == dateKey) setHasDraft(false)
                    is DraftCommitResult.Conflict -> {
                        if (getDateKey() == dateKey) setHasDraft(true)
                        setConflict(JournalConflictPrompt(result.details))
                        Toast.makeText(
                            context,
                            "内容已在其他设备修改，当前草稿已安全保留",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (_: Exception) {
                if (getDateKey() == dateKey) setHasDraft(true)
                Toast.makeText(context, "保存失败，草稿仍保留在本机", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 丢弃当天未提交的改动（未保存弹窗里的「丢弃」）。
     *
     * 与冲突处理不同，这里没有服务器冲突要解决：只是把本机排队中的那份文档拿掉，
     * 让已安装的记录重新生效。
     */
    fun discardDraft() {
        val dateKey = getDateKey()
        scope.launch {
            try {
                mutex.withLock { store.discardDraft(dateKey) }
                if (getDateKey() == dateKey) {
                    setHasDraft(false)
                    setContent(store.get(dateKey))
                }
            } catch (_: Exception) {
                Toast.makeText(context, "无法丢弃草稿，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun resolveOverwrite(details: DraftConflict) {
        val overwritten = try {
            kotlinx.coroutines.runBlocking {
                mutex.withLock { store.overwriteDraft(details) }
            }
        } catch (_: Exception) { false }
        if (overwritten) {
            if (details.target.entityId == getDateKey()) {
                setContent(details.draftContent)
                setHasDraft(false)
            }
            setConflict(null)
            Toast.makeText(context, "已采用当前草稿", Toast.LENGTH_SHORT).show()
        }
    }

    fun resolveDiscard(details: DraftConflict) {
        scope.launch {
            try {
                mutex.withLock { store.discardDraft(details) }
                setConflict(null)
                if (details.target.entityId == getDateKey()) setHasDraft(false)
            } catch (_: Exception) {
                Toast.makeText(context, "操作失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
