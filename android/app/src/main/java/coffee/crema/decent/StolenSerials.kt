package coffee.crema.decent

import android.content.Context
import coffee.crema.net.HttpClients
import coffee.crema.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Decent's stolen-machine serial list (de1app `stolen_serials.json`, exported
 * for other apps by `export_stolen_serials_json`). de1app tells the owner of a
 * listed machine to contact Decent support. Crema's UX (user decision,
 * 2026-10): a non-blocking one-line notice in Settings → Machine, the list
 * fetched at most once a day and cached, every failure ignored silently — a
 * missing list just means no notice.
 *
 * The rules (parsing, the match, the cadence, the URL) are core's
 * (`de1_domain::stolen_serials`); this file only fetches and caches.
 */

/** The cached list body and when it was fetched (epoch ms). */
@Serializable
data class StolenSerialsCache(val fetchedAtMs: Long, val body: String)

/** Where the cached list lives — a file in production, a fake in tests. */
interface StolenSerialsCacheStore {
    suspend fun read(): StolenSerialsCache?
    suspend fun write(cache: StolenSerialsCache)
}

/** File-JSON cache, the same pattern as the other stores. */
class StolenSerialsFileStore(private val context: Context) : StolenSerialsCacheStore {
    private val file get() = File(context.filesDir, "stolenSerials.json")

    override suspend fun read(): StolenSerialsCache? = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()?.let { Json.decodeFromString<StolenSerialsCache>(it) }
        }.getOrNull()
    }

    override suspend fun write(cache: StolenSerialsCache) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(Json.encodeToString(StolenSerialsCache.serializer(), cache)) }
        }
    }
}

/** Core's stolen-serial rules, injectable so the JVM tests need no native lib. */
data class StolenSerialRules(
    val url: () -> String,
    val refreshDue: (lastFetchMs: Long?, nowMs: Long) -> Boolean,
    val listIsValid: (body: String) -> Boolean,
    val serialOnList: (serial: UInt, body: String) -> Boolean,
) {
    companion object {
        val Core = StolenSerialRules(
            url = { coffee.crema.core.stolenSerialsUrl() },
            refreshDue = { last, now ->
                coffee.crema.core.stolenSerialsRefreshDue(last?.toULong(), now.toULong())
            },
            listIsValid = { coffee.crema.core.stolenSerialsListIsValid(it) },
            serialOnList = { serial, body -> coffee.crema.core.serialOnStolenList(serial, body) },
        )
    }
}

private val stolenSerialsHttp: HttpClient by lazy {
    HttpClients.create(connectTimeoutMs = 10_000, socketTimeoutMs = 15_000)
}

/** GET [url] and return the body, or null on a non-2xx status. */
private suspend fun fetchBody(url: String): String? {
    val res = stolenSerialsHttp.get(url) { header(HttpHeaders.UserAgent, "crema-app") }
    return if (res.status.isSuccess()) res.bodyAsText(Charsets.UTF_8) else null
}

/**
 * Answers "is this DE1 on Decent's stolen-machine list?", refreshing the
 * cached list first when it is more than a day old. Never throws: any failure
 * answers from the cache, or `false`.
 */
class StolenSerialsCheck(
    private val store: StolenSerialsCacheStore,
    private val fetch: suspend (url: String) -> String? = ::fetchBody,
    private val now: () -> Long = System::currentTimeMillis,
    private val rules: StolenSerialRules = StolenSerialRules.Core,
) {
    /** One refresh at a time — a burst of serial reads shares one fetch. */
    private val refreshLock = Mutex()

    suspend fun isStolen(serial: UInt): Boolean = runCatchingCancellable {
        refreshLock.withLock { refreshIfDue() }
        store.read()?.body?.let { rules.serialOnList(serial, it) } ?: false
    }.getOrDefault(false)

    private suspend fun refreshIfDue() {
        if (!rules.refreshDue(store.read()?.fetchedAtMs, now())) return
        val body = runCatchingCancellable { fetch(rules.url()) }.getOrNull() ?: return
        // Only a real list replaces the cache — never an error page.
        if (rules.listIsValid(body)) store.write(StolenSerialsCache(now(), body))
    }
}
