package coffee.crema.ui

import androidx.lifecycle.ViewModelStore
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.newBean
import coffee.crema.ble.proxy.RelayHub
import coffee.crema.ble.proxy.TestHubServer
import coffee.crema.core.RelayKeys
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.ConfigSnapshot
import coffee.crema.ble.proxy.Peer
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A remote under test (spec §8): a real [MainViewModel] restored in the
 * remote role, paired with a real [RelayHub] behind a relay v4 socket (each
 * side has pinned the other's key), whose config snapshot the test sets
 * ([push]). [P] is the remote's own settings, written before the VM is built;
 * [S] is a hub snapshot whose machine and session values all differ from P.
 */
internal class RemoteFixture(private val store: ViewModelStore) {
    /** The hub's relay key: the same across a [restart]. */
    val hubKeys: RelayKeys = RelayKeys.generate()

    /** This remote's relay key (from its store, set by [start]). */
    lateinit var remoteKeys: RelayKeys
    @Volatile var hubConfig: String = ""

    /** What the hub was asked to run, in order. */
    val handled = CopyOnWriteArrayList<Pair<String, String>>()

    /** The edits the hub applied ([echoEdits]), in the order it applied them. */
    val echoed = CopyOnWriteArrayList<Pair<String, String>>()

    /** Verbs the hub refuses. */
    val refused: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    lateinit var vm: MainViewModel
    lateinit var endpoint: TestHubServer
    lateinit var hub: RelayHub

    /** The hub admits this remote view-only. */
    var viewOnly = false

    /** The hub applies each accepted relayed edit to its snapshot and pushes
     *  it before replying, as a real hub does (§7.3). */
    var echoEdits = false

    /** The hub holds its replies (an edit stays pending on the remote). */
    @Volatile var holdReplies = false

    /** The hub's current snapshot, when [echoEdits] is on. */
    @Volatile var current: ConfigSnapshot = S

    /** Runs each relayed control instead of the default handler (a real
     *  dispatcher, say). */
    @Volatile var dispatch: (suspend (method: String, args: String) -> Result<Unit>)? = null

    /** The hub holds the DE1 (its roster lists it). */
    var hubHoldsDe1 = true

    /** The NSD peers the remote sees, when the test drives them. */
    var peers: MutableStateFlow<List<Peer>>? = null

    /** Restore a remote with [own] as its settings and wait for the hub's Welcome. */
    fun start(own: AppPrefs = P, onBuilt: (MainViewModel) -> Unit = {}): MainViewModel {
        val dir = VmHarness.app().filesDir
        val (relayStore, keys) = VmHarness.relayStore()
        remoteKeys = keys
        File(dir, "beanLibrary.json").writeText(coreJson.encodeToString(BeanLibrary.serializer(), LIBRARY))
        serve()
        VmHarness.pinHub(relayStore, hubKeys, endpoint.port)
        writePrefs(own.copy(proxyRole = "secondary", proxyPrimaryHost = "127.0.0.1", proxyPrimaryPort = endpoint.port))
        vm = VmHarness.build(store)
        peers?.let { VmHarness.proxyController(vm).overridePeers(it) }
        onBuilt(vm)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        try {
            VmHarness.idleUntil("the hub's Welcome") { hub.remotes().isNotEmpty() && !vm.ui.value.mirrorReconnecting }
        } catch (e: IllegalStateException) {
            // Timed out under load in PR-6's validation: say where it stuck.
            val ps = VmHarness.proxyController(vm).state.value
            throw IllegalStateException(
                "${e.message}; hub remotes ${hub.remotes().size}, role ${ps.role}, reconnecting ${ps.mirrorReconnecting}, " +
                    "dials ${endpoint.dials}\nlog:\n${VmHarness.log(vm).take(20).joinToString("\n")}",
                e,
            )
        }
        // The rest of the startup load (custom profiles, maintenance, …) runs
        // after the prefs: a test step taken before it ends can be undone by it.
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    /** The hub pushes [snapshot] with its next `rev` (a v4 hub numbers every
     *  one); waits until the remote applied it. */
    fun push(snapshot: ConfigSnapshot = S) {
        // An explicit rev above the counter is kept (a test pinning the order).
        val numbered = snapshot.copy(rev = revs.updateAndGet { maxOf(it + 1, snapshot.rev) })
        hubConfig = coreJson.encodeToString(ConfigSnapshot.serializer(), numbered)
        hub.pushConfig(hubConfig)
        VmHarness.idleUntil("the snapshot") { VmHarness.ownState(vm).mirror?.snapshot == numbered }
    }

    /** The hub's snapshot counter: the rev of the last snapshot it pushed. */
    private val revs = java.util.concurrent.atomic.AtomicLong(0)

    fun kill() = runCatching { endpoint.kill() }

    /** The hub restarts: the old relay is gone and a new one listens on
     *  another port, with the same pairing, handler and snapshot source.
     *  The remote finds it through [peers]. */
    fun restart() {
        kill()
        serve()
        peers?.value = listOf(Peer("nsd-restarted", "Kitchen tablet", "primary", holdsDe1 = false, host = "127.0.0.1", port = endpoint.port))
    }

    /** Start a relay for this remote: [endpoint] and [hub]. */
    private fun serve() {
        val (e, h) = TestHubServer.relay(
            remote = remoteKeys,
            name = "Kitchen tablet",
            hubKeys = hubKeys,
            roster = { if (hubHoldsDe1) listOf(coffee.crema.ble.proxy.DeviceInfo("AA:BB", "DE1", "de1", "CONNECTED")) else emptyList() },
            configSource = { hubConfig },
            controlHandler = { method, args, _, _ ->
                handled += method to args
                while (holdReplies) kotlinx.coroutines.delay(10)
                val custom = dispatch
                when {
                    custom != null -> custom(method, args)
                    method in refused -> Result.failure(IllegalStateException("refused by the hub"))
                    else -> {
                        if (echoEdits) echo(method, args)
                        Result.success(Unit)
                    }
                }
            },
            canControl = !viewOnly,
        )
        endpoint = e
        hub = h
    }

    /** The hub forgets this remote: out of its pins, and its session gets an authenticated CLOSE(REVOKED). */
    fun revoke() {
        endpoint.trust.forget(remoteKeys.deviceId())
        hub.revoke(remoteKeys.deviceId())
    }

    /** Apply a relayed edit to [current] and push it (on the hub's thread,
     *  before the reply). One at a time, as a hub's main lane does, even
     *  when two sessions' handlers run on different threads. */
    @Synchronized
    private fun echo(method: String, args: String) {
        echoed += method to args
        current = when (method) {
            "setStopOnWeight" -> current.copy(stopOnWeight = args.toBoolean())
            "setAutoTare" -> current.copy(autoTare = args.toBoolean())
            "setQcGrind" -> current.copy(qcGrind = args.toFloat())
            "quickAdjustBrew" -> args.split(",").let { p ->
                current.copy(brewDoseG = p[0].toDouble(), brewYieldG = p[1].toDouble(), brewTempC = p[2].toDouble(), brewPreinfuseS = p.getOrNull(3)?.toDoubleOrNull())
            }
            else -> current
        }.let { it.copy(rev = revs.incrementAndGet()) }
        hubConfig = coreJson.encodeToString(ConfigSnapshot.serializer(), current)
        hub.pushConfig(hubConfig)
    }

    companion object {
        const val PROFILE_P = "019e5fb5-63b5-7333-a2ac-34b9f4f0e604"
        const val PROFILE_S = "019e5fb5-63b5-7333-a2ac-34c516215b2c"
        const val BEAN_P = "bean:p"
        const val BEAN_S = "bean:s"

        val LIBRARY = BeanLibrary(
            beans = listOf(
                newBean("P bag", null, null, null, 0L).copy(id = BEAN_P),
                newBean("S bag", null, null, null, 0L).copy(id = BEAN_S),
            ),
            roasters = emptyList(),
            activeBeanId = BEAN_P,
        )

        /** The remote's own settings. */
        val P = AppPrefs(
            themeMode = "light", weightUnit = "oz", tempUnit = "F",
            maxShotDurationS = 33f, autoTare = false, stopOnWeight = false, volumeStopWithScale = false,
            requireScale = false, steamEco = false, steamTwoTap = false, fanThresholdC = 50f, preFlush = false,
            steamPurge = false, idleSleepMin = 30, grinderModel = "P grinder",
            qcSteamTimeS = 20f, qcSteamFlowMlS = 1.0f, qcSteamTempC = 140f, qcHotWaterTempC = 70f,
            qcHotWaterVolumeMl = 100f, qcFlushTimeS = 3f, qcFlushTempC = 90f, qcGrind = 10f,
            qcHotWaterMode = coffee.crema.core.HotWaterMode.Volume, qcHotWaterTimeS = 30f,
            qcHotWaterTimeNoLimit = false, qcHotWaterRatio = 3f,
            activeProfileId = PROFILE_P, waterRefillPointMm = 5f,
        )

        /** A hub snapshot whose machine and session values all differ from [P]. */
        val S = ConfigSnapshot(
            activeProfileId = PROFILE_S, activeBeanId = BEAN_S, activeBeanSummary = "S bag",
            stopOnWeight = true, autoTare = true, maxShotDurationS = 44f, grinderModel = "S grinder",
            qcSteamTimeS = 30f, qcSteamFlowMlS = 1.6f, qcSteamTempC = 150f, qcHotWaterTempC = 85f,
            qcHotWaterVolumeMl = 200f, qcFlushTimeS = 6f, qcFlushTempC = 93f, qcGrind = 15f,
            qcHotWaterMode = coffee.crema.core.HotWaterMode.Time, qcHotWaterTimeS = 45f,
            qcHotWaterTimeNoLimit = true, qcHotWaterRatio = 2.5f,
            brewDoseG = 20.0, brewYieldG = 40.0, brewTempC = 94.0, brewPreinfuseS = 6.0,
            primaryName = "Kitchen tablet",
            rev = 0,
            steamEco = true, steamTwoTap = true, fanThresholdC = 58f, waterRefillPointMm = 8f,
            heaterVoltage = 230, flowMultiplier = 1.1f, lineFrequencyHz = 50f, lineFrequencyOverride = 50f,
            cupWarmerTempC = 60, volumeStopWithScale = true, requireScale = true, preFlush = true, steamPurge = true,
            idleSleepMin = 60,
        )

        fun writePrefs(prefs: AppPrefs) {
            File(VmHarness.app().filesDir, "prefs.json").writeText(coreJson.encodeToString(AppPrefs.serializer(), prefs))
        }

        /** What `prefs.json` holds now, read without touching it (a store load
         *  would recover a file caught mid-save, racing the VM's writer). Null
         *  fields while a save is renaming the file read as the last value. */
        fun savedPrefs(): AppPrefs {
            val file = File(VmHarness.app().filesDir, "prefs.json")
            repeat(50) {
                runCatching { return coffee.crema.settings.decodePrefs(coreJson, file.readText()) }
                Thread.sleep(5)
            }
            return coffee.crema.settings.decodePrefs(coreJson, file.readText())
        }

        /** The bean library's active bean on disk. */
        fun savedActiveBean(): String? =
            coreJson.decodeFromString(BeanLibrary.serializer(), File(VmHarness.app().filesDir, "beanLibrary.json").readText()).activeBeanId
    }
}

/** The machine and session values of [p] that are in [AppPrefs], for comparing. */
internal fun abOf(p: AppPrefs): Map<String, Any?> = mapOf(
    "maxShotDurationS" to p.maxShotDurationS, "autoTare" to p.autoTare, "stopOnWeight" to p.stopOnWeight,
    "volumeStopWithScale" to p.volumeStopWithScale, "requireScale" to p.requireScale, "steamEco" to p.steamEco,
    "steamTwoTap" to p.steamTwoTap, "fanThresholdC" to p.fanThresholdC, "preFlush" to p.preFlush, "steamPurge" to p.steamPurge,
    "idleSleepMin" to p.idleSleepMin, "grinderModel" to p.grinderModel, "qcSteamTimeS" to p.qcSteamTimeS,
    "qcSteamFlowMlS" to p.qcSteamFlowMlS, "qcSteamTempC" to p.qcSteamTempC, "qcHotWaterTempC" to p.qcHotWaterTempC,
    "qcHotWaterVolumeMl" to p.qcHotWaterVolumeMl, "qcFlushTimeS" to p.qcFlushTimeS, "qcFlushTempC" to p.qcFlushTempC,
    "qcGrind" to p.qcGrind, "activeProfileId" to p.activeProfileId, "waterRefillPointMm" to p.waterRefillPointMm,
    "qcHotWaterMode" to p.qcHotWaterMode, "qcHotWaterTimeS" to p.qcHotWaterTimeS,
    "qcHotWaterTimeNoLimit" to p.qcHotWaterTimeNoLimit, "qcHotWaterRatio" to p.qcHotWaterRatio,
)
