package coffee.crema.decent

import coffee.crema.core.DecentLoginReply
import coffee.crema.core.DecentMachine
import coffee.crema.core.DecentMachinesReply
import coffee.crema.core.DecentUploadReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * The Decent Espresso support API, as de1app's `shot_upload` plugin and
 * decaid's `DecentAccountService` speak it. Three calls, all HTTP Basic:
 *
 *   GET  /support/api/login_test                 Basic email:PASSWORD → token text
 *   GET  /support/api/sn?onlyespressomachines=1  Basic email:token    → "serial [sku]" lines
 *   POST /support/api/shot_upload[?replace=1]    Basic email:token, JSON ShotRecord → {id,…}
 *
 * This client owns only the IO. What an answer MEANS (a `0` body is a bad
 * login / a dead token, which 4xx are permanent, where the id hides in the
 * reply) is the core's call (`de1_domain::decent_wire`), shared with the web
 * shell; here the classified reply maps onto [DecentError].
 *
 * Calls run on Dispatchers.IO and are cancellable: cancelling the coroutine
 * cancels the OkHttp call. Reading the body is inside the same failure net, so
 * a connection dropped mid-body is a [DecentError.Network], never a crash.
 */

const val DECENT_BASE = "https://decentespresso.com"

/** The account's own shot-history page (owner-only; no per-shot link needed). */
const val DECENT_HISTORY_URL = "$DECENT_BASE/support/espressomachine"

sealed class DecentError(message: String) : Exception(message) {
    /** The stored token stopped working (HTTP 401 / a `0` answer) — the user must re-link. */
    class Auth : DecentError("Decent account login no longer works — sign in again.")

    /** The server refused this shot for good (4xx other than auth / 403 / 408 / 429). */
    class Rejected(val status: Int, body: String) :
        DecentError("Decent rejected the shot (HTTP $status)" + (if (body.isBlank()) "" else ": $body"))

    /**
     * Transport failure (no HTTP answer: [status] null) or a retryable HTTP
     * answer. [requestSent] is true when the failure came after the request
     * went out (a read timeout, a dropped body) — the server may have stored
     * the shot, so a blind re-POST could duplicate it.
     */
    class Network(val status: Int?, detail: String, val requestSent: Boolean = false) :
        DecentError(if (status == null) "Couldn't reach decentespresso.com: $detail" else "decentespresso.com answered HTTP $status")

    /** The shot could not be turned into a ShotRecord (a converter error) — nothing was sent. */
    class Invalid(detail: String) : DecentError("Couldn't build the Decent shot record: $detail")

    val recoverable: Boolean get() = this is Network

    /** No HTTP answer at all — offline, DNS, connect or read failure. */
    val offline: Boolean get() = this is Network && status == null
}

/** What the server hands back for a stored shot; [id] is null when the answer carried none. */
data class DecentUploadResult(val id: String?)

/** The three account calls — an interface so [DecentSync] is testable with a fake. */
interface DecentApi {
    /** Email + password → the account token; null when the server rejects the login. */
    suspend fun login(email: String, password: String): String?

    /** The DE1s registered on the account. Throws [DecentError.Auth] for a dead token. */
    suspend fun fetchMachines(email: String, token: String): List<DecentMachine>

    /** POST one ShotRecord; `replace` re-uploads over the server's copy. */
    suspend fun uploadShot(email: String, token: String, recordJson: String, replace: Boolean = false): DecentUploadResult
}

class DecentClient(
    private val json: Json,
    private val core: DecentCore = UniffiDecentCore,
    baseUrl: String = DECENT_BASE,
    http: OkHttpClient? = null,
) : DecentApi {
    private val base = baseUrl.trimEnd('/')

    /** Set on the request by [SentListener] once its headers start going out. */
    private class SentFlag {
        @Volatile var sent = false
    }

    private object SentListener : EventListener() {
        override fun requestHeadersStart(call: Call) {
            call.request().tag(SentFlag::class.java)?.sent = true
        }
    }

    private val http: OkHttpClient = (http?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .eventListener(SentListener)
        // No silent transport-level re-sends of a POST (OkHttp would replay a
        // 408 or a stale-connection failure): the retry policy — including the
        // replace-on-ambiguity rule — lives in DecentSync.
        .retryOnConnectionFailure(false)
        .build()

    private data class Reply(val status: Int, val text: String)

    private suspend fun call(path: String, user: String, secret: String, body: String? = null, query: String = ""): Reply =
        withContext(Dispatchers.IO) {
            val flag = SentFlag()
            val builder = Request.Builder()
                .url("$base$path$query")
                .header("Authorization", Credentials.basic(user, secret))
                .header("User-Agent", "crema-android")
                .tag(SentFlag::class.java, flag)
            if (body != null) builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            try {
                http.newCall(builder.build()).executeAsync().use { r -> Reply(r.code, r.body.string()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw DecentError.Network(null, e.message ?: e.javaClass.simpleName, requestSent = flag.sent)
            }
        }

    private fun <T> decode(serializer: KSerializer<T>, status: Int, classify: () -> String): T =
        try {
            json.decodeFromString(serializer, classify())
        } catch (e: Exception) {
            // The core classifier effectively never fails; a skewed reply shape
            // must not crash the caller.
            throw DecentError.Network(status, e.message ?: "unreadable reply", requestSent = true)
        }

    /**
     * Exchange email + password for the account token. Null when the server
     * rejects the login; throws [DecentError.Network] otherwise.
     * The password is used for this one request and never stored.
     */
    override suspend fun login(email: String, password: String): String? {
        val r = call("/support/api/login_test", email, password)
        return when (val reply = decode(DecentLoginReply.serializer(), r.status) { core.loginReplyJson(r.status, r.text) }) {
            is DecentLoginReply.Token -> reply.content.token
            DecentLoginReply.Rejected -> null
            is DecentLoginReply.Retry -> throw DecentError.Network(reply.content.status.toInt(), reply.content.detail, requestSent = true)
        }
    }

    override suspend fun fetchMachines(email: String, token: String): List<DecentMachine> {
        val r = call("/support/api/sn", email, token, query = "?onlyespressomachines=1&withskus=1")
        return when (val reply = decode(DecentMachinesReply.serializer(), r.status) { core.machinesReplyJson(r.status, r.text) }) {
            is DecentMachinesReply.Machines -> reply.content.machines
            DecentMachinesReply.Auth -> throw DecentError.Auth()
            is DecentMachinesReply.Retry -> throw DecentError.Network(reply.content.status.toInt(), reply.content.detail, requestSent = true)
        }
    }

    override suspend fun uploadShot(email: String, token: String, recordJson: String, replace: Boolean): DecentUploadResult {
        val r = call("/support/api/shot_upload", email, token, body = recordJson, query = if (replace) "?replace=1" else "")
        return when (val reply = decode(DecentUploadReply.serializer(), r.status) { core.uploadReplyJson(r.status, r.text) }) {
            is DecentUploadReply.Uploaded -> DecentUploadResult(reply.content.id?.takeIf { it.isNotBlank() })
            DecentUploadReply.Auth -> throw DecentError.Auth()
            is DecentUploadReply.Rejected -> throw DecentError.Rejected(reply.content.status.toInt(), reply.content.body)
            is DecentUploadReply.Retry -> throw DecentError.Network(reply.content.status.toInt(), reply.content.detail, requestSent = true)
        }
    }
}
