package coffee.crema.ui

import androidx.lifecycle.ViewModelStore
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.newBean
import coffee.crema.ble.proxy.PairingCrypto
import coffee.crema.ble.proxy.RelayHub
import coffee.crema.ble.proxy.TestHubServer
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.ConfigSnapshot
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A remote under test (spec §8): a real [MainViewModel] restored in the
 * remote role, paired with and pinned to a real [RelayHub] behind a socket,
 * whose config snapshot the test sets ([push]). [P] is the remote's own
 * settings, written before the VM is built; [S] is a hub snapshot whose
 * machine and session values all differ from P.
 */
internal class RemoteFixture(private val store: ViewModelStore) {
    private val secret = PairingCrypto.newSecret()
    @Volatile var hubConfig: String = ""

    /** What the hub was asked to run, in order. */
    val handled = CopyOnWriteArrayList<Pair<String, String>>()

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

    /** Restore a remote with [own] as its settings and wait for the hub's Welcome. */
    fun start(own: AppPrefs = P, onBuilt: (MainViewModel) -> Unit = {}): MainViewModel {
        val dir = VmHarness.app().filesDir
        File(dir, "proxy-pairing.json").writeText(
            """{"hostId":"this-device","held":{"host-1":"$secret"},"pinnedHost":"host-1"}""",
        )
        File(dir, "beanLibrary.json").writeText(coreJson.encodeToString(BeanLibrary.serializer(), LIBRARY))
        val (e, h) = TestHubServer.relay(
            "host-1", "Kitchen tablet", secret,
            clientId = { VmHarness.proxyController(vm).deviceId },
            configSource = { hubConfig },
            controlHandler = { method, args, _, _ ->
                handled += method to args
                while (holdReplies) kotlinx.coroutines.delay(10)
                if (method in refused) {
                    Result.failure(IllegalStateException("refused by the hub"))
                } else {
                    if (echoEdits) echo(method, args)
                    Result.success(Unit)
                }
            },
            canControl = !viewOnly,
        )
        endpoint = e
        hub = h
        writePrefs(own.copy(proxyRole = "secondary", proxyPrimaryHost = "127.0.0.1", proxyPrimaryPort = e.port))
        vm = VmHarness.build(store)
        onBuilt(vm)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        VmHarness.idleUntil("the hub's Welcome") { hub.remotes().isNotEmpty() && !vm.ui.value.mirrorReconnecting }
        return vm
    }

    /** The hub pushes [snapshot]; waits until the remote applied it. */
    fun push(snapshot: ConfigSnapshot = S) {
        hubConfig = coreJson.encodeToString(ConfigSnapshot.serializer(), snapshot)
        hub.pushConfig(hubConfig)
        VmHarness.idleUntil("the snapshot") { VmHarness.ownState(vm).mirror?.snapshot == snapshot }
    }

    fun kill() = runCatching { endpoint.kill() }

    /** The hub forgets this remote: it gets a Denied (revoked). */
    fun revoke() = hub.revoke(VmHarness.proxyController(vm).deviceId)

    /** Apply a relayed edit to [current] and push it (on the hub's thread, before the reply). */
    private fun echo(method: String, args: String) {
        current = when (method) {
            "setStopOnWeight" -> current.copy(stopOnWeight = args.toBoolean())
            "setAutoTare" -> current.copy(autoTare = args.toBoolean())
            "setQcGrind" -> current.copy(qcGrind = args.toFloat())
            "quickAdjustBrew" -> args.split(",").let { p ->
                current.copy(brewDoseG = p[0].toDouble(), brewYieldG = p[1].toDouble(), brewTempC = p[2].toDouble(), brewPreinfuseS = p.getOrNull(3)?.toDoubleOrNull())
            }
            else -> current
        }.let { it.copy(rev = (it.rev ?: 0) + 1) }
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
            activeProfileId = PROFILE_P, waterRefillPointMm = 5f,
        )

        /** A hub snapshot whose machine and session values all differ from [P]. */
        val S = ConfigSnapshot(
            activeProfileId = PROFILE_S, activeBeanId = BEAN_S, activeBeanSummary = "S bag",
            stopOnWeight = true, autoTare = true, maxShotDurationS = 44f, grinderModel = "S grinder",
            weightUnit = "g", tempUnit = "C",
            qcSteamTimeS = 30f, qcSteamFlowMlS = 1.6f, qcSteamTempC = 150f, qcHotWaterTempC = 85f,
            qcHotWaterVolumeMl = 200f, qcFlushTimeS = 6f, qcFlushTempC = 93f, qcGrind = 15f,
            brewDoseG = 20.0, brewYieldG = 40.0, brewTempC = 94.0, brewPreinfuseS = 6.0,
            primaryName = "Kitchen tablet",
            settingsSchema = 1,
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
)
