package coffee.crema.decent

import coffee.crema.core.DecentLoginReply
import coffee.crema.core.DecentMachine
import coffee.crema.core.DecentMachinesReply
import coffee.crema.core.DecentUploadReply
import coffee.crema.net.HttpClients
import coffee.crema.net.SafeResend
import coffee.crema.net.unwrapNotSent
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.Base64

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
 * cancels the Ktor call. Reading the body is inside the same failure net, so
 * a connection dropped mid-body is a [DecentError.Network], never a crash.
 *
 * Retries live in ONE place — the shared policy in `net/HttpClients.kt`
 * (5xx / 408 / 429 and transport failures, 3 attempts, backoff). The GETs
 * are idempotent; the upload POST carries the [SafeResend] marker, so a
 * resend after an attempt that may have reached the server goes out with
 * `replace=1` and overwrites instead of duplicating (the replace-on-ambiguity
 * rule). Whatever this client throws is the FINAL result after those retries.
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
     * answer — the final result, after the shared retry policy's attempts.
     */
    class Network(val status: Int?, detail: String) :
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
    /** Tests inject a MockEngine / a test OkHttp engine; null → the shared OkHttp engine. */
    engine: HttpClientEngine? = null,
    /** The retry policy's first backoff (tests shrink it). */
    retryBaseDelayMs: Long = HttpClients.RETRY_BASE_DELAY_MS,
    /** Tests only: allow an `http://` [baseUrl] (a local stub). */
    allowCleartext: Boolean = false,
) : DecentApi, Closeable {
    private val base = baseUrl.trimEnd('/')

    private val http = HttpClients.create(
        connectTimeoutMs = 15_000,
        socketTimeoutMs = 30_000,
        engine = engine,
        retryBaseDelayMs = retryBaseDelayMs,
        httpsOnly = !allowCleartext,
    )

    override fun close() = http.close()

    private data class Reply(val status: Int, val text: String)

    private suspend fun call(
        path: String,
        user: String,
        secret: String,
        body: String? = null,
        query: String = "",
        safeResend: ((HttpRequestBuilder) -> Unit)? = null,
    ): Reply =
        withContext(Dispatchers.IO) {
            try {
                val r = http.request("$base$path$query") {
                    method = if (body != null) HttpMethod.Post else HttpMethod.Get
                    header(HttpHeaders.Authorization, basicAuth(user, secret))
                    header(HttpHeaders.UserAgent, "crema-android")
                    if (body != null) setBody(TextContent(body, JSON_UTF8))
                    if (safeResend != null) attributes.put(SafeResend, safeResend)
                }
                Reply(r.status.value, r.bodyAsText(Charsets.UTF_8))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A not-sent failure reads as its cause ("Unable to resolve host …").
                val cause = e.unwrapNotSent()
                throw DecentError.Network(null, cause.message ?: cause.javaClass.simpleName)
            }
        }

    internal companion object {
        private val JSON_UTF8 = ContentType.Application.Json.withCharset(Charsets.UTF_8)

        /** `Basic base64(user:secret)`, UTF-8 — what OkHttp's `Credentials.basic` sent. */
        fun basicAuth(user: String, secret: String): String =
            "Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray(Charsets.UTF_8))

        /**
         * The upload's [SafeResend]: a resend after an attempt that may have
         * reached the server re-uploads over the server's copy (`replace=1`)
         * instead of creating a duplicate. Idempotent — never adds it twice,
         * and leaves an existing `replace` (any value) alone.
         */
        val REPLACE_ON_RESEND: (HttpRequestBuilder) -> Unit = { b ->
            if (!b.url.parameters.contains("replace")) b.url.parameters.append("replace", "1")
        }
    }

    private fun <T> decode(serializer: KSerializer<T>, status: Int, classify: () -> String): T =
        try {
            json.decodeFromString(serializer, classify())
        } catch (e: Exception) {
            // The core classifier effectively never fails; a skewed reply shape
            // must not crash the caller.
            throw DecentError.Network(status, e.message ?: "unreadable reply")
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
            is DecentLoginReply.Retry -> throw DecentError.Network(reply.content.status.toInt(), reply.content.detail)
        }
    }

    override suspend fun fetchMachines(email: String, token: String): List<DecentMachine> {
        val r = call("/support/api/sn", email, token, query = "?onlyespressomachines=1&withskus=1")
        return when (val reply = decode(DecentMachinesReply.serializer(), r.status) { core.machinesReplyJson(r.status, r.text) }) {
            is DecentMachinesReply.Machines -> reply.content.machines
            DecentMachinesReply.Auth -> throw DecentError.Auth()
            is DecentMachinesReply.Retry -> throw DecentError.Network(reply.content.status.toInt(), reply.content.detail)
        }
    }

    override suspend fun uploadShot(email: String, token: String, recordJson: String, replace: Boolean): DecentUploadResult {
        val r = call("/support/api/shot_upload", email, token, body = recordJson, query = if (replace) "?replace=1" else "", safeResend = REPLACE_ON_RESEND)
        return when (val reply = decode(DecentUploadReply.serializer(), r.status) { core.uploadReplyJson(r.status, r.text) }) {
            is DecentUploadReply.Uploaded -> DecentUploadResult(reply.content.id?.takeIf { it.isNotBlank() })
            DecentUploadReply.Auth -> throw DecentError.Auth()
            is DecentUploadReply.Rejected -> throw DecentError.Rejected(reply.content.status.toInt(), reply.content.body)
            is DecentUploadReply.Retry -> throw DecentError.Network(reply.content.status.toInt(), reply.content.detail)
        }
    }
}
