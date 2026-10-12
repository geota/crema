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
 * `offerUpdate` says whether a newer official build than the installed one
 * exists; the card then leads with "Update firmware…" (the alpha flow in
 * `$lib/firmware/firmware-update.svelte`). The flow is available on request
 * either way.
 */

import { classifyFirmwareBuild } from '$lib/wasm/de1_wasm.js';
import { FirmwareBuildStatus, type FirmwareBuildReport } from '$lib/core/crema-core';

/** Classify an installed build (MMR `0x800010`; `null` = not read yet). */
export function firmwareBuildReport(build: number | null | undefined): FirmwareBuildReport {
	return JSON.parse(classifyFirmwareBuild(build ?? undefined)) as FirmwareBuildReport;
}

/** What the firmware card shows. */
export interface FirmwareCardCopy {
	/** Headline, e.g. "Build 1333 · stable". */
	title: string;
	/** One short sentence explaining the headline. */
	note: string;
	/** A newer official build than the installed one exists. */
	offerUpdate: boolean;
}

/** The card copy for a classified build. `connected` = a DE1 is connected. */
export function firmwareCardCopy(r: FirmwareBuildReport, connected: boolean): FirmwareCardCopy {
	const b = r.installed;
	if (!connected) {
		return {
			title: 'No build info',
			note: 'Connect your DE1 to see which firmware build it runs.',
			offerUpdate: false
		};
	}
	if (r.status === FirmwareBuildStatus.Unknown || b == null) {
		return {
			title: 'Build not read yet',
			note: 'Waiting for the DE1 to report its firmware build.',
			offerUpdate: false
		};
	}
	const newest = `build ${r.newestBuild}${r.nightlyBuild === r.newestBuild && r.stableBuild !== r.newestBuild ? ' (nightly)' : ''}`;
	switch (r.status) {
		case FirmwareBuildStatus.Newest:
			return {
				title: `Build ${b} · newest`,
				note:
					r.stableBuild === b
						? 'Decent has no newer build.'
						: `Decent has no newer build. Its stable channel is on build ${r.stableBuild}.`,
				offerUpdate: false
			};
		case FirmwareBuildStatus.Stable:
			return {
				title: `Build ${b} · stable`,
				note: `Decent's stable build. Newer: ${newest}.`,
				offerUpdate: true
			};
		case FirmwareBuildStatus.BetweenStableAndNewest:
			return {
				title: `Build ${b} · newer than stable`,
				note: `Newer than Decent's stable build ${r.stableBuild}. Newest: ${newest}.`,
				offerUpdate: true
			};
		case FirmwareBuildStatus.OlderThanStable:
			return {
				title: `Build ${b} · older than stable`,
				note: `Decent stable is build ${r.stableBuild}; the newest is ${newest}.`,
				offerUpdate: true
			};
		case FirmwareBuildStatus.RolledBack:
			return {
				title: `Build ${b} · withdrawn`,
				note:
					`Decent withdrew this build and went back to build ${r.stableBuild}.` +
					(b > r.newestBuild ? ` Its number is higher, but it isn't newer than build ${r.newestBuild}.` : ''),
				offerUpdate: true
			};
		case FirmwareBuildStatus.NewerThanKnown:
			return {
				title: `Build ${b} · newer than Crema knows`,
				note: `Newer than build ${r.newestBuild}, the newest in Crema's list (checked ${r.tableVerified}).`,
				offerUpdate: false
			};
		default:
			return { title: `Build ${b}`, note: '', offerUpdate: false };
	}
}
