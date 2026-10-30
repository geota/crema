package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.persist.SafeFile
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The one-time move from relay v3 to v4 (spec §5.1, §6.4). Not wired yet
 * (PR-3b runs it on start, before `ProxyController.start()` reads roles, right
 * after [RelayKeyStore.load]).
 *
 * v3 left: `proxy-pairing.json` (+ its `.bak`/`.tmp`/kept-aside copies) with the
 * HMAC pairing secrets, `filesDir/deviceId` (the v3 client id), and
 * `pairedDevices` in `prefs.json`. The migration deletes all three and keeps
 * `proxyRole`, `proxyPrimaryHost`, `proxyPrimaryPort`, `relayPort` and every
 * other preference as they were. No role changes. It records what the UI needs
 * in the new store: a remote (`secondary`) gets `needsRepair` (it shows "Pair
 * again" and does not dial), a hub (`primary`) gets the one-time upgrade banner.
 *
 * It runs only when the relay store has a key loaded: when the keys are
 * unavailable this start (a transient keystore error) it changes nothing and the
 * next start retries. The flags are written before anything is deleted, so an
 * interrupted run simply runs again, and with them a one-time marker
 * (`v3Migrated`): after that, v3 leftovers are still removed but never raise the
 * flags again. `pairedDevices` is dropped from `prefs.json` and from the `.bak`
 * and `.tmp` copies SafeFile could restore it from.
 */
class RelayUpgradeMigration(private val filesDir: File, private val json: Json) {
    /** What [run] did. */
    enum class Outcome {
        /** No v3 state: nothing to do. */
        NOTHING_TO_MIGRATE,

        /** v3 state removed, flags recorded. */
        MIGRATED,

        /** The relay store has no key this start, or couldn't record the flags: nothing touched; retry next start. */
        DEFERRED,
    }

    private val pairingFile get() = File(filesDir, V3_PAIRING_FILE)
    private val deviceIdFile get() = File(filesDir, V3_DEVICE_ID_FILE)
    private val prefsFile get() = File(filesDir, PREFS_FILE)

    fun run(store: RelayKeyStore): Outcome {
        val v3Files = listOf(pairingFile, deviceIdFile).flatMap(::copiesOf).filter(File::exists)
        // `prefs.json` and the copies SafeFile may restore it from: none may keep `pairedDevices`.
        val prefsCopies = listOf(prefsFile, SafeFile.backupOf(prefsFile), File(prefsFile.path + ".tmp"))
            .mapNotNull { f -> readJson(f)?.let { f to it } }
        val withPairedDevices = prefsCopies.filter { (_, obj) -> obj.containsKey(PAIRED_DEVICES) }
        if (v3Files.isEmpty() && withPairedDevices.isEmpty()) {
            if (store.isLoaded && !store.v3Migrated) store.markV3Migrated()
            return Outcome.NOTHING_TO_MIGRATE
        }
        if (!store.isLoaded) return Outcome.DEFERRED

        val prefs = prefsCopies.firstOrNull { it.first == prefsFile }?.second
        val role = (prefs?.get(PROXY_ROLE) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!store.v3Migrated) {
            // Only real v3 pairings raise the flags: a bare `"pairedDevices": []`
            // (an older build saving its defaults again) is just dropped.
            val v3Paired = v3Files.isNotEmpty() || (prefs?.get(PAIRED_DEVICES) as? JsonArray)?.isNotEmpty() == true
            // Recorded (with the one-time marker) before anything is deleted, so an
            // interrupted run runs again; once marked, a leftover found later (a
            // restored `.bak`, an older build's save) is cleaned up without
            // sending a re-paired remote back to "Pair again".
            val recorded = store.markUpgraded(needsRepair = v3Paired && role == ROLE_REMOTE, upgradeNotice = v3Paired && role == ROLE_HUB)
            if (!recorded) {
                // The flags never reached relay-v4.json: deleting now would lose
                // the "Pair again" state for good. Touch nothing; retry next start.
                Log.w(TAG, "couldn't record the v3 migration in ${RelayKeyStore.FILE_NAME}; the next start retries")
                return Outcome.DEFERRED
            }
        }

        SafeFile.erase(pairingFile)
        SafeFile.erase(deviceIdFile)
        for ((f, obj) in withPairedDevices) {
            val kept = json.encodeToString(JsonObject.serializer(), JsonObject(obj - PAIRED_DEVICES))
            // No new `.bak`: the old prefs would keep `pairedDevices` there.
            runCatching { SafeFile.writeBytes(f, kept.toByteArray(Charsets.UTF_8), keepBackup = false) }
                .onFailure { Log.w(TAG, "couldn't drop $PAIRED_DEVICES from ${f.name}; the next start retries") }
        }
        Log.i(TAG, "migrated relay v3 state (role ${role ?: "unknown"})")
        return Outcome.MIGRATED
    }

    /** [f] as a JSON object (either `prefs.json` shape keeps these keys at the top level), or null. */
    private fun readJson(f: File): JsonObject? = runCatching {
        f.takeIf(File::isFile)?.readText()?.let { json.parseToJsonElement(it).jsonObject }
    }.getOrNull()

    /** A file and the copies SafeFile may have left (`.bak`, `.tmp`, kept-aside corrupt copies). */
    private fun copiesOf(f: File): List<File> =
        listOf(f, SafeFile.backupOf(f), File(f.path + ".tmp")) + SafeFile.keptAside(f)

    companion object {
        const val V3_PAIRING_FILE = "proxy-pairing.json"
        const val V3_DEVICE_ID_FILE = "deviceId"
        private const val PREFS_FILE = "prefs.json"
        private const val PAIRED_DEVICES = "pairedDevices"
        private const val PROXY_ROLE = "proxyRole"
        private const val ROLE_REMOTE = "secondary"
        private const val ROLE_HUB = "primary"
        private const val TAG = "RelayUpgradeMigration"
    }
}
