package coffee.crema.profiles

import android.content.Context
import coffee.crema.persist.eraseStore
import coffee.crema.persist.inSaveOrder
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SafeLoad
import coffee.crema.persist.loadStore
import coffee.crema.persist.saveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Hidden (archived) built-in profiles — a tiny persisted id set.
 *
 * Built-in profiles can't be deleted (they re-decode from the core every launch),
 * so "archiving" one just hides it from the active grid. This is the persisted set
 * of hidden built-in ids, overlaid onto the merged list in
 * [MainViewModel.refreshProfiles]; the Profiles "Hidden" filter surfaces them for
 * restore. Mirrors [PinnedProfileStore]: a dependency-free JSON file in filesDir.
 */

/** The persisted set of hidden (archived) built-in profile ids. */
@Serializable
private data class HiddenProfiles(val ids: List<String> = emptyList())

/** File-backed hidden-id set (`filesDir/hiddenProfiles.json`). */
class HiddenProfileStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    /** The ids are in memory: a save can't carry defaults now. */
    fun openSaves() = SafeFile.openSaves(file)

    /** Load the hidden ids, or an empty set if absent / unreadable. Saves stay
     *  refused until [openSaves]. */
    suspend fun load(): Set<String> = withContext(Dispatchers.IO) {
        when (val outcome = loadStore(file, WHAT, open = false) { json.decodeFromString(HiddenProfiles.serializer(), it) }) {
            is SafeLoad.Loaded -> outcome.value.ids.toSet()
            SafeLoad.Absent, is SafeLoad.Corrupt, is SafeLoad.Unavailable -> emptySet()
        }
    }

    /** Persist the hidden ids (best-effort). */
    suspend fun save(ids: Set<String>) {
        saveStore(file, WHAT) { json.encodeToString(HiddenProfiles.serializer(), HiddenProfiles(ids.toList())) }
    }

    /** Delete the store file and every copy of it (Erase all data; final review 05 N-1). */
    suspend fun erase() {
        inSaveOrder(file) { eraseStore(file) }
    }

    internal companion object {
        const val WHAT = "hidden profiles list"
        const val FILE_NAME = "hiddenProfiles.json"
    }
}
