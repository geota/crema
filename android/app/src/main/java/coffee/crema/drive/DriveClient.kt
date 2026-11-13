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
 * Upload a backup as a NEW Drive file. We always create (timestamped names)
 * rather than overwrite, so Drive keeps a short backup history the user can
 * pick from on restore.
 *
 * A resumable upload (#310 P2a): the session opens with the metadata, then the
 * file goes in one `PUT` streamed from disk with a fixed length, so a large
 * backup is never held in memory. Without a `Location` for the session, one
 * multipart request, also streamed from the file. Drive's size for the new
 * file must be the file's.
 */
suspend fun driveUploadBackup(
    accessToken: String,
    name: String,
    content: java.io.File,
    json: Json,
    mime: String = "application/zip",
): DriveFile =
    withContext(Dispatchers.IO) {
        val meta = buildJsonObject {
            put("name", name)
            put("mimeType", mime)
        }.toString().toByteArray(Charsets.UTF_8)
        val length = content.length()
        val start = (
            URL("$UPLOAD_URL?uploadType=resumable&fields=id,name,modifiedTime,size").openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setFixedLengthStreamingMode(meta.size)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", mime)
            setRequestProperty("X-Upload-Content-Length", length.toString())
        }
        val session = try {
            start.outputStream.use { it.write(meta) }
            val status = start.responseCode
            if (status !in 200..299) {
                val resp = start.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                throw DriveHttpError(status, resp, errorMessage(json, resp, status, "upload"))
            }
            start.getHeaderField("Location")
        } finally {
            start.disconnect()
        }
        val file = if (session != null) {
            send(URL(session), "PUT", mime, null, content, null, accessToken = null, json)
        } else {
            val boundary = "crema" + System.nanoTime().toString(16)
            val head = (
                "--$boundary\r\n" +
                    "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                    "${String(meta, Charsets.UTF_8)}\r\n" +
                    "--$boundary\r\n" +
                    "Content-Type: $mime\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            val tail = "\r\n--$boundary--".toByteArray(Charsets.UTF_8)
            send(
                URL("$UPLOAD_URL?uploadType=multipart&fields=id,name,modifiedTime,size"), "POST",
                "multipart/related; boundary=$boundary", head, content, tail, accessToken, json,
            )
        }
        // Unverified is not verified (#391 review): a reply without the size asks
        // Drive for it, and fails only if Drive still won't say.
        val stored = file.size?.toLongOrNull()
            ?: driveFileSize(accessToken, file.id, json)
            ?: throw IOException("Drive upload failed: Drive didn't say how large the file is.")
        if (stored != length) throw IOException("Drive upload failed: Drive holds $stored bytes of $length.")
        file
    }

/** Drive's stored size for [fileId] (`files.get?fields=size`), or null if it won't say. */
private fun driveFileSize(accessToken: String, fileId: String, json: Json): Long? {
    val url = "$FILES_URL/${URLEncoder.encode(fileId, "UTF-8")}?fields=size"
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 20_000
        setRequestProperty("Authorization", "Bearer $accessToken")
    }
    return try {
        val status = conn.responseCode
        val resp = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
        if (status !in 200..299) throw DriveHttpError(status, resp, errorMessage(json, resp, status, "upload check"))
        runCatching { json.parseToJsonElement(resp).jsonObject["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() }.getOrNull()
    } finally {
        conn.disconnect()
    }
}

/** One request whose body is [head], then [content] streamed, then [tail], at a fixed length. */
private fun send(
    url: URL,
    method: String,
    contentType: String,
    head: ByteArray?,
    content: java.io.File,
    tail: ByteArray?,
    accessToken: String?,
    json: Json,
): DriveFile {
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 60_000
        doOutput = true
        setFixedLengthStreamingMode((head?.size ?: 0) + content.length() + (tail?.size ?: 0))
        accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        setRequestProperty("Content-Type", contentType)
    }
    try {
        conn.outputStream.use { out ->
            head?.let { out.write(it) }
            content.inputStream().use { it.copyTo(out, 64 * 1024) }
            tail?.let { out.write(it) }
        }
        val status = conn.responseCode
        val resp = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
        if (status !in 200..299) throw DriveHttpError(status, resp, errorMessage(json, resp, status, "upload"))
        return json.decodeFromString(DriveFile.serializer(), resp)
    } finally {
        conn.disconnect()
    }
}

/** List this app's backup files, newest first: all of them, as retention can't
 *  hold on to a fullest copy it was never shown. Throws if any page fails. */
suspend fun driveListBackups(accessToken: String, json: Json): List<DriveFile> {
    val files = mutableListOf<DriveFile>()
    val seen = HashSet<String>()
    var pageToken: String? = null
    var pages = 0
    do {
        if (pages++ >= DRIVE_LIST_MAX_PAGES) throw IOException("Drive list failed: it didn't end")
        val page = driveListPage(accessToken, pageToken, json)
        files += page.files
        pageToken = page.nextPageToken
        // A token seen before would go round for ever.
        if (pageToken != null && !seen.add(pageToken)) throw IOException("Drive list failed: it went round")
    } while (pageToken != null)
    return files
}

/** Pages a listing may take: 10,000 files, far past what Crema makes. */
internal const val DRIVE_LIST_MAX_PAGES = 100

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
            if (status !in 200..299) throw DriveHttpError(status, resp, errorMessage(json, resp, status, "list"))
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
                val err = response.body.string()
                throw DriveHttpError(response.code, err, errorMessage(json, err, response.code, "trash"))
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
                throw DriveHttpError(status, err, errorMessage(json, err, status, "download"))
            }
        } finally {
            conn.disconnect()
        }
    }
