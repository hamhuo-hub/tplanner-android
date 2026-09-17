package com.hamhuo.tplanner.timeline.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens
import com.hamhuo.tplanner.designsystem.TPlannerTypography
import com.hamhuo.tplanner.timeline.TimelineGeometry
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale

@Composable
internal fun TimelineGrid(
    days: List<LocalDate>,
    onLongPress: (Offset) -> Unit,
) {
    val density = LocalDensity.current
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val timeGutterPx = with(density) { TimelineGeometry.timeGutterWidth.toPx() }
    val hourHeightPx = with(density) { TimelineGeometry.hourHeight.toPx() }
    val lineWidthPx = with(density) { 0.7.dp.toPx() }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(days) {
                detectTapGestures(
                    onLongPress = { position ->
                        currentOnLongPress(position)
                    },
                )
            },
    ) {
        for (hour in 0..24) {
            val y = hour * hourHeightPx
            drawLine(
                color = Color(TPlannerLightTokens.Semantic.Color.BorderSubtle),
                start = Offset(timeGutterPx, y),
                end = Offset(size.width, y),
                strokeWidth = lineWidthPx,
            )
        }
        val visibleDayCount = days.size.coerceAtLeast(1)
        val dayWidthPx =
            (size.width - timeGutterPx) / visibleDayCount
        for (dayIndex in 0..visibleDayCount) {
            val x = timeGutterPx + dayWidthPx * dayIndex
            drawLine(
                color = Color(TPlannerLightTokens.Semantic.Color.BorderSubtle),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = lineWidthPx,
            )
        }

    }

    VerticalHourLabels(
        gutterWidth = TimelineGeometry.timeGutterWidth,
        hourHeight = TimelineGeometry.hourHeight,
    )
}

/**
 * 24 个小时刻度，数字整体旋转 90°（自下而上读），横向只占一行字高。
 *
 * 标签先按自然宽度测量再整体旋转：父级只给刻度宽度，若先按父级约束换行或省略，
 * "07:00" 会在旋转前就被截断。
 */
@Composable
private fun VerticalHourLabels(gutterWidth: Dp, hourHeight: Dp) {
    for (hour in 0 until 24) {
        Box(
            modifier = Modifier
                .offset(y = hourHeight * hour)
                .width(gutterWidth)
                .height(hourHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = String.format(Locale.US, "%02d:00", hour),
                color = Color(TPlannerLightTokens.Semantic.Color.TextSecondary),
                fontFamily = FontFamily.Monospace,
                fontSize = TPlannerTypography.TimelineTimeSp.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .wrapContentWidth(unbounded = true)
                    .rotate(TPlannerLightTokens.Component.Agenda.HourLabelRotation),
            )
        }
    }
}

@Composable
internal fun TimelineNowIndicator(
    days: List<LocalDate>,
    today: LocalDate,
    now: ZonedDateTime,
    modifier: Modifier = Modifier,
) {
    val todayIndex = days.indexOf(today)
    if (todayIndex < 0) return

    val density = LocalDensity.current
    val timeGutterPx = with(density) { TimelineGeometry.timeGutterWidth.toPx() }
    val hourHeightPx = with(density) { TimelineGeometry.hourHeight.toPx() }
    val visibleDayCount = days.size.coerceAtLeast(1)

    Canvas(modifier.fillMaxSize()) {
        val dayWidthPx = (size.width - timeGutterPx) / visibleDayCount
        val minutes = now.hour * 60 + now.minute
        val y = minutes / 60f * hourHeightPx
        val left = timeGutterPx + dayWidthPx * todayIndex
        drawLine(
            color = Color(TPlannerLightTokens.Semantic.Color.AccentText),
            start = Offset(left, y),
            end = Offset(left + dayWidthPx, y),
            strokeWidth = with(density) { 1.5.dp.toPx() },
        )
        drawCircle(
            color = Color(TPlannerLightTokens.Semantic.Color.AccentText),
            radius = with(density) { 3.dp.toPx() },
            center = Offset(left + with(density) { 3.dp.toPx() }, y),
        )
    }
}
