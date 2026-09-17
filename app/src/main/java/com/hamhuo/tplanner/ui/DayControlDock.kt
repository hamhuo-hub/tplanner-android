package com.hamhuo.tplanner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens as Tokens

/**
 * 每一个控制都是同一个规格：44dp 圆 + 20dp 图标。
 *
 * 这是整个右上角唯一的形状语言——没有父底板、没有描边、没有长方形按钮。
 */
private val DockControlSize = 44.dp
private val DockIconSize = 20.dp
private val DockControlGap = 8.dp
private val DockTodayDotSize = 12.dp
private val DockTouchTargetSize = 48.dp
private val DockEdgePadding = 12.dp

/**
 * 每枚圆自己的 material：半透明悬浮面，没有描边。
 *
 * 也不能写成全透明——底下的时间轴会直接穿过文字。
 */
@Composable
private fun dockSurface(): Color =
    Color(Tokens.Component.Panel.RaisedBackground).copy(alpha = Tokens.Component.Dock.SurfaceOpacity)

/**
 * 右上角的主页面控制器：**一列独立圆形按钮**。
 *
 * 两条硬规则：
 * 1. 父 Box/Column 完全透明，只有每一枚圆自己有 surface——不存在"白色面板"，
 *    也不存在"父底板套子按钮"的分层；
 * 2. 收起与展开是两套不同的内容，**同一时刻只存在一套**：否则收起的控制点会和展开后的
 *    某一枚叠在同一个位置。
 *
 * 这一版不做 morph 动画：先把形状与层级做对，动画之后单独加。
 */
@Composable
fun DayControlDock(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    destinationIcon: ImageVector,
    destinationLabel: String,
    onDestination: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    dayBadge: String? = null,
) {
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        // 展开时点空白处收起；这一层只在展开时存在，收起态完全不干扰时间轴手势。
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

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = DockEdgePadding, end = DockEdgePadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DockControlGap),
            ) {
                CircleControlButton(
                    icon = destinationIcon,
                    description = destinationLabel,
                    onClick = onDestination,
                )
                CircleControlButton(
                    icon = Icons.Default.KeyboardArrowUp,
                    description = stringResource(R.string.timeline_previous_days),
                    onClick = onPreviousDay,
                )
                CircleControlButton(
                    icon = Icons.Default.KeyboardArrowDown,
                    description = stringResource(R.string.timeline_next_days),
                    onClick = onNextDay,
                )
                CircleControlButton(
                    icon = Icons.Default.Settings,
                    description = stringResource(R.string.sync_server_title),
                    onClick = onOpenSettings,
                )
            }
        } else {
            // 不在今天时这一枚自己显示当天日期：方向感不靠 header。
            CircleControlButton(
                icon = Icons.Default.GridView,
                description = stringResource(R.string.day_control_open),
                onClick = { onExpandedChange(true) },
                badge = dayBadge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = DockEdgePadding, end = DockEdgePadding),
            )
        }
    }
}

/**
 * 统一规格的圆形控制；[badge] 非空时用它替代图标（当天日期）。
 *
 * 44dp 圆同时就是触摸目标，涟漪裁在圆内，不会溢出到隔壁按钮。
 */
@Composable
private fun CircleControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Box(
        modifier = modifier
            .size(DockControlSize)
            .clip(CircleShape)
            .background(dockSurface())
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (badge != null) {
            Text(
                badge,
                color = TEXT_PRIMARY,
                fontSize = PhoneTypography.PhoneMetaSp.sp,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = DIM,
                modifier = Modifier.size(DockIconSize),
            )
        }
    }
}

/**
 * 回今天的小圆点：只在翻到别的日期时出现，落在底部输入条上方。
 *
 * 视觉上就只是一个点（日历里"今天"的写法），触摸目标是它外面那圈 48dp。
 */
@Composable
fun ReturnToTodayDot(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Tokens.Semantic.Motion.Standard.toInt())) + scaleIn(
            animationSpec = tween(Tokens.Semantic.Motion.Standard.toInt()),
            initialScale = 0.85f,
        ),
        exit = fadeOut(tween(Tokens.Semantic.Motion.Fast.toInt())) + scaleOut(
            animationSpec = tween(Tokens.Semantic.Motion.Fast.toInt()),
            targetScale = 0.85f,
        ),
    ) {
        val description = stringResource(R.string.day_back_to_today)
        Box(
            // 间距画在内容上：收起时 AnimatedVisibility 是 0 尺寸，不会在底部留下空档。
            modifier = modifier
                .size(DockTouchTargetSize)
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(DockTodayDotSize)
                    .clip(CircleShape)
                    .background(GOLD),
            )
        }
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
        enter = fadeIn(tween(Tokens.Semantic.Motion.Standard.toInt())),
        exit = fadeOut(tween(Tokens.Semantic.Motion.Slow.toInt())),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(Tokens.Semantic.Radius.Pill.dp)
        Box(
            modifier = Modifier
                .clip(shape)
                // 与控制器同一套分层：它同样浮在时间轴之上，所以同样是半透明面、没有描边。
                .background(dockSurface())
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
