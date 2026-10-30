package coffee.crema.ui

/**
 * Hand the active profile to the core (`setActiveProfile`, wire `Profile` JSON;
 * null = none) so its beverage type and per-step weight exits follow the ACTIVE
 * profile, not only the last upload (issue 11): [MainViewModel.startShot] skips
 * the upload on a fingerprint match, and a restarted app's fresh core would
 * otherwise never learn the step weights. [activeCremaJson] is the library's
 * complete CremaProfile JSON; [toWire] is `cremaProfileToWire`. Top-level +
 * `internal` so it's unit-testable without the native core.
 */
internal fun pushActiveProfile(
    activeCremaJson: String?,
    toWire: (String) -> String,
    setActiveProfile: (String?) -> Unit,
    log: (String) -> Unit = {},
) {
    // A profile the core can't convert clears the core's active profile
    // (espresso, no weight exits) rather than leaving the previous one's.
    val wire = activeCremaJson?.let { cremaJson ->
        runCatching { toWire(cremaJson) }
            .onFailure { log("Active profile conversion failed: ${it.message}") }
            .getOrNull()
    }
    runCatching { setActiveProfile(wire) }
        .onFailure { log("Push active profile failed: ${it.message}") }
}
