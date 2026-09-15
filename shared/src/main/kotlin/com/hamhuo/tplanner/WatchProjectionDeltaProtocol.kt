package com.hamhuo.tplanner

import com.hamhuo.tplanner.syncv3.Jcs
import com.hamhuo.tplanner.syncv3.SnapshotDeltaCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Delta of the bounded watch projection, not of the central server's full entity mirror.
 * An exact parent fingerprint permits coalescing several phone snapshots into one commit.
 * The fingerprint includes tasks, recurrence metadata and central provenance, not just dial days.
 */
object WatchProjectionDeltaProtocol {
    const val DELTA_VERSION = 1
    private const val OPERATION = "scheduleDelta"
    private const val HELLO = "scheduleDeltaHello"
    private const val BASE = "scheduleDeltaBase"
    private val hashPattern = Regex("sha256:[0-9a-f]{64}")
    private val types = mapOf(
        "task.put" to "tasks", "task.remove" to "tasks",
        "day.put" to "days", "day.remove" to "days", "metadata.put" to "metadata",
    )
    private val removeTypes = setOf("task.remove", "day.remove")

    data class Baseline(val version: Long, val stateHash: String)

    fun baseline(snapshot: String): Baseline {
        val state = parse(snapshot)
        requireSnapshot(state)
        return Baseline(integer(state, "version"), SnapshotDeltaCore.canonicalStateHash(state))
    }

    fun encodeHello(): String = envelope(HELLO).toString()

    fun isHello(raw: String): Boolean = runCatching {
        val value = parse(raw)
        value.optString("operation") == HELLO && integer(value, "deltaVersion") == 1L
    }.getOrDefault(false)

    fun encodeBaseline(baseline: Baseline?): String = envelope(BASE).apply {
        put("baseline", baseline?.let(::baselineJson) ?: JSONObject.NULL)
    }.toString()

    fun decodeBaseline(raw: String): Baseline? {
        val value = parse(raw)
        require(value.getString("operation") == BASE && integer(value, "deltaVersion") == 1L)
        require(value.has("baseline"))
        return if (value.isNull("baseline")) null else parseBaseline(value.getJSONObject("baseline"))
    }

    internal fun baselineJson(baseline: Baseline): JSONObject {
        require(baseline.version >= 0L && hashPattern.matches(baseline.stateHash))
        return JSONObject().put("version", baseline.version).put("stateHash", baseline.stateHash)
    }

    internal fun parseBaseline(value: JSONObject): Baseline {
        val version = integer(value, "version")
        val hash = value.get("stateHash") as? String ?: error("DELTA_BASE_HASH_MISSING")
        require(hashPattern.matches(hash)) { "DELTA_BASE_HASH_INVALID" }
        return Baseline(version, hash)
    }

    fun isDelta(raw: String): Boolean = runCatching {
        parse(raw).optString("operation") == OPERATION
    }.getOrDefault(false)

    /** Never send a larger patch or guess a missing/invalid baseline. The full target is durable. */
    fun create(base: String?, target: String): String {
        val next = parse(target)
        requireSnapshot(next)
        if (base == null) return target
        return runCatching {
            val previous = parse(base)
            requireSnapshot(previous)
            require(integer(next, "version") > integer(previous, "version"))
            require(integer(next, "sourceSnapshotVersion") >= integer(previous, "sourceSnapshotVersion"))
            val before = toMirror(previous)
            val after = toMirror(next)
            val changes = JSONArray()
            for ((table, putType, removeType) in listOf(
                Triple("tasks", "task.put", "task.remove"),
                Triple("days", "day.put", "day.remove"),
                Triple("metadata", "metadata.put", ""),
            )) {
                val oldMap = before.getJSONObject(table)
                val newMap = after.getJSONObject(table)
                oldMap.keys().asSequence().toList().sorted().forEach { id ->
                    if (!newMap.has(id)) {
                        require(removeType.isNotEmpty())
                        changes.put(JSONObject().put("type", removeType).put("entityId", id))
                    }
                }
                newMap.keys().asSequence().toList().sorted().forEach { id ->
                    val value = newMap.getJSONObject(id)
                    if (!oldMap.has(id) || Jcs.canonicalize(oldMap.get(id)) != Jcs.canonicalize(value)) {
                        changes.put(JSONObject().put("type", putType).put("entityId", id).put("value", value))
                    }
                }
            }
            val wire = envelope(OPERATION).apply {
                put("schemaVersion", 3)
                put("version", integer(next, "version"))
                put("hash", next.getString("hash")) // Legacy receipt identifies the reconstructed target.
                put("parentVersion", integer(previous, "version"))
                put("baseStateHash", SnapshotDeltaCore.canonicalStateHash(previous))
                put("stateHashAfter", SnapshotDeltaCore.canonicalStateHash(next))
                put("changes", changes)
            }.toString()
            require(wire.toByteArray(Charsets.UTF_8).size < target.toByteArray(Charsets.UTF_8).size)
            // Ensures array ordering/unknown metadata can round-trip before offering the patch.
            apply(base, wire)
            wire
        }.getOrDefault(target)
    }

