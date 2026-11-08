package coffee.crema.visualizer

import android.content.Context
import android.util.Log
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SafeLoad
import coffee.crema.persist.eraseStore
import coffee.crema.persist.loadStore
import coffee.crema.security.DeviceSecretBox
import coffee.crema.security.SecretBox
import coffee.crema.core.VisualizerSyncPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/*
 * Visualizer state — tokens, the in-flight PKCE handshake, the cached account,
 * and the sync preferences. One file (`filesDir/visualizer.json`), mirroring
 * the web shell's `crema.visualizer.{tokens,sync}.v1` localStorage keys, and
 * keeping tokens out of `prefs.json` (which `eraseAll` / reset-preferences
 * rewrite with defaults — signing out should be an explicit act).
 *
 * Same file-JSON store pattern as the rest of the shell (suspend load/save on
 * Dispatchers.IO, defaults on parse failure).
 */

/** Crema-side projection of the Visualizer `/me` response (web `VisualizerAccount`). */
@Serializable
data class VisualizerAccount(
    val id: String,
    val name: String,
    val public: Boolean = false,
    val avatarUrl: String = "",
)

/** One sync activity-log line (web `SyncLogEntry`) — capped, newest first. */
@Serializable
data class SyncLogEntry(
    /** Where it went (serialised `"visualizer"` / `"decent"`; entries predating the field are Visualizer's). */
    val destination: coffee.crema.ui.UploadTargetId = coffee.crema.ui.UploadTargetId.Visualizer,
    /** `"push" | "pull" | "skip" | "delete"`. */
    val direction: String,
    /** `"shot" | "bean" | "roaster"`. */
    val entity: String,
    val id: String,
    val name: String,
    /** Unix ms. */
    val at: Long,
    val error: String? = null,
)

/** The activity log's entity tag (web ActivityLog `entityLabel`). */
val SyncLogEntry.entityLabel: String
    get() = when (entity) {
        "bean" -> "Bag"
        "roaster" -> "Roaster"
        else -> "Shot"
    }

/**
 * A shot deleted here that is still on Visualizer (web's history tombstone):
 * kept so the next pull's reconcile recognises the remote copy instead of
 * pulling it back in, and — when the user asked for the remote copy to go
 * too ([deleteRemote]) — so the `DELETE /shots/{id}` retries until it lands,
 * after which the tombstone is dropped. A shot never uploaded leaves none.
 */
@Serializable
data class ShotTombstone(
    /** The deleted local shot's id. */
    val id: String,
    /** The remote copy's Visualizer `shot.id`. */
    val visualizerId: String,
    /** Unix ms of the local delete. */
    val deletedAt: Long,
    /** The user asked to delete the Visualizer copy too ("Also delete on Visualizer"). */
    val deleteRemote: Boolean = false,
    /** Failed remote deletes so far (core `visualizer_queue_decision`). */
    val attempts: Int = 0,
    /** Unix ms before which the retry drain leaves the remote delete alone (0 = due). */
    val nextAttemptAt: Long = 0L,
    /**
     * The account the remote copy is on, while another is signed in: its delete
     * waits, since this one would only be refused. Null = the signed-in account's.
     */
    val parkedFor: String? = null,
) {
    /** A remote delete the signed-in account should carry out. */
    val owed: Boolean get() = deleteRemote && parkedFor == null
}

/**
 * A shot deleted here that may be on Visualizer without Crema knowing its id: an
 * upload was attempted, and no answer has come. The delete recorded the intent,
 * and the id arrives later: from the upload if it finishes, or from a pull, which
 * matches the row by upload key and duration instead of adding it back. Kept
 * apart from [ShotTombstone] so an older build can still read this file.
 */
@Serializable
data class UnboundTombstone(
    /** The deleted local shot's id. */
    val id: String,
    /** With [durationMs], what a pull matches a remote row by. */
    val completedAtMs: Long,
    val durationMs: Long,
    /** Unix ms of the local delete. */
    val deletedAt: Long,
    /** The user asked to delete the Visualizer copy too. */
    val deleteRemote: Boolean = false,
    /**
     * [VisualizerState.pullRun] when this was written. Only a complete pull that
     * started later, covered the window of [attemptedAt], and found no match ends
     * it. There's no time limit: this row is small, and a shot coming back is the bug.
     */
    val writtenRun: Long = 0L,
    /** When the POST went (unix ms), or null when nothing recorded it. */
    val attemptedAt: Long? = null,
    /**
     * The account the POST went to, while another is signed in. Its rows aren't
     * this account's to match, and a pull of this one says nothing about it, so it
     * waits for that account to come back. Null = the signed-in account's.
     */
    val parkedFor: String? = null,
)

