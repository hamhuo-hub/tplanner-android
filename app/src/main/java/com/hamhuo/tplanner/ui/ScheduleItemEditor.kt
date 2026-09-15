@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.hamhuo.tplanner

import com.hamhuo.tplanner.ui.components.TPlannerButton
import com.hamhuo.tplanner.ui.components.TPlannerButtonStyle
import com.hamhuo.tplanner.ui.components.TPlannerIconButton
import com.hamhuo.tplanner.designsystem.TPlannerCategories
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens as Tokens
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.hamhuo.tplanner.PhoneGeometry as TPlannerGeometry
import com.hamhuo.tplanner.PhoneTypography as TPlannerTypography
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleItemDetailScreen(
    event: ScheduleItem,
    onDraftChange: (ScheduleItem) -> Unit,
    onSave: (ScheduleItem, (Boolean) -> Unit) -> Unit,
    onDelete: ((Boolean) -> Unit) -> Unit,
    onNoteSave: (ScheduleItem, (Boolean) -> Unit) -> Unit,
) {
    val initialNote = event.note
    var title     by remember { mutableStateOf(event.title) }
    var renaming  by remember { mutableStateOf(false) }
    var start     by remember { mutableStateOf(event.start) }
    var end       by remember { mutableStateOf(event.end) }
    var checklist by remember { mutableStateOf(event.checklist) }
    var completed by remember { mutableStateOf(event.completed) }
    var note      by remember(event.id) { mutableStateOf(initialNote) }
    var noteEditorDraft by remember(event.id) { mutableStateOf(initialNote) }
    var noteEditorOpen by remember { mutableStateOf(false) }
    var noteEditorCloseRequested by remember { mutableStateOf(false) }
    var colorId   by remember { mutableStateOf(event.colorId) }
    var type      by remember { mutableStateOf(event.type) }
    var recurrenceType by remember(event.id) {
        mutableStateOf(
            event.extras["recurrenceType"]
                ?.toString()
                ?.lowercase()
                ?.takeIf { it in setOf("daily", "weekly", "monthly") }
                ?: "none",
        )
    }
    var recurrenceCount by remember(event.id) {
        val raw = event.extras["recurrenceCount"]
        val count = if (raw is Number) raw.toInt() else raw?.toString()?.toIntOrNull() ?: 1
        mutableStateOf(count.coerceIn(1, MAX_TASK_RECURRENCE_COUNT))
    }
    var recurrenceEdited by remember(event.id) { mutableStateOf(false) }

    var showTypeSheet by remember { mutableStateOf(false) }
    val typeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val zone    = remember { APP_ZONE }
    val dateTimePattern = stringResource(R.string.date_pattern_month_day_time)
    val dateFmt = remember(dateTimePattern) { DateTimeFormatter.ofPattern(dateTimePattern) }
    val context = LocalContext.current
    var saveRequested by remember { mutableStateOf(false) }
    var deleteRequested by remember { mutableStateOf(false) }

    fun pickDateTime(initial: Instant, onPicked: (Instant) -> Unit) {
        val cal = Calendar.getInstance(appLegacyTimeZone()).apply {
            timeInMillis = initial.toEpochMilli()
        }
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                cal.set(y, m, d, h, min)
                onPicked(Instant.ofEpochMilli(cal.timeInMillis))
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun buildResult(updatedAt: Long = System.currentTimeMillis()): ScheduleItem {
        val nextExtras = event.extras.toMutableMap().apply {
            if (recurrenceType == "none") {
                remove("recurrenceType")
                remove("recurrenceCount")
                // Preserve unsupported future rules through unrelated edits; clear only on intent.
                if (recurrenceEdited) remove("_syncV3Recurrence")
            } else {
                put("recurrenceType", recurrenceType)
                put("recurrenceCount", recurrenceCount.coerceIn(1, MAX_TASK_RECURRENCE_COUNT))
            }
            remove("groupId")
        }
        return event.copy(
            title     = title.ifBlank { event.title },
            type      = type,
            start     = start,
            end       = end,
            // Non-task editors hide checklist controls, but the data must survive a
            // temporary type conversion so converting back to a task is lossless.
            checklist = checklist,
            completed = if (type == "task") completed else false,
            note      = if (noteEditorOpen) noteEditorDraft else note,
            colorId   = colorId,
            updatedAt = updatedAt,
            extras = nextExtras,
        )
    }

    fun persistDraft() {
        if (!saveRequested && !noteEditorCloseRequested) {
            // Enqueue from the input callback itself. A LaunchedEffect can be cancelled before it
            // observes the last accepted keystroke when the Activity is stopped or recreated.
            onDraftChange(buildResult(updatedAt = event.updatedAt))
        }
    }

    fun commitResult() {
        if (saveRequested || deleteRequested) return
        saveRequested = true
        onSave(buildResult()) { completed ->
            if (!completed) saveRequested = false
        }
    }

    fun deleteAndClose() {
        if (saveRequested || deleteRequested) return
        deleteRequested = true
        onDelete { completed ->
            if (!completed) deleteRequested = false
        }
    }

    fun closeNoteEditor(updatedNote: String = noteEditorDraft) {
        if (!noteEditorOpen || noteEditorCloseRequested) return
        note = updatedNote
        noteEditorDraft = updatedNote
        noteEditorCloseRequested = true
        onNoteSave(buildResult().copy(note = updatedNote)) { completed ->
            if (!completed) {
                // The durable draft remains intact. Remount the editor so its local finish latch
                // cannot leave the text permanently read-only after a conflict or I/O failure.
                noteEditorCloseRequested = false
                noteEditorOpen = false
            } else {
                // The Room commit is the completion authority. Preview rendering may fail or be
                // delayed and must not leave the editor permanently read-only.
                noteEditorCloseRequested = false
                noteEditorOpen = false
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (noteEditorOpen) closeNoteEditor() else commitResult()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val imeVisible = WindowInsets.isImeVisible

        fun saveAndClose() {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            commitResult()
        }

        BackHandler(enabled = !showTypeSheet && !noteEditorOpen) {
            if (imeVisible) {
                keyboardController?.hide()
            } else {
                saveAndClose()
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(BG)
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            Column(Modifier.fillMaxSize().imePadding()) {
                // 顶部栏：返回 + 完成
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::saveAndClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = TEXT_PRIMARY)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TPlannerButton(stringResource(R.string.cd_delete), ::deleteAndClose,
                            style = TPlannerButtonStyle.Destructive, enabled = !saveRequested && !deleteRequested)
                        TPlannerButton(stringResource(R.string.action_done), ::saveAndClose,
                            enabled = !saveRequested && !deleteRequested)
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    // 标题行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(TPlannerGeometry.RadiusFieldDp.dp))
                                .background(
                                    Color(TPlannerCategories.forColorId(colorId).background),
                                    RoundedCornerShape(TPlannerGeometry.RadiusFieldDp.dp)
                                )
                                .clickable {
                                    renaming = false
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    showTypeSheet = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                typeIcon(type), contentDescription = null,
                                tint = Color(TPlannerCategories.forColorId(colorId).foreground), modifier = Modifier.size(Tokens.Platform.Phone.Geometry.IconSize.dp)
                            )
                        }
                        if (renaming) {
                            val titleFocusRequester = remember { FocusRequester() }
                            BasicTextField(
                                value = title,
                                onValueChange = {
                                    title = it
                                    persistDraft()
                                },
                                textStyle = TextStyle(
                                    color = TEXT_PRIMARY, fontSize = TPlannerTypography.PhoneHeadingSp.sp, fontWeight = FontWeight.SemiBold
                                ),
                                cursorBrush = SolidColor(FOCUS),
                                singleLine  = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { saveAndClose() }),
                                modifier    = Modifier
                                    .weight(1f)
                                    .clipToBounds()
                                    .focusRequester(titleFocusRequester)
                            )
                            LaunchedEffect(Unit) { titleFocusRequester.requestFocus() }
                        } else {
                            Text(
                                title.ifBlank { stringResource(R.string.untitled_placeholder) },
                                color = TEXT_PRIMARY, fontSize = TPlannerTypography.PhoneHeadingSp.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { renaming = true }
                            )
                        }
                        if (type == "task") {
                            IconButton(onClick = {
                                completed = !completed
                                persistDraft()
                            }) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            if (completed) GOLD else Color.Transparent,
                                            RoundedCornerShape(TPlannerGeometry.RadiusCompactDp.dp),
                                        )
                                        .border(
                                            1.5.dp,
                                            if (completed) FOCUS else BORDER,
                                            RoundedCornerShape(TPlannerGeometry.RadiusCompactDp.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (completed) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = stringResource(R.string.cd_mark_incomplete),
                                            tint = ON_ACCENT,
                                            modifier = Modifier.size(17.dp),
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = stringResource(R.string.cd_mark_complete),
                                            tint = Color.Transparent,
                                            modifier = Modifier.size(17.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider(color = BORDER)
                    Spacer(Modifier.height(20.dp))

                    // 时间
                    DetailSectionLabel(stringResource(R.string.section_time))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeChip(
                            label = stringResource(R.string.label_start), value = start.atZone(zone).format(dateFmt),
                            onClick = { pickDateTime(start) { newStart ->
                                start = newStart
                                // 开始时间不能晚于结束时间，否则自动把结束时间往后推 1 小时
                                if (!newStart.isBefore(end)) {
                                    end = newStart.plusSeconds(3600)
                                }
                                persistDraft()
                            } }
                        )
                        TimeChip(
                            label = stringResource(R.string.label_end), value = end.atZone(zone).format(dateFmt),
                            onClick = { pickDateTime(end) { newEnd ->
                                end = newEnd
                                // 结束时间不能早于开始时间，否则自动把开始时间往前推 1 小时
                                if (!newEnd.isAfter(start)) {
                                    start = newEnd.minusSeconds(3600)
                                }
                                persistDraft()
                            } }
                        )
                    }

                    if (type == "task") {
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = BORDER)
                        Spacer(Modifier.height(20.dp))

                        DetailSectionLabel(stringResource(R.string.section_recurrence))
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                "none" to stringResource(R.string.recurrence_none),
                                "daily" to stringResource(R.string.recurrence_daily),
                                "weekly" to stringResource(R.string.recurrence_weekly),
                                "monthly" to stringResource(R.string.recurrence_monthly),
                            ).forEach { (value, label) ->
                                RecurrenceOptionChip(
                                    label = label,
                                    selected = recurrenceType == value,
                                    onClick = {
                                        recurrenceEdited = true
                                        recurrenceType = value
                                        if (value != "none" && recurrenceCount < 2) {
                                            recurrenceCount = 2
                                        }
                                        persistDraft()
                                    },
                                )
                            }
                        }
                        if (recurrenceType != "none") {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    stringResource(R.string.recurrence_count, recurrenceCount),
                                    modifier = Modifier.weight(1f),
                                    color = TEXT_PRIMARY,
                                    fontSize = TPlannerTypography.PhoneSupportingSp.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Box(
                                    modifier = Modifier
                                        .size(Tokens.Platform.Phone.Geometry.TouchTargetMin.dp)
                                        .clip(RoundedCornerShape(TPlannerGeometry.RadiusMediumDp.dp))
                                        .background(SURFACE_LOW, RoundedCornerShape(TPlannerGeometry.RadiusMediumDp.dp))
                                        .border(1.dp, BORDER, RoundedCornerShape(TPlannerGeometry.RadiusMediumDp.dp))
                                        .clickable(enabled = recurrenceCount > 1) {
                                            recurrenceEdited = true
                                            recurrenceCount--
                                            persistDraft()
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("−", color = TEXT_PRIMARY, fontSize = TPlannerTypography.PhoneSectionSp.sp)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(Tokens.Platform.Phone.Geometry.TouchTargetMin.dp)
                                        .clip(RoundedCornerShape(TPlannerGeometry.RadiusMediumDp.dp))
                                        .background(SURFACE_LOW, RoundedCornerShape(TPlannerGeometry.RadiusMediumDp.dp))
                                        .border(1.dp, BORDER, RoundedCornerShape(TPlannerGeometry.RadiusMediumDp.dp))
                                        .clickable(enabled = recurrenceCount < MAX_TASK_RECURRENCE_COUNT) {
                                            recurrenceEdited = true
                                            recurrenceCount++
                                            persistDraft()
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("+", color = TEXT_PRIMARY, fontSize = TPlannerTypography.PhoneTitleSp.sp)
                                }
                            }
                            Text(
                                stringResource(R.string.recurrence_series_hint),
                                color = DIM,
                                fontSize = TPlannerTypography.PhoneCaptionSp.sp,
                                lineHeight = TPlannerTypography.PhoneCompactLineHeightSp.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }

                    // 清单只对「任务」类型有意义——事件/提醒不是待办事项，不需要子项打勾。
                    if (type == "task") {
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = BORDER)
                        Spacer(Modifier.height(20.dp))

                        DetailSectionLabel(stringResource(R.string.section_checklist))
                        Spacer(Modifier.height(10.dp))
                        checklist.forEachIndexed { idx, item ->
                            ChecklistRow(
                                item = item,
                                onToggle = {
                                    checklist = checklist.toMutableList()
                                        .also { it[idx] = item.copy(completed = !item.completed) }
                                    persistDraft()
                                },
                                onTextChange = { newText ->
                                    checklist = checklist.toMutableList()
                                        .also { it[idx] = item.copy(text = newText) }
                                    persistDraft()
                                },
                                onDelete = {
                                    checklist = checklist.toMutableList().also { it.removeAt(idx) }
                                    persistDraft()
                                }
                            )
                        }
                        Row(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable {
                                    checklist = checklist + CheckItem(
                                        id = UUID.randomUUID().toString(), text = "", completed = false
                                    )
                                    persistDraft()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = DIM, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.action_add_item), color = DIM, fontSize = TPlannerTypography.PhoneSupportingSp.sp)
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = BORDER)
                    Spacer(Modifier.height(20.dp))

                    // 备注——手动输入不需要 MD 工具栏，但同步来的内容可能携带 PC 端写的 MD，
                    // 查看态要渲染，而不是把 "##"/"**" 之类原样显示给用户。
                    DetailSectionLabel(stringResource(R.string.section_note))
                    Spacer(Modifier.height(10.dp))
                    MarkdownField(
                        content = note,
                        onSave = { newNote ->
                            note = newNote
                            persistDraft()
                        },
                        placeholder = stringResource(R.string.note_placeholder),
                        contentPadding = PaddingValues(0.dp),
                        // 详情页仅在这里预览；编辑交给根层的全屏 MarkdownEditor。
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        onEditRequest = {
                            renaming = false
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            showTypeSheet = false
                            noteEditorDraft = note
                            noteEditorCloseRequested = false
                            noteEditorOpen = true
                        },
                    )

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = BORDER)
                    Spacer(Modifier.height(20.dp))

                    // 颜色
                    DetailSectionLabel(stringResource(R.string.section_color))
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Semantic.Spacing.Inline.dp),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Semantic.Spacing.Inline.dp)) {
                        EVENT_COLORS.forEachIndexed { idx, c ->
                            Box(
                                modifier = Modifier
                                    .size(Tokens.Platform.Phone.Geometry.TouchTargetMin.dp)
                                    .clip(CircleShape)
                                    .semantics { contentDescription = "颜色 ${idx + 1}"; selected = idx == colorId }
                                    .clickable {
                                        colorId = idx
                                        persistDraft()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(32.dp).background(c, CircleShape)
                                    .border(if (idx == colorId) Tokens.Semantic.Stroke.Focus.dp else Tokens.Semantic.Stroke.Control.dp,
                                        if (idx == colorId) FOCUS else BORDER, CircleShape))
                            }
                        }
                    }

                    Spacer(Modifier.height(40.dp))
                }
            }

            // 修改类型底部面板
            if (showTypeSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showTypeSheet = false },
                    sheetState       = typeSheetState,
                    containerColor   = SURFACE,
                    dragHandle       = null,
                ) {
                    ItemTypeChangeSheet(
                        currentType = type,
                        onSelect = { newType ->
                            if (newType != type) {
                                type = newType
                                // 切换到非任务类型时清空清单和完成状态
                                if (newType != "task") {
                                    checklist = emptyList()
                                    completed = false
                                }
                                persistDraft()
                            }
                            showTypeSheet = false
                        },
                        onDismiss = { showTypeSheet = false }
                    )
                }
            }

            if (noteEditorOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(10f)
                        .imePadding(),
                ) {
                    // The visual fullscreen rectangle is also a modal hit-test rectangle. This
                    // sibling catches title/padding/blank-area input that no editor child handles,
                    // so it can never fall through to the detail screen underneath.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(SURFACE)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false).consume()
                                    do {
                                        val pointerEvent = awaitPointerEvent()
                                        pointerEvent.changes.forEach { it.consume() }
                                    } while (pointerEvent.changes.any { it.pressed })
                                }
                            },
                    )
                    MarkdownEditor(
                        value = noteEditorDraft,
                        onValueChange = { text ->
                            noteEditorDraft = text
                            persistDraft()
                        },
                        placeholder = stringResource(R.string.note_placeholder),
                        onSaveAndClose = ::closeNoteEditor,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 8.dp,
                            bottom = 24.dp,
                        ),
                        showToolbar = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailSectionLabel(text: String) {
    Text(text, color = GOLD_DARK, fontSize = TPlannerTypography.PhoneMetaSp.sp, letterSpacing = TPlannerTypography.PhoneLetterSpacingSp.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun RecurrenceOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(TPlannerGeometry.RadiusChipDp.dp))
            .background(
                if (selected) GOLD else SURFACE_LOW,
                RoundedCornerShape(TPlannerGeometry.RadiusChipDp.dp),
            )
            .border(1.dp, if (selected) FOCUS else BORDER, RoundedCornerShape(TPlannerGeometry.RadiusChipDp.dp))
            .clickable(enabled = !selected, onClick = onClick)
            .heightIn(min = Tokens.Platform.Phone.Geometry.ControlMinHeight.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (selected) ON_ACCENT else TEXT_PRIMARY,
            fontSize = TPlannerTypography.PhoneMetaSp.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun TimeChip(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(TPlannerGeometry.RadiusPanelDp.dp))
            .background(SURFACE_LOW, RoundedCornerShape(TPlannerGeometry.RadiusPanelDp.dp))
            .border(1.dp, BORDER, RoundedCornerShape(TPlannerGeometry.RadiusPanelDp.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, color = DIM, fontSize = TPlannerTypography.PhoneBadgeSp.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = TEXT_PRIMARY, fontSize = TPlannerTypography.PhoneSupportingSp.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChecklistRow(item: CheckItem, onToggle: () -> Unit, onTextChange: (String) -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Checkbox(
            checked = item.completed, onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = GOLD, uncheckedColor = BORDER, checkmarkColor = ON_ACCENT),
        )
        BasicTextField(
            value = item.text,
            onValueChange = onTextChange,
            textStyle = TextStyle(
                color = if (item.completed) DIM else TEXT_PRIMARY,
                fontSize = TPlannerTypography.PhoneTaskTitleSp.sp,
                textDecoration = if (item.completed) TextDecoration.LineThrough else TextDecoration.None
            ),
            cursorBrush = SolidColor(FOCUS),
            singleLine  = true,
            modifier    = Modifier.weight(1f)
        )
        TPlannerIconButton(Icons.Default.Delete, stringResource(R.string.cd_delete), onDelete)
    }
}
