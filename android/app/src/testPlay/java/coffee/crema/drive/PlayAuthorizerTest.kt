package coffee.crema.drive

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.SyncFailure
import coffee.crema.core.SyncFailureUnavailableInner
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #336 §4 and §8: [PlayAuthorizer] over a fake [PlayAuthApi], each result and
 * exception mapped to what [DriveSync] acts on. Robolectric for a real
 * PendingIntent.
 */
@RunWith(RobolectricTestRunner::class)
class PlayAuthorizerTest {
    private val clock = 2_000_000_000_000L
    private val calls = CopyOnWriteArrayList<String>()

    private val consent: PendingIntent = PendingIntent.getActivity(
        ApplicationProvider.getApplicationContext(), 0, Intent(), PendingIntent.FLAG_IMMUTABLE,
    )

    private inner class FakeApi(
        var authorizes: () -> PlayAuthorization = { PlayAuthorization("new", "a@example.com", hasResolution = false) },
        var fromIntent: () -> PlayAuthorization = { PlayAuthorization("consented", "a@example.com", hasResolution = false) },
    ) : PlayAuthApi {
        override fun available() = true
        override suspend fun authorize(account: String?): PlayAuthorization {
            calls += "authorize $account"
            return authorizes()
        }
        override fun fromIntent(data: Intent?): PlayAuthorization {
            calls += "fromIntent"
            return fromIntent()
        }
        override suspend fun clearToken(accessToken: String) {
            calls += "clearToken $accessToken"
        }
        override suspend fun revokeAccess(account: String) {
            calls += "revokeAccess $account"
            throw ApiException(Status(CommonStatusCodes.NETWORK_ERROR))
        }
    }

    private fun way(api: PlayAuthApi = FakeApi()) =
        PlayAuthorizer(api, now = { clock }, revokeToken = { calls += "revoke endpoint ${it.accessToken}" })

    private fun stored(expiresAt: Long = clock - 1) =
        DriveTokenSet(accessToken = "old", expiresAt = expiresAt, method = AuthMethod.PLAY, account = "a@example.com")

    private fun thrown(code: Int): () -> Nothing = { throw ApiException(Status(code)) }

    private val host = object : SignInHost {
        val launched = CopyOnWriteArrayList<PendingIntent>()
        var canLaunch = true
        override fun openUrl(url: String) = fail("no browser here")
        override fun launchConsent(consent: PendingIntent): Boolean {
            launched += consent
            return canLaunch
        }
    }

    private fun failureOf(block: suspend () -> Unit): SignInFailure.Kind = runBlocking {
        try {
            block()
            fail("expected a SignInFailure")
            error("unreachable")
        } catch (e: SignInFailure) {
            e.kind
        }
    }

