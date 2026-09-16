package com.hamhuo.tplanner.calendar

import com.hamhuo.tplanner.syncv5.JcalDocument
import org.json.JSONObject

/**
 * Text codecs between the canonical jCal document and the raw `CalendarContract` string columns.
 *
 * Nothing here reads provider state back into task facts; these are the two representations of the
 * same projection, and the comparison helpers exist only so a retry rewrites what is wrong instead
 * of duplicating what already matches.
 */

/** Canonical times are UTC instants or date-only values, so this is the only provider timezone written. */
internal const val PROJECTION_TIME_ZONE = "UTC"

/** Ownership marker written to `SYNC_DATA1`; `_SYNC_ID` carries the bare record UID. */
private const val OWNERSHIP_PREFIX = "com.hamhuo.tplanner.calendar/1/"

internal fun ownershipMarker(uid: String): String = OWNERSHIP_PREFIX + uid

/** The record UID an owned provider row carries, or null when the row is not owned by this app. */
internal fun ownershipUid(syncId: String?, syncData1: String?): String? =
    syncId?.takeIf { it.isNotBlank() }
        ?: syncData1?.takeIf { it.startsWith(OWNERSHIP_PREFIX) }
            ?.removePrefix(OWNERSHIP_PREFIX)
            ?.takeIf { it.isNotBlank() }

/** RFC 5545 duration text. The provider stores duration as `P<days>D` (all-day) or `P<seconds>S`. */
internal object CalendarDurations {
    private const val DAY_SECONDS = 86_400L
    private const val DAY_MILLIS = 86_400_000L
    private const val MAX_UNIT = 1_000_000_000L

    internal fun daysFromMillis(millis: Long): Long = (millis / DAY_MILLIS).coerceAtLeast(1L)

    internal fun secondsFromMillis(millis: Long): Long = ((millis + 999L) / 1000L).coerceIn(1L, MAX_UNIT)

    internal fun secondsFromDays(days: Long): Long = days.coerceAtLeast(1L) * DAY_SECONDS

    fun text(seconds: Long, allDay: Boolean): String =
        if (allDay) "P${(seconds / DAY_SECONDS).coerceAtLeast(1L)}D" else "P${seconds.coerceIn(1L, MAX_UNIT)}S"

    /**
     * Total seconds for any RFC 5545 duration the provider may have stored, or null when the text
     * cannot be understood — a null result never matches a desired row, so it is rewritten.
     */
    fun seconds(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        var value = text.trim().uppercase()
        var negative = false
        if (value.startsWith("-") || value.startsWith("+")) {
            negative = value.startsWith("-")
            value = value.substring(1)
        }
        if (!value.startsWith("P")) return null
        value = value.substring(1)
        var total = 0L
        var inTime = false
        var index = 0
        while (index < value.length) {
            if (value[index] == 'T') {
                inTime = true
                index++
                continue
            }
            val start = index
            while (index < value.length && value[index].isDigit()) index++
            if (start == index) return null
            val number = value.substring(start, index).toLongOrNull() ?: return null
            if (number > MAX_UNIT || index >= value.length) return null
            val unit = value[index]
            index++
            val factor = when (unit) {
                'W' -> 604_800L
                'D' -> DAY_SECONDS
                'H' -> if (inTime) 3_600L else return null
                'M' -> if (inTime) 60L else return null
                'S' -> if (inTime) 1L else return null
                else -> return null
            }
            total += number * factor
        }
        return if (negative) -total else total
    }
}

/** Every recurrence part Android's provider parses; `X-` parts are ignored by it and kept here. */
private val PROVIDER_RULE_PARTS = setOf(
    "FREQ", "UNTIL", "COUNT", "INTERVAL", "BYSECOND", "BYMINUTE", "BYHOUR", "BYDAY",
    "BYMONTHDAY", "BYYEARDAY", "BYWEEKNO", "BYMONTH", "BYSETPOS", "WKST",
)

