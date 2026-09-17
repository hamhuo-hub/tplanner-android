package com.hamhuo.tplanner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens as Tokens

/*
 * 当天 Note 的三个状态，和 TimelineScreen 同处一屏：
 *
 * - 收起：MiniNoteBar 贴在时间轴下方，只显示一行摘要。
 * - 展开：NoteSheet 从底部升起，顶部留出放 "Note" 字样的缝，上面是两个圆角。
 * - 未保存返回：NoteUnsavedDialog 在右下角给出对勾保存。
 *
 * 面板是不透明的：背后的时间轴一律不参与画面，避免半透明叠加导致的"花"。
 * 颜色、圆角、间距全部来自 design-assets/tokens，这里不持有任何原始色值。
 */

/** 摘要只取第一行有效内容，并去掉 Markdown 标记。 */
private val NOTE_LEADING_MARKUP = Regex("^\\s*(#{1,6}\\s*|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)+")
private val NOTE_INLINE_LINK = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
private val NOTE_EMPHASIS = Regex("[`*_~]")

private fun notePreviewLine(markdown: String): String {
    val firstLine = markdown.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
    return firstLine
        .replace(NOTE_LEADING_MARKUP, "")
        .replace(NOTE_INLINE_LINK, "$1")
        .replace(NOTE_EMPHASIS, "")
        .trim()
}

/**
 * 视觉矩形同时充当命中测试矩形：标题、正文间距与四周空白处的触摸都在这里被吞掉，
 * 不会落到底下的时间轴上。它必须画在可交互内容之下、时间轴之上。
 */
private fun Modifier.consumeAllPointerInput(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * 收起状态的 mini note：一行摘要加一个强调色入口。
 *
 * 整条都是点击目标，所以右侧圆形入口只是视觉提示，不再单独注册手势。
 */
@Composable
fun MiniNoteBar(
    noteText: String,
    placeholder: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = remember(noteText) { notePreviewLine(noteText) }
    val hasPreview = preview.isNotBlank()
    val shape = RoundedCornerShape(Tokens.Component.Note.MiniNoteRadius.dp)
    val openLabel = stringResource(R.string.note_open)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Tokens.Component.Note.MiniNoteHeight.dp)
            .clip(shape)
            .background(Color(Tokens.Component.Panel.RaisedBackground), shape)
            .border(Tokens.Component.Panel.EdgeWidth.dp, BORDER_SUBTLE, shape)
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
            .padding(
                start = Tokens.Semantic.Spacing.Block.dp,
                end = Tokens.Semantic.Spacing.Inline.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Semantic.Spacing.Block.dp),
    ) {
        Text(
            text = if (hasPreview) preview else placeholder,
            color = if (hasPreview) TEXT_PRIMARY else DIM,
            fontSize = PhoneTypography.PhoneMetaSp.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(Tokens.Platform.Phone.Geometry.TouchTargetMin.dp)
                .background(GOLD, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = ON_ACCENT,
                modifier = Modifier.size(Tokens.Platform.Phone.Geometry.IconSize.dp),
            )
        }
    }
}

/**
 * 展开的 Note：完全不透明，顶部的缝留给 "Note" 四个字母与关闭入口，
 * 面板本身只有上面两个圆角。
 *
 * 系统栏与输入法只在这一处让位（`systemBars ∪ ime`）：键盘顶起来时不会再和别处的
 * inset 叠加，滑入动画结束之后才请求焦点，输入法不会在动画中途把布局顶乱。
 *
 * [onExitRequest] 由调用方决定返回语义（未保存时弹窗），编辑器不自作主张保存。
 */
