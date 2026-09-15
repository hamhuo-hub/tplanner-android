package com.hamhuo.tplanner

import com.hamhuo.tplanner.syncv5.JcalDocument
import java.time.Instant

/**
 * Uncommitted local form draft produced by the creation flow.
 *
 * This is not a sync domain model: [toDocument] turns the user's answers into the canonical jCal
 * document, and only that document is persisted and exchanged. A field the user never set stays
 * null, so an absent time is never invented.
 */
data class WatchTaskDraft(
    val id: String,
    val title: String,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
    val colorId: Int,
) {
    fun toDocument(): JcalDocument = JcalDocument.task(
        title = title,
        start = startEpochMs?.let(Instant::ofEpochMilli),
        due = endEpochMs?.let(Instant::ofEpochMilli),
        uid = id,
    ).withColorId(colorId)
}
