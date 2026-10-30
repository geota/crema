package coffee.crema.ble.proxy

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ui.VmHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * P2a validation: a hub restarts with a paired remote dialing it at once.
 * Its paired devices come from the prefs, which load after the hub role
 * starts, so a relay serving earlier would turn the remote away. The hub
 * serves only once its prefs are loaded: the remote meets a closed port,
 * redials, and is admitted, with no refusal. A real [coffee.crema.ui.MainViewModel]
 * as the hub, a raw remote over a real socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HubLoadingAdmitsTest {

    private val store = ViewModelStore()
    private val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        remoteScope.cancel()
        store.clear()
        VmHarness.idle()
    }

    @Test
    fun `a restarting hub admits a paired remote that dials at once`() = VmHarness.holdingStartupLoad { release ->
        val files = VmHarness.app().filesDir
        val secret = PairingCrypto.newSecret()
        val port = ServerSocket(0).use { it.localPort }
        // The hub's last run: Hub mode, the relay on `port`, phone-1 paired
        // (plaintext secret: Robolectric has no keystore, and the store opens
        // a pre-keystore value).
        File(files, "relayPort").writeText("$port")
        File(files, "proxy-pairing.json").writeText("""{"hostId":"host-1","issued":{"phone-1":"$secret"}}""")
        File(files, "prefs.json").writeText(
            """{"proxyRole":"primary","pairedDevices":[{"id":"phone-1","name":"Phone","canControl":true}]}""",
        )
        VmHarness.build(store) // its startup load is held: the prefs haven't loaded

        val refusals = CopyOnWriteArrayList<String>()
        val welcomed = AtomicBoolean(false)
        val link = ReconnectingClientLink("ws://127.0.0.1:$port${LanRelayServer.PATH}", remoteScope)
        ProxyTransport(
            link, remoteScope, "phone-1", "Phone",
            pairingSecretFor = { secret }, allowRepair = false,
            onRefused = { reason, _, _ -> refusals += reason },
            onWelcome = { _, _ -> welcomed.set(true) },
        )
        Thread.sleep(1_500)
        assertEquals(emptyList(), refusals.toList(), "no Denied before the prefs load")
        assertFalse(welcomed.get())

        release.complete(Unit)
        VmHarness.idleUntil("the remote's welcome") { welcomed.get() }
        assertEquals(emptyList(), refusals.toList(), "no refusal at any point")
    }
}
