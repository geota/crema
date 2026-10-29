package coffee.crema.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import coffee.crema.ble.proxy.Frame
import coffee.crema.ble.proxy.FrameCodec
import coffee.crema.ble.proxy.MockWsServerLink
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Construct-and-clear smoke test for MainViewModel (cohort review
 * 2026-10-25, Android P3-2). The July 2026 launch crash was an NPE inside
 * `MainViewModel.<init>`: a property declared below an `init` block was
 * still null while that block ran. No test built the VM, so nothing caught
 * it. This one builds it the way the activity does (through a
 * ViewModelStore), drains the main looper so the init coroutines run, then
 * clears it, which also runs the sleep-on-quit teardown path.
 *
 * Runs against the host build of the Rust core (see cargoBuildHostCore in
 * app/build.gradle.kts). A plain Application keeps CremaApplication's
 * crash-reporter hooks out of the test JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MainViewModelSmokeTest {

    private fun build(store: ViewModelStore): MainViewModel = VmHarness.build(store)

    @Test
    fun constructsCollectsAndClears() {
        val store = ViewModelStore()
        val vm = build(store)
        shadowOf(Looper.getMainLooper()).idle()

        // The published state is live, and nothing is connected yet.
        val ui = vm.ui.value
        assertTrue(ui.profiles.isNotEmpty(), "built-in profiles load during construction")
        assertEquals(false, ui.saverVisible)

        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ── Each persisted proxy role (cohort review 2026-10-29, 07 H6) ──────────
    // ProxyController builds the role's transport during its own field init
    // (buildInitialDelegate), the path behind two launch-dead APKs (review #36,
    // 4eeb92f2). These build the VM in each role read from prefs.json.

    @Test
    fun constructsAndClearsInThePersistedNormalRole() {
        VmHarness.persistRole("normal")
        val store = ViewModelStore()
        val vm = build(store)
        VmHarness.idle()

        assertEquals("normal", vm.ui.value.proxyRole)
        assertEquals("normal", VmHarness.proxyController(vm).state.value.role)
        assertNull(VmHarness.proxyController(vm).relayHub, "a normal device runs no relay")

        store.clear()
        VmHarness.idle()
    }

    @Test
    fun constructsAndClearsInThePersistedPrimaryRole() {
        VmHarness.persistRole("primary")
        val store = ViewModelStore()
        val vm = build(store)
        val controller = VmHarness.proxyController(vm)
        assertEquals("primary", controller.state.value.role)
        assertNotNull(controller.relayHub, "the primary's relay is built during construction")

        // The relay server binds on a background thread; its port lands in the log.
        val listening = Regex("LAN relay listening on :(\\d+)")
        VmHarness.idleUntil("the LAN relay to listen") { VmHarness.log(vm).any { listening.containsMatchIn(it) } }
        val port = VmHarness.log(vm).firstNotNullOf { listening.find(it) }.groupValues[1].toInt()
        // The UI's copy of the role arrives with the async prefs load.
        VmHarness.idleUntil("the prefs load to reach the UI") { vm.ui.value.proxyRole == "primary" }

        store.clear()
        VmHarness.idle()
        // Clearing the VM stops the relay: its port can be bound again.
        ServerSocket(port, 50, InetAddress.getByName("0.0.0.0")).close()
    }

    @Test
    fun constructsAndClearsInThePersistedSecondaryRole() {
        // A primary that upgrades the socket and then stays silent: the
        // secondary's session goes live and sends its Hello, and nothing
        // completes the handshake, so the role holds until the VM is cleared.
        val primary = MockWsServerLink()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().webSocketUpgrade(primary).build())
            server.start(InetAddress.getLoopbackAddress(), 0)
            VmHarness.persistRole("secondary", "127.0.0.1", server.port)
            val store = ViewModelStore()
            val vm = build(store)
            val controller = VmHarness.proxyController(vm)
            assertEquals("secondary", controller.state.value.role)
            assertNull(controller.relayHub, "a secondary runs no relay")

            VmHarness.idleUntil("the secondary's Hello") { primary.receivedText.isNotEmpty() }
            assertIs<Frame.Hello>(FrameCodec.decode(primary.receivedText.first()))
            VmHarness.idleUntil("the prefs load to reach the UI") { vm.ui.value.proxyRole == "secondary" }

            store.clear()
            // Clearing the VM disposes the mirror link: its socket closes.
            VmHarness.idleUntil("the secondary's socket to close on clear") { primary.gone.isCompleted }
        }
    }

    @Test
    fun aSecondViewModelInTheSameProcessAlsoConstructsAndClears() {
        // Activity finish + relaunch without process death: the process-wide
        // singletons (QuitSleep, the recorder, ProcessScope) must tolerate a
        // second generation.
        repeat(2) {
            val store = ViewModelStore()
            build(store)
            shadowOf(Looper.getMainLooper()).idle()
            store.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
