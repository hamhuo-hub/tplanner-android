package com.hamhuo.tplanner

import com.hamhuo.tplanner.ai.PlannedAction
import com.hamhuo.tplanner.ai.TimeSource
import com.hamhuo.tplanner.ui.components.TPlannerButton

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.PhoneGeometry as TPlannerGeometry
import com.hamhuo.tplanner.PhoneTypography as TPlannerTypography

/**
 * 预览里的一项：一个动作 + 用户当前的勾选与时间取舍。
 *
 * [clearTime] 是"不要这个时间、保留任务"——`inferred` 的时间是模型猜的，用户有权只要事不要时间。
 */
data class ReviewItem(
    val action: PlannedAction,
    val selected: Boolean = true,
    val clearTime: Boolean = false,
) {
    /** 时间是否来自推断；只有推断的时间才提供"不要这个时间"这个开关。 */
    val timeIsInferred: Boolean get() = action.time.source == TimeSource.INFERRED
}

/**
 * 契约里的时间是带偏移量的 ISO 8601，这里按用户所在时区渲染；
 * 只有 `end` 没有 `start` 时渲染成截止期限（"周五 21:00 前"），它同样是真实的时间约束。
 */
private fun prettyWhen(startIso: String?, endIso: String?): String {
    val start = startIso?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(APP_ZONE) }.getOrNull() }
    val end = endIso?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(APP_ZONE) }.getOrNull() }
    val today = appToday()
    if (start == null) {
        val deadline = end ?: return ""
        return "${dayLabelOf(deadline.toLocalDate(), today)} %02d:%02d 前".format(deadline.hour, deadline.minute)
    }
    val date = dayLabelOf(start.toLocalDate(), today)
    val startHm = "%02d:%02d".format(start.hour, start.minute)
    return when {
        end == null -> "$date $startHm"
        start.toLocalDate() == end.toLocalDate() -> "$date $startHm–%02d:%02d".format(end.hour, end.minute)
        else -> "$date $startHm – ${end.monthValue}月${end.dayOfMonth}日 %02d:%02d".format(end.hour, end.minute)
    }
}

private fun dayLabelOf(date: java.time.LocalDate, today: java.time.LocalDate): String {
    val weekday = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]
    return when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> "${date.monthValue}月${date.dayOfMonth}日 $weekday"
    }
}

/**
 * 纯预览：只回答一个问题——**TPlanner 对刚才那句话理解得对不对。**
 *
 * 这里刻意没有：
 * - 输入框与"提取"按钮：输入已经由那张纸完成，纸上的原文就摆在预览上方；
 * - 面向用户的诊断信息（本地兜底、assumptions、地点）：这些只进 Logcat；
 * - 需要用户"重新审批 AI"的仪式感：默认全选，用户只需要发现哪里理解错了。
 *
 * 每项只显示三个可理解的层级：
 * - 用户明确写了时间 → 干净的一行时间；
 * - 模型按依据推测 → 时间 + 可点开的「预计」，点开才解释推算依据；
 * - 无从推断 → 「未排期」，不编时间。
 */
