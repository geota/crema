package coffee.crema.drive

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import coffee.crema.core.SyncFailure
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.IOException

/*
 * Google Play services' way to a Drive token (#336 §4), `play` builds only:
 * the only file that imports com.google.android.gms. The Authorization API
 * hands back access tokens, never a refresh token: each silent [fresh] asks it
 * again, and it answers without UI while the grant stands, from a background
 * backup too. Google matches the app by package + signing SHA-1, so no client
 * id is compiled in for this way.
 */

/** Google's access tokens live an hour, and the result doesn't say how long: renew well before. */
internal const val PLAY_TOKEN_TTL_MS = 50L * 60 * 1000

/** What an `authorize` call gave, without the gms types, so JVM tests can fake it. */
internal class PlayAuthorization(
    val accessToken: String?,
    /** The Google account the grant is for, when the result names it. */
    val account: String?,
    /** The user must act: a consent screen to launch ([consent]). */
    val hasResolution: Boolean,
    val consent: PendingIntent? = null,
)

/** The thin wrapper over [com.google.android.gms.auth.api.identity.AuthorizationClient]. Throws [ApiException]. */
internal interface PlayAuthApi {
    /** Play services works on this phone (microG says so too). */
    fun available(): Boolean

    /** `authorize(drive.file)`, on [account] when given. */
    suspend fun authorize(account: String?): PlayAuthorization

    /** The consent screen's result. */
    fun fromIntent(data: Intent?): PlayAuthorization

    /** Drop [accessToken] from Play services' cache, so the next authorize mints another. */
    suspend fun clearToken(accessToken: String)

    /** Withdraw the drive.file grant for [account]. */
    suspend fun revokeAccess(account: String)
}

/** The real [PlayAuthApi]: the application context, so a background backup can call it. */
internal class GmsPlayAuthApi(context: Context) : PlayAuthApi {
    private val context = context.applicationContext
    private val client get() = Identity.getAuthorizationClient(context)
    private val scopes = listOf(Scope(DRIVE_SCOPE))

    override fun available() =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    override suspend fun authorize(account: String?): PlayAuthorization {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .apply { if (account != null) setAccount(Account(account, GOOGLE_ACCOUNT_TYPE)) }
            .build()
        return client.authorize(request).await().toPlay()
    }

    override fun fromIntent(data: Intent?): PlayAuthorization = client.getAuthorizationResultFromIntent(data).toPlay()

    override suspend fun clearToken(accessToken: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(accessToken).build()).await()
    }

    override suspend fun revokeAccess(account: String) {
        client.revokeAccess(
            RevokeAccessRequest.builder().setAccount(Account(account, GOOGLE_ACCOUNT_TYPE)).setScopes(scopes).build(),
        ).await()
    }

    private fun AuthorizationResult.toPlay() = PlayAuthorization(
        accessToken = accessToken,
        account = toGoogleSignInAccount()?.let { it.account?.name ?: it.email },
        hasResolution = hasResolution(),
        consent = pendingIntent,
    )

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}