/**
 * A shot upload waiting for its retry (F17): a recoverable failure (offline, a
 * 5xx, a 429) is queued here and retried by the drain on the core's backoff
 * (`visualizer_queue_decision`), at most three attempts in all.
 */
/**
 * What a History edit changed on a shot already on Visualizer, until a PATCH
 * carrying it lands. A PATCH sends only what is marked: the journal fields for
 * [annotations], `barista` for [barista]. Android has no tag editor, so it never
 * marks tags. The set is also the PATCH retry list, walked by the drain.
 */
@Serializable
data class PendingEdit(
    /** The rating, notes, privacy, bean or grind changed. */
    val annotations: Boolean = false,
    /** The barista changed. */
    val barista: Boolean = false,
    /** Failed attempts since the last success. */
    val attempts: Int = 0,
    /** Unix ms before which the drain leaves this shot alone; 0 = due. */
    val nextAttemptAt: Long = 0,
    /** Unix ms of the last tier probe a Premium refusal of this edit made; 0 = none. */
    val tierProbedAt: Long = 0,
) {
    /** Whether this still owes Visualizer anything. */
    val owes: Boolean get() = annotations || barista

    /** This and [other] together, due at once (a new edit). A probe stamp stays: one a day. */
    fun with(other: PendingEdit) = PendingEdit(
        annotations = annotations || other.annotations,
        barista = barista || other.barista,
        tierProbedAt = maxOf(tierProbedAt, other.tierProbedAt),
    )
}

@Serializable
data class QueuedUpload(
    /** The local shot's id. */
    val shotId: String,
    /** Attempts made so far. */
    val attempts: Int,
    /** Unix ms of the earliest retry. */
    val nextAttemptAt: Long,
)

