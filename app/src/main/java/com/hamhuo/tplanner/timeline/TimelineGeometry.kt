package com.hamhuo.tplanner.timeline

import androidx.compose.ui.unit.dp
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal object TimelineGeometry {
    const val visibleDayCount = 1
    const val snapMinutes = 10

    /**
     * 刻度写成旋转 90° 的数字，横向只需要容纳一行字高；宽度来自共享令牌，
     * 拖拽、分列与长按换算都读同一个值，不会各自漂移。
     */
    val timeGutterWidth = TPlannerLightTokens.Component.Agenda.TimeGutterWidth.dp
    val hourHeight = 72.dp
    val minEventHeight = 24.dp

    val eventHorizontalPadding = 3.dp
    val compactEventThreshold = 43.dp

    const val twoLaneWidthFraction = 0.72f
    const val multiLaneWidthFraction = 0.62f
}

internal fun timelineWallClockMinutes(
    instant: Instant,
    day: LocalDate,
    zone: ZoneId,
): Float {
    val local = instant.atZone(zone)
    return when {
        local.toLocalDate().isBefore(day) -> 0f
        local.toLocalDate().isAfter(day) -> 24f * 60f
        else -> local.hour * 60f +
            local.minute +
            local.second / 60f +
            local.nano / 60_000_000_000f
    }
}
