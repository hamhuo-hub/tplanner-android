package com.hamhuo.tplanner.syncv5

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One atomic document store: server mirror + local document revisions + immutable in-flight command. */
class V5Store(context: Context, namespace: String = "tplanner_v5") {
    private val prefs = context.applicationContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val lock = locks.getOrPut(namespace) { Any() }
    private var cachedState: String? = null
    private var cachedDocuments: List<JcalDocument> = emptyList()
    init { synchronized(lock) { if (!prefs.contains("state")) write(empty()) } }
    private fun empty() = JSONObject().put("deviceId", UUID.randomUUID().toString()).put("nextSequence", 1L)
        .put("revision", 0L).put("records", JSONArray()).put("queue", JSONArray()).put("conflicts", JSONArray())
    private fun read() = JSONObject(prefs.getString("state", null) ?: error("V5 store unavailable"))
    private fun write(state: JSONObject) {
        val encoded = state.toString()
        check(prefs.edit().putString("state", encoded).commit()) { "无法保存本地数据" }
        cachedState = encoded
    }
    private fun mutate(block: (JSONObject) -> Unit) = synchronized(lock) { val state = read(); block(state); write(state) }
    val deviceId: String get() = synchronized(lock) { read().getString("deviceId") }
    val revision: Long get() = synchronized(lock) { read().getLong("revision") }
    val serverId: String? get() = synchronized(lock) { read().optString("serverId").takeIf(String::isNotBlank) }
    val pendingCount: Int get() = synchronized(lock) { read().let { it.getJSONArray("queue").length() + if (it.has("inFlight")) 1 else 0 } }
    val lastError: String? get() = synchronized(lock) { read().optString("lastError").takeIf(String::isNotBlank) }
    fun setError(error: String?) = mutate { if (error == null) it.remove("lastError") else it.put("lastError", error) }
    fun snapshot(): JSONObject = synchronized(lock) { read().let { state ->
        JSONObject().put("protocolVersion", 5).put("serverId", state.optString("serverId"))
            .put("revision", state.getLong("revision")).put("records", state.getJSONArray("records"))
    } }

    /**
     * The UI's live view: installed records with every unaccepted local document overlaid.
     *
     * The projection is memoized against the raw stored state, so a recomposition or a repeated
     * read does not re-validate the whole dataset; [write] is the only mutation path and keeps the
     * cache honest.
     */
    fun documents(): List<JcalDocument> = synchronized(lock) {
        val raw = prefs.getString("state", null) ?: error("V5 store unavailable")
        if (raw == cachedState) return cachedDocuments
        val state = JSONObject(raw)
        val result = linkedMapOf<String, JcalDocument>()
        state.getJSONArray("records").objects().filterNot { it.getBoolean("deleted") }.forEach {
            val doc = JcalDocument(it.getJSONArray("calendar")); result[doc.uid] = doc
        }
        fun overlay(command: JSONObject) {
            if (command.getString("operation") == "delete") result.remove(command.getString("uid")) else {
                val doc = JcalDocument(command.getJSONArray("calendar")); result[doc.uid] = doc
            }
        }
        state.optJSONObject("inFlight")?.let(::overlay)
        state.getJSONArray("queue").objects().forEach(::overlay)
        state.getJSONArray("conflicts").objects().forEach { overlay(it.getJSONObject("command")) }
        val projected = result.values.toList()
        cachedState = raw
        cachedDocuments = projected
        projected
    }
    fun put(document: JcalDocument) = putAll(listOf(document))
    fun putAll(documents: List<JcalDocument>) = mutate { state ->
        require(documents.map { it.uid }.distinct().size == documents.size)
        documents.forEach { enqueue(state, JSONObject().put("operation", "put").put("calendar", it.calendar)) }
    }
    fun delete(uid: String) = mutate { state -> enqueue(state, JSONObject().put("operation", "delete").put("uid", uid)) }
    private fun enqueue(state: JSONObject, desired: JSONObject) {
        val uid = commandUid(desired)
        require(state.getJSONArray("conflicts").objects().none { commandUid(it.getJSONObject("command")) == uid }) { "请先处理此事项的同步冲突" }
        val queue = state.getJSONArray("queue")
        val existing = queue.objects().firstOrNull { commandUid(it) == uid }
        desired.put("baseRevision", existing?.getLong("baseRevision") ?: recordRevision(state, uid))
        for (i in queue.length() - 1 downTo 0) if (commandUid(queue.getJSONObject(i)) == uid) queue.remove(i)
        queue.put(desired)
    }
    /** Returns the exact same immutable command after uncertain transport outcomes. */
    fun prepareBatch(): JSONObject? = synchronized(lock) {
        val state = read()
        var command = state.optJSONObject("inFlight")
        if (command == null) {
            val queue = state.getJSONArray("queue")
            if (queue.length() == 0) return@synchronized null
            command = queue.getJSONObject(0); queue.remove(0)
            command.put("commandId", UUID.randomUUID().toString()).put("sequence", state.getLong("nextSequence"))
            state.put("inFlight", command); write(state)
        }
        JSONObject().put("protocolVersion", 5).put("deviceId", state.getString("deviceId"))
            .put("commands", JSONArray().put(command))
    }
    fun acceptReceipt(response: JSONObject) = mutate { state ->
        require(response.getInt("protocolVersion") == 5)
        acceptServer(state, response.getString("serverId"))
        val command = state.getJSONObject("inFlight")
        val receipts = response.getJSONArray("receipts")
        require(receipts.length() == 1)
        val receipt = receipts.getJSONObject(0)
        require(receipt.getString("commandId") == command.getString("commandId") && receipt.getLong("sequence") == command.getLong("sequence")) { "不匹配的同步回执" }
        require(receipt.getString("status") in setOf("applied", "conflict", "rejected"))
        require(receipt.getLong("revision") >= 0 && response.getLong("revision") >= receipt.getLong("revision"))
        state.put("receipt", receipt)
    }
    fun installSnapshot(snapshot: JSONObject) = mutate { state ->
        require(snapshot.getInt("protocolVersion") == 5)
        acceptServer(state, snapshot.getString("serverId"))
        val revision = snapshot.getLong("revision")
        require(revision >= state.getLong("revision")) { "收到过期快照" }
        val records = snapshot.getJSONArray("records")
        val ids = mutableSetOf<String>()
        records.objects().forEach { row ->
            require(row.getLong("revision") in 1..revision)
            require(ids.add(recordUid(row))) { "重复 UID" }
            if (!row.getBoolean("deleted")) JcalDocument(row.getJSONArray("calendar"))
        }
        state.put("records", records).put("revision", revision)
        val receipt = state.optJSONObject("receipt")
        val command = state.optJSONObject("inFlight")
        if (receipt != null && command != null && revision >= receipt.getLong("revision")) {
            val uid = commandUid(command)
            val queue = state.getJSONArray("queue")
            if (receipt.getString("status") == "applied") {
                queue.objects().filter { commandUid(it) == uid }.forEach {
                    // This is a successor to our own write, not a rebase over a remote edit.
                    it.put("baseRevision", receipt.getLong("revision"))
                }
            } else {
                var local = command
                for (i in queue.length() - 1 downTo 0) if (commandUid(queue.getJSONObject(i)) == uid) {
                    local = queue.getJSONObject(i); queue.remove(i)
                }
                state.getJSONArray("conflicts").put(JSONObject().put("command", local).put("receipt", receipt))
            }
            state.put("nextSequence", command.getLong("sequence") + 1L)
            state.remove("inFlight"); state.remove("receipt")
        }
    }
    /** True when the installed mirror already holds this UID as a live record. */
    fun isInstalled(uid: String): Boolean = synchronized(lock) {
        read().getJSONArray("records").objects()
            .any { !it.getBoolean("deleted") && runCatching { recordUid(it) }.getOrNull() == uid }
    }