/** Everything Visualizer-related the shell persists. */
@Serializable
data class VisualizerState(
    /** The current token set, or null when signed out. */
    val tokens: TokenSet? = null,
    /** PKCE verifier for the in-flight browser handshake (single-use). */
    val pendingVerifier: String? = null,
    /** CSRF state for the in-flight browser handshake (single-use). */
    val pendingState: String? = null,
    /** Cached `/me` projection; refreshed on sign-in and on Settings open. */
    val account: VisualizerAccount? = null,
    /**
     * Cached Visualizer Premium tier — the web's bean-sync `premium` flag:
     * set by the sentinel roaster probe ([VisualizerClient.probePremium]) on
     * sign-in, at app start (at most once per 24 h) and on Test. Null until
     * probed (or after sign-out): the edit sync then treats the account as
     * free and drops the Premium-only PATCH fields.
     */
    val premium: Boolean? = null,
    /** Unix ms of the last conclusive [premium] probe, or null = never (gates the daily app-start re-probe). */
    val premiumCheckedAt: Long? = null,
    /**
     * Unified sync PREFERENCES — the shared core [VisualizerSyncPrefs] shape both
     * shells serialise identically, so a backup's `visualizerPrefs` line moves
     * between web and Android with no per-shell tag. Pre-unification builds stored
     * these as flat fields (`autoSync`/`privacy`/…); [VisualizerStore.load]
     * migrates such a file forward.
     */
    val prefs: VisualizerSyncPrefs = DEFAULT_VISUALIZER_SYNC_PREFS,
    /** Unix ms of the last successful shot push, or null. */
    val lastShotSyncAt: Long? = null,
    /**
     * Unix ms of the last completed bean / roaster sync (web bean-sync
     * `lastSyncAt`), or null = never. Bound bags edited after it are PATCHed
     * by the next run.
     */
    val beanLastSyncAt: Long? = null,
    /**
     * Incremental pull cursor (unix ms); null pulls everything. Advanced ONLY
     * by a successful pull — never by a push (the web learned this the hard
     * way: a shared cursor starved the pull).
     */
    val shotPullCursor: Long? = null,
    /**
     * The Visualizer account this device's cursor, marks and queue belong to. It
     * outlives a sign-out, so a sign-in as someone else is noticed; only Erase
     * clears it. Seeded from [account] at load, so the first reconnect after this
     * field arrived is covered.
     */
    val syncAccountId: String? = null,
    /**
     * Signed in, but `/me` hasn't said as whom. Nothing is sent or fetched until
     * it does: the drain keeps asking.
     */
    val accountUnconfirmed: Boolean = false,
    /** Signed in as a different account: uploads wait for the user's answer. */
    val accountChangeAsk: Boolean = false,
    /**
     * Shots completed before this (unix ms) stay off the account: the user said
     * no when asked. New shots flow, so uploads can't stay held for good.
     */
    val uploadExcludedBefore: Long? = null,
    /**
     * The server ended the session. Signed out, with the account, cursor, marks
     * and tombstones kept, and a lasting banner until the user signs in or
     * disconnects.
     */
    val needsReconnect: Boolean = false,
    /** Recent sync activity — capped at 20 entries, newest first. */
    val log: List<SyncLogEntry> = emptyList(),
    /**
     * The old form of [pendingEdits]: shots whose barista edit hadn't reached
     * Visualizer. Read once, on load, into [pendingEdits], and stored empty.
     */
    val baristaPending: Set<String> = emptySet(),
    /**
     * Edits of uploaded shots waiting to reach Visualizer, by shot id (see
     * [PendingEdit]). Persisted, so a restart keeps them and a pull after one
     * still doesn't refill a cleared barista (round-2 verification V2 P3-4);
     * the web keeps the same set in `crema.visualizer.pendingEdits.v1`.
     */
    val pendingEdits: Map<String, PendingEdit> = emptyMap(),
    /**
     * Shots deleted here whose Visualizer copy still exists — see
     * [ShotTombstone]. Not part of the history, so nothing user-visible ever
     * reads them; only the shot reconcile and the remote delete do.
     */
    val shotTombstones: List<ShotTombstone> = emptyList(),
    /** Shot uploads waiting for a retry — see [QueuedUpload]. */
    val retryQueue: List<QueuedUpload> = emptyList(),
    /**
     * Shots a POST may have reached Visualizer for, with when (unix ms). Stored
     * before each POST and cleared on a bind or a refusal, so a delete during an
     * upload knows there may be a remote copy, even after a kill.
     */
    val uploadAttempted: Map<String, Long> = emptyMap(),
    /** See [UnboundTombstone]. */
    val unboundTombstones: List<UnboundTombstone> = emptyList(),
    /** Counts pulls, to order them against [UnboundTombstone.writtenRun]. */
    val pullRun: Long = 0L,
    /**
     * The stored state as read, when the keystore couldn't open a secret this
     * start ([SecretBox.Opened.Unavailable]); in memory only. Meanwhile the
     * session reads as signed out (no tokens, account or handshake), and every
     * save writes the stored sealed values back unchanged, so a transient
     * keystore error never erases the sign-in. Setting new tokens or signing
     * out drops it ([VisualizerSync]).
     */
    @Transient val unopened: VisualizerState? = null,
)

/** The canonical default sync prefs — mirrors the core `VisualizerSyncPrefs::default`. */
val DEFAULT_VISUALIZER_SYNC_PREFS: VisualizerSyncPrefs = VisualizerSyncPrefs(
    autoUpload = true,
    autoSync = true,
    privacy = "unlisted",
    includeProfile = true,
    includeNotes = false,
    shotsDirection = "backup",
    beansDirection = "two-way",
    roastersDirection = "two-way",
)

/** The at-rest form: token + PKCE verifier sealed ([SecretBox]). Idempotent. */
internal fun VisualizerState.sealedWith(box: SecretBox): VisualizerState {
    fun seal(v: String) = if (SecretBox.isWrapped(v)) v else box.wrap(v)
    val kept = unopened?.takeIf { tokens == null }
    return copy(
        tokens = tokens?.let { t -> t.copy(accessToken = seal(t.accessToken), refreshToken = t.refreshToken?.let(::seal)) }
            ?: kept?.tokens,
        account = account ?: kept?.account,
        // A handshake is single-use: one in flight at that start isn't kept.
        pendingVerifier = pendingVerifier?.let(::seal),
    )
}

/**
 * The in-memory form, plus whether to re-save (a legacy plaintext secret, or
 * one the key can no longer open — then the session is dropped: signed out).
 * When a secret can't be opened this start (transient), the session reads as
 * signed out, the stored state is kept in [VisualizerState.unopened], and
 * nothing is re-saved.
 */
