package coffee.crema.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/*
 * The one place the app's Ktor HTTP clients come from: the REST clients
 * (Visualizer, Decent, the GitHub update check) via [HttpClients.create], and
 * the LAN proxy's WebSocket link via [HttpClients.webSocketClient].
 *
 * ENGINE — OkHttp (ktor-client-okhttp, which brings OkHttp 5.3.x), for:
 *  - HTTP/2 to the REST APIs;
 *  - a precise "did this request reach the server" signal ([SentTracking]):
 *    an application interceptor + a network interceptor, so a failure is
 *    either provably not sent ([RequestNotSentException]) or "maybe sent".
 * (Not ktor-client-android: it sits on HttpURLConnection, which rejects the
 * PATCH verb Visualizer shot-edit sync needs.)
 *
 * ONE OkHttpClient ([HttpClients.base]: one connection pool, one dispatcher)
 * underlies every client. Each Ktor client's timeouts come from its
 * HttpTimeout install: the OkHttp engine derives (and caches) a per-timeout
 * OkHttpClient from the preconfigured base with `newBuilder()`, which keeps
 * the pool + dispatcher. The engines are process-lifetime and never closed:
 * closing an OkHttp engine shuts down its dispatcher's executor, which is
 * SHARED — so each HttpClient is built over an engine it does not own, and
 * closing it leaves the engine (and every other client) running.
 *
 * ONE RETRY POLICY ([installRetryPolicy]) for all REST clients. OkHttp's own
 * `retryOnConnectionFailure` is off everywhere, so nothing below Ktor re-sends
 * anything, and DecentSync / the clients have no retry loops of their own.
 *
 * Otherwise deliberately plain: `expectSuccess = false` (each client maps
 * statuses itself), redirects followed as Ktor does by default (GET/HEAD
 * only, never an https → http downgrade; OkHttp's own redirects are off), and
 * only connect + socket (idle read/write) timeouts — no whole-call deadline.
 *
 * CLEARTEXT — the LAN proxy dials `ws://<lan-ip>:<port>`, and OkHttp on
 * Android enforces the network security policy, so
 * res/xml/network_security_config.xml permits cleartext app-wide (a
 * domain-config can only name exact hosts, never "any private IP"). The
 * compensating control is here: REST clients refuse any non-https URL
 * ([HttpsOnly]) before a socket is opened, so only the LAN WebSocket ever
 * speaks cleartext.
 */
object HttpClients {
    /** Retry schedule: 3 attempts total. */
    const val MAX_RETRIES = 2

    /**
     * First backoff; the second is twice this — 2 s + 4 s (+ up to 25 %
     * jitter each) mirrors DecentSync's old 2 s + 4 s linear schedule.
     */
    const val RETRY_BASE_DELAY_MS = 2_000L

    /** A `Retry-After` longer than this is clamped (a request shouldn't hang for minutes). */
    const val MAX_RETRY_AFTER_MS = 30_000L

    /** The one OkHttpClient: shared pool + dispatcher, the sent-signal interceptors, no OkHttp retries. */
    val base: OkHttpClient by lazy { newBaseOkHttp() }

    /** The shared REST engine (timeouts come per client from HttpTimeout). */
    private val restEngine: HttpClientEngine by lazy { okHttpEngine(base) }

