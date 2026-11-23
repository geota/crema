package coffee.crema.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.ble.proxy.RelayKeyLoad
import coffee.crema.ble.proxy.RelayKeyStore
import coffee.crema.core.CremaBridge
import coffee.crema.core.RelayKeys
import coffee.crema.security.FakeKeystore
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

    /** The keystore every VM's relay key store uses: Robolectric has no
     *  AndroidKeyStore (installed by [build] and [relayStore]). */
    val keystore = FakeKeystore()

    /** A new factory per call: `AndroidViewModelFactory.getInstance` caches the
     *  first test's Application, whose files dir a later test doesn't use. */
    fun build(store: ViewModelStore): MainViewModel {
        RelayKeyStore.backendForTests = keystore
        // One restore per process: a test's last one never outlives it.
        coffee.crema.restore.RestoreEngine.resetForTest()
        return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app()))[MainViewModel::class.java]
    }

    /**
     * This device's relay store before the VM is built (the same file and
     * keystore the VM's [ProxyController] will load), with its key: a test pins
     * peers or the saved hub here.
     */
    fun relayStore(): Pair<RelayKeyStore, RelayKeys> {
        RelayKeyStore.backendForTests = keystore
        val store = RelayKeyStore(app().filesDir, RelayKeyStore.deviceBox(keystore))
        val keys = (store.load() as RelayKeyLoad.Ready).keys
        return store to keys
    }

    /** Pin the hub [hubKeys] at 127.0.0.1:[port] in [store] as this remote's saved hub (what pairing leaves). */
    fun pinHub(store: RelayKeyStore, hubKeys: RelayKeys, port: Int, name: String = "Kitchen tablet") {
        val id = hubKeys.deviceId()
        store.pinHub(coffee.crema.ble.proxy.RelayHubPin(id, RelayKeyStore.b64(hubKeys.publicKey()), name, "127.0.0.1", port, pinnedAtMs = 1))
        store.setSavedHub(id)
    }

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

    /**
     * Run the suspend call [block] to completion with the main looper running
     * meanwhile, and return its result (or rethrow its failure). For calls
     * that may wait on work posted to Main, such as anything behind
     * `LibraryController.awaitRows` (a backup, a restore's plan): a
     * `runBlocking` on the Robolectric main thread deadlocks there whenever
     * that work hasn't run yet (FLAKY.md, #417).
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun <T> onMain(what: String, timeoutMs: Long = 60_000, block: suspend () -> T): T {
        val job = kotlinx.coroutines.GlobalScope.async(
            kotlinx.coroutines.Dispatchers.Unconfined,
            start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED,
        ) { block() }
        idleUntil(what, timeoutMs) { job.isCompleted }
        return job.getCompleted()
    }

    /** [MainViewModel.buildBackupZipBytes], run with [onMain]. */
    fun backupBytes(vm: MainViewModel): ByteArray? = onMain("the backup") { vm.buildBackupZipBytes() }

    /**
     * The shot [id] whole, as a screen loads it (#310 P4a: a row holds no curve).
     * Runs the main looper while it waits, as the load may need it.
     */
    fun whole(vm: MainViewModel, id: String): coffee.crema.history.StoredShot {
        var out: Result<coffee.crema.history.StoredShot?>? = null
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { out = runCatching { vm.loadShot(id) } }
        idleUntil("shot $id to load") { out != null }
        job.cancel()
        return out!!.getOrThrow() ?: error("no shot $id in history")
    }

    /** [whole], or null when history has no such shot. */
    fun wholeOrNull(vm: MainViewModel, id: String): coffee.crema.history.StoredShot? {
        var out: Result<coffee.crema.history.StoredShot?>? = null
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { out = runCatching { vm.loadShot(id) } }
        idleUntil("shot $id to load") { out != null }
        job.cancel()
        return out!!.getOrThrow()
    }

    /** [MainViewModel.shotsJson], with the main looper running while it reads. */
    fun shotsJson(vm: MainViewModel, ids: List<String>? = null): String? {
        var out: Result<String?>? = null
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { out = runCatching { vm.shotsJson(ids) } }
        idleUntil("the export") { out != null }
        job.cancel()
        return out!!.getOrThrow()
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

    /** The VM's [CoreLane], found by type. */
    fun coreLane(vm: MainViewModel): CoreLane {
        val field = MainViewModel::class.java.declaredFields.singleOrNull { it.type == CoreLane::class.java }
            ?: error("MainViewModel no longer holds exactly one CoreLane field: update VmHarness.coreLane")
        field.isAccessible = true
        return field.get(vm) as CoreLane
    }

    /** The VM's [GuidedBrewController], found by type. */
    fun guidedBrew(vm: MainViewModel): GuidedBrewController {
        val field = MainViewModel::class.java.declaredFields.singleOrNull { it.type == GuidedBrewController::class.java }
            ?: error("MainViewModel no longer holds exactly one GuidedBrewController field: update VmHarness.guidedBrew")
        field.isAccessible = true
        return field.get(vm) as GuidedBrewController
    }

    /** The VM's [LibraryController], found by type. */
    /** The history's rows, newest first: the store's (#310 A3b). The UI state holds no list of them. */
    fun rows(vm: MainViewModel): List<coffee.crema.history.ShotRow> = library(vm).historyRows()

    /** Every id the VM's history store holds on its truth file: `history.db` on stage 2, `shots.db` on stage 1 (#482). */
    fun truthIds(vm: MainViewModel): Set<String> = library(vm).historyStoreForTest.truthIdsForTest()

    /** [key] of [id]'s row as the VM's history store holds it on its truth file, or null (#482). */
    fun truthField(vm: MainViewModel, id: String, key: String): String? =
        library(vm).historyStoreForTest.truthRowJsonForTest(id)?.let { coreJson.parseToJsonElement(it).jsonObject[key]?.jsonPrimitive?.content }

    fun library(vm: MainViewModel): LibraryController {
        val field = MainViewModel::class.java.declaredFields.singleOrNull { it.type == LibraryController::class.java }
            ?: error("MainViewModel no longer holds exactly one LibraryController field: update VmHarness.library")
        field.isAccessible = true
        return field.get(vm) as LibraryController
    }

    /** The VM's [MaintenanceController], found by type. */
    fun maintenance(vm: MainViewModel): MaintenanceController {
        val field = MainViewModel::class.java.declaredFields.singleOrNull { it.type == MaintenanceController::class.java }
            ?: error("MainViewModel no longer holds exactly one MaintenanceController field: update VmHarness.maintenance")
        field.isAccessible = true
        return field.get(vm) as MaintenanceController
    }

    /** The VM's [RemoteConfigController], found by type. */
    fun remoteConfig(vm: MainViewModel): RemoteConfigController {
        val field = MainViewModel::class.java.declaredFields.singleOrNull { it.type == RemoteConfigController::class.java }
            ?: error("MainViewModel no longer holds exactly one RemoteConfigController field: update VmHarness.remoteConfig")
        field.isAccessible = true
        return field.get(vm) as RemoteConfigController
    }

    /** The remote → standalone leave waiting on the T6 answer, or null. */
    fun pendingLeave(vm: MainViewModel): Any? =
        RemoteConfigController::class.java.getDeclaredField("pendingLeave").apply { isAccessible = true }.get(remoteConfig(vm))

    /** Drop the pending leave, as a switch that cancelled it would. */
    fun dropPendingLeave(vm: MainViewModel) {
        RemoteConfigController::class.java.getDeclaredField("pendingLeave").apply { isAccessible = true }.set(remoteConfig(vm), null)
    }

    /** Hand [raw] (a `CoreOutput`) to the VM's core lane the way the BLE
     *  managers do: it applies there, after whatever is already queued. */
    fun deliverCoreOutput(vm: MainViewModel, raw: String) = coreLane(vm).deliver(raw)

    /** Apply [raw] (a `CoreOutput`) on the calling thread, as a task already
     *  on the core lane would. */
    fun applyCoreOutput(vm: MainViewModel, raw: String) = coreLane(vm).applyJson(raw)

    /** Wait for every task already queued on the VM's single-threaded core
     *  lane (it runs FIFO, so a no-op queued now runs after them). */
    fun drainCoreLane(vm: MainViewModel) {
        val lane = coreLane(vm).dispatcher
        repeat(3) { kotlinx.coroutines.runBlocking(lane) {} }
    }

    /** Idle the main looper until the VM's async prefs load has hydrated
     *  the UI, so a later setter isn't overwritten by it. */
    fun awaitPrefsLoaded(vm: MainViewModel) {
        // The history loads apart from the prefs since #310 P3a; tests that read
        // both after this wait for both, as they loaded in one chain before.
        idleUntil("the prefs load") { prefsLoaded(vm) && vm.historyLoad.isCompleted }
    }

    /** Whether the VM's startup prefs load has hydrated the UI ([SettingsController.loaded]). */
    fun prefsLoaded(vm: MainViewModel): Boolean {
        val field = MainViewModel::class.java.declaredFields.singleOrNull { it.type == SettingsController::class.java }
            ?: error("MainViewModel no longer holds exactly one SettingsController field: update VmHarness.prefsLoaded")
        field.isAccessible = true
        return (field.get(vm) as SettingsController).loaded
    }

    /** Wait for the whole startup load (library, prefs, custom profiles, …):
     *  [awaitPrefsLoaded] returns before the custom profiles load, which would
     *  overwrite a profile a test saves in that window, and before the load's
     *  own push of the desired profile. */
    fun awaitStartupLoaded(vm: MainViewModel) {
        idleUntil("the startup load") { vm.startupLoad.isCompleted && vm.historyLoad.isCompleted && vm.ownStateLoads.all { it.isCompleted } }
    }

    /** The VM's own state: what it persists (the screens see [MainViewModel.ui]). */
    fun ownState(vm: MainViewModel): MainUiState = vm.ownState.value

    /**
     * Run [block] with the startup load (library, prefs, …) of every VM built
     * in it held until [block] completes `release`, so a test can act before
     * the prefs load: unheld, the load can finish inside the constructor.
     */
    fun holdingStartupLoad(block: (release: kotlinx.coroutines.CompletableDeferred<Unit>) -> Unit) {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        MainViewModel.holdStartupLoad = release
        try {
            block(release)
        } finally {
            MainViewModel.holdStartupLoad = null
            release.complete(Unit)
        }
    }

    /** The VM's event log, newest first. */
    fun log(vm: MainViewModel): List<String> = vm.liveStore.value.eventLog

    /**
     * Restore [backup] and wait until the restore has finished. It runs in
     * its own coroutine whose saves post back to Main, so one idle() can
     * return halfway and let the rest land after the test's next step (a
     * reset whose values the restore then puts back, say). Its last step
     * says "Restored …".
     */
    /**
     * Before a test's teardown: let [vms]' running restores finish, so none
     * resumes in a later test after its stores reset (the strict SaveFloor
     * would throw into that test's runTest, #532 CI). A restore a test holds
     * on purpose (a kill hook) is left after [timeoutMs]; its scope's
     * cancellation ends it.
     */
    fun settleRestores(vms: List<MainViewModel>, timeoutMs: Long = 10_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (vms.any { it.restoreRunning } && System.nanoTime() < deadline) {
            idle()
            Thread.sleep(5)
        }
        idle()
    }

    /** How many "Restored …" messages [vm] has shown: a confirmed run's last word. */
    fun restoredCount(vm: MainViewModel): Int = vm.ui.value.userMessages.count { it.startsWith("Restored ") }

    /**
     * Wait for [vm]'s confirmed restore to run to its end, past the rows a
     * test waits for: the singletons (maintenance, settings) save after them,
     * and a run left going lands its saves in a later test, whose strict
     * SaveFloor throws into that test's runTest (#530, FLAKY.md).
     */
    fun awaitRestored(vm: MainViewModel, before: Int) = try {
        idleUntil("the restore's end") { restoredCount(vm) > before }
    } catch (e: IllegalStateException) {
        throw IllegalStateException("${e.message}; said ${vm.ui.value.userMessages.takeLast(4)}", e)
    }

    fun restore(vm: MainViewModel, backup: ByteArray, mode: MainViewModel.RestoreMode = MainViewModel.RestoreMode.MERGE) {
        fun restored() = vm.ui.value.userMessages.count { it.startsWith("Restored ") }
        val before = restored()
        onMain("the restore's plan") { vm.restoreBackupBytes(backup, mode) }
        try {
            idleUntil("the restore") { restored() > before }
        } catch (e: IllegalStateException) {
            throw IllegalStateException("${e.message}; said ${vm.ui.value.userMessages.takeLast(3)}, report ${vm.ui.value.restoreReport}", e)
        }
    }
}
