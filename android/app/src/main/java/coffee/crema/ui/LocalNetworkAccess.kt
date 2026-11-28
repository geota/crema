package coffee.crema.ui

import android.Manifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Local network access (Android 17, API 37). An app that targets 37 needs the
 * ACCESS_LOCAL_NETWORK runtime permission for any traffic to the LAN: outgoing
 * and incoming TCP, UDP, and mDNS (NsdManager). Below 37 the INTERNET
 * permission still covers it. The hub (Ktor CIO relay server), the remotes'
 * links to it and the NSD browse/advertise all need it.
 *
 * The permission is in the NEARBY_DEVICES group, like BLUETOOTH_SCAN/CONNECT,
 * so a user who already allowed Bluetooth gets no second dialog: the request
 * still has to be made, and it comes back granted at once.
 *
 * Crema asks when the user turns on a hub or remote feature (Hub mode,
 * Connect through, Pair again, Pair a device), and at launch only when this
 * device already is a hub or remote. A plain device never asks on its own,
 * and NSD stays off until access is allowed: on API 37 a browse without it
 * makes the system open its own service picker over the app.
 */

/** First API level with the local network permission. */
internal const val LOCAL_NETWORK_MIN_SDK = 37

/** The permissions LAN features need on a device running [sdkInt]: none
 *  below API 37, where INTERNET covers local traffic. */
internal fun localNetworkPermissions(sdkInt: Int): Array<String> =
    if (sdkInt >= LOCAL_NETWORK_MIN_SDK) arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK) else emptyArray()

/** Whether Crema may use the LAN, as the picker shows it. */
enum class LocalNetworkAccess {
    /** Allowed (or not needed on this Android version). */
    GRANTED,

    /** Not allowed yet and not asked in this run. */
    NOT_ASKED,

    /** The user said no; asking again shows the dialog. */
    DENIED,

    /** Denied for good ("Don't allow" twice): only the app's Settings page can turn it on. */
    BLOCKED,
}

/** The snackbar after a refusal. */
internal fun localNetworkDeniedMessage(access: LocalNetworkAccess): String = when (access) {
    LocalNetworkAccess.BLOCKED ->
        "Local network access is off for Crema. Allow Nearby devices for Crema in Settings to use the hub and remotes."
    else ->
        "Crema needs local network access to reach other Crema devices on your Wi-Fi. Try again to allow it."
}

/** The picker row's title while access is missing. */
internal fun localNetworkRowTitle(access: LocalNetworkAccess): String =
    if (access == LocalNetworkAccess.NOT_ASKED) "Find other Crema devices" else "Local network access is off"

/** The picker row's line while access is missing. */
internal fun localNetworkRowCopy(access: LocalNetworkAccess): String = when (access) {
    LocalNetworkAccess.BLOCKED ->
        "Crema can't find or reach a hub or remotes on your Wi-Fi until you allow Nearby devices for it in Settings."
    else ->
        "Allow local network access so Crema can find and reach a hub or remotes on your Wi-Fi."
}

/** The picker row's button while access is missing. */
internal fun localNetworkRowAction(access: LocalNetworkAccess): String =
    if (access == LocalNetworkAccess.BLOCKED) "Open settings" else "Allow"

/**
 * Runs LAN actions only once local network access is allowed. The view model
 * owns it (an action survives the activity being recreated during the dialog);
 * the activity watches [requests], shows the system dialog and reports back
 * through [onResult].
 *
 * Pure on purpose: [isGranted] reads the real grant, so the rules are unit
 * tested without Android.
 */
internal class LocalNetworkGate(
    private val isGranted: () -> Boolean,
    /** Publish the state for the UI. */
    private val publish: (LocalNetworkAccess) -> Unit,
    private val notify: (String) -> Unit,
    /** Access was just allowed: start what waited for it (NSD browse/advertise). */
    private val onGranted: () -> Unit,
) {
    private val _requests = MutableStateFlow(false)

    /** True while the activity should show the system dialog. */
    val requests: StateFlow<Boolean> = _requests.asStateFlow()

    private var pending: (() -> Unit)? = null

    /** A dialog was asked for and its answer hasn't come back. */
    private var inFlight = false

    var access: LocalNetworkAccess = if (isGranted()) LocalNetworkAccess.GRANTED else LocalNetworkAccess.NOT_ASKED
        private set

    init {
        publish(access)
    }

    /** Run [action] now if access is allowed; otherwise ask, and run it once allowed.
     *  A newer action replaces one still waiting for the answer. */
    fun withAccess(action: () -> Unit) {
        if (isGranted()) {
            set(LocalNetworkAccess.GRANTED)
            action()
            return
        }
        pending = action
        // One dialog at a time: a second tap only replaces the waiting action.
        if (!inFlight) {
            inFlight = true
            _requests.value = true
        }
    }

    /** Ask without an action (the picker's Allow, or launch as a hub or remote). */
    fun ask() = withAccess {}

    /** The activity has shown the dialog for the current request. */
    fun onRequestShown() {
        _requests.value = false
    }

    /** The dialog's answer. [canAskAgain]: Android will show the dialog again
     *  (`shouldShowRequestPermissionRationale`). */
    fun onResult(granted: Boolean, canAskAgain: Boolean) {
        val action = pending
        pending = null
        inFlight = false
        _requests.value = false
        if (granted || isGranted()) {
            set(LocalNetworkAccess.GRANTED)
            action?.invoke()
        } else {
            val state = if (canAskAgain) LocalNetworkAccess.DENIED else LocalNetworkAccess.BLOCKED
            set(state)
            notify(localNetworkDeniedMessage(state))
        }
    }

    /** Re-read the grant (on resume: the user may have changed it in Settings). */
    fun refresh() {
        when {
            isGranted() -> set(LocalNetworkAccess.GRANTED)
            // Revoked in Settings: the next LAN action asks again.
            access == LocalNetworkAccess.GRANTED -> set(LocalNetworkAccess.NOT_ASKED)
        }
    }

    private fun set(next: LocalNetworkAccess) {
        val was = access
        access = next
        if (next != was) publish(next)
        if (next == LocalNetworkAccess.GRANTED && was != LocalNetworkAccess.GRANTED) onGranted()
    }
}
