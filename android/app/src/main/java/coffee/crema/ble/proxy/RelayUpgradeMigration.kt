package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.persist.SafeFile
import coffee.crema.persist.saveStore
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
 * interrupted run simply runs again.
 */
class RelayUpgradeMigration(private val filesDir: File, private val json: Json) {
    /** What [run] did. */
    enum class Outcome {
        /** No v3 state: nothing to do. */
        NOTHING_TO_MIGRATE,

        /** v3 state removed, flags recorded. */
        MIGRATED,

        /** The relay store has no key this start: nothing touched; retry next start. */
        DEFERRED,
    }

    private val pairingFile get() = File(filesDir, V3_PAIRING_FILE)
    private val deviceIdFile get() = File(filesDir, V3_DEVICE_ID_FILE)
    private val prefsFile get() = File(filesDir, PREFS_FILE)

    fun run(store: RelayKeyStore): Outcome {
        val prefs = readPrefs()
        val v3Files = listOf(pairingFile, deviceIdFile).flatMap(::copiesOf).filter(File::exists)
        val hasPairedDevices = prefs?.containsKey(PAIRED_DEVICES) == true
        if (v3Files.isEmpty() && !hasPairedDevices) return Outcome.NOTHING_TO_MIGRATE
        if (!store.isLoaded) return Outcome.DEFERRED

        // Only real v3 pairings raise the flags: a bare `"pairedDevices": []`
        // (an older build saving its defaults again) is just dropped, so it can't
        // send a re-paired remote back to "Pair again".
        val v3Paired = v3Files.isNotEmpty() || (prefs?.get(PAIRED_DEVICES) as? JsonArray)?.isNotEmpty() == true
        val role = (prefs?.get(PROXY_ROLE) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (v3Paired) store.markUpgraded(needsRepair = role == ROLE_REMOTE, upgradeNotice = role == ROLE_HUB)

        SafeFile.erase(pairingFile)
        SafeFile.erase(deviceIdFile)
        if (prefs != null && hasPairedDevices) {
            val kept = JsonObject(prefs - PAIRED_DEVICES)
            if (!saveStore(prefsFile, json.encodeToString(JsonObject.serializer(), kept))) {
                Log.w(TAG, "couldn't drop $PAIRED_DEVICES from $PREFS_FILE; the next start retries")
            }
        }
        Log.i(TAG, "migrated relay v3 state (role ${role ?: "unknown"})")
        return Outcome.MIGRATED
    }

    /** `prefs.json` as a JSON object (either shape keeps these keys at the top level), or null. */
    private fun readPrefs(): JsonObject? = runCatching {
        prefsFile.takeIf(File::exists)?.readText()?.let { json.parseToJsonElement(it).jsonObject }
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
