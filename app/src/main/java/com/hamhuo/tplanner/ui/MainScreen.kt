package com.hamhuo.tplanner

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hamhuo.tplanner.ai.PlanExtractor
import com.hamhuo.tplanner.timeline.TimelineScreen
import com.hamhuo.tplanner.timeline.rememberTimelineState
import com.hamhuo.tplanner.ui.SyncLogPanel
import com.hamhuo.tplanner.ui.components.TPlannerPullToSync
import com.hamhuo.tplanner.ui.components.TPlannerSyncFeedback
import com.hamhuo.tplanner.ui.components.TPlannerSyncFeedbackPresentation
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens as Tokens
import com.hamhuo.tplanner.designsystem.TPlannerSyncFeedbackTone
import com.hamhuo.tplanner.drafts.DraftCommitResult
import com.hamhuo.tplanner.drafts.EventDraftRecovery
import com.hamhuo.tplanner.drafts.EventEditStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val LLM_LOG_TAG = "TplannerLLM"
private const val DAY_POLL_MILLIS = 30_000L

/** 换日提示停留多久：足够看清，又不占着屏幕。 */
private const val DAY_FLASH_MILLIS = 1_000L

/** 新卡片出现动画的时长：只让"刚刚确认的那几条"亮一下，之后恢复正常渲染。 */
private const val PLAN_REVEAL_MILLIS = 1_200L