    /** Reconstruct on a copy. The caller must validate and commit result + baseline atomically. */
    fun apply(base: String?, wire: String): String {
        val delta = parse(wire)
        if (delta.optString("operation") != OPERATION) return wire
        require(integer(delta, "schemaVersion") == 3L && integer(delta, "deltaVersion") == 1L) {
            "DELTA_SCHEMA_UNSUPPORTED"
        }
        val targetVersion = integer(delta, "version")
        val parentVersion = integer(delta, "parentVersion")
        require(targetVersion > parentVersion) { "DELTA_VERSION_GAP" }
        val expectedHash = delta.getString("stateHashAfter")
        val baseHash = delta.getString("baseStateHash")
        require(hashPattern.matches(expectedHash) && hashPattern.matches(baseHash))
        val current = parse(requireNotNull(base) { "NO_DELTA_BASELINE" })
        requireSnapshot(current)
        val input = delta.getJSONArray("changes")
        require(input.length() <= 400) { "DELTA_TOO_MANY_CHANGES" }
        val seen = mutableSetOf<Pair<String, String>>()
        val changes = (0 until input.length()).map { index ->
            val change = input.getJSONObject(index)
            val type = change.getString("type")
            val table = requireNotNull(types[type]) { "UNKNOWN_DELTA_TYPE:$type" }
            val id = change.getString("entityId")
            require(id.isNotBlank() && seen.add(table to id)) { "DELTA_DUPLICATE_ENTITY" }
            if (table == "metadata") require(id == "snapshot")
            val value = if (type in removeTypes) {
                require(!change.has("value"))
                null
            } else {
                change.getJSONObject("value").also {
                    if (table == "tasks") require(it.getString("id") == id)
                    if (table == "days") require(it.getString("date") == id)
                    if (table == "metadata") require(!it.has("tasks") && !it.has("days"))
                }
            }
            SnapshotDeltaCore.Change(type, id, value)
        }
        if (integer(current, "version") == targetVersion &&
            SnapshotDeltaCore.canonicalStateHash(current) == expectedHash
        ) {
            require(current.getString("hash") == delta.getString("hash"))
            return base // Lost ACK: the exact target is already committed.
        }
        SnapshotDeltaCore.requireParent(integer(current, "version"), parentVersion)
        SnapshotDeltaCore.verifyHash(current, baseHash)
        val result = fromMirror(SnapshotDeltaCore.applyToMirror(toMirror(current), changes, types, removeTypes))
        requireSnapshot(result)
        require(integer(result, "version") == targetVersion && result.getString("hash") == delta.getString("hash"))
        require(integer(result, "sourceSnapshotVersion") >= integer(current, "sourceSnapshotVersion"))
        SnapshotDeltaCore.verifyHash(result, expectedHash)
        return result.toString().also { ScheduleRfcommProtocol.encodePayload(it) }
    }

    private fun toMirror(state: JSONObject): JSONObject {
        val metadata = JSONObject(state.toString()).apply { remove("tasks"); remove("days") }
        fun index(array: JSONArray, idField: String): JSONObject = JSONObject().apply {
            for (i in 0 until array.length()) {
                val value = array.getJSONObject(i)
                val id = value.getString(idField)
                require(id.isNotBlank() && !has(id)) { "DELTA_DUPLICATE_ENTITY" }
                put(id, value)
            }
        }
        return JSONObject().put("metadata", JSONObject().put("snapshot", metadata))
            .put("days", index(state.getJSONArray("days"), "date"))
            .put("tasks", index(state.getJSONArray("tasks"), "id"))
    }

    private fun fromMirror(mirror: JSONObject): JSONObject {
        fun values(table: String): List<JSONObject> {
            val map = mirror.getJSONObject(table)
            return map.keys().asSequence().map { map.getJSONObject(it) }.toList()
        }
        return JSONObject(mirror.getJSONObject("metadata").getJSONObject("snapshot").toString()).apply {
            put("days", JSONArray(values("days").sortedBy { it.getString("date") }))
            put("tasks", JSONArray(values("tasks").sortedWith(compareBy(
                { it.getLong("startEpochMs") }, { it.getLong("endEpochMs") }, { it.getString("id") },
            ))))
        }
    }

    private fun requireSnapshot(value: JSONObject) {
        require(integer(value, "schemaVersion") == 3L && !value.has("operation"))
        integer(value, "version")
        require(integer(value, "sourceSnapshotVersion") > 0L)
        require(Regex("[0-9a-f]{64}").matches(value.getString("hash")))
        require(value.getJSONArray("days").length() in 1..31)
        require(value.getJSONArray("tasks").length() in 0..128)
    }

    private fun envelope(operation: String): JSONObject =
        JSONObject().put("operation", operation).put("deltaVersion", DELTA_VERSION)

    private fun parse(raw: String): JSONObject {
        ScheduleRfcommProtocol.encodePayload(raw)
        return JSONObject(raw)
    }

    private fun integer(value: JSONObject, field: String): Long {
        val number = when (val raw = value.get(field)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> throw IllegalArgumentException("$field must be an integer")
        }
        require(number >= 0L) { "$field must be non-negative" }
        return number
    }
}
