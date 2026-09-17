package com.hamhuo.tplanner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens as Tokens

/**
 * 收起时只有一枚控制点；展开后是同一枚点长出来的控制组。
 *
 * 尺寸是三个功能块加间距算出来的（44 + 10 + 89 + 10 + 48 + 上下 8），
 * 换内容时保持这个算式一致，容器就不会在动画中途跳动。
 */
private val DockCollapsedSize = 44.dp
private val DockExpandedWidth = 128.dp
private val DockExpandedHeight = 216.dp
private val DockChildGap = 10.dp
private val DockActionHeight = 44.dp

/**
 * 右上角的主页面控制器。
 *
 * 它不再代表"设置"，而是**当前页面的控制器**：Inbox / 日期 / 设置都从同一个物理点长出来。
 *
 * 动画只有**一个** `Transition(expanded)`：容器尺寸、圆角、子项缩放与透明度全部由它驱动，
 * 所以看起来是"按钮本身变成了控制组"，而不是"四个按钮冒出来"。关闭时完全反向。
 *
 * 三个功能块按逻辑关系分组：
 * - Inbox（或回到今天）与设置是独立行为，各自一枚圆；
 * - `‹ / ›` 属于同一条轴，做成连体胶囊——有关系的动作在空间上就是连着的。
 */
@Composable
fun DayControlDock(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    destinationLabel: String,
    onDestination: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    dayBadge: String? = null,
) {
    val transition = updateTransition(targetState = expanded, label = "dayControl")
    val width by transition.animateDp(label = "dockWidth") {
        if (it) DockExpandedWidth else DockCollapsedSize
    }
    val height by transition.animateDp(label = "dockHeight") {
        if (it) DockExpandedHeight else DockCollapsedSize
    }
    val radius by transition.animateDp(label = "dockRadius") {
        if (it) Tokens.Semantic.Radius.Card.dp else DockCollapsedSize / 2
    }
    // Transition.animate* 没有 animationSpec 参数：每条曲线走 transitionSpec。
    val contentAlpha by transition.animateFloat(
        transitionSpec = { tween(Tokens.Semantic.Motion.Standard.toInt()) },
        label = "dockContent",
    ) { if (it) 1f else 0f }
    val triggerAlpha by transition.animateFloat(
        transitionSpec = { tween(Tokens.Semantic.Motion.Fast.toInt()) },
        label = "dockTrigger",
    ) { if (it) 0f else 1f }
    val contentScale = 0.92f + 0.08f * contentAlpha

    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        // 展开时点空白处收起；收起时这一层不存在，时间轴手势不受影响。
        if (expanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = stringResource(R.string.day_control_close),
                        role = Role.Button,
                    ) { onExpandedChange(false) },
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = Tokens.Semantic.Spacing.Block.dp, end = Tokens.Semantic.Spacing.Block.dp)
                .size(width = width, height = height)
                .clip(RoundedCornerShape(radius))
                .background(Color(Tokens.Component.Panel.RaisedBackground))
                .border(
                    Tokens.Semantic.Stroke.Control.dp,
                    BORDER_SUBTLE,
                    RoundedCornerShape(radius),
                ),
        ) {
            // ── 收起：整枚控制点 ────────────────────────────────────────────
            if (!expanded) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = triggerAlpha }
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = stringResource(R.string.day_control_open),
                            role = Role.Button,
                        ) { onExpandedChange(true) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (dayBadge != null) {
                        // 不在今天时，控制点自己带一个极小的日期：方向感不靠 header。
                        Text(
                            dayBadge,
                            color = TEXT_PRIMARY,
                            fontSize = PhoneTypography.PhoneMetaSp.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Icon(
                            Icons.Default.GridView,
                            contentDescription = null,
                            tint = DIM,
                            modifier = Modifier.size(DockTriggerIcon),
                        )
                    }
                }
            }

            // ── 展开：三个功能块 ────────────────────────────────────────────
            if (expanded || contentAlpha > 0.01f) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = contentAlpha
                            scaleX = contentScale
                            scaleY = contentScale
                        }
                        .padding(Tokens.Semantic.Spacing.InlineTight.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(DockChildGap),
                ) {
                    DockPill(label = destinationLabel, onClick = onDestination)
                    DayStepper(onPreviousDay = onPreviousDay, onNextDay = onNextDay)
                    DockRoundAction(
                        icon = Icons.Default.Settings,
                        description = stringResource(R.string.sync_server_title),
                        onClick = onOpenSettings,
                    )
                }
            }
        }
    }
}

