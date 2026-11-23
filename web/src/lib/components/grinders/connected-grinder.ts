/**
 * Devices → Grinder's "Connected grinder" card (beta): the status line, the
 * "close the vendor app first" messages (spec §2.7) and the live readout, as
 * pure functions of the app's {@link ConnectedGrinderView}.
 *
 * Nothing here names a grinder model: the maker and model come from the
 * grinder catalogue entry the core identified, the units from its
 * capabilities.
 */

import { grinderCatalogue } from '#lib/bean/grinder.js';
import type { CatalogueGrinderInfo, GrinderCapabilities, GrinderReading } from '#lib/core/crema-core.js';
import { GrinderState } from '#lib/core/crema-core.js';
import type { GrinderProblem } from '#lib/ble/grinder.js';
import type { ConnectedGrinderView } from '#lib/state/ui-state.svelte.js';

/** The catalogue entry for a connected model id, or null. */
export function catalogueFor(model: string | null): CatalogueGrinderInfo | null {
	if (!model) return null;
	return grinderCatalogue().find((c) => c.id === model) ?? null;
}

/** The one-line status under the card's title. */
export function grinderStatusLine(view: ConnectedGrinderView): string {
	if (view.reading?.state === GrinderState.Updating && view.state === 'ready') {
		return 'Updating firmware…';
	}
	switch (view.state) {
		case 'connecting':
			return 'Connecting…';
		case 'subscribing':
			return 'Waiting for the grinder…';
		case 'ready':
			return 'Live';
		case 'reconnecting':
			return 'Reconnecting…';
		case 'failed':
			if (view.problem?.kind === 'firmware') return 'Update firmware';
			return view.problem?.kind === 'no-data' ? 'Not sending data' : 'Not connected';
		case 'idle':
		case 'disconnected':
			return view.problem?.kind === 'not-found' ? 'Not found' : 'Not connected';
	}
}

/**
 * What to tell the user about a link that ended with no retry (§2.7), or
 * null. `entry` is the connected model's catalogue entry, when known.
 */
export function grinderProblemText(
	problem: GrinderProblem | null,
	entry: CatalogueGrinderInfo | null
): string | null {
	if (problem === null) return null;
	const vendorApp = entry ? `the ${entry.maker} app` : "the grinder maker's app";
	switch (problem.kind) {
		case 'not-found':
			return `Can't see your ${entry?.model ?? 'grinder'}. Make sure it's on and not connected to ${vendorApp} or another phone, then try again.`;
		case 'no-answer':
			return `The grinder didn't answer. Close ${vendorApp}, turn the grinder off and on, and try again.`;
		case 'no-data':
			return 'Grinder not sending data. Crema stopped reconnecting: check it is on and not connected to another app, then reconnect.';
		case 'firmware':
			return `Update the grinder in ${vendorApp} (it needs firmware ${problem.minimum} or later, it has ${problem.firmware}), then connect again.`;
		case 'failed':
			// The subscribe fails when the service or its characteristics aren't
			// there (§2.6.2).
			if (problem.step === 'subscription' && entry) {
				return `This doesn't look like a ${entry.model}: ${problem.message}`;
			}
			return `Couldn't connect (${problem.step}): ${problem.message}`;
	}
}

/** One readout row. */
export interface ReadoutRow {
	readonly label: string;
	readonly value: string;
}

const STATE_TEXT: Record<GrinderState, string> = {
	[GrinderState.Idle]: 'Idle',
	[GrinderState.Grinding]: 'Grinding',
	[GrinderState.Purging]: 'Purging',
	[GrinderState.Busy]: 'Busy',
	[GrinderState.Updating]: 'Updating firmware',
	[GrinderState.Warning]: 'Warning',
	[GrinderState.Unknown]: 'Unknown'
};

/**
 * The live readout: what the reading carries, in the units the capabilities
 * give, then the firmware and the serial's last four characters (the serial
 * is shown from memory only, never stored or logged).
 */
export function grinderReadout(
	reading: GrinderReading | null,
	caps: GrinderCapabilities | null,
	info: ConnectedGrinderView['info']
): ReadoutRow[] {
	const rows: ReadoutRow[] = [];
	if (reading) {
		const state =
			reading.state === GrinderState.Unknown && reading.raw_state
				? reading.raw_state
				: STATE_TEXT[reading.state];
		rows.push({ label: 'State', value: state });
		if (reading.setting) {
			const unit = caps?.setting?.unit ?? '';
			rows.push({ label: 'Grind', value: unit ? `${reading.setting.value} ${unit}` : reading.setting.value });
		}
		if (reading.grind_rpm != null) rows.push({ label: 'Speed', value: `${reading.grind_rpm} rpm` });
		if (reading.feed_rpm != null) rows.push({ label: 'Feed', value: `${reading.feed_rpm} rpm` });
		if (reading.preset) {
			rows.push({
				label: 'Preset',
				value: reading.preset.type === 'Manual' ? 'Manual' : `Preset ${reading.preset.content.index + 1}`
			});
		}
		if (reading.humidity_pct != null) rows.push({ label: 'Humidity', value: `${reading.humidity_pct} %` });
		if (reading.total_grinds != null) {
			rows.push({ label: 'Lifetime grinds', value: reading.total_grinds.toLocaleString() });
		}
	}
	if (info?.firmware) rows.push({ label: 'Firmware', value: info.firmware });
	if (info?.serial) rows.push({ label: 'Serial', value: `…${info.serial.slice(-4)}` });
	return rows;
}

/** "Last grind 9.8 s at 480 μm" for the run the link last completed, or null. */
export function lastRunText(view: ConnectedGrinderView, caps: GrinderCapabilities | null): string | null {
	const run = view.lastRun;
	if (!run) return null;
	const what = run.kind === 'Purge' ? 'Last purge' : 'Last grind';
	const secs = `${(run.duration_ms / 1000).toFixed(1)} s`;
	const unit = caps?.setting?.unit ? ` ${caps.setting.unit}` : '';
	const at = run.kind !== 'Purge' && run.setting ? ` at ${run.setting.value}${unit}` : '';
	return `${what} ${secs}${at}`;
}
