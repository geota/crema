package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.persist.SafeFile
import coffee.crema.security.SecretBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** The on-disk shape of [PairingStore] (`filesDir/proxy-pairing.json`). */
@Serializable
internal data class PairingFile(
    /** This install's host id: what a secondary files its pairing secret under. */
    val hostId: String = "",
    /** Secrets this host issued, by the secondary's client id. Sealed at rest. */
    val issued: Map<String, String> = emptyMap(),
    /** Secrets this device holds for hosts it mirrors, by host id. Sealed at rest. */
    val held: Map<String, String> = emptyMap(),
    /** The host id the saved mirror target last completed a handshake with. */
    val pinnedHost: String? = null,
)

/**
 * The LAN pairing secrets, both directions: the ones this device issued as a
 * host and the ones it holds as a mirror. Kept out of `prefs.json` (which the
 * in-app backup carries) in their own credential file, excluded from cloud
 * backup and device transfer, with every secret sealed by [SecretBox]. A secret
 * that no longer opens (keystore key lost) reads as absent, so that device
 * simply pairs again.
 *
 * Writes are synchronous and rare (a pairing, a forget); the file is tiny.
 */
class PairingStore(
    private val file: File,
    private val box: SecretBox,
    private val json: Json,
) {
    private val lock = Any()
    private var state: PairingFile = load()

    /** This install's host id (stable; generated on first use). */
    val hostId: String get() = state.hostId

    /** The secret issued to [clientId], or null. */
    fun issuedSecret(clientId: String): String? = open(synchronized(lock) { state.issued[clientId] })

    /** Client ids that hold a secret from this host. */
    fun issuedIds(): Set<String> = synchronized(lock) { state.issued.keys.toSet() }

    /** Record [secret] as issued to [clientId]; false when it couldn't be stored. */
    fun putIssued(clientId: String, secret: String): Boolean =
        update { s -> seal(secret)?.let { s.copy(issued = s.issued + (clientId to it)) } }

    /** Forget the secret issued to [clientId]. */
    fun removeIssued(clientId: String) {
        update { s -> if (clientId in s.issued) s.copy(issued = s.issued - clientId) else null }
    }

    /** The secret this device holds for the host [hostId], or null. */
    fun heldSecret(hostId: String): String? = open(synchronized(lock) { state.held[hostId] })

    /** Whether a secret is stored for [hostId], without opening it (no
     *  keystore work). One whose key was lost still counts here. */
    fun holdsSecretFor(hostId: String): Boolean = synchronized(lock) { hostId in state.held }

    /** The host id the saved mirror target is pinned to, or null. */
    val pinnedHost: String? get() = synchronized(lock) { state.pinnedHost }

    /** Pin the saved mirror target to [hostId] (null clears it). */
    fun pinHost(hostId: String?) {
        update { s -> if (s.pinnedHost == hostId) null else s.copy(pinnedHost = hostId) }
    }

    /** Forget the secret this device holds for the host [hostId]. */
    fun removeHeld(hostId: String) {
        update { s -> if (hostId in s.held) s.copy(held = s.held - hostId) else null }
    }

    /** Keep [secret] for the host [hostId]; false when it couldn't be stored. */
    fun putHeld(hostId: String, secret: String): Boolean =
        update { s -> seal(secret)?.let { s.copy(held = s.held + (hostId to it)) } }

    private fun open(stored: String?): String? = when (val o = box.openOrNull(stored)) {
        is SecretBox.Opened.Value -> o.value
        SecretBox.Opened.Lost, null -> null
    }

    private fun seal(secret: String): String? = try {
        box.wrap(secret)
    } catch (e: Exception) {
        // Never fall back to plaintext: the pairing works for this session only.
        Log.e(TAG, "couldn't seal a pairing secret", e)
        null
    }

    /** Apply [change] (null = nothing to do) and write; false if nothing was stored. */
    private fun update(change: (PairingFile) -> PairingFile?): Boolean = synchronized(lock) {
        val next = change(state) ?: return false
        state = next
        write(next)
    }

    private fun load(): PairingFile {
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString(PairingFile.serializer(), file.readText()) else null
        }.onFailure { Log.w(TAG, "unreadable ${file.name}; starting without pairings", it) }.getOrNull()
        if (loaded != null && loaded.hostId.isNotBlank()) return loaded
        val fresh = (loaded ?: PairingFile()).copy(hostId = UUID.randomUUID().toString())
        write(fresh)
        return fresh
    }

    private fun write(state: PairingFile): Boolean = try {
        SafeFile.writeCredential(file, json.encodeToString(PairingFile.serializer(), state))
        true
    } catch (e: Exception) {
        Log.e(TAG, "couldn't save ${file.name}", e)
        false
    }

    companion object {
        const val FILE_NAME = "proxy-pairing.json"
        private const val TAG = "PairingStore"
    }
}
