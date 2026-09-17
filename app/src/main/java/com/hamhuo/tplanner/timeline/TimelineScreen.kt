package com.hamhuo.tplanner.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.hamhuo.tplanner.APP_ZONE
import com.hamhuo.tplanner.ScheduleItem
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens
import com.hamhuo.tplanner.timeline.components.TimelineBody
import com.hamhuo.tplanner.timeline.components.TimelineStatusStrip
import java.time.Instant

/**
 * A deterministic, non-AI schedule view. Conflicts are intentionally allowed:
 * they are narrowed and stacked instead of blocking a save or opening a dialog.
 *
 * 只画**一天**：默认就是今天，顶部没有日期条——翻日期不再属于主界面。
 * 新建也不在这里（右下角的加号已删除）：长按空白处仍然能在指定时刻落一个任务。
 *
 * [state] 由调用方持有，因为同屏的 Note 必须知道当前展示的是哪一天。
 */
@Composable
internal fun TimelineScreen(
    state: TimelineState,
    events: List<ScheduleItem>,
    onEventClick: (ScheduleItem) -> Unit,
    onAddTaskAt: (Instant) -> Unit,
    onEventMove: (ScheduleItem, Instant, Instant) -> Unit,
    modifier: Modifier = Modifier,
    // 刚在预览里确认的那几条：只让它们播一次出现动画。
    revealedEventIds: Set<String> = emptySet(),
    // 合并后的当日界面把 Plan 条放在时间轴下方；时间轴本身不解释它的语义。
    planPanel: (@Composable () -> Unit)? = null,
) {
    val zone = APP_ZONE
    val now = rememberTimelineNow(zone)
    val today = now.toLocalDate()
    val selectedDay = state.firstDay
    val days = remember(state.firstDayEpoch) {
        List(TimelineGeometry.visibleDayCount) { index ->
            selectedDay.plusDays(index.toLong())
        }
    }
    val visibleEvents = remember(events) { events.filter { it.deletedAt == 0L } }
    val placements = remember(visibleEvents, days, zone) {
        TimelinePlacementMapper.createDayPlacements(
            events = visibleEvents,
            days = days,
            zone = zone,
        )
    }
    val density = LocalDensity.current
    val hourHeightPx = with(density) { TimelineGeometry.hourHeight.toPx() }

    TimelineInitialScrollEffect(
        state = state,
        now = now,
        hourHeightPx = hourHeightPx,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(TPlannerLightTokens.Semantic.Color.Canvas)),
    ) {
        Column(Modifier.fillMaxSize()) {
            TimelineStatusStrip(
                day = selectedDay,
                events = visibleEvents,
                now = now.toInstant(),
                zone = zone,
                onEventClick = onEventClick,
            )
            TimelineBody(
                days = days,
                today = today,
                now = now,
                events = visibleEvents,
                placements = placements,
                zone = zone,
                state = state,
                hourHeightPx = hourHeightPx,
                revealedEventIds = revealedEventIds,
                onEventClick = onEventClick,
                onAddTaskAt = onAddTaskAt,
                onEventMove = onEventMove,
                modifier = Modifier.weight(1f),
            )
            if (planPanel != null) {
                planPanel()
            }
        }
    }
}
