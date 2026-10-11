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
// Crema does not install firmware. The guidance line says how to update today
// (Decent's tablet app) and which build each de1app channel actually installs.

/** What the firmware card shows. */
data class FirmwareCardCopy(
    /** Headline, e.g. "Build 1333 · Decent stable". */
    val title: String,
    /** One or two sentences explaining the headline. */
    val note: String,
    /** How to update today. */
    val guidance: String,
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

/** The de1app channels that install [build], in stable → beta → nightly order. */
private fun FirmwareBuildReport.channelsFor(build: UInt): List<String> = buildList {
    if (stableBuild == build) add("stable")
    if (betaBuild == build) add("beta")
    if (nightlyBuild == build) add("nightly")
}

private fun joinAnd(items: List<String>): String =
    if (items.size <= 1) items.joinToString("") else items.dropLast(1).joinToString(", ") + " and " + items.last()

/** "de1app's stable and beta channels install" / "de1app's nightly channel installs". */
private fun channelsInstall(channels: List<String>): String {
    val many = channels.size > 1
    return "de1app’s ${joinAnd(channels)} channel${if (many) "s" else ""} install${if (many) "" else "s"}"
}

/** Which build each de1app channel installs, as one sentence. */
private fun FirmwareBuildReport.channelSummary(): String = when {
    stableBuild == betaBuild && betaBuild == nightlyBuild -> "Every de1app channel installs build $stableBuild."
    stableBuild == betaBuild -> "Its stable and beta versions install build $stableBuild; build $nightlyBuild needs the nightly version."
    else -> "Its stable version installs build $stableBuild, beta $betaBuild and nightly $nightlyBuild."
}

private const val HOW_TO_UPDATE =
    "Crema can’t install firmware yet. To update, use Decent’s tablet app (de1app): Settings → Machine → Firmware."

/** The card copy for a classified build. [connected] = a DE1 is connected. */
fun firmwareCardCopy(r: FirmwareBuildReport, connected: Boolean): FirmwareCardCopy {
    val guidance = "$HOW_TO_UPDATE ${r.channelSummary()}"
    val b = r.installed
    if (!connected) {
        return FirmwareCardCopy("No build info", "Connect your DE1 to see which firmware build it runs.", guidance)
    }
    if (r.status == FirmwareBuildStatus.Unknown || b == null) {
        return FirmwareCardCopy("Build not read yet", "Waiting for the DE1 to report its firmware build.", guidance)
    }
    return when (r.status) {
        FirmwareBuildStatus.Newest -> {
            val ch = r.channelsFor(b)
            val note = when {
                "stable" in ch -> "The newest Decent release; ${channelsInstall(ch)} it."
                ch.isNotEmpty() -> "The newest Decent release. Only ${channelsInstall(ch)} it; stable installs build ${r.stableBuild}."
                else -> "The newest Decent release. de1app’s stable channel installs build ${r.stableBuild}."
            }
            FirmwareCardCopy(
                "Build $b · newest Decent release",
                note,
                "Crema can’t install firmware yet. Nothing to do: Decent has no newer build.",
            )
        }
        FirmwareBuildStatus.Stable -> {
            val ch = r.channelsFor(b).ifEmpty { listOf("stable") }
            FirmwareCardCopy(
                "Build $b · Decent stable",
                "The build ${channelsInstall(ch)}. The newest release is build ${r.newestBuild}.",
                guidance,
            )
        }
        FirmwareBuildStatus.BetweenStableAndNewest -> FirmwareCardCopy(
            "Build $b · newer than Decent stable",
            "Newer than build ${r.stableBuild} (de1app stable), older than the newest release, build ${r.newestBuild}.",
            guidance,
        )
        FirmwareBuildStatus.OlderThanStable -> FirmwareCardCopy(
            "Build $b · older than Decent’s current stable",
            "de1app’s stable channel installs build ${r.stableBuild}.",
            guidance,
        )
        FirmwareBuildStatus.RolledBack -> FirmwareCardCopy(
            "Build $b · rolled-back build",
            "Decent published this build, then withdrew it and went back to build ${r.stableBuild}." +
                if (b > r.newestBuild) " Its number is higher, but it isn’t newer than build ${r.newestBuild}." else "",
            guidance,
        )
        FirmwareBuildStatus.NewerThanKnown -> FirmwareCardCopy(
            "Build $b · newer than Crema knows about",
            "Newer than build ${r.newestBuild}, the newest release in Crema’s list (checked ${r.tableVerified}).",
            "Crema can’t install firmware yet. Nothing to do.",
        )
        FirmwareBuildStatus.Unknown -> FirmwareCardCopy("Build not read yet", "Waiting for the DE1 to report its firmware build.", guidance)
    }
}
