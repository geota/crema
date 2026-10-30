package coffee.crema.ble.proxy

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.RelayKeys
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
 * The hub serves only once its prefs are loaded (relay v4 keeps the pins in
 * relay-v4.json, loaded at construction, but the relay still waits for the
 * prefs): the remote meets a closed port, redials, resumes and is admitted,
 * with no refusal. A real [coffee.crema.ui.MainViewModel] as the hub, a raw
 * remote over a real socket.
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
        val port = ServerSocket(0).use { it.localPort }
        // The hub's last run: Hub mode, the relay on `port`, the remote pinned.
        val (hubStore, hubKeys) = VmHarness.relayStore()
        val remote = RelayKeys.generate()
        hubStore.pinRemote(RelayRemotePin(remote.deviceId(), RelayKeyStore.b64(remote.publicKey()), "Phone", SCOPE_CONTROL, pairedAtMs = 1))
        File(files, "relayPort").writeText("$port")
        File(files, "prefs.json").writeText("""{"proxyRole":"primary"}""")
        VmHarness.build(store) // its startup load is held: the prefs haven't loaded

        val refusals = CopyOnWriteArrayList<String>()
        val welcomed = AtomicBoolean(false)
        val link = ReconnectingClientLink("ws://127.0.0.1:$port${LanRelayServer.PATH}", remoteScope, remote, hubKeys.publicKey())
        ProxyTransport(
            link, remoteScope,
            onRefused = { reason, _, _ -> refusals += reason },
            onWelcome = { _, _ -> welcomed.set(true) },
        )
        Thread.sleep(1_500)
        assertEquals(emptyList(), refusals.toList(), "no refusal before the prefs load")
        assertFalse(welcomed.get())

        release.complete(Unit)
        VmHarness.idleUntil("the remote's welcome") { welcomed.get() }
        assertEquals(emptyList(), refusals.toList(), "no refusal at any point")
    }
}
