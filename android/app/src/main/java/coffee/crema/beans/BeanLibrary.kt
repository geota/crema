package coffee.crema.beans

import android.content.Context
import coffee.crema.core.Bean
import coffee.crema.core.BeanOrigin
import coffee.crema.core.Roaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/*
 * The bean library — persistence + factories.
 *
 * Beans are user data (no core built-ins), so the shell owns CRUD + storage,
 * while the core owns the @Serializable Bean / Roaster / BeanOrigin shapes
 * (typeshare'd into CremaCoreTypes.kt). This is Crema's first local persistence
 * layer: a dependency-free JSON file in filesDir. The blob shape is just the
 * core types, so DataStore/Room can replace the backing later behind the same
 * suspend API without touching callers.
 */

/** The persisted library — beans + the roaster directory + the active selection. */
@Serializable
data class BeanLibrary(
    val beans: List<Bean> = emptyList(),
    val roasters: List<Roaster> = emptyList(),
    val activeBeanId: String? = null,
)

/** File-backed JSON persistence for [BeanLibrary] (`filesDir/beanLibrary.json`). */
class LibraryStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    /** Load the library, or an empty one if absent / unreadable / stale. */
    suspend fun load(): BeanLibrary = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(BeanLibrary.serializer(), it) }
        }.getOrNull() ?: BeanLibrary()
    }

    /** Persist the library (best-effort; failures are swallowed). */
    suspend fun save(library: BeanLibrary) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(BeanLibrary.serializer(), library)) }
        }
    }

    private companion object {
        const val FILE_NAME = "beanLibrary.json"
    }
}

private val emptyMeta = JsonObject(emptyMap())

/**
 * Mint a new bean row, filling the [Bean] type's many required fields with
 * sensible blanks. The shell owns id minting (`bean:<uuid>`) and the
 * created/updated stamps; further edits ride on `copy(...)`.
 */
fun newBean(
    name: String,
    roasterId: String?,
    roastLevel: Int?,
    roastedOn: String?,
    nowMs: Long,
): Bean = Bean(
    id = "bean:" + UUID.randomUUID(),
    name = name,
    roasterId = roasterId,
    roastedOn = roastedOn,
    roastLevel = roastLevel?.toUByte(),
    decaf = false,
    origin = BeanOrigin(),
    bagSize = 0f,
    remaining = 0f,
    qualityScore = "",
    tastingNotes = "",
    // UByte.MIN_VALUE (== 0), not 0.toUByte(): the literal conversion is a
    // compile-time constant that trips a Kotlin const-eval back-end bug
    // (ConstEvaluationLowering: "Unknown function: toUByte(kotlin.Int)").
    rating = UByte.MIN_VALUE,
    notes = "",
    favourite = false,
    grinder = "",
    grinderSetting = "",
    metadata = emptyMeta,
    createdAt = nowMs,
    updatedAt = nowMs,
)

/** Mint a new roaster-directory row. */
fun newRoaster(name: String, nowMs: Long): Roaster = Roaster(
    id = "roaster:" + UUID.randomUUID(),
    name = name,
    notes = "",
    metadata = emptyMeta,
    createdAt = nowMs,
    updatedAt = nowMs,
)
