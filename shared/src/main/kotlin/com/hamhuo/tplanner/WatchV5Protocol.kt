package com.hamhuo.tplanner

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Transport framing only: batch, receipt and snapshot bodies are the shared V5 JSON. */
object WatchV5Protocol {
    const val REQUEST_PATH = "/tplanner/v5/requests/"
    const val RESPONSE_PATH = "/tplanner/v5/responses/"
    const val ASSET_KEY = "packet"
    const val MAX_BYTES = 16 * 1024 * 1024
    val RFCOMM_UUID: UUID = UUID.fromString("3ef00821-75e8-4da4-b1d9-f986b2f05c15")

    fun request(deviceId: String, kind: String, body: JSONObject? = null): JSONObject =
        JSONObject().put("protocolVersion", 5).put("deviceId", deviceId)
            .put("requestId", UUID.randomUUID().toString()).put("kind", kind).apply {
                if (body != null) put("body", body)
            }

    fun validateRequest(value: JSONObject) {
        require(value.getInt("protocolVersion") == 5)
        UUID.fromString(value.getString("deviceId"))
        UUID.fromString(value.getString("requestId"))
        require(value.getString("kind") in setOf("snapshot", "batch", "installed"))
    }

    fun requestPath(request: JSONObject) = REQUEST_PATH + request.getString("deviceId") + "/" + request.getString("requestId")
    fun responsePath(request: JSONObject) = RESPONSE_PATH + request.getString("deviceId") + "/" + request.getString("requestId")

    fun response(request: JSONObject, body: JSONObject): JSONObject =
        JSONObject().put("protocolVersion", 5).put("requestId", request.getString("requestId"))
            .put("deviceId", request.getString("deviceId")).put("body", body)

    fun responseBody(request: JSONObject, response: JSONObject): JSONObject {
        require(response.getInt("protocolVersion") == 5)
        require(response.getString("deviceId") == request.getString("deviceId"))
        require(response.getString("requestId") == request.getString("requestId"))
        if (response.has("error")) error(response.getString("error"))
        return response.getJSONObject("body")
    }

    fun write(output: OutputStream, raw: String) {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES) { "V5_PACKET_TOO_LARGE" }
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
    }

    fun read(input: InputStream): String {
        val stream = DataInputStream(input)
        val size = stream.readInt()
        require(size in 1..MAX_BYTES) { "V5_PACKET_TOO_LARGE" }
        return ByteArray(size).also(stream::readFully).toString(Charsets.UTF_8)
    }
}
