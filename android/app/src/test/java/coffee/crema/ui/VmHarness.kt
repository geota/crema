package coffee.crema.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.CremaBridge
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * Building a real [MainViewModel] under Robolectric (host-built core), the
 * way the activity does, in a chosen persisted proxy role. Only public VM API
 * is used, plus [proxyController], so the MainViewModel breakup can move code
 * between files of this package without breaking the tests built on this.
 */
internal object VmHarness {

    fun app(): Application = ApplicationProvider.getApplicationContext()

    /** Persist the proxy role the VM reads once at construction (`prefs.json`,
     *  read by ProxyController before the async prefs load). */
    fun persistRole(role: String, host: String = "", port: Int = 0) {
        File(app().filesDir, "prefs.json").writeText(
            """{"proxyRole":"$role","proxyPrimaryHost":"$host","proxyPrimaryPort":$port}""",
        )
    }

    /** A new factory per call: `AndroidViewModelFactory.getInstance` caches the
     *  first test's Application, whose files dir a later test doesn't use. */
    fun build(store: ViewModelStore): MainViewModel =
        ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app()))[MainViewModel::class.java]

    /** Run everything due on the main looper (no clock advance). */
    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Advance the main looper's virtual clock by [ms], running what falls due. */
    fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /**
     * Idle the main looper until [done] holds: for work that crosses a real
     * socket, whose threads post back to Main. A bounded poll on a condition,
     * never a fixed wait behind an assertion. The virtual clock does not move,
     * so no request timeout can fire while this waits.
     */
    fun idleUntil(what: String, timeoutMs: Long = 10_000, done: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            idle()
            if (done()) return
            check(System.nanoTime() < deadline) { "Timed out after ${timeoutMs}ms waiting for $what" }
            Thread.sleep(5)
        }
    }

    /** The VM's [ProxyController], found by type rather than by field name. */
    fun proxyController(vm: MainViewModel): ProxyController {
        val field = MainViewModel::class.java.declaredFields.singleOrNull { it.type == ProxyController::class.java }
            ?: error("MainViewModel no longer holds exactly one ProxyController field: update VmHarness.proxyController")
        field.isAccessible = true
        return field.get(vm) as ProxyController
    }

    /** The VM's [CremaBridge] (the host-built core), found by type. */
    fun bridge(vm: MainViewModel): CremaBridge {
        val field = MainViewModel::class.java.declaredFields.singleOrNull { it.type == CremaBridge::class.java }
            ?: error("MainViewModel no longer holds exactly one CremaBridge field: update VmHarness.bridge")
        field.isAccessible = true
        return field.get(vm) as CremaBridge
    }

    /** Idle the main looper until the VM's async prefs load has hydrated
     *  the UI, so a later setter isn't overwritten by it. */
    fun awaitPrefsLoaded(vm: MainViewModel) {
        val field = runCatching { MainViewModel::class.java.getDeclaredField("prefsLoaded") }.getOrNull()
            ?: error("MainViewModel no longer has a prefsLoaded flag: update VmHarness.awaitPrefsLoaded")
        field.isAccessible = true
        idleUntil("the prefs load") { field.getBoolean(vm) }
    }

    /** The VM's own state: what it persists (the screens see [MainViewModel.ui]). */
    fun ownState(vm: MainViewModel): MainUiState = vm.ownState.value

    /** The VM's event log, newest first. */
    fun log(vm: MainViewModel): List<String> = vm.ui.value.eventLog

    /**
     * Restore [backup] and wait until the restore has finished. It runs in
     * its own coroutine whose saves post back to Main, so one idle() can
     * return halfway and let the rest land after the test's next step (a
     * reset whose values the restore then puts back, say). Its last step
     * says "Restored …".
     */
    fun restore(vm: MainViewModel, backup: ByteArray, mode: MainViewModel.RestoreMode = MainViewModel.RestoreMode.MERGE) {
        fun restored() = vm.ui.value.userMessages.count { it.startsWith("Restored ") }
        val before = restored()
        kotlinx.coroutines.runBlocking { vm.restoreBackupBytes(backup, mode) }
        idleUntil("the restore") { restored() > before }
    }
}
