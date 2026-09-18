package com.hamhuo.tplanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.ACCENT_TEXT
import com.hamhuo.tplanner.BLUE
import com.hamhuo.tplanner.DIM
import com.hamhuo.tplanner.R
import com.hamhuo.tplanner.RED
import com.hamhuo.tplanner.SURFACE2
import com.hamhuo.tplanner.TEXT_PRIMARY
import com.hamhuo.tplanner.WARNING
import com.hamhuo.tplanner.diagnostics.DiagnosticsEvent
import com.hamhuo.tplanner.diagnostics.DiagnosticsEvents
import com.hamhuo.tplanner.diagnostics.DiagnosticsLevel
import com.hamhuo.tplanner.ui.components.TPlannerIconButton
import com.hamhuo.tplanner.PhoneTypography as TPlannerTypography
import com.hamhuo.tplanner.PhoneGeometry as TPlannerGeometry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 同步日志面板：结构化诊断缓冲区的投影，只读 + 清空。
 *
 * 每条事件在这里被翻译成一句人话，并附带机器字段（trace / command / sequence / code）。
 * 纯诊断，不参与任何正确性路径。
 */
@Composable
fun SyncLogPanel(
    entries: List<DiagnosticsEvent>,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    dropped: Int = 0,
) {
    Card(
        modifier = modifier
            .width(330.dp)
            .heightIn(max = 430.dp)
            .shadow(16.dp, RoundedCornerShape(TPlannerGeometry.RadiusPanelDp.dp)),
        shape = RoundedCornerShape(TPlannerGeometry.RadiusPanelDp.dp),
        colors = CardDefaults.cardColors(containerColor = SURFACE2),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        stringResource(R.string.sync_logs_title),
                        color = DIM,
                        fontSize = TPlannerTypography.PhoneMicroSp.sp,
                        letterSpacing = TPlannerTypography.PhoneLetterSpacingSp.sp,
                    )
                    if (dropped > 0) {
                        Text(
                            stringResource(R.string.sync_logs_dropped, dropped),
                            color = WARNING,
                            fontSize = TPlannerTypography.PhoneMicroSp.sp,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.sync_logs_clear), color = ACCENT_TEXT)
                    }
                    TPlannerIconButton(Icons.Default.Close, "Close", onClose)
                }
            }
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.sync_logs_empty),
                    color = DIM,
                    fontSize = TPlannerTypography.PhoneBadgeSp.sp,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = { "${it.time}-${it.span.spanId}-${it.event}" }) { entry ->
                        DiagnosticsRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsRow(event: DiagnosticsEvent) {
    val levelColor = when (event.level) {
        DiagnosticsLevel.WARN -> WARNING
        DiagnosticsLevel.ERROR -> RED
        else -> BLUE
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatLogTime(event.time),
                color = DIM,
                fontSize = TPlannerTypography.PhoneMicroSp.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                event.component.uppercase(),
                color = levelColor,
                fontSize = TPlannerTypography.PhoneMicroSp.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                diagnosticsSentence(event.event),
                color = TEXT_PRIMARY,
                fontSize = TPlannerTypography.PhoneMicroSp.sp,
            )
        }
        Text(
            diagnosticsAttributes(event),
            color = DIM,
            fontSize = TPlannerTypography.PhoneMicroSp.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** 机器字段：一眼看出这条事件属于哪一轮同步、哪条命令、什么错误。 */
private fun diagnosticsAttributes(event: DiagnosticsEvent): String = buildList {
    event.transport?.let { add(it) }
    event.commandId?.let { add("cmd=${it.take(8)}") }
    event.sequence?.let { add("seq=$it") }
    event.expectedSequence?.let { add("expected=$it") }
    event.revision?.let { add("rev=$it") }
    event.localRevision?.let { add("from=$it") }
    event.queueDepth?.let { add("queue=$it") }
    event.errorCode?.let { add("code=$it") }
    add("trace=${event.span.traceId.take(8)}")
}.joinToString(" · ")

/** 事件词典 → 人话。未知事件名直接显示，便于发现词典外的写入。 */
@ReadOnlyComposable
@Composable
private fun diagnosticsSentence(name: String): String {
    val id = when (name) {
        DiagnosticsEvents.SYNC_RUN_STARTED -> R.string.diagnostics_sync_run_started
        DiagnosticsEvents.SYNC_RUN_COMPLETED -> R.string.diagnostics_sync_run_completed
        DiagnosticsEvents.SYNC_RUN_FAILED -> R.string.diagnostics_sync_run_failed
        DiagnosticsEvents.SYNC_RUN_DEFERRED -> R.string.diagnostics_sync_run_deferred
        DiagnosticsEvents.STORE_BATCH_PREPARED -> R.string.diagnostics_store_batch_prepared
        DiagnosticsEvents.STORE_RECEIPT_ACCEPTED -> R.string.diagnostics_store_receipt_accepted
        DiagnosticsEvents.STORE_RECEIPT_RETAINED -> R.string.diagnostics_store_receipt_retained
        DiagnosticsEvents.STORE_SNAPSHOT_INSTALLED -> R.string.diagnostics_store_snapshot_installed
        DiagnosticsEvents.STORE_INFLIGHT_RELEASED -> R.string.diagnostics_store_inflight_released
        DiagnosticsEvents.STORE_INFLIGHT_RETAINED -> R.string.diagnostics_store_inflight_retained
        DiagnosticsEvents.TRANSPORT_REQUEST_STARTED -> R.string.diagnostics_transport_request_started
        DiagnosticsEvents.TRANSPORT_RESPONSE_RECEIVED -> R.string.diagnostics_transport_response_received
        DiagnosticsEvents.TRANSPORT_REQUEST_COMPLETED -> R.string.diagnostics_transport_request_completed
        DiagnosticsEvents.TRANSPORT_REQUEST_FAILED -> R.string.diagnostics_transport_request_failed
        DiagnosticsEvents.TRANSPORT_RESPONSE_FAILED -> R.string.diagnostics_transport_response_failed
        else -> null
    }
    return if (id != null) stringResource(id) else name
}

private val LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun formatLogTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime().format(LOG_TIME_FORMAT)