@Composable
fun PlanPreview(
    tasks: List<ReviewItem>,
    committing: Boolean,
    onToggleSelected: (Int) -> Unit,
    onToggleTime: (Int) -> Unit,
    onEditRequest: () -> Unit,
    onConfirm: (List<ReviewItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = remember(tasks) { tasks.filter { it.selected } }
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.plan_preview_title),
                color = DIM,
                fontSize = TPlannerTypography.PhoneSupportingSp.sp,
            )
            TextButton(onClick = onEditRequest) {
                Text(
                    stringResource(R.string.plan_preview_edit),
                    color = ACCENT_TEXT,
                    fontSize = TPlannerTypography.PhoneMetaSp.sp,
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tasks.forEachIndexed { index, item ->
                PreviewCard(
                    item = item,
                    showTopic = index == 0 ||
                        tasks[index - 1].action.topicTitle != item.action.topicTitle,
                    onToggleSelected = { onToggleSelected(index) },
                    onToggleTime = { onToggleTime(index) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (committing) {
                // 提交中：按钮位置给一个很小的进行状态，不再重复文案。
                Box(
                    modifier = Modifier.weight(1f).height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = ACCENT_TEXT,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                TPlannerButton(
                    stringResource(R.string.plan_preview_confirm),
                    { onConfirm(selected) },
                    Modifier.weight(1f),
                    enabled = selected.isNotEmpty(),
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(
    item: ReviewItem,
    showTopic: Boolean,
    onToggleSelected: () -> Unit,
    onToggleTime: () -> Unit,
) {
    val action = item.action
    val checked = item.selected
    val shape = RoundedCornerShape(TPlannerGeometry.RadiusCardDp.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SURFACE, shape)
            .border(1.dp, if (checked) FOCUS else BORDER, shape)
            .clickable(onClickLabel = action.title, role = Role.Checkbox, onClick = onToggleSelected)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        if (checked) GOLD else Color.Transparent,
                        RoundedCornerShape(TPlannerGeometry.RadiusCompactDp.dp),
                    )
                    .border(
                        1.5.dp,
                        if (checked) FOCUS else BORDER,
                        RoundedCornerShape(TPlannerGeometry.RadiusCompactDp.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = ON_ACCENT,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Text(
                action.title,
                color = TEXT_EDITOR,
                fontSize = TPlannerTypography.PhoneTitleSp.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = TPlannerTypography.PhoneBodyLineHeightSp.sp,
                modifier = Modifier.weight(1f),
            )
        }

        // 主题多了以后标题会重复，只在换主题时显示一次归属。
        if (showTopic && action.topicTitle.isNotBlank()) {
            Text(action.topicTitle, color = DIM, fontSize = TPlannerTypography.PhoneMetaSp.sp)
        }

        TimeRow(item = item, onToggleTime = onToggleTime)

        if (action.subtasks.isNotEmpty()) {
            Text(
                stringResource(R.string.plan_preview_checklist),
                color = DIM,
                fontSize = TPlannerTypography.PhoneMetaSp.sp,
            )
            action.subtasks.forEach { subtask ->
                Text(
                    "· ${subtask.text}",
                    color = DIM,
                    fontSize = TPlannerTypography.PhoneMetaSp.sp,
                    lineHeight = TPlannerTypography.PhoneSupportingLineHeightSp.sp,
                )
            }
        }
        if (action.note.isNotBlank()) {
            Text(
                action.note,
                color = DIM,
                fontSize = TPlannerTypography.PhoneMetaSp.sp,
                lineHeight = TPlannerTypography.PhoneSupportingLineHeightSp.sp,
            )
        }
    }
}

/**
 * 时间的三个层级。推断出来的时间默认只写「预计」，依据要用户点一下才出现——
 * 默认显示结论，必要时解释推断。
 */
@Composable
private fun TimeRow(item: ReviewItem, onToggleTime: () -> Unit) {
    val action = item.action
    val inferred = item.timeIsInferred && !item.clearTime
    var explain by remember(item.action.id) { mutableStateOf(false) }
    val label = when {
        item.clearTime -> stringResource(R.string.plan_preview_no_time)
        else -> prettyWhen(action.time.start, action.time.end)
            .ifBlank { stringResource(R.string.plan_preview_no_time) }
    }
    val timeColor = when {
        item.clearTime -> DIM
        action.time.source == TimeSource.STATED -> ACCENT_TEXT
        action.time.source == TimeSource.INFERRED -> GOLD
        else -> DIM
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = timeColor,
                fontSize = TPlannerTypography.PhoneSupportingSp.sp,
            )
            if (inferred) {
                Text(
                    stringResource(R.string.plan_preview_estimated),
                    color = DIM,
                    fontSize = TPlannerTypography.PhoneMetaSp.sp,
                    modifier = Modifier
                        .clickable(
                            onClickLabel = stringResource(R.string.plan_preview_estimated_why),
                            role = Role.Button,
                        ) { explain = !explain },
                )
            }
            if (item.timeIsInferred && (action.time.start != null || action.time.end != null)) {
                Text(
                    stringResource(
                        if (item.clearTime) R.string.plan_preview_restore_time
                        else R.string.plan_preview_drop_time,
                    ),
                    color = ACCENT_TEXT,
                    fontSize = TPlannerTypography.PhoneMetaSp.sp,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onToggleTime),
                )
            }
        }
        if (explain && inferred) {
            Text(
                action.time.basis?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.plan_preview_estimated_fallback),
                color = DIM,
                fontSize = TPlannerTypography.PhoneMetaSp.sp,
                lineHeight = TPlannerTypography.PhoneSupportingLineHeightSp.sp,
            )
        }
    }
}
