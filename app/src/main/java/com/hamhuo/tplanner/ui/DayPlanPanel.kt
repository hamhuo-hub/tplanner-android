package com.hamhuo.tplanner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
 * 当天 Plan（底部那张纸）的三个状态，和 TimelineScreen 同处一屏：
 *
 * - 收起：MiniPlanBar 贴在时间轴下方，只显示一行摘要。
 * - 展开：PlanSheet 从底部升起，顶部留出放 "Plan" 字样的缝，上面是两个圆角。
 * - 未保存返回：PlanUnsavedDialog 在右下角给出对勾保存。
 *
 * 面板是不透明的：背后的时间轴一律不参与画面，避免半透明叠加导致的"花"。
 * 颜色、圆角、间距全部来自 design-assets/tokens，这里不持有任何原始色值。
 */

/** 摘要只取第一行有效内容，并去掉 Markdown 标记。 */
private val PLAN_LEADING_MARKUP = Regex("^\\s*(#{1,6}\\s*|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)+")
private val PLAN_INLINE_LINK = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
private val PLAN_EMPHASIS = Regex("[`*_~]")

private fun planPreviewLine(markdown: String): String {
    val firstLine = markdown.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
    return firstLine
        .replace(PLAN_LEADING_MARKUP, "")
        .replace(PLAN_INLINE_LINK, "$1")
        .replace(PLAN_EMPHASIS, "")
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
 * 收起状态的 mini plan 条：一行摘要加一个强调色入口。
 *
 * 整条都是点击目标，所以右侧圆形入口只是视觉提示，不再单独注册手势。
 */
@Composable
fun MiniPlanBar(
    planText: String,
    placeholder: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = remember(planText) { planPreviewLine(planText) }
    val hasPreview = preview.isNotBlank()
    val shape = RoundedCornerShape(Tokens.Component.Plan.BarRadius.dp)
    val openLabel = stringResource(R.string.plan_open)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Tokens.Component.Plan.BarHeight.dp)
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

/** 纸在"交给 TPlanner"那一刻收拢的比例：很小的物理反馈，不是特效。 */
private const val SubmittingScale = 0.985f

/** 底部那张纸的状态机。界面不自己猜当前在哪一步，MainScreen 持有它。 */
internal enum class PlanStage {
    /** 只剩底部 mini 条。 */
    Collapsed,

    /** 纸摊开、可写：键盘与光标都在。 */
    Editing,

    /** 已交给 TPlanner：正文锁定，纸轻微收拢，等模型回答。 */
    Submitting,

    /** 整理结果长在同一张纸上，等用户确认。 */
    Preview,

    /** 用户已确认，正在写入日程。 */
    Committing,
}

/**
 * 展开的 Plan：完全不透明，顶部的缝留给 "Plan" 四个字母与关闭入口，
 * 面板本身只有上面两个圆角。
 *
 * 从 Edit 到 Preview 始终是**同一张纸**在继续，而不是"进入 AI 页面"：
 * - [PlanStage.Editing]：能写的纸；
 * - [PlanStage.Submitting]：正文锁定、光标消失，纸收拢到 [SubmittingScale]，
 *   底部对勾换成一个很小的进行状态，纸面写「正在整理…」；
 * - [PlanStage.Preview] / [PlanStage.Committing]：整理结果由 [preview] 插槽画在纸上。
 *
 * 系统栏与输入法只在这一处让位（`systemBars ∪ ime`）：键盘顶起来时不会再和别处的
 * inset 叠加，滑入动画结束之后才请求焦点，输入法不会在动画中途把布局顶乱。
 *
 * [onExitRequest] 由调用方决定返回语义（未保存时弹窗、预览时回编辑）。
 */
@Composable
// internal 与 TimelineScreen 同一处理：公开函数不能暴露 internal 的 PlanStage。
internal fun PlanSheet(
    stage: PlanStage,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: (String) -> Unit,
    onExitRequest: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
) {
    val easing = CubicBezierEasing(
        Tokens.Semantic.Motion.Easing[0],
        Tokens.Semantic.Motion.Easing[1],
        Tokens.Semantic.Motion.Easing[2],
        Tokens.Semantic.Motion.Easing[3],
    )
    val visible = stage != PlanStage.Collapsed
    val paperScale by animateFloatAsState(
        targetValue = if (stage == PlanStage.Submitting) SubmittingScale else 1f,
        animationSpec = tween(Tokens.Semantic.Motion.Standard.toInt(), easing = easing),
        label = "planPaperScale",
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
                PlanSheetHeader(onClose = onExitRequest)
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = paperScale
                            scaleY = paperScale
                        },
                    shape = RoundedCornerShape(
                        topStart = Tokens.Component.Plan.SheetRadius.dp,
                        topEnd = Tokens.Component.Plan.SheetRadius.dp,
                    ),
                    color = SURFACE,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        // 三种形态在同一张纸里就地换，不换页：写下的话 → 整理中 → 整理结果。
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Crossfade(
                                targetState = stage,
                                animationSpec = tween(Tokens.Semantic.Motion.Standard.toInt(), easing = easing),
                                label = "planPaperBody",
                            ) { current ->
                                when (current) {
                                    PlanStage.Editing -> MarkdownEditor(
                                        value = value,
                                        onValueChange = onValueChange,
                                        placeholder = placeholder,
                                        onSaveAndClose = onSubmit,
                                        onExitRequest = onExitRequest,
                                        autoFocus = settled && current == PlanStage.Editing,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PlanContentPadding,
                                    )

                                    PlanStage.Submitting -> PlanProcessingBody(
                                        text = value,
                                        modifier = Modifier.fillMaxSize(),
                                    )

                                    else -> Column(
                                        Modifier
                                            .fillMaxSize()
                                            .padding(
                                                start = Tokens.Semantic.Spacing.Section.dp,
                                                end = Tokens.Semantic.Spacing.Section.dp,
                                                top = Tokens.Semantic.Spacing.Inline.dp,
                                            ),
                                    ) {
                                        // 刚才那句话留在纸上当上下文：用户核对的是"我写的这行字"
                                        // 被理解成了什么，而不是凭空出现的一列任务。
                                        if (value.isNotBlank()) {
                                            Text(
                                                value,
                                                color = DIM,
                                                fontSize = PhoneTypography.PhoneSupportingSp.sp,
                                                lineHeight = PhoneTypography.PhoneSupportingLineHeightSp.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(bottom = Tokens.Semantic.Spacing.Block.dp),
                                            )
                                        }
                                        Box(Modifier.weight(1f).fillMaxWidth()) { preview() }
                                    }
                                }
                            }
                        }
                        // 预览自带确认区，只有纸还能写或正在整理时才显示底部那一个动作。
                        if (stage == PlanStage.Editing || stage == PlanStage.Submitting) {
                            PlanSubmitBar(stage = stage, value = value, onSubmit = onSubmit)
                        }
                    }
                }
            }
        }
    }
}