private val PROVIDER_FREQUENCIES =
    setOf("SECONDLY", "MINUTELY", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")

private val NUMERIC_RULE_PARTS = setOf(
    "COUNT", "INTERVAL", "BYSECOND", "BYMINUTE", "BYHOUR", "BYMONTHDAY", "BYYEARDAY",
    "BYWEEKNO", "BYMONTH", "BYSETPOS",
)

private val WEEKDAYS = setOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

private val BYDAY_VALUE = Regex("[+-]?\\d{0,2}(MO|TU|WE|TH|FR|SA|SU)")

/**
 * Translates the canonical jCal `recur` object into the RFC 5545 RRULE text the provider stores.
 *
 * Returns null when the rule cannot be represented, so the caller can keep the record inside
 * TPlanner and report it locally instead of writing a row the provider rejects on every retry.
 */
internal fun providerRecurrence(rule: JSONObject?): String? {
    if (rule == null || rule.length() == 0 || !rule.has("freq")) return null
    val parts = JcalDocument.ruleText(rule).split(';').map(String::trim).filter(String::isNotEmpty)
    if (parts.isEmpty()) return null
    val named = LinkedHashMap<String, String>()
    for (part in parts) {
        val separator = part.indexOf('=')
        if (separator <= 0 || separator == part.length - 1) return null
        val name = part.substring(0, separator).uppercase()
        if (name !in PROVIDER_RULE_PARTS && !name.startsWith("X-")) return null
        named[name] = part.substring(separator + 1)
    }
    val frequency = named["FREQ"]?.uppercase() ?: return null
    if (frequency !in PROVIDER_FREQUENCIES) return null
    named.forEach { (name, value) ->
        when {
            name in NUMERIC_RULE_PARTS ->
                if (value.split(',').any { it.trim().toLongOrNull() == null }) return null
            name == "BYDAY" ->
                if (value.split(',').any { !BYDAY_VALUE.matches(it.trim().uppercase()) }) return null
            name == "WKST" -> if (value.trim().uppercase() !in WEEKDAYS) return null
            else -> Unit
        }
    }
    // FREQ comes first because the provider's parser requires it; the remaining parts are sorted so
    // the same canonical rule always produces the same text and never rewrites an unchanged row.
    val ordered = ArrayList<Pair<String, String>>(named.size)
    ordered += "FREQ" to frequency
    named.filterKeys { it != "FREQ" }.toSortedMap().forEach { (name, value) -> ordered += name to value }
    return ordered.joinToString(";") { (name, value) -> "$name=$value" }
}

/** Part order and case are not semantic in RFC 5545; `WKST` alone changes nothing here. */
internal fun normalizedRule(rule: String?): String =
    rule?.split(';')
        ?.map { it.trim().uppercase() }
        ?.filter { it.isNotEmpty() && !it.startsWith("WKST=") }
        ?.sorted()
        ?.joinToString(";")
        .orEmpty()

private val BASIC_UTC = java.time.format.DateTimeFormatter
    .ofPattern("yyyyMMdd'T'HHmmss'Z'")
    .withZone(java.time.ZoneOffset.UTC)

/**
 * Provider `EXDATE` text for the occurrences this record cancels.
 *
 * A cancelled occurrence is a standard recurrence fact, so the system calendar has to skip it as
 * well: otherwise TPlanner and the calendar it feeds would disagree about which days the task
 * happens. Values are written in RFC 5545 basic UTC form, separated by `;`.
 */
internal fun providerExdate(document: JcalDocument): String? {
    val components = document.calendar.getJSONArray(2)
    val master = (0 until components.length())
        .map { components.getJSONArray(it) }
        .firstOrNull {
            it.getString(0) in setOf("vtodo", "vjournal") &&
                JcalDocument.property(it, "recurrence-id") == null
        } ?: return null
    val props = master.getJSONArray(1)
    val instants = mutableListOf<java.time.Instant>()
    for (index in 0 until props.length()) {
        val prop = props.getJSONArray(index)
        if (prop.optString(0) != "exdate") continue
        for (slot in 3 until prop.length()) {
            // Reuse the canonical parser so a TZID-qualified value resolves exactly as it does
            // everywhere else; anything unreadable is skipped rather than guessed at.
            val single = org.json.JSONArray()
                .put(prop.getString(0))
                .put(prop.optJSONObject(1) ?: JSONObject())
                .put(prop.getString(2))
                .put(prop.getString(slot))
            runCatching { JcalDocument.instant(single) }.getOrNull()?.let(instants::add)
        }
    }
    if (instants.isEmpty()) return null
    return instants.distinct().sorted().joinToString(";") { BASIC_UTC.format(it) }
}

/** EXDATE order is not semantic, so the comparison sorts the instants before comparing. */
internal fun normalizedExdate(text: String?): String =
    text?.split(';')
        ?.map { it.trim().uppercase() }
        ?.filter(String::isNotEmpty)
        ?.sorted()
        ?.joinToString(";")
        .orEmpty()
