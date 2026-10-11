/**
 * `$lib/firmware/build-copy` — the Settings → Machine firmware card's copy.
 *
 * The classification (what an installed DE1 build *means*) lives in the core:
 * `de1_domain::classify_firmware_build`, which compares the build with a
 * versioned table of Decent's builds and channels instead of a raw "is it
 * below the latest number" check (the rolled-back 1363 is numerically above
 * 1358). This module only maps the core's status key to English copy, the way
 * the Android shell's `FirmwareBuildCopy.kt` does.
 *
 * Crema does not install firmware. The guidance line says how to update today
 * (Decent's tablet app) and which build each de1app channel actually installs,
 * so it never promises the newest build from de1app's stable channel.
 */

import { classifyFirmwareBuild } from '$lib/wasm/de1_wasm.js';
import { FirmwareBuildStatus, type FirmwareBuildReport } from '$lib/core/crema-core';

/** Classify an installed build (MMR `0x800010`; `null` = not read yet). */
export function firmwareBuildReport(build: number | null | undefined): FirmwareBuildReport {
	return JSON.parse(classifyFirmwareBuild(build ?? undefined)) as FirmwareBuildReport;
}

/** What the firmware card shows. */
export interface FirmwareCardCopy {
	/** Headline, e.g. "Build 1333 · Decent stable". */
	title: string;
	/** One or two sentences explaining the headline. */
	note: string;
	/** How to update today (Crema does not install firmware). */
	guidance: string;
}

/** The de1app channels that install `build`, in stable → beta → nightly order. */
function channelsFor(r: FirmwareBuildReport, build: number): string[] {
	const out: string[] = [];
	if (r.stableBuild === build) out.push('stable');
	if (r.betaBuild === build) out.push('beta');
	if (r.nightlyBuild === build) out.push('nightly');
	return out;
}

function joinAnd(items: string[]): string {
	if (items.length <= 1) return items.join('');
	return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

/** "de1app's stable and beta channels install" / "de1app's nightly channel installs". */
function channelsInstall(channels: string[]): string {
	const many = channels.length > 1;
	return `de1app's ${joinAnd(channels)} channel${many ? 's' : ''} install${many ? '' : 's'}`;
}

/** Which build each de1app channel installs, as one sentence. */
function channelSummary(r: FirmwareBuildReport): string {
	if (r.stableBuild === r.betaBuild && r.betaBuild === r.nightlyBuild) {
		return `Every de1app channel installs build ${r.stableBuild}.`;
	}
	if (r.stableBuild === r.betaBuild) {
		return `Its stable and beta versions install build ${r.stableBuild}; build ${r.nightlyBuild} needs the nightly version.`;
	}
	return `Its stable version installs build ${r.stableBuild}, beta ${r.betaBuild} and nightly ${r.nightlyBuild}.`;
}

const HOW_TO_UPDATE =
	"Crema can't install firmware yet. To update, use Decent's tablet app (de1app): Settings → Machine → Firmware.";

/** The card copy for a classified build. `connected` = a DE1 is connected. */
export function firmwareCardCopy(r: FirmwareBuildReport, connected: boolean): FirmwareCardCopy {
	const guidance = `${HOW_TO_UPDATE} ${channelSummary(r)}`;
	const b = r.installed;
	if (!connected) {
		return {
			title: 'No build info',
			note: 'Connect your DE1 to see which firmware build it runs.',
			guidance
		};
	}
	if (r.status === FirmwareBuildStatus.Unknown || b == null) {
		return {
			title: 'Build not read yet',
			note: 'Waiting for the DE1 to report its firmware build.',
			guidance
		};
	}
	switch (r.status) {
		case FirmwareBuildStatus.Newest: {
			const ch = channelsFor(r, b);
			const note = ch.includes('stable')
				? `The newest Decent release; ${channelsInstall(ch)} it.`
				: ch.length > 0
					? `The newest Decent release. Only ${channelsInstall(ch)} it; stable installs build ${r.stableBuild}.`
					: `The newest Decent release. de1app's stable channel installs build ${r.stableBuild}.`;
			return {
				title: `Build ${b} · newest Decent release`,
				note,
				guidance: "Crema can't install firmware yet. Nothing to do: Decent has no newer build."
			};
		}
		case FirmwareBuildStatus.Stable: {
			const ch = channelsFor(r, b);
			return {
				title: `Build ${b} · Decent stable`,
				note: `The build ${channelsInstall(ch.length > 0 ? ch : ['stable'])}. The newest release is build ${r.newestBuild}.`,
				guidance
			};
		}
		case FirmwareBuildStatus.BetweenStableAndNewest:
			return {
				title: `Build ${b} · newer than Decent stable`,
				note: `Newer than build ${r.stableBuild} (de1app stable), older than the newest release, build ${r.newestBuild}.`,
				guidance
			};
		case FirmwareBuildStatus.OlderThanStable:
			return {
				title: `Build ${b} · older than Decent's current stable`,
				note: `de1app's stable channel installs build ${r.stableBuild}.`,
				guidance
			};
		case FirmwareBuildStatus.RolledBack:
			return {
				title: `Build ${b} · rolled-back build`,
				note:
					`Decent published this build, then withdrew it and went back to build ${r.stableBuild}.` +
					(b > r.newestBuild
						? ` Its number is higher, but it isn't newer than build ${r.newestBuild}.`
						: ''),
				guidance
			};
		case FirmwareBuildStatus.NewerThanKnown:
			return {
				title: `Build ${b} · newer than Crema knows about`,
				note: `Newer than build ${r.newestBuild}, the newest release in Crema's list (checked ${r.tableVerified}).`,
				guidance: "Crema can't install firmware yet. Nothing to do."
			};
		default:
			return { title: `Build ${b}`, note: '', guidance };
	}
}
