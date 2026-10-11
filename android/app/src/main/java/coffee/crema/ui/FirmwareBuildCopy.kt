package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coffee.crema.core.FirmwareBuildReport
import coffee.crema.core.FirmwareBuildStatus
import coffee.crema.core.classifyFirmwareBuild
import kotlinx.serialization.json.Json

// The Settings → Machine firmware copy, shared by the tablet hero card and the
// phone Identity row. The classification (what an installed DE1 build *means*)
// lives in the core — `de1_domain::classify_firmware_build`, which compares the
// build with a versioned table of Decent's builds and channels instead of a raw
// "below the latest number" check (the rolled-back 1363 is numerically above
// 1358). This file only maps the core's status key to copy — web parity with
// `$lib/firmware/build-copy.ts`.
//
// `offerUpdate` says whether a newer official build than the installed one
// exists; the card then leads with "Update firmware…" (the alpha flow,
// `coffee.crema.firmware`). The flow is available on request either way.

/** What the firmware card shows. */
data class FirmwareCardCopy(
    /** Headline, e.g. "Build 1333 · Decent stable". */
    val title: String,
    /** One short sentence explaining the headline. */
    val note: String,
    /** A newer official build than the installed one exists. */
    val offerUpdate: Boolean,
)

private val firmwareJson = Json { ignoreUnknownKeys = true }

/** Classify an installed build (MMR `0x800010`; null = not read yet) via the core. */
fun firmwareBuildReport(build: UInt?): FirmwareBuildReport? = runCatching {
    firmwareJson.decodeFromString(FirmwareBuildReport.serializer(), classifyFirmwareBuild(build))
}.getOrNull()

/** [firmwareCardCopy] for the live build, memoized against its inputs. */
@Composable
fun rememberFirmwareCardCopy(build: UInt?, connected: Boolean): FirmwareCardCopy? =
    remember(build, connected) { firmwareBuildReport(build)?.let { firmwareCardCopy(it, connected) } }

/** The card copy for a classified build. [connected] = a DE1 is connected. */
fun firmwareCardCopy(r: FirmwareBuildReport, connected: Boolean): FirmwareCardCopy {
    val b = r.installed
    if (!connected) {
        return FirmwareCardCopy("No build info", "Connect your DE1 to see which firmware build it runs.", false)
    }
    if (r.status == FirmwareBuildStatus.Unknown || b == null) {
        return FirmwareCardCopy("Build not read yet", "Waiting for the DE1 to report its firmware build.", false)
    }
    val newest = "build ${r.newestBuild}" +
        if (r.nightlyBuild == r.newestBuild && r.stableBuild != r.newestBuild) " (nightly)" else ""
    return when (r.status) {
        FirmwareBuildStatus.Newest -> FirmwareCardCopy(
            "Build $b · newest Decent release",
            if (r.stableBuild == b) {
                "Decent has no newer build."
            } else {
                "Decent has no newer build. Its stable channel is on build ${r.stableBuild}."
            },
            false,
        )
        FirmwareBuildStatus.Stable -> FirmwareCardCopy("Build $b · Decent stable", "Newer: $newest.", true)
        FirmwareBuildStatus.BetweenStableAndNewest ->
            FirmwareCardCopy("Build $b · newer than Decent stable", "Newest: $newest.", true)
        FirmwareBuildStatus.OlderThanStable -> FirmwareCardCopy(
            "Build $b · older than Decent stable",
            "Decent stable is build ${r.stableBuild}; the newest is $newest.",
            true,
        )
        FirmwareBuildStatus.RolledBack -> FirmwareCardCopy(
            "Build $b · rolled-back build",
            "Decent withdrew this build and went back to build ${r.stableBuild}." +
                if (b > r.newestBuild) " Its number is higher, but it isn’t newer than build ${r.newestBuild}." else "",
            true,
        )
        FirmwareBuildStatus.NewerThanKnown -> FirmwareCardCopy(
            "Build $b · newer than Crema knows about",
            "Newer than build ${r.newestBuild}, the newest in Crema’s list (checked ${r.tableVerified}).",
            false,
        )
        FirmwareBuildStatus.Unknown -> FirmwareCardCopy("Build not read yet", "Waiting for the DE1 to report its firmware build.", false)
    }
}
