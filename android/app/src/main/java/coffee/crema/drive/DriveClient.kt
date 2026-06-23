package coffee.crema.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/*
 * Google Drive v3 REST — the calls the backup feature needs (Android twin of
 * `web/src/lib/drive/rest.ts`). Scope `drive.file` limits every call to files
 * THIS app created, so listing never sees the user's wider Drive. The caller
 * passes a valid access token (from the Drive auth flow, refreshed as needed).
 */

private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"

/** A backup file in Drive (the subset of fields we request; newest-first on list). */
@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val modifiedTime: String = "",
    val size: String? = null,
)

private fun errorMessage(json: Json, body: String, status: Int, what: String): String {
    val detail = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            ?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return "Drive $what failed (HTTP $status)" + (if (detail != null) " — $detail" else "") + "."
}

/**
 * Upload a backup as a NEW Drive file (multipart: metadata + media). We always
 * create (timestamped names) rather than overwrite, so Drive keeps a short
 * backup history the user can pick from on restore.
 */
suspend fun driveUploadBackup(
    accessToken: String,
    name: String,
    content: ByteArray,
    json: Json,
    mime: String = "application/zip",
): DriveFile =
    withContext(Dispatchers.IO) {
        val boundary = "crema" + System.nanoTime().toString(16)
        val meta = buildJsonObject {
            put("name", name)
            put("mimeType", mime)
        }.toString()
        // Build the multipart body in BYTES so the binary media part (the
        // `.crema.zip`) rides through intact — a String body would mangle it.
        val head = (
            "--$boundary\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                "$meta\r\n" +
                "--$boundary\r\n" +
                "Content-Type: $mime\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--".toByteArray(Charsets.UTF_8)
        val conn = (
            URL("$UPLOAD_URL?uploadType=multipart&fields=id,name,modifiedTime,size")
                .openConnection() as HttpURLConnection
        ).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        }
        try {
            conn.outputStream.use { out -> out.write(head); out.write(content); out.write(tail) }
            val status = conn.responseCode
            val resp = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (status !in 200..299) throw IOException(errorMessage(json, resp, status, "upload"))
            json.decodeFromString(DriveFile.serializer(), resp)
        } finally {
            conn.disconnect()
        }
    }

/** List this app's backup files, newest first. */
suspend fun driveListBackups(accessToken: String, json: Json): List<DriveFile> =
    withContext(Dispatchers.IO) {
        val q = URLEncoder.encode("name contains 'crema-backup' and trashed = false", "UTF-8")
        val fields = URLEncoder.encode("files(id,name,modifiedTime,size)", "UTF-8")
        val url = "$FILES_URL?q=$q&fields=$fields&orderBy=modifiedTime%20desc&pageSize=50"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            val status = conn.responseCode
            val resp = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (status !in 200..299) throw IOException(errorMessage(json, resp, status, "list"))
            val files = runCatching { json.parseToJsonElement(resp).jsonObject["files"]?.jsonArray }.getOrNull()
                ?: return@withContext emptyList()
            files.mapNotNull {
                runCatching { json.decodeFromString(DriveFile.serializer(), it.toString()) }.getOrNull()
            }
        } finally {
            conn.disconnect()
        }
    }

/** Download a backup file's raw bytes by id (a `.crema.zip`, or legacy text — the
 *  caller sniffs the zip magic vs decoding it as text). */
suspend fun driveDownloadBackup(accessToken: String, fileId: String, json: Json): ByteArray =
    withContext(Dispatchers.IO) {
        val url = "$FILES_URL/${URLEncoder.encode(fileId, "UTF-8")}?alt=media"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            val status = conn.responseCode
            if (status in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else {
                val err = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                throw IOException(errorMessage(json, err, status, "download"))
            }
        } finally {
            conn.disconnect()
        }
    }
