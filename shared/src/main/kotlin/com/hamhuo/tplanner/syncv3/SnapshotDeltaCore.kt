package com.hamhuo.tplanner.syncv3

import org.json.JSONObject
import java.security.MessageDigest

/** Pure copy/apply/verify primitives shared by central V4 delta and the Bluetooth projection. */
object SnapshotDeltaCore {
    data class Change(val type: String, val entityId: String, val value: JSONObject?)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun canonicalStateHash(state: JSONObject): String =
        "sha256:${sha256Hex(Jcs.canonicalize(state).toByteArray(Charsets.UTF_8))}"

    fun requireParent(expected: Long, parent: Long) {
        require(parent == expected) { "DELTA_VERSION_GAP: parent $parent, expected $expected" }
    }

    fun verifyHash(state: JSONObject, expected: String) {
        require(canonicalStateHash(state) == expected) { "DELTA_HASH_MISMATCH" }
    }

    /** Central deletes remain tombstone puts. Only explicitly declared projection types remove. */
    fun applyToMirror(
        mirror: JSONObject,
        changes: List<Change>,
        mapKeyByType: Map<String, String>,
        removeTypes: Set<String> = emptySet(),
    ): JSONObject {
        val next = JSONObject(mirror.toString())
        changes.forEach { change ->
            val key = requireNotNull(mapKeyByType[change.type]) { "UNKNOWN_DELTA_TYPE:${change.type}" }
            require(change.entityId.isNotBlank()) { "DELTA_ENTITY_ID_MISSING" }
            val table = next.getJSONObject(key)
            if (change.type in removeTypes) {
                table.remove(change.entityId)
            } else {
                // Detach change values too: the returned mirror must not alias caller-owned JSON.
                table.put(change.entityId, JSONObject(requireNotNull(change.value).toString()))
            }
        }
        return next
    }
}
