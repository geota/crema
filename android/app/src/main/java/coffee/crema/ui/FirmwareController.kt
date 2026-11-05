package coffee.crema.ui

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import coffee.crema.ble.De1BleManager
import coffee.crema.core.CremaBridge
import coffee.crema.core.MmrRegister
import coffee.crema.core.WriteTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Firmware updates, alpha (MainViewModel breakup step 8): the flash driver's
 * scope, the DE1 link surface it writes through, the Settings flow
 * ([update]) and whether a flash holds the link ([holds]), which keeps the
 * reconnect side effects off the machine. The connect-time work a flash held
 * off runs in the VM when it lets go ([afterRelease]).
 */
internal class FirmwareController(
    private val app: Context,
    private val json: Json,
    private val bridge: CremaBridge,
    private val ble: De1BleManager,
    private val scope: CoroutineScope,
    private val uiState: () -> MainUiState,
    private val appendLog: (String) -> Unit,
    /** The keep-alive service's flash hold. */
    private val setFlashActive: (Boolean) -> Unit,
    /** The flash let go of the link: run the connect-time work it held off. */
    private val afterRelease: () -> Unit,
) {
    // The core's flash state machine (`de1_app::firmware_flash`) driven over
    // the DE1 link by its FirmwareFlashDriver; [update] is the Settings flow. The
    // driver runs off Main (29k blocking FFI round trips per flash) in a child
    // of the VM's scope; the foreground service + screen-on keep it alive.

    private val firmwareScope = kotlinx.coroutines.CoroutineScope(
        scope.coroutineContext +
            kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job]) +
            Dispatchers.Default +
            // A throw in the driver must reach the log, not kill the process
            // mid-flash (audit L9); the core keeps the flash and its lock.
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> appendLog("Firmware: driver error: ${e.message}") },
    )

    private val firmwareCore = coffee.crema.firmware.BridgeFlashCore(bridge)

    /** The DE1 link surface for the flash: its own writes pass the exclusive
     *  gate; the gate waits for ordinary writes already on the air (audit M1). */
    private val firmwareIo = object : coffee.crema.firmware.FlashIoPort {
        override suspend fun write(target: WriteTarget, data: ByteArray) = ble.firmwareWrite(target, data)
        override suspend fun readFwMap(): ByteArray = ble.firmwareReadFwMap()
        override suspend fun subscribeFwMap(onFrame: (ByteArray) -> Unit) = ble.firmwareSubscribeFwMap(onFrame)
        override suspend fun awaitQuiet(): Boolean =
            ble.awaitNonFlashWritesDrained(coffee.crema.firmware.DRAIN_TIMEOUT_MS)
        override fun setExclusive(on: Boolean) {
            ble.firmwareExclusive = on
            if (!on) ble.firmwareUnsubscribeFwMap()
        }
    }

    /**
     * The connect-time setup (identity reads + settings sweep) has run on this
     * link. A flash only starts after it, so its writes are not still queued
     * behind the gate (audit M1). Reset on every drop.
     */
    @Volatile
    var setupDone = false

    /** Settings → Machine → "Update firmware…" (alpha): the VM's [MainViewModel.firmware]. */
    val update: coffee.crema.firmware.FirmwareUpdateController = coffee.crema.firmware.FirmwareUpdateController(
        scope = firmwareScope,
        core = firmwareCore,
        catalog = object : coffee.crema.firmware.FirmwareCatalog {
            override fun manifest() = json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(coffee.crema.core.FirmwareArtifact.serializer()),
                coffee.crema.core.firmwareManifest(),
            )
            override fun validate(image: ByteArray) = json.decodeFromString(
                coffee.crema.core.FirmwareImageCheck.serializer(),
                coffee.crema.core.validateFirmwareImage(image),
            )
        },
        fetcher = coffee.crema.firmware.KtorFirmwareFetcher(),
        conditions = {
            val state = ble.state.value
            coffee.crema.core.FlashShellConditions(
                connected = state == De1BleManager.State.READY,
                ready = state == De1BleManager.State.READY && setupDone,
                guidedBrewActive = uiState().guidedBrew.live,
                powerOk = firmwarePowerOk(app),
            )
        },
        installedBuild = { uiState().de1MachineInfo[MmrRegister.FirmwareVersion] },
        log = appendLog,
        markers = coffee.crema.firmware.SharedPrefsAwaitingMarkers(app),
        machineId = { ble.connectedAddress },
        linkUp = { ble.state.value == De1BleManager.State.READY },
        // The flash let go of the link: run the connect-time work it held off.
        onRelease = { scope.launch { afterRelease() } },
        driverFactory = { onStatus ->
            coffee.crema.firmware.FirmwareFlashDriver(
                core = firmwareCore,
                io = firmwareIo,
                scope = firmwareScope,
                clock = SystemClock::elapsedRealtime,
                onStatus = onStatus,
                onLog = appendLog,
                keepAlive = { active ->
                    scope.launch { setFlashActive(active) }
                    // HIGH priority for the transfer: one acknowledged 16-byte
                    // write at a time makes the connection interval the
                    // speed limit (~29,000 round trips), so this cuts it from
                    // Android's default ~30-50 ms to ~11-15 ms (Decenza keeps
                    // its DE1 link at HIGH). Back to BALANCED when it ends.
                    scope.launch { ble.setConnectionPriority(high = active) }
                },
                transportLabel = {
                    "Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}), " +
                        "Nordic BLE, DE1 ${ble.connectedAddress ?: "unknown"}"
                },
            )
        },
    )

    /**
     * A flash (or its erase / power-cycle window) holds the DE1 link: the
     * reconnect side-effects — settings sweep, identity reads, charger checks,
     * presence pokes, the saver's sleep — stay off the machine (audit H2). The
     * core refuses them too; this keeps them from running (and being marked
     * done) at all.
     */
    fun holds(): Boolean =
        runCatching { bridge.firmwareLocksWrites() }.getOrDefault(update.ui.value.holdsLink)
}

/** Charging, or battery ≥ 50 % — the firmware flash's power precondition. */
private fun firmwarePowerOk(context: android.content.Context): Boolean {
    val status = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return true
    val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
    return coffee.crema.firmware.powerOk(plugged, percent)
}