class PlayAuthorizer internal constructor(
    private val api: PlayAuthApi,
    /** Wall clock (unix ms); tests pin it. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Google's revoke endpoint for the access token, the browser way's, best-effort. */
    private val revokeToken: suspend (DriveTokenSet) -> Unit = { revokeDriveGrant(it) },
) : DriveAuthorizer {
    constructor(context: Context) : this(GmsPlayAuthApi(context))

    override val method = AuthMethod.PLAY

    override fun available() = api.available()

    /**
     * The cached token while it's good; else ask Play services again, without
     * UI. A grant that needs the user can't be shown from a background backup:
     * [FreshToken.NeedsReconnect] raises the reconnect notice instead.
     */
    override suspend fun fresh(stored: DriveTokenSet, rejected: Boolean): FreshToken {
        if (!rejected && now() < stored.expiresAt - 60_000) return FreshToken.Token(stored)
        // Drive refused it: drop it from Play's cache, or authorize hands it back.
        if (rejected) bestEffort { api.clearToken(stored.accessToken) }
        return try {
            val got = api.authorize(stored.account)
            val token = got.accessToken
            if (got.hasResolution || token == null) FreshToken.NeedsReconnect else FreshToken.Token(grant(token, got.account ?: stored.account))
        } catch (c: CancellationException) {
            throw c
        } catch (e: ApiException) {
            if (e.statusCode in TRANSIENT) FreshToken.Waiting(SyncFailure.Offline) else FreshToken.NeedsReconnect
        } catch (e: IOException) {
            FreshToken.Waiting(SyncFailure.Offline)
        }
    }

    /** Ask for drive.file; launch the consent screen when the user must act. */
    override suspend fun begin(host: SignInHost): Begin {
        val got = try {
            api.authorize(null)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            throw failure(e)
        }
        val token = got.accessToken
        return when {
            got.hasResolution -> {
                val consent = got.consent ?: throw SignInFailure(SignInFailure.Kind.UNSUPPORTED, "Google Play services gave no consent screen")
                if (!host.launchConsent(consent)) throw SignInFailure(SignInFailure.Kind.FAILED, "couldn’t open Google’s consent screen; open Crema and try again")
                Begin.Launched
            }
            token != null -> Begin.Granted(grant(token, got.account))
            else -> throw SignInFailure(SignInFailure.Kind.UNSUPPORTED, "Google Play services gave no access token")
        }
    }

    override suspend fun complete(result: SignInResult): DriveTokenSet {
        require(result is SignInResult.Consent) { "not a Play services result" }
        if (result.data == null) {
            if (result.resultCode == Activity.RESULT_CANCELED) throw SignInFailure(SignInFailure.Kind.CANCELLED, "cancelled")
            throw SignInFailure(SignInFailure.Kind.UNSUPPORTED, "Google Play services returned nothing")
        }
        val got = try {
            api.fromIntent(result.data)
        } catch (e: Exception) {
            throw failure(e)
        }
        val token = got.accessToken ?: throw SignInFailure(SignInFailure.Kind.UNSUPPORTED, "Google Play services gave no access token")
        return grant(token, got.account)
    }

    /**
     * Withdraw the grant, then drop the cached token; Google's revoke endpoint
     * on the access token too, as it takes those. Each step best-effort.
     */
    override suspend fun revoke(stored: DriveTokenSet) {
        stored.account?.let { account -> bestEffort { api.revokeAccess(account) } }
        bestEffort { api.clearToken(stored.accessToken) }
        bestEffort { revokeToken(stored) }
    }

    private fun grant(accessToken: String, account: String?) = DriveTokenSet(
        accessToken = accessToken,
        expiresAt = now() + PLAY_TOKEN_TTL_MS,
        method = AuthMethod.PLAY,
        account = account,
    )

    private suspend fun bestEffort(step: suspend () -> Unit) {
        try {
            step()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
        }
    }

    internal companion object {
        /** Statuses time can fix: no network, a timeout, an interrupted call. */
        val TRANSIENT = setOf(CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT, CommonStatusCodes.INTERRUPTED)

        /** The user backing out: `CANCELED`, and the older sign-in API's 12501. */
        val CANCELLED = setOf(CommonStatusCodes.CANCELED, 12501)

        /** What a failed interactive call means for [DriveSync]'s fallback (#336 §5). */
        fun failure(e: Exception): SignInFailure = when {
            e is ApiException && e.statusCode in CANCELLED -> SignInFailure(SignInFailure.Kind.CANCELLED, "cancelled", e)
            e is ApiException && e.statusCode in TRANSIENT -> SignInFailure(SignInFailure.Kind.OFFLINE, "no network", e)
            e is IOException -> SignInFailure(SignInFailure.Kind.OFFLINE, "no network", e)
            else -> SignInFailure(SignInFailure.Kind.UNSUPPORTED, "Google Play services: ${(e as? ApiException)?.statusCode ?: e.javaClass.simpleName}", e)
        }
    }
}
