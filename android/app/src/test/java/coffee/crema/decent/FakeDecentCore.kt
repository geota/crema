package coffee.crema.decent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A JVM stand-in for the core's Decent classifiers (the native library does
 * not load in unit tests). It answers with the same JSON shapes and the same
 * rules as `de1_domain::decent_wire` — the contract itself is tested in core;
 * here it only lets the shell's IO / mapping be exercised.
 */
class FakeDecentCore : DecentCore {
    var lastShotJson: String? = null
    var lastMachineJson: String? = null

    override fun shotRecordJson(shotJson: String, machineJson: String, appVersion: String): String {
        lastShotJson = shotJson
        lastMachineJson = machineJson
        return """{"app":{"version":"$appVersion"}}"""
    }

    private fun snippet(s: String) = s.trim().take(200)
    private fun ok(status: Int) = status in 200..299
    private fun retry(status: Int, body: String) = buildJsonObject {
        put("type", "Retry")
        put("content", buildJsonObject { put("status", status); put("detail", snippet(body)) })
    }.toString()

    override fun loginReplyJson(status: Int, body: String): String {
        val t = body.trim()
        if (ok(status)) {
            return if (t.isEmpty() || t == "0" || t.length <= 4) """{"type":"Rejected"}"""
            else buildJsonObject { put("type", "Token"); put("content", buildJsonObject { put("token", t) }) }.toString()
        }
        return if (status == 401) """{"type":"Rejected"}""" else retry(status, body)
    }

    override fun machinesReplyJson(status: Int, body: String): String {
        val t = body.trim()
        if (ok(status) && t != "0") {
            val seen = HashSet<String>()
            val machines = buildJsonArray {
                t.lineSequence().forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    val serial = parts.firstOrNull().orEmpty()
                    if (serial.isNotEmpty() && seen.add(serial)) {
                        add(buildJsonObject { put("serial", serial); put("sku", parts.getOrNull(1).orEmpty()) })
                    }
                }
            }
            return buildJsonObject { put("type", "Machines"); put("content", buildJsonObject { put("machines", machines) }) }.toString()
        }
        return if (status == 401 || ok(status)) """{"type":"Auth"}""" else retry(status, body)
    }

    override fun uploadReplyJson(status: Int, body: String): String {
        val t = body.trim()
        if (ok(status)) {
            if (t == "0") return """{"type":"Auth"}"""
            return buildJsonObject {
                put("type", "Uploaded")
                put("content", buildJsonObject { put("id", extractId(t)) })
            }.toString()
        }
        if (status == 401) return """{"type":"Auth"}"""
        if (status in 400..499 && status !in setOf(403, 408, 429)) {
            return buildJsonObject {
                put("type", "Rejected")
                put("content", buildJsonObject { put("status", status); put("body", snippet(body)) })
            }.toString()
        }
        return retry(status, body)
    }

    private fun extractId(t: String): String? {
        if (t.isEmpty()) return null
        val el = runCatching { Json.parseToJsonElement(t) }.getOrNull()
        val raw = when (el) {
            is JsonObject -> listOf("id", "shot_id", "shotId").firstNotNullOfOrNull { (el[it] as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> el.contentOrNull
            is JsonArray -> null
            null -> t.takeIf { Regex("^[A-Za-z0-9_-]{1,64}$").matches(it) }
            else -> null
        }
        return raw?.takeIf { it.isNotBlank() && it != "0" && !it.equals("ok", ignoreCase = true) }
    }

    override fun shotViewUrl(serial: String?, decentId: String?): String? {
        if (serial.isNullOrBlank() || decentId.isNullOrBlank() || decentId.startsWith("uploaded:")) return null
        return "https://decentespresso.com/shot/$serial/$decentId"
    }
}
