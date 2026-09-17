package com.hamhuo.tplanner

import com.hamhuo.tplanner.ai.PlannedAction
import com.hamhuo.tplanner.ai.TimeSource
import com.hamhuo.tplanner.ui.components.TPlannerButton
import com.hamhuo.tplanner.ui.components.TPlannerButtonStyle
import com.hamhuo.tplanner.ui.components.TPlannerIconButton
import com.hamhuo.tplanner.ui.components.TPlannerInputFrame
import androidx.compose.ui.focus.onFocusChanged

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.PhoneGeometry as TPlannerGeometry
import com.hamhuo.tplanner.PhoneTypography as TPlannerTypography

// ── Helpers ────────────────────────────────────────────────────────────────

/**
 * 复核列表里的一项：一个动作 + 用户当前的勾选与时间取舍。
 *
 * [clearTime] 是"只去掉时间、保留任务"——`inferred` 的时间是模型猜的，用户有权只要事不要时间。
 */
data class ReviewItem(
    val action: PlannedAction,
    val selected: Boolean = true,
    val clearTime: Boolean = false,
) {
    /** 时间是否来自推断；只有推断的时间才提供"去掉时间"这个开关。 */
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
 * Full-screen task-extraction panel. Three states:
 *
 *   EDIT     — write text describing the goals on your mind
 *   THINKING — the model is splitting that text into topics → actions → subtasks
 *   CONFIRM  — review the extracted actions, toggle the ones to keep, confirm them together
 *
 * 时间来源必须看得见：`stated`（用户写的）与 `inferred`（模型按依据推测的）用不同样式，
 * 推测定为低置信度时默认不勾选，并且可以"只去掉时间、保留任务"。
 * 没有时间就没有时间，界面不会替它编一个。整份选择在一次本地事务里落盘。
 */
@Composable
fun UntangleSheet(
    requestId: String,
    prefillLocation: String,
    locationLoading: Boolean,
    initialText: String,
    thinking: Boolean,
    tasks: List<ReviewItem>,
    assumptions: List<String>,
    usedFallback: Boolean,
    onTextChange: (String) -> Unit,
    onToggleSelected: (Int) -> Unit,
    onToggleTime: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (text: String) -> Unit,
    onConfirmTasks: (List<ReviewItem>) -> Unit,
) {
    var text by remember(requestId) { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }
    val showEditor = tasks.isEmpty() && !thinking
    LaunchedEffect(showEditor) { if (showEditor) focusRequester.requestFocus() }

    Column(
        modifier = Modifier.fillMaxSize().background(BG).imePadding().padding(horizontal = 20.dp)
    ) {
        // ── Top bar ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    tasks.isNotEmpty() -> stringResource(R.string.untangle_title_confirm)
                    thinking -> "识别中…"
                    else -> "写日程"
                },
                color = ACCENT_TEXT,
                fontSize = TPlannerTypography.PhoneTitleSp.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showEditor) {
                    TPlannerButton("提取", { onSubmit(text) }, enabled = text.isNotBlank())
                }
                TPlannerIconButton(Icons.Default.Close, "Close", onDismiss)
            }
        }

        // ── Location ─────────────────────────────────────────────────
        if (showEditor) {
            Text(
                when {
                    prefillLocation.isNotBlank() -> prefillLocation
                    locationLoading -> stringResource(R.string.location_locating)
                    else -> stringResource(R.string.location_unavailable)
                },
                color = if (prefillLocation.isNotBlank()) ACCENT_TEXT else DIM,
                fontSize = TPlannerTypography.PhoneMetaSp.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }

        when {
            // ── thinking ─────────────────────────────────────────────
            thinking -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(color = ACCENT_TEXT, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        Text("识别任务信息…", color = DIM, fontSize = TPlannerTypography.PhoneSupportingSp.sp)
                    }
                }
            }

            // ── task confirmation list ───────────────────────────────
            tasks.isNotEmpty() -> {
                val selected = tasks.filter { it.selected }
                Text(
                    if (usedFallback) "本地规则识别（未使用模型）" else stringResource(R.string.untangle_confirm_title),
                    color = if (usedFallback) ACCENT_TEXT else DIM,
                    fontSize = TPlannerTypography.PhoneSupportingSp.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    tasks.forEachIndexed { index, item ->
                        val action = item.action
                        val checked = item.selected
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(SURFACE, RoundedCornerShape(TPlannerGeometry.RadiusCardDp.dp))
                                .border(
                                    1.dp,
                                    if (checked) FOCUS else BORDER,
                                    RoundedCornerShape(TPlannerGeometry.RadiusCardDp.dp),
                                )
                                .clickable { onToggleSelected(index) }
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
                            if (index == 0 || tasks[index - 1].action.topicTitle != action.topicTitle) {
                                Text(
                                    action.topicTitle,
                                    color = DIM,
                                    fontSize = TPlannerTypography.PhoneMetaSp.sp,
                                )
                            }
                            val timeLabel = if (item.clearTime) {
                                stringResource(R.string.untangle_no_time)
                            } else {
                                prettyWhen(action.time.start, action.time.end)
                                    .ifBlank { stringResource(R.string.untangle_no_time) }
                            }
                            val timeColor = when {
                                item.clearTime -> DIM
                                action.time.source == TimeSource.STATED -> ACCENT_TEXT
                                action.time.source == TimeSource.INFERRED -> GOLD
                                else -> DIM
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    timeLabel,
                                    color = timeColor,
                                    fontSize = TPlannerTypography.PhoneSupportingSp.sp,
                                )
                                if (action.time.source == TimeSource.INFERRED && !item.clearTime) {
                                    Text(
                                        "（预计）",
                                        color = DIM,
                                        fontSize = TPlannerTypography.PhoneMetaSp.sp,
                                    )
                                }
                            }
                            // 推测的依据要能看见，否则用户没有理由相信这个时间。
                            if (action.time.source == TimeSource.INFERRED && !item.clearTime &&
                                !action.time.basis.isNullOrBlank()
                            ) {
                                Text(
                                    action.time.basis,
                                    color = DIM,
                                    fontSize = TPlannerTypography.PhoneMetaSp.sp,
                                    lineHeight = TPlannerTypography.PhoneSupportingLineHeightSp.sp,
                                )
                            }
                            // 推测出来的时间可以单独去掉，任务本身留着。
                            if (item.timeIsInferred &&
                                (action.time.start != null || action.time.end != null)
                            ) {
                                Text(
                                    if (item.clearTime) "恢复这个时间" else "不要这个时间",
                                    color = ACCENT_TEXT,
                                    fontSize = TPlannerTypography.PhoneMetaSp.sp,
                                    modifier = Modifier.clickable { onToggleTime(index) },
                                )
                            }
                            if (action.subtasks.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.untangle_checklist_label),
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
                            if (action.note.isNotBlank()) Text(
                                action.note,
                                color = DIM,
                                fontSize = TPlannerTypography.PhoneMetaSp.sp,
                                lineHeight = TPlannerTypography.PhoneSupportingLineHeightSp.sp,
                            )
                        }
                    }
                }
                if (assumptions.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("时间是怎么估的", color = DIM, fontSize = TPlannerTypography.PhoneMetaSp.sp)
                        assumptions.forEach { assumption ->
                            Text(
                                "· $assumption",
                                color = DIM,
                                fontSize = TPlannerTypography.PhoneMetaSp.sp,
                                lineHeight = TPlannerTypography.PhoneSupportingLineHeightSp.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TPlannerButton(stringResource(R.string.action_cancel), onDismiss, Modifier.weight(1f), TPlannerButtonStyle.Secondary)
                    TPlannerButton(
                        stringResource(R.string.untangle_confirm_action),
                        { onConfirmTasks(selected) },
                        Modifier.weight(1f),
                        enabled = selected.isNotEmpty(),
                    )
                }
            }

            // ── editor ───────────────────────────────────────────────
            else -> {
                var editorFocused by remember { mutableStateOf(false) }
                TPlannerInputFrame(modifier = Modifier.weight(1f).fillMaxWidth(), focused = editorFocused) {
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            onTextChange(it)
                        },
                        textStyle = TextStyle(
                            color = TEXT_EDITOR,
                            fontSize = TPlannerTypography.PhoneEditorSp.sp,
                            lineHeight = TPlannerTypography.PhoneEditorLineHeightSp.sp,
                        ),
                        cursorBrush = SolidColor(FOCUS),
                        modifier = Modifier.fillMaxSize().padding(16.dp).focusRequester(focusRequester)
                            .onFocusChanged { editorFocused = it.isFocused },
                        decorationBox = { inner -> inner() }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
