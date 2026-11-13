package coffee.crema.faults

import androidx.compose.runtime.Composable

// Release and nightly (the shipped builds): no fault injection. The debug
// build's twin of this file is `src/debug/java/coffee/crema/faults/Faults.kt`;
// the signatures match, the bodies here do nothing.

/** The app's injector: never drops anything. */
@Suppress("UNUSED_PARAMETER")
internal fun installedFaults(log: (String) -> Unit): FaultInjector = NoFaults

/** The Debug screen's fault controls: none in a shipped build. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun FaultInjectionPanel(faults: FaultInjector) {
}

/** The "fault injection active" banner: never shown in a shipped build. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun FaultInjectionBanner(faults: FaultInjector) {
}