    @Test
    fun `a token that's still good is used as it stands`() = runBlocking {
        val held = stored(expiresAt = clock + 10 * 60_000)
        assertEquals(FreshToken.Token(held), way().fresh(held, rejected = false))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `an expiring token is asked for again on the same account, for 50 minutes`() = runBlocking {
        val got = way().fresh(stored(), rejected = false) as FreshToken.Token
        assertEquals(listOf("authorize a@example.com"), calls)
        assertEquals("new", got.set.accessToken)
        assertEquals(AuthMethod.PLAY, got.set.method)
        assertEquals("a@example.com", got.set.account)
        assertNull(got.set.refreshToken)
        assertEquals(clock + 50 * 60_000, got.set.expiresAt)
    }

    @Test
    fun `a token Drive refused is cleared from Play's cache first`() = runBlocking {
        way().fresh(stored(expiresAt = Long.MAX_VALUE), rejected = true)
        assertEquals(listOf("clearToken old", "authorize a@example.com"), calls)
    }

    @Test
    fun `a grant that needs the user, or gives no token, asks to reconnect`() = runBlocking {
        val api = FakeApi(authorizes = { PlayAuthorization(null, null, hasResolution = true, consent = consent) })
        assertEquals(FreshToken.NeedsReconnect, way(api).fresh(stored(), rejected = false))
        api.authorizes = { PlayAuthorization(null, null, hasResolution = false) }
        assertEquals(FreshToken.NeedsReconnect, way(api).fresh(stored(), rejected = false))
    }

    @Test
    fun `no network, a timeout or an interruption waits, and any other failure asks to reconnect`() = runBlocking {
        for (code in listOf(CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT, CommonStatusCodes.INTERRUPTED)) {
            assertEquals("$code", FreshToken.Waiting(SyncFailure.Offline), way(FakeApi(authorizes = thrown(code))).fresh(stored(), false))
        }
        for (code in listOf(CommonStatusCodes.INTERNAL_ERROR, CommonStatusCodes.API_NOT_CONNECTED)) {
            assertEquals(
                "$code: Play services busy",
                FreshToken.Waiting(SyncFailure.Unavailable(SyncFailureUnavailableInner(0u))),
                way(FakeApi(authorizes = thrown(code))).fresh(stored(), false),
            )
        }
        for (code in listOf(CommonStatusCodes.SIGN_IN_REQUIRED, CommonStatusCodes.DEVELOPER_ERROR)) {
            assertEquals("$code", FreshToken.NeedsReconnect, way(FakeApi(authorizes = thrown(code))).fresh(stored(), false))
        }
    }

    @Test
    fun `sign-in with the grant standing is granted without UI`() = runBlocking {
        val begun = way().begin(host) as Begin.Granted
        assertEquals("new", begun.tokens.accessToken)
        assertEquals("a@example.com", begun.tokens.account)
        assertTrue(host.launched.isEmpty())
    }

    @Test
    fun `sign-in that needs consent launches its screen`() = runBlocking {
        val api = FakeApi(authorizes = { PlayAuthorization(null, null, hasResolution = true, consent = consent) })
        assertSame(Begin.Launched, way(api).begin(host))
        assertEquals(listOf(consent), host.launched)
    }

    @Test
    fun `how a failed sign-in is told apart`() {
        val needsConsent = FakeApi(authorizes = { PlayAuthorization(null, null, hasResolution = true, consent = null) })
        assertEquals(SignInFailure.Kind.UNSUPPORTED, failureOf { way(needsConsent).begin(host) })
        assertEquals(SignInFailure.Kind.UNSUPPORTED, failureOf { way(FakeApi(authorizes = { PlayAuthorization(null, null, false) })).begin(host) })
        assertEquals(SignInFailure.Kind.CANCELLED, failureOf { way(FakeApi(authorizes = thrown(CommonStatusCodes.CANCELED))).begin(host) })
        assertEquals(SignInFailure.Kind.CANCELLED, failureOf { way(FakeApi(authorizes = thrown(12501))).begin(host) })
        assertEquals(SignInFailure.Kind.OFFLINE, failureOf { way(FakeApi(authorizes = thrown(CommonStatusCodes.NETWORK_ERROR))).begin(host) })
        assertEquals(SignInFailure.Kind.UNSUPPORTED, failureOf { way(FakeApi(authorizes = thrown(CommonStatusCodes.DEVELOPER_ERROR))).begin(host) })
        // Play services updating: said, never a switch to the browser.
        assertEquals(SignInFailure.Kind.FAILED, failureOf { way(FakeApi(authorizes = thrown(CommonStatusCodes.INTERNAL_ERROR))).begin(host) })
        assertEquals(SignInFailure.Kind.FAILED, failureOf { way(FakeApi(authorizes = thrown(CommonStatusCodes.API_NOT_CONNECTED))).begin(host) })
        assertEquals(SignInFailure.Kind.UNSUPPORTED, failureOf { way(FakeApi(authorizes = { throw SecurityException("unknown caller") })).begin(host) })

        host.canLaunch = false
        val launchable = FakeApi(authorizes = { PlayAuthorization(null, null, hasResolution = true, consent = consent) })
        assertEquals(SignInFailure.Kind.FAILED, failureOf { way(launchable).begin(host) })
    }

    @Test
    fun `a consent screen's result`() = runBlocking {
        val got = way().complete(SignInResult.Consent(Activity.RESULT_OK, Intent()))
        assertEquals("consented", got.accessToken)
        assertEquals(AuthMethod.PLAY, got.method)

        assertEquals(SignInFailure.Kind.CANCELLED, failureOf { way().complete(SignInResult.Consent(Activity.RESULT_CANCELED, null)) })
        assertEquals(
            SignInFailure.Kind.CANCELLED,
            failureOf { way(FakeApi(fromIntent = thrown(CommonStatusCodes.CANCELED))).complete(SignInResult.Consent(Activity.RESULT_CANCELED, Intent())) },
        )
        assertEquals(
            SignInFailure.Kind.UNSUPPORTED,
            failureOf { way(FakeApi(fromIntent = { PlayAuthorization(null, null, false) })).complete(SignInResult.Consent(Activity.RESULT_OK, Intent())) },
        )
    }

    @Test
    fun `revoke withdraws the grant, clears the token and revokes it at Google, each best-effort`() = runBlocking {
        way().revoke(stored())
        assertEquals(listOf("revokeAccess a@example.com", "clearToken old", "revoke endpoint old"), calls)
    }

    @Test
    fun `revoke without the account revokes a live token at Google`() = runBlocking {
        way(FakeApi(authorizes = { PlayAuthorization("live", null, hasResolution = false) })).revoke(stored().copy(account = null))
        assertEquals(
            listOf("authorize null", "revoke endpoint live", "clearToken live", "clearToken old", "revoke endpoint old"),
            calls,
        )
    }

    @Test
    fun `revoke without the account and no live token still clears and revokes the stored one`() = runBlocking {
        way(FakeApi(authorizes = { PlayAuthorization(null, null, hasResolution = true, consent = consent) })).revoke(stored().copy(account = null))
        assertEquals(listOf("authorize null", "clearToken old", "revoke endpoint old"), calls)
    }
}