@Composable
fun NoteSheet(
    visible: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSave: (String) -> Unit,
    onExitRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val easing = CubicBezierEasing(
        Tokens.Semantic.Motion.Easing[0],
        Tokens.Semantic.Motion.Easing[1],
        Tokens.Semantic.Motion.Easing[2],
        Tokens.Semantic.Motion.Easing[3],
    )
    Box(modifier) {
        // 不透明的底：整屏都用画布色盖住，背后没有任何时间轴透出来。
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Tokens.Semantic.Motion.Standard.toInt())),
            exit = fadeOut(tween(Tokens.Semantic.Motion.Fast.toInt())),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(Tokens.Semantic.Color.Canvas))
                    .consumeAllPointerInput(),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                animationSpec = tween(Tokens.Semantic.Motion.Slow.toInt(), easing = easing),
                initialOffsetY = { fullHeight -> fullHeight },
            ),
            exit = slideOutVertically(
                animationSpec = tween(Tokens.Semantic.Motion.Standard.toInt(), easing = easing),
                targetOffsetY = { fullHeight -> fullHeight },
            ),
        ) {
            // 动画期间 currentState != targetState：到位之后才让编辑器抢焦点。
            // 收起时 visible 已经为 false，退出动画结束时也不会再把输入法弹回来。
            val settled = visible && transition.currentState == transition.targetState
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
            ) {
                NoteSheetHeader(onClose = onExitRequest)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(
                        topStart = Tokens.Component.Note.SheetRadius.dp,
                        topEnd = Tokens.Component.Note.SheetRadius.dp,
                    ),
                    color = SURFACE,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        MarkdownEditor(
                            value = value,
                            onValueChange = onValueChange,
                            placeholder = placeholder,
                            onSaveAndClose = onSave,
                            onExitRequest = onExitRequest,
                            autoFocus = settled,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(
                                start = Tokens.Semantic.Spacing.Section.dp,
                                end = Tokens.Semantic.Spacing.Section.dp,
                                top = Tokens.Semantic.Spacing.Inline.dp,
                                bottom = Tokens.Semantic.Spacing.Section.dp,
                            ),
                        )
                        NoteSaveBar(value = value, onSave = onSave)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteSheetHeader(onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(Tokens.Component.Note.SheetTopGap.dp)) {
        HorizontalDivider(
            color = BORDER_SUBTLE,
            thickness = Tokens.Semantic.Stroke.Control.dp,
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Tokens.Semantic.Spacing.Block.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 与关闭按钮等宽的占位，保证 "Note" 在视觉上居中。
            Spacer(Modifier.size(Tokens.Platform.Phone.Geometry.TouchTargetMin.dp))
            Text(
                text = stringResource(R.string.note_sheet_title),
                color = TEXT_PRIMARY,
                fontSize = PhoneTypography.PhoneTitleSp.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = DIM,
                    modifier = Modifier.size(Tokens.Platform.Phone.Geometry.IconSize.dp),
                )
            }
        }
    }
}

/**
 * 面板右下角的对勾：保存并收起。
 *
 * 没有第二个按钮：note 的正文会在保存时自动交给 AI 识别成日程，
 * 所以既不需要"提取"按钮，也不需要复核界面。
 */
@Composable
private fun NoteSaveBar(value: String, onSave: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.Component.Note.SheetBottomBarHeight.dp)
            .padding(horizontal = Tokens.Semantic.Spacing.Block.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SaveCheckButton(onClick = { onSave(value) })
    }
}

@Composable
private fun SaveCheckButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(Tokens.Platform.Phone.Geometry.TouchTargetMin.dp)
            .background(GOLD, CircleShape),
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = stringResource(R.string.note_save),
            tint = ON_ACCENT,
            modifier = Modifier.size(Tokens.Platform.Phone.Geometry.IconSize.dp),
        )
    }
}

/**
 * 未保存返回时的确认弹窗。
 *
 * 「继续编辑」在左下角；右下角是丢弃与对勾保存，对勾是唯一的主操作。
 */
@Composable
fun NoteUnsavedDialog(
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        containerColor = SURFACE,
        icon = {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = DIM,
            )
        },
        title = { Text(stringResource(R.string.note_unsaved_title)) },
        text = { Text(stringResource(R.string.note_unsaved_message)) },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Semantic.Spacing.Inline.dp),
            ) {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.note_discard), color = RED)
                }
                SaveCheckButton(onClick = onSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text(stringResource(R.string.note_keep_editing), color = DIM)
            }
        },
    )
}