internal fun VisualizerState.openedWith(box: SecretBox): Pair<VisualizerState, Boolean> {
    var resave = false
    var lost = false
    var unavailable = false
    fun open(v: String?): String? = when (val o = box.openOrNull(v)) {
        null -> null
        is SecretBox.Opened.Value -> { if (o.migrate) resave = true; o.value }
        SecretBox.Opened.Lost -> { lost = true; resave = true; null }
        SecretBox.Opened.Unavailable -> { unavailable = true; null }
    }
    val access = tokens?.let { open(it.accessToken) }
    val refresh = tokens?.refreshToken?.let { open(it) }
    val verifier = open(pendingVerifier)
    if (unavailable && !lost) {
        return copy(tokens = null, account = null, pendingVerifier = null, pendingState = null, unopened = this) to false
    }
    val opened = copy(
        tokens = if (lost || access == null) null else tokens.copy(accessToken = access, refreshToken = refresh),
        account = if (lost) null else account,
        pendingVerifier = if (lost) null else verifier,
        pendingState = if (lost) null else pendingState,
    )
    return opened to resave
}

/** File-backed JSON persistence for [VisualizerState] (`filesDir/visualizer.json`). */
class VisualizerStore(
    private val context: Context,
    private val json: Json,
    private val box: SecretBox = DeviceSecretBox.instance,
) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): VisualizerState = withContext(Dispatchers.IO) {
        // Unreadable: kept aside + reported (see [loadStore]); starts signed out.
        val outcome = loadStore(file, "Visualizer settings") { text ->
            val state = json.decodeFromString(VisualizerState.serializer(), text)
            // Migrate a pre-unification file (flat `autoSync`/`privacy`/… fields and
            // no `prefs` object) forward into the shared [VisualizerSyncPrefs] shape.
            val obj = json.parseToJsonElement(text).jsonObject
            if (obj["prefs"] == null && obj.keys.any { it in LEGACY_PREF_KEYS }) {
                state.copy(prefs = migrateLegacyPrefs(obj))
            } else {
                state
            }
        }
        val raw = when (outcome) {
            is SafeLoad.Loaded -> outcome.value
            SafeLoad.Absent, is SafeLoad.Corrupt, is SafeLoad.Unavailable -> return@withContext VisualizerState()
        }
        // A `.bak` left by an older build may hold a previous (or plaintext) token.
        SafeFile.purgeCopies(file)
        val (state, resave) = raw.openedWith(box)
        if (resave) write(state)
        state
    }

    /** Build [VisualizerSyncPrefs] from a pre-unification file's flat fields. The
     *  legacy `autoSync` WAS the shot auto-upload gate (Android's single auto flag). */
    private fun migrateLegacyPrefs(obj: JsonObject): VisualizerSyncPrefs {
        fun bool(k: String, d: Boolean) = (obj[k] as? JsonPrimitive)?.booleanOrNull ?: d
        fun str(k: String, d: String) = (obj[k] as? JsonPrimitive)?.contentOrNull ?: d
        return VisualizerSyncPrefs(
            autoUpload = bool("autoSync", true),
            autoSync = true,
            privacy = str("privacy", "unlisted"),
            includeProfile = bool("includeProfile", true),
            includeNotes = bool("includeNotes", false),
            shotsDirection = str("shotsDirection", "backup"),
            beansDirection = "two-way",
            roastersDirection = "two-way",
        )
    }

    /** Write [state]; false (logged) when it could not be stored. */
    suspend fun save(state: VisualizerState): Boolean = withContext(Dispatchers.IO) { write(state) }

    /** Delete the file and every copy (Erase all data); true when nothing remains. */
    suspend fun erase(): Boolean = withContext(Dispatchers.IO) { eraseStore(file) }

    private fun write(state: VisualizerState): Boolean = try {
        // No `.bak` copies of sealed tokens (review 05 P3-5).
        SafeFile.writeCredential(file, json.encodeToString(VisualizerState.serializer(), state.sealedWith(box)), "Visualizer settings")
        true
    } catch (e: Exception) {
        // Includes a keystore failure while sealing: never fall back to plaintext.
        Log.e(TAG, "couldn't save visualizer.json", e)
        false
    }

    internal companion object {
        const val FILE_NAME = "visualizer.json"
        const val TAG = "VisualizerStore"
        val LEGACY_PREF_KEYS = setOf(
            "autoSync", "privacy", "includeProfile", "includeNotes", "shotsDirection",
        )
    }
}
