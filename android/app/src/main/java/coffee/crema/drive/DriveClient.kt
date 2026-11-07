package coffee.crema.drive

import coffee.crema.persist.ImportLimits
import coffee.crema.persist.readBytesCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import coffee.crema.net.HttpClients
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/*
 * Google Drive v3 REST — the calls the backup feature needs (Android twin of
 * `web/src/lib/drive/rest.ts`). Scope `drive.file` limits every call to files
 * THIS app created, so listing never sees the user's wider Drive. The caller
 * passes a valid access token (from the Drive auth flow, refreshed as needed).
 */

/** The Drive API's host, which a test points at a local server. */
@Volatile internal var driveApiRoot = "https://www.googleapis.com"
private val UPLOAD_URL get() = "$driveApiRoot/upload/drive/v3/files"
private val FILES_URL get() = "$driveApiRoot/drive/v3/files"

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

/** List this app's backup files, newest first: all of them, as retention can't
 *  hold on to a fullest copy it was never shown. Throws if any page fails. */
suspend fun driveListBackups(accessToken: String, json: Json): List<DriveFile> {
    val files = mutableListOf<DriveFile>()
    var pageToken: String? = null
    do {
        val page = driveListPage(accessToken, pageToken, json)
        files += page.files
        pageToken = page.nextPageToken
    } while (pageToken != null)
    return files
}

private class DriveFilePage(val files: List<DriveFile>, val nextPageToken: String?)

private suspend fun driveListPage(accessToken: String, pageToken: String?, json: Json): DriveFilePage =
    withContext(Dispatchers.IO) {
        val q = URLEncoder.encode("name contains 'crema-backup' and trashed = false", "UTF-8")
        val fields = URLEncoder.encode("nextPageToken,files(id,name,modifiedTime,size)", "UTF-8")
        val page = pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val url = "$FILES_URL?q=$q&fields=$fields&orderBy=modifiedTime%20desc&pageSize=100$page"
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
            // A body it can't read is a failed page, not an empty one: whatever
            // acts on this listing takes what's missing for gone.
            val page = runCatching {
                val body = json.parseToJsonElement(resp).jsonObject
                DriveFilePage(
                    body.getValue("files").jsonArray.map {
                        json.decodeFromString(DriveFile.serializer(), it.toString())
                    },
                    body["nextPageToken"]?.jsonPrimitive?.contentOrNull,
                )
            }
            page.getOrElse { throw IOException("Drive list failed: couldn't read the reply", it) }
        } finally {
            conn.disconnect()
        }
    }

/** Download a backup file's raw bytes by id (a `.crema.zip`, or legacy text — the
 *  caller sniffs the zip magic vs decoding it as text). */
/** Move one Drive file to the trash (retention pruning of old auto-backups),
 *  where the user can restore it for 30 days. `files.update` takes the
 *  `drive.file` scope, and only the owner can trash a file, which is the user
 *  for the files Crema creates. Over OkHttp, as HttpURLConnection can't PATCH. */
suspend fun driveTrashFile(accessToken: String, fileId: String, json: Json): Unit =
    withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$FILES_URL/${URLEncoder.encode(fileId, "UTF-8")}")
            .header("Authorization", "Bearer $accessToken")
            .patch("""{"trashed":true}""".toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        HttpClients.base.newCall(request).execute().use { response ->
            // 404 counts as done (already gone).
            if (!response.isSuccessful && response.code != 404) {
                throw IOException(errorMessage(json, response.body.string(), response.code, "trash"))
            }
        }
    }

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
                // Size-capped like every import (review 05 P3-2).
                conn.inputStream.use { it.readBytesCapped(ImportLimits.MAX_FILE_BYTES, "The Drive backup") }
            } else {
                val err = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                throw IOException(errorMessage(json, err, status, "download"))
            }
        } finally {
            conn.disconnect()
        }
    }