    /**
     * The shared WebSocket engine: the same base (pool + dispatcher) with no
     * read / write timeout — a quiet proxy link must not be dropped — and no
     * ping — unchanged from the previous engine (neither end pings; the relay
     * protocol and the link's redial loop cover liveness).
     */
    private val webSocketEngine: HttpClientEngine by lazy {
        okHttpEngine(
            base.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    /** A fresh base like [base]; tests derive variants (e.g. a custom SocketFactory) from it. */
    fun newBaseOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false) // Ktor's HttpRedirect follows them
        .followSslRedirects(false)
        .addInterceptor(SentTracking.Application)
        .addNetworkInterceptor(SentTracking.Network)
        .build()

    /**
     * A Ktor OkHttp engine over [preconfigured]. The engine applies its own
     * default config block — which turns `retryOnConnectionFailure` back ON —
     * after `newBuilder()`, so it is switched off again here.
     */
    fun okHttpEngine(preconfigured: OkHttpClient): HttpClientEngine = OkHttp.create {
        this.preconfigured = preconfigured
        config { retryOnConnectionFailure(false) }
    }

    /**
     * A REST client with the given timeouts and the shared retry policy.
     *
     * [engine] is for tests (a MockEngine, or an OkHttp engine over a test
     * base); null uses the shared OkHttp engine. Either way the returned
     * client does NOT own the engine. [retryBaseDelayMs] is the first backoff
     * (tests shrink it). [httpsOnly] false lets tests talk to a local
     * `http://` stub; production clients never turn it off.
     */
    fun create(
        connectTimeoutMs: Long,
        socketTimeoutMs: Long,
        engine: HttpClientEngine? = null,
        retryBaseDelayMs: Long = RETRY_BASE_DELAY_MS,
        httpsOnly: Boolean = true,
    ): HttpClient = HttpClient(engine ?: restEngine) {
        expectSuccess = false
        followRedirects = true
        if (httpsOnly) install(HttpsOnly)
        installRetryPolicy(retryBaseDelayMs)
        // After HttpRequestRetry, so a timeout is seen (and judged) by the policy.
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
        }
    }

    /**
     * The LAN proxy's WebSocket client: no retry policy (the link has its own
     * redial loop) and no https-only guard (the LAN link is `ws://`). The
     * OkHttp engine does NOT honour `WebSockets { maxFrameSize }` — setting it
     * throws "Max frame size switch is not supported in OkHttp engine" — so
     * the frame cap is enforced by the link (KtorWsFrameLink's `maxFrameBytes`).
     */
    fun webSocketClient(engine: HttpClientEngine? = null): HttpClient =
        HttpClient(engine ?: webSocketEngine) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
            install(WebSockets)
        }
}

// ── The sent signal ──────────────────────────────────────────────────────────

/**
 * The request provably never reached the server: the failure came before
 * OkHttp had a connection to write it to (DNS, a refused / unreachable /
 * timed-out connect, a TLS handshake failure). Safe to re-send for ANY method.
 * Wraps the original failure as [cause].
 */
class RequestNotSentException(cause: IOException) :
    IOException("request not sent: ${cause.message ?: cause.javaClass.simpleName}", cause)

/** Whether [this] or anything in its cause chain is a [RequestNotSentException]. */
fun Throwable.requestNotSent(): Boolean = causeChain().any { it is RequestNotSentException }

/** The original failure behind a [RequestNotSentException] (for messages), else [this]. */
fun Throwable.unwrapNotSent(): Throwable =
    causeChain().firstOrNull { it is RequestNotSentException }?.cause ?: this

private fun Throwable.causeChain(): Sequence<Throwable> {
    val seen = HashSet<Throwable>()
    return generateSequence(this) { it.cause }.takeWhile { seen.add(it) }
}

/**
 * How [RequestNotSentException] is produced. OkHttp runs application
 * interceptors once per call, before any connection exists, and network
 * interceptors only once a connection is established and the request is
 * about to be written. So the application interceptor tags the request with
 * a per-call flag, the network interceptor sets it, and an IOException that
 * escapes `proceed` with the flag still false never reached a socket.
 *
 * "Sent" is conservative: it means the request was handed to a live
 * connection, not that the server processed it — a read timeout, a reset
 * after the headers went out, or a stale pooled connection failing on write
 * all count as "maybe sent".
 */
internal object SentTracking {
    private class Flag {
        @Volatile var sent = false
    }

    val Application = Interceptor { chain ->
        val flag = Flag()
        val request = chain.request().newBuilder().tag(Flag::class.java, flag).build()
        try {
            chain.proceed(request)
        } catch (e: IOException) {
            // A cancelled call is the caller's cancellation, not a transport verdict.
            if (flag.sent || e is RequestNotSentException || chain.call().isCanceled()) throw e
            throw RequestNotSentException(e)
        }
    }

    val Network = Interceptor { chain ->
        chain.request().tag(Flag::class.java)?.sent = true
        chain.proceed(chain.request())
    }
}

