package coffee.crema.ui

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import coffee.crema.ble.companion.CompanionDevice

/**
 * Runs the one-time system "associate this device" dialog for a remembered
 * DE1 / scale (CompanionDeviceManager.associate), filtered to that device's
 * address so the sheet shows exactly one device to confirm.
 *
 *  - API 33+: `associate(request, executor, callback)` — `onAssociationPending`
 *    hands us the dialog's IntentSender, `onAssociationCreated` the
 *    `AssociationInfo` (id + MAC). The activity result's `EXTRA_ASSOCIATION`
 *    is the fallback when the callback was lost (activity recreated).
 *  - API 31–32: the deprecated `associate(request, callback, handler)` —
 *    `onDeviceFound` gives the IntentSender; RESULT_OK means associated, and
 *    there is no association id (the address is the key).
 *
 * Must be constructed while the activity is being created (it registers an
 * activity-result launcher).
 */
class CompanionAssociator(
    private val activity: ComponentActivity,
    private val onAssociated: (device: CompanionDevice, address: String, id: Int?) -> Unit,
    /** Declined, cancelled or failed (reason for the log), or null. */
    private val onEnded: (reason: String?) -> Unit,
) {
    private var pending: Pair<CompanionDevice, String>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (pending == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            end("declined or cancelled")
            return@registerForActivityResult
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            // Normally onAssociationCreated already completed it; this covers a lost callback.
            if (info != null) complete(info.id)
        } else {
            complete(null)
        }
    }

    fun associate(device: CompanionDevice, address: String) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            onEnded("companion devices unavailable")
            return
        }
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(address).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()
        pending = device to address
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.associate(
                    request,
                    activity.mainExecutor,
                    object : CompanionDeviceManager.Callback() {
                        override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)
                        override fun onAssociationCreated(associationInfo: AssociationInfo) = complete(associationInfo.id)
                        override fun onFailure(error: CharSequence?) = end(error?.toString() ?: "failed")
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                cdm.associate(
                    request,
                    object : CompanionDeviceManager.Callback() {
                        @Deprecated("API 33+ uses onAssociationPending")
                        override fun onDeviceFound(intentSender: IntentSender) = launch(intentSender)
                        override fun onFailure(error: CharSequence?) = end(error?.toString() ?: "failed")
                    },
                    null,
                )
            }
        }.onFailure { end(it.message ?: "failed") }
    }

    private fun launch(sender: IntentSender) {
        runCatching { launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .onFailure { end(it.message ?: "could not open the system dialog") }
    }

    private fun complete(id: Int?) {
        val (device, address) = pending ?: return
        pending = null
        onAssociated(device, address, id)
    }

    private fun end(reason: String) {
        if (pending == null) return
        pending = null
        onEnded(reason)
    }
}