    /** Unaccepted UIDs, most recent local edit first. Used to resume an interrupted editor. */
    fun pendingUids(): List<String> = synchronized(lock) {
        val state = read()
        val ordered = mutableListOf<String>()
        state.optJSONObject("inFlight")?.let { ordered += commandUid(it) }
        state.getJSONArray("queue").objects().forEach { ordered += commandUid(it) }
        state.getJSONArray("conflicts").objects().forEach { ordered += commandUid(it.getJSONObject("command")) }
        ordered.distinct().reversed()
    }

    fun conflicts(): List<JSONObject> = synchronized(lock) { read().getJSONArray("conflicts").objects() }

    /** True when this UID has a local document that the server has not accepted yet. */
    fun isPending(uid: String): Boolean = synchronized(lock) {
        val state = read()
        state.getJSONArray("queue").objects().any { commandUid(it) == uid } ||
            state.optJSONObject("inFlight")?.let { commandUid(it) == uid } == true ||
            state.getJSONArray("conflicts").objects().any { commandUid(it.getJSONObject("command")) == uid }
    }

    /** Drops local edits for one UID without touching the installed mirror. */
    fun discard(uid: String) = mutate { state ->
        val queue = state.getJSONArray("queue")
        for (i in queue.length() - 1 downTo 0) if (commandUid(queue.getJSONObject(i)) == uid) queue.remove(i)
        state.optJSONObject("inFlight")?.let { if (commandUid(it) == uid) state.remove("inFlight") }
        val conflicts = state.getJSONArray("conflicts")
        for (i in conflicts.length() - 1 downTo 0) {
            if (commandUid(conflicts.getJSONObject(i).getJSONObject("command")) == uid) conflicts.remove(i)
        }
    }
    fun resolveConflict(uid: String, reapply: Boolean) = mutate { state ->
        val conflicts = state.getJSONArray("conflicts")
        for (i in conflicts.length() - 1 downTo 0) {
            val item = conflicts.getJSONObject(i)
            val command = item.getJSONObject("command")
            if (commandUid(command) == uid) {
                conflicts.remove(i)
                if (reapply) {
                    command.remove("commandId"); command.remove("sequence")
                    command.put("baseRevision", recordRevision(state, uid))
                    state.getJSONArray("queue").put(command)
                }
            }
        }
    }
    /** Explicit user action only; device identity must never be reused against a fresh server. */
    fun reset() = synchronized(lock) { write(empty()) }
    fun subscribe(listener: () -> Unit): () -> Unit {
        val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "state") listener() }
        prefs.registerOnSharedPreferenceChangeListener(callback)
        return { prefs.unregisterOnSharedPreferenceChangeListener(callback) }
    }
    private fun acceptServer(state: JSONObject, id: String) {
        require(id.isNotBlank())
        val old = state.optString("serverId")
        require(old.isBlank() || old == id) { "服务器已更换，请明确重置连接后重试" }
        state.put("serverId", id)
    }
    private fun recordRevision(state: JSONObject, uid: String): Long = state.getJSONArray("records").objects()
        .firstOrNull { recordUid(it) == uid }?.getLong("revision") ?: 0L
    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun commandUid(command: JSONObject): String = if (command.getString("operation") == "delete") command.getString("uid") else JcalDocument(command.getJSONArray("calendar")).uid
        fun recordUid(record: JSONObject): String = if (record.getBoolean("deleted")) record.getString("uid") else JcalDocument(record.getJSONArray("calendar")).uid
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
