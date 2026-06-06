package coffee.crema.profiles

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Pinned built-in profiles — a tiny persisted id set (the Favorites star).
 *
 * A profile's `pinned` flag normally lives inside its full `CremaProfile` JSON, so
 * CUSTOM profiles persist their star for free via [CustomProfileStore] (the editor
 * switch + the card star both patch that JSON). BUILT-IN profiles, however, are
 * re-decoded from the core on every launch ([builtinCremaProfiles]) and carry no
 * writable store entry — so their star needs its own home. This is it: a flat set
 * of pinned built-in ids, overlaid onto the merged list in [MainViewModel.refreshProfiles].
 *
 * Mirrors [CustomProfileStore]: a dependency-free JSON file in filesDir, suspend
 * load/save on Dispatchers.IO.
 */

/** The persisted set of pinned built-in profile ids. */
@Serializable
private data class PinnedProfiles(val ids: List<String> = emptyList())

/** File-backed pinned-id set (`filesDir/pinnedProfiles.json`). */
class PinnedProfileStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    /** Load the pinned ids, or an empty set if absent / unreadable. */
    suspend fun load(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(PinnedProfiles.serializer(), it) }?.ids?.toSet()
        }.getOrNull() ?: emptySet()
    }

    /** Persist the pinned ids (best-effort). */
    suspend fun save(ids: Set<String>) {
        withContext(Dispatchers.IO) {
            runCatching {
                file.writeText(
                    json.encodeToString(PinnedProfiles.serializer(), PinnedProfiles(ids.toList())),
                )
            }
        }
    }

    private companion object {
        const val FILE_NAME = "pinnedProfiles.json"
    }
}