private val PlanContentPadding = PaddingValues(
    start = Tokens.Semantic.Spacing.Section.dp,
    end = Tokens.Semantic.Spacing.Section.dp,
    top = Tokens.Semantic.Spacing.Inline.dp,
    bottom = Tokens.Semantic.Spacing.Section.dp,
)

/**
 * 整理中的纸面：原文完整保留（这是用户刚写下的那句话），下面只写「正在整理…」。
 *
 * 没有转圈的 AI 加载页、没有进度百分比：用户看到的应该是"我的纸被拿去整理了"。
 */
@Composable
private fun PlanProcessingBody(text: String, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(PlanContentPadding),
        verticalArrangement = Arrangement.spacedBy(Tokens.Semantic.Spacing.Block.dp),
    ) {
        Text(
            text,
            color = TEXT_EDITOR,
            fontSize = PhoneTypography.PhoneTaskTitleSp.sp,
            lineHeight = PhoneTypography.PhoneBodyLineHeightSp.sp,
        )
        Text(
            stringResource(R.string.plan_processing),
            color = DIM,
            fontSize = PhoneTypography.PhoneMetaSp.sp,
        )
    }
}

@Composable
private fun PlanSheetHeader(onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(Tokens.Component.Plan.SheetTopGap.dp)) {
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
            // 与关闭按钮等宽的占位，保证 "Plan" 在视觉上居中。
            Spacer(Modifier.size(Tokens.Platform.Phone.Geometry.TouchTargetMin.dp))
            Text(
                text = stringResource(R.string.plan_sheet_title),
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
 * 纸底部的那一个动作。
 *
 * 对勾的语义不是"保存"：点击时还没有任何日程被创建，只是把这段话交给 TPlanner 整理，
 * 所以提交之后同一个位置变成很小的进行状态，而不是变成"已保存"。
 */
@Composable
private fun PlanSubmitBar(stage: PlanStage, value: String, onSubmit: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.Component.Plan.SheetBottomBarHeight.dp)
            .padding(horizontal = Tokens.Semantic.Spacing.Block.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stage == PlanStage.Submitting) {
            CircularProgressIndicator(
                color = ACCENT_TEXT,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .padding(end = Tokens.Semantic.Spacing.Block.dp)
                    .size(Tokens.Platform.Phone.Geometry.IconSize.dp),
            )
        } else {
            SubmitCheckButton(onClick = { onSubmit(value) })
        }
    }
}

@Composable
private fun SubmitCheckButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(Tokens.Platform.Phone.Geometry.TouchTargetMin.dp)
            .background(GOLD, CircleShape),
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = stringResource(R.string.plan_submit),
            tint = ON_ACCENT,
            modifier = Modifier.size(Tokens.Platform.Phone.Geometry.IconSize.dp),
        )
    }
}

/**
 * 未保存返回时的确认弹窗。
 *
 * 「继续编辑」在左下角；右下角是丢弃与对勾（交给 TPlanner），对勾是唯一的主操作。
 */
@Composable
fun PlanUnsavedDialog(
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
    onSubmit: () -> Unit,
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
        title = { Text(stringResource(R.string.plan_unsaved_title)) },
        text = { Text(stringResource(R.string.plan_unsaved_message)) },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Semantic.Spacing.Inline.dp),
            ) {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.plan_discard), color = RED)
                }
                SubmitCheckButton(onClick = onSubmit)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text(stringResource(R.string.plan_keep_editing), color = DIM)
            }
        },
    )
}
