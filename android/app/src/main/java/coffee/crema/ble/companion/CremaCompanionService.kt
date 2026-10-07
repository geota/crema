package coffee.crema.ble.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.util.Log

/**
 * Receives companion-device presence from the system (declared in the
 * manifest with `BIND_COMPANION_DEVICE_SERVICE`). The system binds this
 * service while an associated, observed device is nearby — starting Crema's
 * process if it isn't running — and keeps the process in a raised importance
 * while bound. Each report goes to [CompanionPresence]; the connection
 * controller (when the app's ViewModel is alive) turns "appeared" into an
 * immediate reconnect and "disappeared" into an idle lurk.
 *
 * Exactly one callback family is acted on per API level, because the
 * platform's default implementations may forward between them:
 *  - API 36+: `onDevicePresenceEvent` (BLE appeared / disappeared).
 *  - API 33–35: `onDeviceAppeared/onDeviceDisappeared(AssociationInfo)`.
 *  - API 31–32: `onDeviceAppeared/onDeviceDisappeared(String address)`.
 */
class CremaCompanionService : CompanionDeviceService() {

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        val present = when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> true
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED, DevicePresenceEvent.EVENT_BT_DISCONNECTED -> false
            else -> return
        }
        val address = addressFor(event.associationId) ?: return
        report(address, present)
    }

    @Deprecated("Superseded by onDevicePresenceEvent on API 36")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) return
        associationInfo.deviceMacAddress?.toString()?.let { report(it, true) }
    }

    @Deprecated("Superseded by onDevicePresenceEvent on API 36")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) return
        associationInfo.deviceMacAddress?.toString()?.let { report(it, false) }
    }

    @Deprecated("Superseded by onDeviceAppeared(AssociationInfo) on API 33")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        report(address, true)
    }

    @Deprecated("Superseded by onDeviceDisappeared(AssociationInfo) on API 33")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        report(address, false)
    }

    private fun report(address: String, present: Boolean) {
        Log.i(TAG, "companion presence: $address ${if (present) "appeared" else "disappeared"}")
        CompanionPresence.report(address, present)
    }

    /** API 36's event carries only the association id — resolve its MAC. */
    private fun addressFor(associationId: Int): String? =
        runCatching { AndroidCompanionGateway(this).associations().entries.firstOrNull { it.value == associationId }?.key }
            .getOrNull()

    private companion object {
        const val TAG = "CremaCompanion"
    }
}
