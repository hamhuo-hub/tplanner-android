package com.hamhuo.tplanner

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

/** Renders a stated time; a task with no time never gets one invented for display either. */
private fun prettyWhen(startIso: String, endIso: String?): String {
    return try {
        val start = java.time.LocalDateTime.parse(startIso)
        val end = endIso?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
        val today = appToday()
        val startHm = "%02d:%02d".format(start.hour, start.minute)
        val zh = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[start.dayOfWeek.value - 1]
        val date = when (start.toLocalDate()) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> "${start.monthValue}月${start.dayOfMonth}日 $zh"
        }
        when {
            end == null -> "$date $startHm"
            start.toLocalDate() == end.toLocalDate() ->
                "$date $startHm–%02d:%02d".format(end.hour, end.minute)
            else -> "$date $startHm – ${end.monthValue}月${end.dayOfMonth}日 %02d:%02d".format(end.hour, end.minute)
        }
    } catch (_: Exception) { startIso }
}

/**
 * Full-screen task-extraction panel. Three states:
 *
 *   EDIT     — write text describing the goals on your mind
 *   THINKING — the LLM is splitting that text into independent goal/theme tasks
 *   CONFIRM  — review the extracted tasks, toggle the ones to keep, confirm them together
 *
 * No QA, no clarifying questions. Every task is a task record; a task with no stated time keeps
 * no time, and the whole selection is accepted in one local transaction.
 */
@Composable
fun UntangleSheet(
    requestId: String,
    prefillLocation: String,
    locationLoading: Boolean,
    initialText: String,
    thinking: Boolean,
    tasks: List<DeepSeekAnalysisService.ProposedTask>,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (text: String) -> Unit,
    onConfirmTasks: (List<DeepSeekAnalysisService.ProposedTask>) -> Unit,
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
                var selectedIndexes by remember(requestId) { mutableStateOf(tasks.indices.toSet()) }
                val selected = tasks.filterIndexed { index, _ -> index in selectedIndexes }
                Text(
                    stringResource(R.string.untangle_confirm_title),
                    color = DIM,
                    fontSize = TPlannerTypography.PhoneSupportingSp.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    tasks.forEachIndexed { index, task ->
                        val checked = index in selectedIndexes
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(SURFACE, RoundedCornerShape(TPlannerGeometry.RadiusCardDp.dp))
                                .border(
                                    1.dp,
                                    if (checked) FOCUS else BORDER,
                                    RoundedCornerShape(TPlannerGeometry.RadiusCardDp.dp),
                                )
                                .clickable {
                                    selectedIndexes =
                                        if (checked) selectedIndexes - index else selectedIndexes + index
                                }
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
                                    task.title,
                                    color = TEXT_EDITOR,
                                    fontSize = TPlannerTypography.PhoneTitleSp.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = TPlannerTypography.PhoneBodyLineHeightSp.sp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                task.startIso?.let { prettyWhen(it, task.endIso) }
                                    ?: stringResource(R.string.untangle_no_time),
                                color = if (task.startIso != null) ACCENT_TEXT else DIM,
                                fontSize = TPlannerTypography.PhoneSupportingSp.sp,
                            )
                            if (task.checklist.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.untangle_checklist_label),
                                    color = DIM,
                                    fontSize = TPlannerTypography.PhoneMetaSp.sp,
                                )
                                task.checklist.forEach { item ->
                                    Text(
                                        "· $item",
                                        color = DIM,
                                        fontSize = TPlannerTypography.PhoneMetaSp.sp,
                                        lineHeight = TPlannerTypography.PhoneSupportingLineHeightSp.sp,
                                    )
                                }
                            }
                            if (task.note.isNotBlank()) Text(
                                task.note,
                                color = DIM,
                                fontSize = TPlannerTypography.PhoneMetaSp.sp,
                                lineHeight = TPlannerTypography.PhoneSupportingLineHeightSp.sp,
                            )
                            Text("颜色 ${task.colorId + 1}", color = DIM, fontSize = TPlannerTypography.PhoneMetaSp.sp)
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
