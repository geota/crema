package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.core.RelayKeys
import coffee.crema.persist.SafeFile
import coffee.crema.security.AndroidKeyStoreBackend
import coffee.crema.security.AndroidKeystoreCipher
import coffee.crema.security.AndroidKeystoreKeySource
import coffee.crema.security.KeystoreBackend
import coffee.crema.security.KeystoreFailure
import coffee.crema.security.SecretBox
import java.io.File
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The on-disk shape of [RelayKeyStore] (`filesDir/relay-v4.json`, relay v4 spec §5.1). */
@Serializable
internal data class RelayFile(
    val v: Int = 1,
    val self: RelaySelf? = null,
    val hubs: List<RelayHubPin> = emptyList(),
    val remotes: List<RelayRemotePin> = emptyList(),
    val savedHub: String? = null,
    /** A definitive loss replaced the key: show the one-time notice. */
    val keyResetNotice: Boolean = false,
    /** Set by the v3 migration on a remote: show "Pair again" and don't dial (§6.4). */
    val needsRepair: Boolean = false,
    /** Set by the v3 migration on a hub: the one-time "pair your phones again" banner. */
    val upgradeNotice: Boolean = false,
    /** The v3 migration has run once: v3 leftovers found later are removed but never raise the flags again. */
    val v3Migrated: Boolean = false,
)

/** This device's key: [private] is always `sb1:`-sealed; never in clear. */
@Serializable
internal data class RelaySelf(val id: String, val public: String, val private: String)

/** A hub this remote pinned at pairing (public data). */
@Serializable
data class RelayHubPin(
    val id: String,
    /** The hub's X25519 public key, base64. */
    val public: String,
    val name: String,
    val host: String,
    val port: Int,
    val pinnedAtMs: Long,
    val lastSeenMs: Long = 0,
)

/** A remote this hub pinned at pairing (public data). */
@Serializable
data class RelayRemotePin(
    val id: String,
    /** The remote's X25519 public key, base64. */
    val public: String,
    val name: String,
    /** `control` or `mirror`. */
    val scope: String,
    val pairedAtMs: Long,
    val lastSeenMs: Long = 0,
)

/** What [RelayKeyStore.load] found. */
sealed interface RelayKeyLoad {
    /**
     * The key is ready. [persistent] is false when it could not be sealed (the
     * keystore can't create a key): it then lives in memory only, and so do the
     * pins, for this process. [keyReset] is true when a definitive loss just
     * replaced the key and cleared every pin: show the notice, and a remote
     * role becomes normal.
     */
    data class Ready(val keys: RelayKeys, val persistent: Boolean, val keyReset: Boolean) : RelayKeyLoad

    /**
     * Transient: the key couldn't be read this start. Nothing was touched (the
     * file and every pin are as they were); the relay stays off and the next
     * start retries. [reason] names the cause, never key material.
     */
    data class Unavailable(val reason: String) : RelayKeyLoad
}

/**
 * This device's relay v4 static key and pins (spec §5.1), replacing the v3
 * `PairingStore`. One instance per process ([forProcess]): two would race on
 * the file.
 *
 * - The private key is sealed with a dedicated AndroidKeyStore key
 *   ([RELAY_ALIAS], AAD [RELAY_AAD]) through [SecretBox.wrapBytes], so it never
 *   becomes a `String`; the unsealed bytes are zero-filled once a [RelayKeys]
 *   holds them. Nothing here logs key material, only exception class names.
 * - Opening it is strict and classified ([coffee.crema.security.KeystoreFailures]):
 *   a **definitive** loss (missing alias, `AEADBadTagException`,
 *   `KeyPermanentlyInvalidatedException`, an unsealed value) moves the old file
 *   to `relay-lost/`, generates a new key and starts with no pins; anything
 *   else is **transient** and [load] returns [RelayKeyLoad.Unavailable] with the
 *   file untouched. An unreadable file is treated as transient too: nothing
 *   proves the key is gone.
 * - When a new key can't be sealed it is kept in memory only, and pins are not
 *   persisted either, so a later start never reads that state as a loss and
 *   clears pins made meanwhile.
 *
 * Public data (pins) is stored in clear in the same credential file, excluded
 * from backup and device transfer (`data_extraction_rules.xml`).
 */
