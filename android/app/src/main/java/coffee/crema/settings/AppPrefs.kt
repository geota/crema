package coffee.crema.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * App preferences — the user's display/behaviour settings, persisted via the
 * same file-JSON pattern as the bean library and shot history.
 *
 * v1 holds just the theme mode; units (weight/temp/pressure) and other prefs are
 * additive later (older records deserialise cleanly via defaults).
 */
@Serializable
data class AppPrefs(
    /** `"system" | "light" | "dark"` — drives CremaTheme. Defaults to dark
     *  (the machine app is dark-skinned). */
    val themeMode: String = "dark",
    /** Max shot duration cap, seconds. Pushed to the core on load + shown as a
     *  Time stop-condition on Brew. */
    val maxShotDurationS: Float = 45f,
)

/** File-backed JSON persistence for [AppPrefs] (`filesDir/prefs.json`). */
class SettingsStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): AppPrefs = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(AppPrefs.serializer(), it) }
        }.getOrNull() ?: AppPrefs()
    }

    suspend fun save(prefs: AppPrefs) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(AppPrefs.serializer(), prefs)) }
        }
    }

    private companion object {
        const val FILE_NAME = "prefs.json"
    }
}
