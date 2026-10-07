package coffee.crema.ble.companion

import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * The real [CompanionGateway] over `CompanionDeviceManager`, with the API-level
 * splits the platform imposes (minSdk 31):
 *
 *  - **Associations.** API 33+: `getMyAssociations()` → `AssociationInfo`
 *    (id + MAC). API 31–32: the deprecated `getAssociations()` → MAC strings,
 *    no id.
 *  - **Presence.** API 36+: `startObservingDevicePresence(
 *    ObservingDevicePresenceRequest)` keyed by association id, delivered to
 *    `CompanionDeviceService.onDevicePresenceEvent`. API 31–35: the
 *    address-keyed `startObservingDevicePresence(String)` (deprecated in 36),
 *    delivered to `onDeviceAppeared/onDeviceDisappeared`. Both need
 *    `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`; for a BLE device the system
 *    "scans for device with the given address" whenever Bluetooth (or BLE
 *    scanning) is on — its own scan, not throttled like ours.
 *  - **Removal.** API 33+: `disassociate(int)`; 31–32: `disassociate(String)`.
 *
 * Gated on `FEATURE_COMPANION_DEVICE_SETUP` (the manifest declares the
 * feature `required="false"`, so devices without it still install Crema and
 * simply never see the companion option).
 */
class AndroidCompanionGateway(context: Context) : CompanionGateway {
    private val app = context.applicationContext

    private val cdm: CompanionDeviceManager? =
        app.getSystemService(CompanionDeviceManager::class.java)

    override val isSupported: Boolean =
        cdm != null && app.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    override fun associations(): Map<String, Int?> {
        val m = cdm ?: return emptyMap()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            m.myAssociations.mapNotNull { info ->
                info.deviceMacAddress?.toString()?.let { normalizeAddress(it) to info.id }
            }.toMap()
        } else {
            @Suppress("DEPRECATION")
            m.associations.associate { normalizeAddress(it) to null }
        }
    }

    override fun startObserving(address: String, id: Int?): Boolean {
        val m = cdm ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && id != null) {
                m.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(id).build(),
                )
            } else {
                @Suppress("DEPRECATION")
                m.startObservingDevicePresence(address)
            }
            true
        }.onFailure { Log.w(TAG, "startObservingDevicePresence($address) failed", it) }
            .getOrDefault(false)
    }

    override fun stopObserving(address: String, id: Int?) {
        val m = cdm ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && id != null) {
                m.stopObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(id).build(),
                )
            } else {
                @Suppress("DEPRECATION")
                m.stopObservingDevicePresence(address)
            }
        }.onFailure { Log.w(TAG, "stopObservingDevicePresence($address) failed", it) }
    }

    override fun disassociate(address: String, id: Int?) {
        val m = cdm ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && id != null) {
                m.disassociate(id)
            } else {
                @Suppress("DEPRECATION")
                m.disassociate(address)
            }
        }.onFailure { Log.w(TAG, "disassociate($address) failed", it) }
    }

    private companion object {
        const val TAG = "CompanionGateway"
    }
}