private val DockTriggerIcon = 20.dp

/** 独立动作：圆角胶囊，文字是它唯一的说明。 */
@Composable
private fun DockPill(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Semantic.Radius.Control.dp)
    Box(
        modifier = Modifier
            .width(DockExpandedWidth - Tokens.Semantic.Spacing.InlineTight.dp * 2)
            .height(DockActionHeight)
            .clip(shape)
            .background(Color(Tokens.Component.Button.Secondary.Background), shape)
            .border(Tokens.Semantic.Stroke.Control.dp, Color(Tokens.Component.Button.Secondary.Border), shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = TEXT_PRIMARY,
            fontSize = PhoneTypography.PhoneMetaSp.sp,
            maxLines = 1,
        )
    }
}

/**
 * 日期导航：两个动作属于同一条轴，所以做成连体胶囊——上圆角 + 下圆角 + 中间一条分隔线，
 * 中间没有第二个容器。以后要支持长按连续翻日，也长在这一个控件上。
 */
@Composable
private fun DayStepper(onPreviousDay: () -> Unit, onNextDay: () -> Unit) {
    val outer = RoundedCornerShape(Tokens.Semantic.Radius.Control.dp)
    val width = DockExpandedWidth - Tokens.Semantic.Spacing.InlineTight.dp * 2
    Column(
        modifier = Modifier
            .width(width)
            .clip(outer)
            .background(Color(Tokens.Component.Button.Secondary.Background))
            .border(Tokens.Semantic.Stroke.Control.dp, Color(Tokens.Component.Button.Secondary.Border), outer),
    ) {
        StepperHalf(
            icon = Icons.Default.KeyboardArrowUp,
            description = stringResource(R.string.timeline_previous_days),
            onClick = onPreviousDay,
        )
        Box(
            Modifier
                .width(width)
                .height(Tokens.Semantic.Stroke.Control.dp)
                .background(Color(Tokens.Component.Button.Secondary.Border)),
        )
        StepperHalf(
            icon = Icons.Default.KeyboardArrowDown,
            description = stringResource(R.string.timeline_next_days),
            onClick = onNextDay,
        )
    }
}

@Composable
private fun StepperHalf(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(DockExpandedWidth - Tokens.Semantic.Spacing.InlineTight.dp * 2)
            .height(DockActionHeight)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = TEXT_PRIMARY,
            modifier = Modifier.size(Tokens.Platform.Phone.Geometry.IconSize.dp),
        )
    }
}

/** 独立动作：一枚圆。 */
@Composable
private fun DockRoundAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Tokens.Platform.Phone.Geometry.TouchTargetMin.dp)
            .clip(CircleShape)
            .background(Color(Tokens.Component.Button.Secondary.Background))
            .border(Tokens.Semantic.Stroke.Control.dp, Color(Tokens.Component.Button.Secondary.Border), CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = DIM,
            modifier = Modifier.size(DockTriggerIcon),
        )
    }
}

/**
 * 换日之后短暂出现的日期：主界面没有 header，方向感靠这一下。
 *
 * 淡入、停一会儿、淡出，不拦截任何触摸。
 */
@Composable
fun DayFlashLabel(text: String, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            tween(Tokens.Semantic.Motion.Standard.toInt()),
        ),
        exit = fadeOut(
            tween(Tokens.Semantic.Motion.Slow.toInt()),
        ),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(Tokens.Semantic.Radius.Pill.dp)
        Box(
            modifier = Modifier
                .clip(shape)
                .background(Color(Tokens.Component.Panel.RaisedBackground))
                .border(Tokens.Semantic.Stroke.Control.dp, BORDER_SUBTLE, shape)
                .padding(
                    horizontal = Tokens.Semantic.Spacing.Block.dp,
                    vertical = Tokens.Semantic.Spacing.InlineTight.dp,
                ),
        ) {
            Text(
                text,
                color = TEXT_PRIMARY,
                fontSize = PhoneTypography.PhoneMetaSp.sp,
            )
        }
    }
}