private fun Throwable.locationForLog(): String {
    val frame = stackTrace.firstOrNull { it.className.startsWith("com.hamhuo.tplanner") }
        ?: stackTrace.firstOrNull()
        ?: return "unknown"
    return "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    eventStore: ScheduleItemStore,
    manager: SyncManager,
    planExtractor: PlanExtractor?,
    amapApiKey: String,
    initialEvents: List<ScheduleItem>,
    initialServerUrl: String,
    initialEventRecovery: EventDraftRecovery?,
) {
    val scope  = rememberCoroutineScope()
    val context = LocalContext.current
    var panelOpen  by remember { mutableStateOf(false) }
    var events     by remember { mutableStateOf(initialEvents) }
    val eventWriteMutex = remember { Mutex() }

    // 底部那张纸的正文：**只活在这一次会话里**。它不是记录，退出即销毁；
    // 真正落盘的只有用户在预览里明确确认过的日程。
    var planText by remember { mutableStateOf("") }

    // 主界面同屏只展示一天。
    val timelineState = rememberTimelineState(APP_ZONE, appToday())
    val displayedDay = timelineState.firstDay

    // 跨零点：界面本来就停在「今天」时跟着前进。没有草稿要提交，纯展示。
    var anchoredToday by remember { mutableStateOf(appToday()) }
    LaunchedEffect(displayedDay) {
        while (true) {
            val today = appToday()
            if (today != anchoredToday) {
                val previous = anchoredToday
                anchoredToday = today
                if (displayedDay == previous) timelineState.goToDate(today)
            }
            delay(DAY_POLL_MILLIS)
        }
    }

    // ── 底部那张纸：写 → 交给 TPlanner → 检查理解 → 落入时间 ──────────────
    // 状态机只有这几步（PlanStage）：Collapsed → Editing → Submitting → Preview → Committing
    // → Collapsed。同一个枚举决定纸长什么样，界面不自己猜。
    var planStage by remember { mutableStateOf(PlanStage.Collapsed) }
    var planUnsavedPrompt by remember { mutableStateOf(false) }
    // 预览数据：模型给出的动作 + 识别这一次的 requestId（回执对不上就丢弃）。
    var planTasks by remember { mutableStateOf<List<ReviewItem>>(emptyList()) }
    var planRequestId by remember { mutableStateOf("") }
    // 刚落入时间线的那几条：只让它们播一次出现动画，之后就恢复正常渲染。
    var revealedEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(revealedEventIds) {
        if (revealedEventIds.isEmpty()) return@LaunchedEffect
        delay(PLAN_REVEAL_MILLIS)
        revealedEventIds = emptySet()
    }

    /** 把纸收回去（预览确认、丢弃、无内容返回都走这里）。 */
    fun collapsePlan() {
        planStage = PlanStage.Collapsed
    }

    /** 摊开纸：直接进入可写状态。没有草稿、没有恢复——正文只活在这次会话里。 */
    fun openPlanEditor() {
        if (planStage != PlanStage.Collapsed) return
        planStage = PlanStage.Editing
    }

    /**
     * 模型回答之后：读到动作就进预览；没读到、或调用失败都退回编辑，原文一字不动。
     *
     * 模型内部行为（本地兜底、assumptions、地点）只进 Logcat：用户只需要看到
     * 「明确时间 / 预计 / 未排期」这三个能理解的结果层级。
     */
    fun runPlanExtraction(requestId: String, text: String) {
        val extractor = planExtractor
        if (extractor == null) {
            planStage = PlanStage.Editing
            Toast.makeText(context, R.string.ai_service_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        // 时间基准由客户端给：模型的提示词只允许用这一份锚点，绝不使用自己的训练时间。
        val now = Instant.now().atZone(APP_ZONE)
        scope.launch {
            try {
                // 传输是阻塞的（HttpURLConnection），放到 IO 线程；编排本身不碰界面。
                val extraction = withContext(Dispatchers.IO) {
                    extractor.extract(
                        text = text,
                        now = now,
                        requestId = requestId,
                        threadId = requestId,
                        locationHint = "",
                        onTelemetry = { Log.i(LLM_LOG_TAG, "phase=telemetry ${it.logLine()}") },
                    )
                }
                if (planRequestId != requestId || planStage != PlanStage.Submitting) return@launch
                val proposal = extraction.proposal
                Log.i(
                    LLM_LOG_TAG,
                    "phase=plan_route request=$requestId fallback=${extraction.usedFallback} " +
                        "actions=${proposal?.actions?.size ?: 0} " +
                        "assumptions=${proposal?.assumptions?.size ?: 0}",
                )
                val actions = proposal?.actions.orEmpty()
                if (actions.isEmpty()) {
                    // 没读到待办：退回那张还能写的纸，比让用户在一个空预览上做决定省事。
                    planStage = PlanStage.Editing
                    val understanding = proposal?.understanding.orEmpty()
                    Toast.makeText(
                        context,
                        when {
                            extraction.reason == "empty_input" -> context.getString(R.string.ai_empty_input)
                            understanding.isNotBlank() ->
                                context.getString(R.string.ai_no_task, understanding)

                            else -> context.getString(R.string.ai_service_unavailable)
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                // 低置信度的推测默认不勾选：预览里看得见，但不会顺手落盘。
                planTasks = actions.map { ReviewItem(action = it, selected = it.time.selectedByDefault) }
                planStage = PlanStage.Preview
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(LLM_LOG_TAG, "phase=plan_extract request=$requestId result=failed", error)
                if (planRequestId == requestId && planStage == PlanStage.Submitting) {
                    planStage = PlanStage.Editing
                    Toast.makeText(context, R.string.ai_service_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 对勾：把这段自然语言交给 TPlanner 整理。
     *
     * 此刻还没有创建任何日程，正文也不落盘——所以这里既不叫 save 也不写库：
     * 纸进入 Submitting，模型回答之前没有第二条路径。
     */
    fun submitPlanForPreview(text: String) {
        if (planStage != PlanStage.Editing) return
        planUnsavedPrompt = false
        planText = text
        val requestId = "plan-${UUID.randomUUID()}"
        planRequestId = requestId
        planStage = PlanStage.Submitting
        runPlanExtraction(requestId, text.trim())
    }

    /**
     * 确认添加：一次本地事务落盘，然后先让新卡片亮起、再把纸收回去。
     *
     * 顺序是刻意的——用户看到的因果是"刚才那句话被塞进了时间线"，而不是"预览消失了，
     * 列表刷新了"。
     */
    fun confirmPlan(selected: List<ReviewItem>) {
        val requestId = planRequestId.ifBlank { return }
        if (selected.isEmpty() || planStage != PlanStage.Preview) return
        val now = System.currentTimeMillis()
        // Every id derives from the persisted requestId, so confirming the same proposal again
        // rewrites exactly these records through the store's upsert path instead of duplicating them.
        val items = selected.mapIndexed { index, review ->
            val action = review.action
            val time = action.time
            val start = if (review.clearTime) null else time.start?.let(::parseContractInstant)
            val end = if (review.clearTime) null else time.end?.let(::parseContractInstant)
            // A task without a stated start keeps no start: never fabricate one. A deadline-only
            // action keeps its DUE and is stored as scheduled.
            val endAt = when {
                start == null && end == null -> Instant.EPOCH
                start == null -> end!!
                else -> end?.takeIf { it.isAfter(start) } ?: start
            }
            ScheduleItem(
                id = stablePlanTaskId("task:$index", requestId),
                title = action.title,
                type = "task",
                start = start ?: Instant.EPOCH,
                end = endAt,
                completed = false,
                checklist = action.subtasks.mapIndexed { checkIndex, subtask ->
                    CheckItem(stablePlanTaskId("task:$index:check:$checkIndex", requestId), subtask.text, false)
                },
                colorId = action.colorId,
                note = action.note,
                deletedAt = 0L,
                updatedAt = now,
                scheduled = start != null || end != null,
                // 识别不再采集位置，落盘的就是"未设置位置"。
                lat = 0.0,
                lng = 0.0,
            )
        }
        planStage = PlanStage.Committing
        scope.launch {
            try {
                // One local transaction for the whole accepted proposal.
                eventWriteMutex.withLock { eventStore.saveAll(items) }
                revealedEventIds = items.mapTo(mutableSetOf()) { it.id }
                collapsePlan()
                // 这句话已经交付了：底部回到"想做什么？"。
                planText = ""
                planTasks = emptyList()
                planRequestId = ""
                Toast.makeText(
                    context,
                    if (items.size == 1) context.getString(R.string.schedule_created_toast, items.first().title)
                    else context.getString(R.string.schedule_created_multiple_toast, items.size),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                Log.e(LLM_LOG_TAG, "request=$requestId phase=commit result=failed", e)
                planStage = PlanStage.Preview
                Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 丢弃：这段文字不要了。它本来就只活在这次会话里，没有任何副本要清理。 */
    fun discardPlanText() {
        planUnsavedPrompt = false
        planText = ""
        collapsePlan()
    }

    /**
     * 纸上的返回语义由这里统一决定，关闭按钮、返回键、预览里的"返回修改"都只是发起请求：
     * - Editing 且正文有改动 → 先问要不要保留；
     * - Editing 无改动 → 直接收纸；
     * - Submitting → 取消这次识别，回到还能写的纸（原文仍然在）；
     * - Preview → 回编辑，改一句话重新整理；
     * - Committing → 已经在写日程，忽略。
     */
    fun requestPlanExit() {
        when (planStage) {
            PlanStage.Collapsed -> Unit
            PlanStage.Editing ->
                if (planText.isNotBlank()) planUnsavedPrompt = true else collapsePlan()

            PlanStage.Submitting -> {
                // 让在途结果作废，再回到编辑。
                planRequestId = ""
                planStage = PlanStage.Editing
            }

            PlanStage.Preview -> planStage = PlanStage.Editing
            PlanStage.Committing -> Unit
        }
    }

    // ── Sync state ───────────────────────────────────────────────────────
    var serverUrl  by remember { mutableStateOf(initialServerUrl) }
    val syncOperation by SyncCoordinator.state.collectAsState()
    val syncStatus = syncOperation.phase.wireName
    var syncFeedback by remember { mutableStateOf<TPlannerSyncFeedbackPresentation?>(null) }
    var syncFeedbackGeneration by remember { mutableIntStateOf(0) }
    var presentedSyncOperationId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSyncLogs by remember { mutableStateOf(false) }
    val syncLogEntries by SyncLog.entries.collectAsState()
    val eventActions = remember(eventStore) {
        ScheduleItemActions(scope, context, eventStore, eventWriteMutex)
    }

    val syncedTemplate = stringResource(R.string.sync_success_with_name)
    val syncCompleteMessage = stringResource(R.string.sync_complete)
    val syncFailedMessage = stringResource(R.string.sync_failed)
    val unknownSyncError = stringResource(R.string.unknown_error)
    val syncSavedMessage = stringResource(R.string.sync_saved)
    val syncUploadingMessage = stringResource(R.string.sync_uploading)
    val syncUpdatingMessage = stringResource(R.string.sync_updating)
    val syncSendingMessage = stringResource(R.string.sync_sending)
    val syncFailedKeptLocalMessage = stringResource(R.string.sync_failed_kept_local)

    fun serverHost(url: String): String =
        try { java.net.URL(SyncManager.normalizeServerUrl(url)).host } catch (_: Exception) { url }

    val syncMsg = when (syncOperation.phase) {
        SyncPhase.IDLE -> ""
        SyncPhase.SAVED -> syncSavedMessage
        SyncPhase.UPLOADING -> syncUploadingMessage
        SyncPhase.UPDATING -> syncUpdatingMessage
        SyncPhase.SUCCESS -> syncedTemplate.format(serverHost(serverUrl))
        SyncPhase.ERROR -> listOfNotNull(
            syncOperation.errorCode,
            syncOperation.detail ?: unknownSyncError,
        ).joinToString(" · ")
    }

    fun requestSync(reason: SyncReason) {
        val requestedServerUrl = serverUrl
        SyncCoordinator.requestSync(reason) { report ->
            manager.saveServerUrl(requestedServerUrl)
            manager.syncAllOrThrow(requestedServerUrl)
            report(SyncPhase.UPDATING)
        }
    }

    val onSync: () -> Unit = { requestSync(SyncReason.USER_GESTURE) }

    LaunchedEffect(manager) {
        val requestedServerUrl = serverUrl
        SyncCoordinator.requestStartupSync { report ->
            manager.saveServerUrl(requestedServerUrl)
            manager.syncAllOrThrow(requestedServerUrl)
            report(SyncPhase.UPDATING)
        }
    }

    LaunchedEffect(syncOperation.operationId, syncOperation.phase) {
        val operationId = syncOperation.operationId ?: return@LaunchedEffect
        // 顶部 terminal 横幅只属于用户主动触发的手动同步(USER_GESTURE)。
        // 保存驱动走 SyncFeedbackBus 两阶段反馈;REMOTE_CHANGE / STARTUP 是后台
        // 收敛,不弹横幅 —— 否则自己保存引发的版本变化会再来一次「同步完成」双动画。
        val terminal = (syncOperation.phase == SyncPhase.SUCCESS || syncOperation.phase == SyncPhase.ERROR) &&
            syncOperation.reason == SyncReason.USER_GESTURE
        if (!terminal || presentedSyncOperationId == operationId) return@LaunchedEffect
        presentedSyncOperationId = operationId
        syncFeedbackGeneration++
        syncFeedback = TPlannerSyncFeedbackPresentation(
            generation = syncFeedbackGeneration,
            message = if (syncOperation.phase == SyncPhase.SUCCESS) {
                syncCompleteMessage
            } else {
                syncFailedMessage
            },
            tone = if (syncOperation.phase == SyncPhase.SUCCESS) {
                TPlannerSyncFeedbackTone.SUCCESS
            } else {
                TPlannerSyncFeedbackTone.ERROR
            },
        )
    }

    // PR C:交互热路径的两阶段反馈 —— 与收敛事务(上面的 SUCCESS/ERROR)解耦。
    // 本地 Room 提交 → Gold「正在同步…」;202 BROKER_PERSISTED → Teal「已同步」;
    // 真失败 → Red「同步失败,已保存在本机」。都不等 receipt/delta 收敛。
    LaunchedEffect(Unit) {
        SyncFeedbackBus.events.collect { event ->
            syncFeedbackGeneration++
            syncFeedback = TPlannerSyncFeedbackPresentation(
                generation = syncFeedbackGeneration,
                message = when (event) {
                    SyncFeedbackEvent.Sending -> syncSendingMessage
                    is SyncFeedbackEvent.CloudAccepted ->
                        syncedTemplate.format(event.serverHost)
                    is SyncFeedbackEvent.FailedLocally -> syncFailedKeptLocalMessage
                },
                tone = when (event) {
                    SyncFeedbackEvent.Sending -> TPlannerSyncFeedbackTone.ACCENT
                    is SyncFeedbackEvent.CloudAccepted -> TPlannerSyncFeedbackTone.SUCCESS
                    is SyncFeedbackEvent.FailedLocally -> TPlannerSyncFeedbackTone.ERROR
                },
            )
        }
    }

    // 主界面只有两面：今天（时间轴 + Plan 条）与 Inbox。没有底栏，
    // 两者之间的切换、翻日期、进设置全部收在右上角那一个控制器里。
    var showInbox by rememberSaveable { mutableStateOf(false) }
    var dockExpanded by remember { mutableStateOf(false) }
    var selectedViewKey by rememberSaveable { mutableStateOf(TaskView.Today.key) }
    val selectedView = TaskView.fromKey(selectedViewKey)
    var showViewSheet by remember { mutableStateOf(false) }
    var taskWidgetModalVisible by remember { mutableStateOf(false) }
    val viewSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Panel building blocks ────────────────────────────────────────────
    // 同步设置与同步日志：主界面唯一的浮层，落在右上角悬浮按钮下面。
    val syncOverlays: @Composable BoxScope.() -> Unit = {
        if (panelOpen) {
            SyncSettingsPanel(
                // 让开右上角悬浮的同步设置按钮。
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 8.dp),
                serverUrl = serverUrl,
                syncStatus = syncStatus,
                syncMsg = syncMsg,
                onUrlChange = { serverUrl = it },
                onClose = { panelOpen = false },
                onOpenLogs = { showSyncLogs = true },
                // 「连接」= 保存当前输入并立刻同步。requestSync 会先落盘再联网,
                // 所以这是输入框唯一的持久化入口。
                onConnect = {
                    panelOpen = false
                    requestSync(SyncReason.USER_GESTURE)
                },
                onReconnect = {
                    panelOpen = false
                    scope.launch {
                        runCatching {
                            // 重置会丢弃本机状态,所以必须先保存当前输入,
                            // 否则重新拉取时读到的还是空配置。
                            manager.saveServerUrl(serverUrl)
                                                    manager.resetConnection()
                        }.onFailure { Log.w("TPlannerSync", "Reconnect failed", it) }
                    }
                },
            )
        }
        if (showSyncLogs) {
            SyncLogPanel(
                entries = syncLogEntries,
                onClear = {
                    SyncLog.clear()
                },
                onClose = { showSyncLogs = false },
                // 让开右上角悬浮的同步设置按钮。
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 8.dp),
            )
        }
    }

    val initiallyRecoveredEvent = initialEventRecovery as? EventDraftRecovery.Recovered
    val initialEventStage = initiallyRecoveredEvent?.stage ?: EventEditStage.DETAIL
    var pendingNewItem by remember {
        mutableStateOf(
            initiallyRecoveredEvent?.event?.takeIf { initialEventStage == EventEditStage.NAMING }
        )
    }
    var editingItem by remember {
        mutableStateOf(
            initiallyRecoveredEvent?.event?.takeUnless { initialEventStage == EventEditStage.NAMING }
        )
    }
    var itemConflict by remember {
        mutableStateOf(initialEventRecovery as? EventDraftRecovery.Conflict)
    }

    fun revealNextEventDraft() {
        eventActions.revealNextDraft(
            onPending = { pendingNewItem = it },
            onEdit = { editingItem = it },
            onConflict = { itemConflict = it },
        )
    }

    fun beginNewItem(type: String) {
        eventActions.beginNewItem(type) { pendingNewItem = it }
    }

    fun beginTaskAt(start: Instant) {
        eventActions.beginNewItem(
            type = "task",
            initialStart = start,
        ) { pendingNewItem = it }
    }

    fun openItem(event: ScheduleItem) {
        eventActions.openItem(
            event = event,
            onPending = { pendingNewItem = it },
            onEdit = { editingItem = it },
            onConflict = { itemConflict = it },
        )
    }
    // 有东西占满屏幕（纸、控制器展开、编辑面板）时，应用级下拉手势必须让位。
    val fullScreenSurfaceOpen = planStage != PlanStage.Collapsed ||
        dockExpanded ||
        showViewSheet ||
        taskWidgetModalVisible ||
        pendingNewItem != null ||
        editingItem != null ||
        itemConflict != null ||
        // 未保存确认弹窗也压住底栏，避免两个出口同时可用。
        planUnsavedPrompt

    // 换日之后短暂报一下"你在哪一天"：主界面没有 header，方向感靠这一下。
    var dayFlashVisible by remember { mutableStateOf(false) }
    var dayFlashGeneration by remember { mutableIntStateOf(0) }
    var lastSeenDay by remember { mutableStateOf(displayedDay) }
    LaunchedEffect(displayedDay) {
        if (displayedDay != lastSeenDay) {
            lastSeenDay = displayedDay
            dayFlashGeneration++
        }
    }
    LaunchedEffect(dayFlashGeneration) {
        if (dayFlashGeneration == 0) return@LaunchedEffect
        dayFlashVisible = true
        delay(DAY_FLASH_MILLIS)
        dayFlashVisible = false
    }
    val dayFlashPattern = stringResource(R.string.date_pattern_month_day_weekday)
    val dayFlashFormatter = remember(dayFlashPattern) {
        DateTimeFormatter.ofPattern(dayFlashPattern)
    }

    val taskCardContent: @Composable () -> Unit = {
        TaskWidget(
            events = events,
            view = selectedView,
            onAddEvent = ::beginNewItem,
            onDelete = { eventId ->
                eventActions.softDelete(events, eventId) { events = it }
            },
            onItemClick = ::openItem,
            onViewPickerClick = { showViewSheet = true },
            onModalVisibilityChange = { taskWidgetModalVisible = it },
        )
    }

    // 合并后的主界面：一天的纵向时间轴 + 底部那张纸。点开纸才升起编辑面板，
    // 所以"看今天"和"写今天"不再是两个页面。
    // 顶部没有日期条，也没有加号：唯一的写入口是底部的 Plan。
    val dayCardContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            TimelineScreen(
                state = timelineState,
                events = events,
                revealedEventIds = revealedEventIds,
                onEventClick = ::openItem,
                onAddTaskAt = ::beginTaskAt,
                onEventMove = { event, newStart, newEnd ->
                    val updated = event.copy(
                        start = newStart,
                        end = newEnd,
                        updatedAt = System.currentTimeMillis(),
                    )
                    val nextEvents = events.map { current ->
                        if (current.id == updated.id) updated else current
                    }
                    events = nextEvents
                    scope.launch {
                        eventWriteMutex.withLock { eventStore.save(updated, original = event) }
                    }
                },
                planPanel = {
                    // 翻到别的日期时，底部浮出一个回今天的圆点（点一下就回来，顺带报一下日期）。
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ReturnToTodayDot(
                            visible = displayedDay != appToday(),
                            onClick = { timelineState.goToDate(appToday()) },
                            modifier = Modifier.padding(bottom = Tokens.Semantic.Spacing.Inline.dp),
                        )
                    }
                    MiniPlanBar(
                        planText = planText,
                        placeholder = stringResource(R.string.plan_edit_hint),
                        onOpen = ::openPlanEditor,
                        modifier = Modifier.padding(
                            start = Tokens.Semantic.Spacing.Block.dp,
                            end = Tokens.Semantic.Spacing.Block.dp,
                            top = Tokens.Semantic.Spacing.Inline.dp,
                            // 底栏已删除：底部只剩这条输入条 + 系统手势区。
                            bottom = Tokens.Semantic.Spacing.Block.dp,
                        ),
                    )
                },
            )
            // 设置入口只在右上角控制器里（DayControlDock），这里不再另外浮一个齿轮：
            // 否则两枚圆会叠在同一个角落。
            syncOverlays()
        }
    }

    // ── Main layout ──────────────────────────────────────────────────────
    // UI 铁律:只有用户主动下拉(USER_GESTURE)才显示「正在同步…」pill。
    // 本地保存走 SyncFeedbackBus 两阶段反馈;REMOTE_CHANGE / STARTUP / Worker
    // 全部静默 —— 否则自己保存引发的版本变化会再弹一次 syncing 动画。
    val isUserGestureSyncing =
        syncOperation.reason == SyncReason.USER_GESTURE && syncOperation.phase.isRunning
    // 根节点不加系统栏内边距：让位分给各自的使用方（主界面卡片用 systemBars，
    // Plan 面板用 systemBars ∪ ime），否则键盘顶起时会叠加两次，把布局顶乱。
    Box(Modifier.fillMaxSize().background(BG)) {
        TPlannerPullToSync(
            isSyncing = isUserGestureSyncing,
            operationId = syncOperation.operationId.takeIf {
                syncOperation.reason == SyncReason.USER_GESTURE
            },
            onSync = onSync,
            enabled = !fullScreenSurfaceOpen,
            modifier = Modifier.fillMaxSize(),
        ) {
            MainLayout(
                showInbox = showInbox,
                taskCard = taskCardContent,
                dayCard = dayCardContent,
            )
        }
        // 右上角：主页面控制能力都从这一个点长出来（今天/Inbox、上一天/下一天、设置）。
        DayControlDock(
            expanded = dockExpanded,
            onExpandedChange = { dockExpanded = it },
            destinationIcon = if (showInbox) Icons.Default.Today else Icons.Default.Inbox,
            destinationLabel = if (showInbox) {
                stringResource(R.string.timeline_today)
            } else {
                stringResource(R.string.list_inbox)
            },
            onDestination = {
                showInbox = !showInbox
                dockExpanded = false
            },
            onPreviousDay = { timelineState.previousPage() },
            onNextDay = { timelineState.nextPage() },
            onOpenSettings = {
                dockExpanded = false
                panelOpen = true
            },
            modifier = Modifier.fillMaxSize(),
            // 不在今天时，控制点自己带一个极小的日期。
            dayBadge = displayedDay.takeIf { it != appToday() }?.dayOfMonth?.toString(),
        )
        DayFlashLabel(
            text = displayedDay.format(dayFlashFormatter),
            visible = dayFlashVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = Tokens.Semantic.Spacing.Section.dp),
        )
        syncFeedback?.let { feedback ->
            TPlannerSyncFeedback(
                presentation = feedback,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 9.dp),
            )
        }
        // 纸浮在整个主界面之上（含导航岛），所以它必须是根 Box 的兄弟节点。
        // 写、整理中、预览、提交中都是这一张纸的连续状态。
        PlanSheet(
            stage = planStage,
            value = planText,
            onValueChange = { text -> planText = text },
            placeholder = stringResource(R.string.plan_edit_hint),
            onSubmit = { text -> submitPlanForPreview(text) },
            onExitRequest = ::requestPlanExit,
            modifier = Modifier.fillMaxSize(),
            preview = {
                PlanPreview(
                    tasks = planTasks,
                    committing = planStage == PlanStage.Committing,
                    onToggleSelected = { index ->
                        planTasks = planTasks.mapIndexed { position, item ->
                            if (position == index) item.copy(selected = !item.selected) else item
                        }
                    },
                    onToggleTime = { index ->
                        // "不要这个时间、保留任务"：复用既有的"未排期"语义，不新建机制。
                        planTasks = planTasks.mapIndexed { position, item ->
                            if (position == index) item.copy(clearTime = !item.clearTime) else item
                        }
                    },
                    onEditRequest = ::requestPlanExit,
                    onConfirm = ::confirmPlan,
                )
            },
        )
    }

    // 纸摊开时系统返回也交给纸：预览回落回编辑，编辑走未保存确认。
    // 编辑器自己那一层 BackHandler 更靠内，编辑态优先由它处理输入法。
    BackHandler(enabled = planStage != PlanStage.Collapsed) { requestPlanExit() }

    // ── Task view picker ───────────────────────────────────────────────
    if (showViewSheet) {
        TaskViewPickerSheet(
            selectedView = selectedView,
            sheetState = viewSheetState,
            onSelectView = { key -> selectedViewKey = key; showViewSheet = false },
            onDismiss = { showViewSheet = false },
        )
    }

    // ── Overlay panels ───────────────────────────────────────────────────
    pendingNewItem?.let { draftEvent ->
        NameInputSheet(
            type = draftEvent.type,
            initialText = draftEvent.title,
            onDraftChange = { name ->
                val updated = draftEvent.copy(title = name)
                pendingNewItem = updated
                eventStore.enqueueEventDraft(updated, EventEditStage.NAMING)
            },
            onCancel = {
                // Unmount first so no later naming callback can enqueue after the ordered delete.
                pendingNewItem = null
                scope.launch {
                    try {
                        eventWriteMutex.withLock { eventStore.discardEventDraft(draftEvent.id) }
                    } catch (_: Exception) {
                        pendingNewItem = draftEvent
                        Toast.makeText(context, "无法丢弃草稿，请重试", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onConfirm = { name ->
                val updated = draftEvent.copy(title = name)
                // Latch the naming UI before its DETAIL transition enters the same draft queue.
                pendingNewItem = null
                scope.launch {
                    try {
                        eventWriteMutex.withLock {
                            eventStore.saveEventDraft(updated, EventEditStage.DETAIL)
                        }
                        editingItem = updated
                    } catch (_: Exception) {
                        pendingNewItem = updated
                        Toast.makeText(context, "无法保存事项名称，请重试", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    editingItem?.let { ev ->
        ScheduleItemDetailScreen(
            event = ev,
            onDraftChange = { snapshot ->
                eventStore.enqueueEventDraft(snapshot, EventEditStage.DETAIL)
            },
            onSave = { updated, onFinished ->
                scope.launch {
                    try {
                        when (val result = eventWriteMutex.withLock {
                            eventStore.saveAndClearEventDraft(updated)
                        }) {
                            DraftCommitResult.Saved,
                            DraftCommitResult.AlreadySaved,
                            -> {
                                events = eventStore.getAll()
                                editingItem = null
                                onFinished(true)
                            }
                            is DraftCommitResult.Conflict -> {
                                itemConflict = EventDraftRecovery.Conflict(
                                    details = result.details,
                                    event = updated,
                                    stage = EventEditStage.DETAIL,
                                )
                                editingItem = null
                                Toast.makeText(
                                    context,
                                    "事项已在其他设备修改；草稿已保留，请选择处理方式",
                                    Toast.LENGTH_LONG,
                                ).show()
                                onFinished(true)
                            }
                        }
                    } catch (_: Exception) {
                        Toast.makeText(context, "保存失败，草稿仍已保留", Toast.LENGTH_LONG).show()
                        onFinished(false)
                    }
                }
            },
            onDelete = { onFinished ->
                eventActions.deleteFromEditor(
                    events = events,
                    eventId = ev.id,
                    onEventsChanged = { events = it },
                    onFinished = { deleted ->
                        if (deleted) editingItem = null
                        onFinished(deleted)
                    },
                )
            },
            onNoteSave = { updated, onFinished ->
                scope.launch {
                    try {
                        when (val result = eventWriteMutex.withLock {
                            eventStore.saveAndClearEventDraft(updated)
                        }) {
                            DraftCommitResult.Saved,
                            DraftCommitResult.AlreadySaved,
                            -> {
                                events = eventStore.getAll()
                                editingItem = events.firstOrNull { it.id == updated.id && it.deletedAt == 0L }
                                onFinished(true)
                            }
                            is DraftCommitResult.Conflict -> {
                                itemConflict = EventDraftRecovery.Conflict(
                                    details = result.details,
                                    event = updated,
                                    stage = EventEditStage.DETAIL,
                                )
                                editingItem = null
                                Toast.makeText(
                                    context,
                                    "事项已在其他设备修改；备注草稿已保留，请选择处理方式",
                                    Toast.LENGTH_LONG,
                                ).show()
                                onFinished(true)
                            }
                        }
                    } catch (_: Exception) {
                        Toast.makeText(context, "保存失败，备注草稿仍已保留", Toast.LENGTH_LONG).show()
                        onFinished(false)
                    }
                }
            },
        )
    }

    // ── Plan 未保存返回 ─────────────────────────────────────────────────
    // 草稿本身是持久的：这里问的不是"要不要落盘"，而是"要不要提交成这一天的正文"。
    if (planUnsavedPrompt) {
        PlanUnsavedDialog(
            onKeepEditing = { planUnsavedPrompt = false },
            onDiscard = ::discardPlanText,
            onSubmit = { submitPlanForPreview(planText) },
        )
    }

    itemConflict?.let { conflict ->
        val conflictDraft = conflict.event
        AlertDialog(
            onDismissRequest = { itemConflict = null },
            title = { Text("事项草稿冲突") },
            text = {
                Text(
                    if (conflictDraft == null) {
                        "原事项已删除或缺失。草稿仍保存在本机，你可以继续保留或使用当前状态。"
                    } else {
                        "其他设备已修改或删除原事项。可保留草稿、使用当前版本，或把草稿另存为新事项。"
                    }
                )
            },
            confirmButton = {
                if (conflictDraft != null) {
                    TextButton(onClick = {
                        eventActions.resolveConflictSaveAsCopy(
                            conflict,
                            { itemConflict = null; revealNextEventDraft() },
                            { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() },
                        )
                    }) { Text("另存副本") }
                } else {
                    TextButton(onClick = { itemConflict = null }) { Text("保留草稿") }
                }
            },
            dismissButton = {
                Row {
                    if (conflictDraft != null) {
                        TextButton(onClick = { itemConflict = null }) { Text("保留草稿") }
                    }
                    TextButton(onClick = {
                        eventActions.resolveConflictDiscard(
                            conflict,
                            { itemConflict = null; revealNextEventDraft() },
                            { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() },
                        )
                    }) { Text("使用当前版本") }
                }
            },
        )
    }
}

/**
 * 契约里的时间一律是带偏移量的 ISO 8601（`2026-09-17T19:30:00+08:00`）。
 * 只接受这一种写法：没有偏移量的字符串在两端会解析成不同时刻，宁可当作没有时间。
 */
private fun parseContractInstant(iso: String): Instant? = try {
    java.time.OffsetDateTime.parse(iso).toInstant()
} catch (_: Exception) { null }

private fun stablePlanTaskId(namespace: String, requestId: String): String =
    UUID.nameUUIDFromBytes(
        // 盐值属于"已经写进用户数据里的东西"：改名可以，这个字节串不能动，
        // 否则同一份提案会算出新 id，旧记录旁边多出一份重复任务。
        "tplanner:untangle:$namespace:$requestId".toByteArray(Charsets.UTF_8)
    ).toString()

