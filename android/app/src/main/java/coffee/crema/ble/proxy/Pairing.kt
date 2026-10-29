package coffee.crema.ble.proxy

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A secondary's pairing request as the host sees it, after the [Frame.Hello] /
 * [Frame.Challenge] / [Frame.Auth] exchange. [proves] checks the peer's proof
 * against a secret the host issued; the challenge it covers stays inside the
 * relay.
 */
class PairingRequest(
    val clientId: String,
    val clientName: String,
    /** Whether the peer sent a proof at all (false = it holds no secret for us). */
    val hasProof: Boolean,
    private val check: (secret: String) -> Boolean,
) {
    /** True when the peer proved it holds [secret]. */
    fun proves(secret: String): Boolean = hasProof && check(secret)
}

/**
 * The pairing secret and its proofs. The host issues a random 256-bit secret
 * when it approves a device ([Frame.Welcome.pairingSecret]). On every later
 * connect both sides prove they hold it with an HMAC over both sessions'
 * nonces: the host first ([hostProof], in the [Frame.Challenge]), then the
 * device ([clientProof], in the [Frame.Auth]). The secret itself crosses the
 * network only once, at pairing; the two proofs carry different labels, so one
 * can't stand in for the other.
 */
object PairingCrypto {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** A new pairing secret (32 random bytes, base64url). */
    fun newSecret(): String = randomToken(32)

    /** A new challenge nonce (16 random bytes, base64url). */
    fun newNonce(): String = randomToken(16)

    /** The host's proof (in the Challenge): HMAC-SHA256 keyed with [secret]
     *  over the host id, the device id and both nonces. */
    fun hostProof(secret: String, primaryId: String, clientId: String, clientNonce: String, hostNonce: String): String =
        mac(secret, HOST, primaryId, clientId, clientNonce, hostNonce)

    /** The device's proof (in the Auth), over the same values with its own label. */
    fun clientProof(secret: String, primaryId: String, clientId: String, clientNonce: String, hostNonce: String): String =
        mac(secret, CLIENT, primaryId, clientId, clientNonce, hostNonce)

    /** Whether [proof] is the host's proof for [secret]. */
    fun verifyHost(secret: String, primaryId: String, clientId: String, clientNonce: String, hostNonce: String, proof: String?): Boolean =
        matches(proof) { hostProof(secret, primaryId, clientId, clientNonce, hostNonce) }

    /** Whether [proof] is the device's proof for [secret]. */
    fun verifyClient(secret: String, primaryId: String, clientId: String, clientNonce: String, hostNonce: String, proof: String?): Boolean =
        matches(proof) { clientProof(secret, primaryId, clientId, clientNonce, hostNonce) }

    private fun mac(secret: String, label: String, vararg parts: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(decoder.decode(secret), HMAC))
        val message = (listOf(DOMAIN, label) + parts).joinToString("\n")
        return encoder.encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    /** Constant-time comparison; false for a null or malformed proof or secret. */
    private fun matches(proof: String?, expected: () -> String): Boolean {
        if (proof == null) return false
        val want = runCatching(expected).getOrNull() ?: return false
        return MessageDigest.isEqual(want.toByteArray(Charsets.US_ASCII), proof.toByteArray(Charsets.US_ASCII))
    }

    private fun randomToken(bytes: Int): String = encoder.encodeToString(ByteArray(bytes).also(random::nextBytes))

    private const val HMAC = "HmacSHA256"
    private const val DOMAIN = "crema-pairing-v3"
    private const val HOST = "host"
    private const val CLIENT = "client"
}

/** A peer-supplied display name made safe to show: no control characters,
 *  trimmed, at most [max] characters; blank becomes "Unnamed device". */
internal fun sanitizePeerName(name: String, max: Int = 32): String =
    name.filterNot { it.isISOControl() || it == '\u2028' || it == '\u2029' }.trim().take(max).ifBlank { "Unnamed device" }

/** The reasons a primary gives in [Frame.Denied] (and pre-approval errors). */
object DenyReason {
    /** The host user declined (the view-only refusal text matches it too). */
    const val NOT_AUTHORIZED = "not authorized"
    /** Not a paired device, and the host's pairing window is closed. */
    const val PAIRING_CLOSED = "pairing-closed"
    /** Too many sessions, or another pairing request is being handled. */
    const val BUSY = "busy"
    /** The host forgot this device. */
    const val REVOKED = "revoked"
    /** The two sides run different protocol versions. */
    const val VERSION_MISMATCH = "version mismatch"
    /** A request arrived before the handshake completed. */
    const val NOT_PAIRED = "not-paired"
    /** Secondary-side: the Welcome came from another host than the one that
     *  challenged this session. */
    const val HANDSHAKE_MISMATCH = "handshake mismatch"
    /** Host-side: this device already holds a secret from this host; the host
     *  user has to Forget it before it can pair again. */
    const val ALREADY_PAIRED = "already-paired"
    /** Secondary-side: the host isn't the one this mirror target is pinned to. */
    const val HOST_MISMATCH = "host-mismatch"
    /** Secondary-side: the host couldn't prove the secret this device holds for
     *  it, and the user didn't choose to pair again. */
    const val HOST_UNVERIFIED = "host-unverified"
}