class RelayKeyStore(
    private val filesDir: File,
    private val box: SecretBox,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val file = File(filesDir, FILE_NAME)
    private var state = RelayFile()
    private var keys: RelayKeys? = null
    private var persistent = false

    /** Open (or create) the key. Call once per start, before anything else. */
    fun load(): RelayKeyLoad = synchronized(lock) {
        val text = try {
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            return unavailable("store unreadable: ${e.javaClass.simpleName}")
        }
        if (text == null) return createKey(RelayFile(), keyReset = false)
        val stored = try {
            json.decodeFromString(RelayFile.serializer(), text)
        } catch (e: Exception) {
            return unavailable("store unparseable: ${e.javaClass.simpleName}")
        }
        val self = stored.self ?: return noSelf(stored)
        when (val opened = box.openBytes(self.private)) {
            is SecretBox.OpenedBytes.Value -> {
                val bytes = opened.bytes
                val loaded = try {
                    RelayKeys.fromPrivate(bytes)
                } catch (e: Exception) {
                    // The blob authenticated but isn't a 32-byte key: not a keystore
                    // verdict, so transient. Leave everything for a retry.
                    return unavailable("sealed key malformed: ${e.javaClass.simpleName}")
                } finally {
                    bytes.fill(0)
                }
                keys = loaded
                persistent = true
                state = stored
                RelayKeyLoad.Ready(loaded, persistent = true, keyReset = false)
            }
            is SecretBox.OpenedBytes.Failed -> when (opened.kind) {
                KeystoreFailure.TRANSIENT -> unavailable("key unreadable: ${opened.reason}")
                KeystoreFailure.DEFINITIVE -> {
                    Log.w(TAG, "relay key lost (${opened.reason}); starting over with a new key and no pins")
                    moveAside()
                    createKey(RelayFile(keyResetNotice = true, v3Migrated = stored.v3Migrated), keyReset = true)
                }
            }
        }
    }

    private fun unavailable(reason: String): RelayKeyLoad.Unavailable {
        Log.w(TAG, "relay keys unavailable this start: $reason")
        keys = null
        return RelayKeyLoad.Unavailable(reason)
    }

    /**
     * A file without a key (hand-edited, or written by something else): its pins
     * belong to a key this device doesn't have, so none are kept. With pins it is
     * a reset like a definitive loss (moved aside, notice); without, just a new key.
     */
    private fun noSelf(stored: RelayFile): RelayKeyLoad {
        val hadPairings = stored.hubs.isNotEmpty() || stored.remotes.isNotEmpty() || stored.savedHub != null
        if (hadPairings) {
            Log.w(TAG, "relay store has pins but no key; starting over with a new key and no pins")
            moveAside()
        }
        return createKey(RelayFile(keyResetNotice = hadPairings, v3Migrated = stored.v3Migrated), keyReset = hadPairings)
    }

    /** Move the unopenable file to `relay-lost/relay-v4.json.<ms>`, kept for diagnosis. */
    private fun moveAside() {
        val lost = File(filesDir, LOST_DIR)
        lost.mkdirs()
        val target = File(lost, "$FILE_NAME.${nowMs()}")
        if (!file.renameTo(target)) {
            // Never leave the old pins in place under a new key.
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        SafeFile.purgeCopies(file, keptAside = true)
    }

    /** Generate a key; seal and write it with [base] (no pins), or keep it in memory when sealing fails. */
    private fun createKey(base: RelayFile, keyReset: Boolean): RelayKeyLoad {
        val fresh = try {
            RelayKeys.generate()
        } catch (e: Exception) {
            // No entropy: nothing to seal. Transient; nothing is written.
            return unavailable("no entropy for a new key: ${e.javaClass.simpleName}")
        }
        val private = fresh.privateForStorage()
        val sealed = try {
            box.wrapBytes(private)
        } catch (e: Exception) {
            Log.e(TAG, "couldn't seal the relay key (${e.javaClass.simpleName}); keeping it in memory only")
            null
        } finally {
            private.fill(0)
        }
        keys = fresh
        // [base] never carries pins: a new key starts with none.
        state = base.copy(self = sealed?.let { RelaySelf(fresh.deviceId(), b64(fresh.publicKey()), it) })
        persistent = sealed != null && write(state)
        return RelayKeyLoad.Ready(fresh, persistent = persistent, keyReset = keyReset)
    }

    // ---- reads --------------------------------------------------------------

    val hubs: List<RelayHubPin> get() = synchronized(lock) { state.hubs }
    val remotes: List<RelayRemotePin> get() = synchronized(lock) { state.remotes }
    val savedHub: String? get() = synchronized(lock) { state.savedHub }
    val keyResetNotice: Boolean get() = synchronized(lock) { state.keyResetNotice }
    val needsRepair: Boolean get() = synchronized(lock) { state.needsRepair }
    val upgradeNotice: Boolean get() = synchronized(lock) { state.upgradeNotice }
    val v3Migrated: Boolean get() = synchronized(lock) { state.v3Migrated }

    /** Whether a key is loaded (false before [load], or after an [RelayKeyLoad.Unavailable]). */
    val isLoaded: Boolean get() = synchronized(lock) { keys != null }

    /** Whether pins are written to disk (false while the key is memory-only). */
    val isPersistent: Boolean get() = synchronized(lock) { persistent }

    // ---- writes (no-ops on disk while memory-only; all need a loaded key) ----

    /**
     * Pin (or replace) a remote. Returns the record it replaced (a re-pair), so a
     * failed `confirm_paired` can put it back with [restoreRemote] (the
     * restore-prior rollback rule, PROTOCOL.md §4 step 9).
     */
    fun pinRemote(pin: RelayRemotePin): RelayRemotePin? = update { s ->
        val previous = s.remotes.firstOrNull { it.id == pin.id }
        previous to s.copy(remotes = s.remotes.filterNot { it.id == pin.id } + pin)
    }

    /**
     * [pinRemote] for the hub's pairing commit (spec §2.4 step 9): returns the
     * record it replaced and whether the file was written (always true while
     * memory-only, where pins last for this process by design), so a failed
     * write can `reject_pairing` instead of confirming.
     */
    fun pinRemoteSaved(pin: RelayRemotePin): PinWrite = synchronized(lock) {
        var previous: RelayRemotePin? = null
        val saved = updateSaved { s ->
            previous = s.remotes.firstOrNull { it.id == pin.id }
            s.copy(remotes = s.remotes.filterNot { it.id == pin.id } + pin)
        }
        PinWrite(previous, saved)
    }

    /** Put back [previous] for [id] (null: the remote wasn't pinned, so unpin it). */
    fun restoreRemote(id: String, previous: RelayRemotePin?) {
        update { s ->
            val others = s.remotes.filterNot { it.id == id }
            Unit to s.copy(remotes = if (previous == null) others else others + previous)
        }
    }

    fun unpinRemote(id: String) {
        update { s -> Unit to s.copy(remotes = s.remotes.filterNot { it.id == id }) }
    }

    /** Pin (or replace) a hub; returns the record it replaced. */
    fun pinHub(pin: RelayHubPin): RelayHubPin? = update { s ->
        val previous = s.hubs.firstOrNull { it.id == pin.id }
        previous to s.copy(hubs = s.hubs.filterNot { it.id == pin.id } + pin)
    }

    /** Forget a hub (and stop targeting it). */
    fun unpinHub(id: String) {
        update { s ->
            Unit to s.copy(hubs = s.hubs.filterNot { it.id == id }, savedHub = s.savedHub.takeUnless { it == id })
        }
    }

    fun setSavedHub(id: String?) {
        update { s -> Unit to s.copy(savedHub = id) }
    }

    fun touchRemote(id: String, atMs: Long) {
        update { s -> Unit to s.copy(remotes = s.remotes.map { if (it.id == id) it.copy(lastSeenMs = atMs) else it }) }
    }

    fun touchHub(id: String, atMs: Long) {
        update { s -> Unit to s.copy(hubs = s.hubs.map { if (it.id == id) it.copy(lastSeenMs = atMs) else it }) }
    }

    fun clearKeyResetNotice() {
        update { s -> Unit to s.copy(keyResetNotice = false) }
    }

    /**
     * Raise the v3 migration's flags (§5.1, §6.4) and record that it ran; never
     * clears a flag already set. Returns false when the file couldn't be
     * written (the caller must not act as if it was recorded); true when it was,
     * or when the store is memory-only (nothing is ever written then).
     */
    fun markUpgraded(needsRepair: Boolean, upgradeNotice: Boolean): Boolean = updateSaved { s ->
        s.copy(
            needsRepair = s.needsRepair || needsRepair,
            upgradeNotice = s.upgradeNotice || upgradeNotice,
            v3Migrated = true,
        )
    }

    /** Record that the v3 migration has run (or had nothing to do); false as for [markUpgraded]. */
    fun markV3Migrated(): Boolean = updateSaved { s -> s.copy(v3Migrated = true) }

    fun clearUpgradeFlags() {
        update { s -> Unit to s.copy(needsRepair = false, upgradeNotice = false) }
    }

    /**
     * Reset pairing key (§2.10): a new key, every pin deleted in both
     * directions. Returns the new keys, or null when no key was loaded.
     *
     * [fromUnavailable]: the user's way out of a store that stays unreadable
     * (SR-3a P2-1): with no key loaded, the old file moves to `relay-lost/` (as
     * for a definitive loss) and a new key starts with the reset notice. Only
     * ever from an explicit user action, never automatically.
     */
    fun resetKey(fromUnavailable: Boolean = false): RelayKeys? = synchronized(lock) {
        if (keys == null) {
            if (!fromUnavailable) return null
            if (file.exists()) moveAside()
            return when (val result = createKey(RelayFile(keyResetNotice = true), keyReset = true)) {
                is RelayKeyLoad.Ready -> result.keys
                is RelayKeyLoad.Unavailable -> null
            }
        }
        when (val result = createKey(RelayFile(v3Migrated = state.v3Migrated), keyReset = false)) {
            is RelayKeyLoad.Ready -> result.keys
            is RelayKeyLoad.Unavailable -> null
        }
    }

    private fun <R> update(change: (RelayFile) -> Pair<R, RelayFile>): R = synchronized(lock) {
        check(keys != null) { "relay keys not loaded" }
        val (result, next) = change(state)
        state = next
        if (persistent) write(next)
        result
    }

    /** [update] that reports whether the change reached the file (always true while memory-only). */
    private fun updateSaved(change: (RelayFile) -> RelayFile): Boolean = synchronized(lock) {
        check(keys != null) { "relay keys not loaded" }
        state = change(state)
        !persistent || write(state)
    }

    private fun write(next: RelayFile): Boolean = try {
        SafeFile.writeCredential(file, json.encodeToString(RelayFile.serializer(), next))
        true
    } catch (e: Exception) {
        Log.e(TAG, "couldn't save $FILE_NAME: ${e.javaClass.simpleName}")
        false
    }

    companion object {
        const val FILE_NAME = "relay-v4.json"
        const val LOST_DIR = "relay-lost"

        /** The relay key's own AndroidKeyStore alias, separate from the account tokens'. */
        const val RELAY_ALIAS = "crema.relay.v1"

        /** Associated data bound into every seal of the relay key. */
        val RELAY_AAD: ByteArray get() = "relay-v4 static".toByteArray(Charsets.US_ASCII)

        private const val TAG = "RelayKeyStore"

        /** Its own format: every field written (the §5.1 shape), unknown fields from a later version kept readable. */
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        private val perProcess = HashMap<String, RelayKeyStore>()

        /**
         * The process's one store for [filesDir] (SR-3a P3-1). The per-alias
         * lock covers key creation, but two instances would still race on the
         * file, so a view model built again in the same process gets the same
         * store (and [load]s it again).
         */
        fun forProcess(filesDir: File): RelayKeyStore = synchronized(perProcess) {
            perProcess.getOrPut(filesDir.absolutePath) { RelayKeyStore(filesDir, deviceBox()) }
        }

        /**
         * The device box for the relay key: dedicated alias and AAD (§5.1), and the
         * strict key lookup with the liveness probe ([AndroidKeystoreKeySource]).
         */
        fun deviceBox(backend: KeystoreBackend = backendForTests ?: AndroidKeyStoreBackend): SecretBox =
            SecretBox(AndroidKeystoreCipher(RELAY_ALIAS, RELAY_AAD, AndroidKeystoreKeySource(RELAY_ALIAS, backend)))

        /**
         * Test seam: the keystore [deviceBox] uses when set. Robolectric has no
         * AndroidKeyStore, so the VM tests install a fake one here before the
         * view model (and its [coffee.crema.ui.ProxyController]) is built.
         */
        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var backendForTests: KeystoreBackend? = null

        internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        /** A pin's public key bytes. */
        fun publicKeyOf(base64: String): ByteArray = Base64.getDecoder().decode(base64)
    }
}