// ── The retry policy ─────────────────────────────────────────────────────────

/**
 * Per-request marker: this non-idempotent request can be made safe to resend
 * by applying the function to the resend (Decent's shot upload adds
 * `replace=1`). It must itself be idempotent — it may run on every later
 * attempt. A POST / PATCH / DELETE without it is never resent once it may
 * have reached the server.
 */
val SafeResend: AttributeKey<(HttpRequestBuilder) -> Unit> = AttributeKey("SafeResend")

/** Put on the ORIGINAL request once any attempt may have reached the server (sticky). */
private val MayHaveReachedServer: AttributeKey<Unit> = AttributeKey("MayHaveReachedServer")

private val IDEMPOTENT = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Put, HttpMethod.Options)

private fun retryableStatus(status: Int) = status in 500..599 || status == 408 || status == 429

private fun resendable(method: HttpMethod, attributes: Attributes) =
    method in IDEMPOTENT || attributes.contains(SafeResend)

/**
 * The one retry policy (see the file header):
 *  - on a RESPONSE: 5xx / 408 / 429 only (never 401 — Visualizer's token
 *    refresh owns that — nor any other 4xx). A response proves the server got
 *    the request, so a non-idempotent method retries only with [SafeResend].
 *  - on an EXCEPTION: never on cancellation, nor on anything that isn't a
 *    transport failure (no IOException in the chain). Provably not sent →
 *    any method. Maybe sent (every timeout included) → idempotent methods, or
 *    a non-idempotent one with [SafeResend].
 *  - a resend of a non-idempotent request gets [SafeResend] applied once ANY
 *    earlier attempt may have reached the server.
 *  - schedule: [HttpClients.MAX_RETRIES] retries (3 attempts), backoff
 *    `base · 2^(n-1)` plus 0–25 % jitter, or the server's `Retry-After`
 *    (seconds) when longer, clamped to [HttpClients.MAX_RETRY_AFTER_MS].
 *    The backoff is a coroutine delay, so cancelling the caller stops it.
 */
private fun HttpClientConfig<*>.installRetryPolicy(baseDelayMs: Long) {
    require(baseDelayMs > 0)
    install(HttpRequestRetry) {
        maxRetries = HttpClients.MAX_RETRIES
        retryIf { request, response ->
            retryableStatus(response.status.value) && resendable(request.method, request.attributes)
        }
        retryOnExceptionIf { request, cause ->
            when {
                cause is CancellationException -> false
                cause.causeChain().none { it is IOException } -> false
                cause.requestNotSent() -> true
                else -> resendable(request.method, request.attributes)
            }
        }
        modifyRequest { resend ->
            val failure = cause
            val reached = response != null || (failure != null && !failure.requestNotSent())
            if (reached) request.attributes.put(MayHaveReachedServer, Unit)
            if (resend.method !in IDEMPOTENT && request.attributes.contains(MayHaveReachedServer)) {
                resend.attributes.getOrNull(SafeResend)?.invoke(resend)
            }
        }
        delayMillis(respectRetryAfterHeader = false) { retry ->
            val backoff = baseDelayMs shl (retry - 1).coerceIn(0, 20)
            val jitter = Random.nextLong(backoff / 4 + 1)
            val retryAfterMs = response?.headers?.get(HttpHeaders.RetryAfter)?.trim()?.toLongOrNull()
                ?.coerceIn(0, HttpClients.MAX_RETRY_AFTER_MS / 1000)?.times(1000)
            maxOf(backoff + jitter, retryAfterMs ?: 0)
        }
    }
}

/**
 * Refuses a non-https request before it is sent (the cleartext compensating
 * control — see the file header). Thrown from the request pipeline, so the
 * retry policy never sees it and nothing touches the network.
 */
class CleartextRefusedException(url: String) :
    IllegalStateException("Refusing a non-https request: $url")

private val HttpsOnly = createClientPlugin("HttpsOnly") {
    onRequest { request, _ ->
        if (request.url.protocol != URLProtocol.HTTPS) throw CleartextRefusedException(request.url.buildString())
    }
}
