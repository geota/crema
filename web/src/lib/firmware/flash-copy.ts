/**
 * `$lib/firmware/flash-copy` — English copy for the firmware-update flow:
 * phases, blockers, failures and the ETA. The Android shell's
 * `FirmwareFlashCopy.kt` carries the same strings.
 */

import type { De1State } from '$lib/ble';
import {
	DecentFirmwareChannel,
	FlashPhase,
	type FlashBlocker,
	type FlashFailure,
	type FlashStatus
} from '$lib/core';

/** The alpha warning, one point per line. */
export const ALPHA_POINTS: readonly string[] = [
	'Firmware updates in Crema are alpha and have not yet been tried on a real DE1. Updating takes about 15 to 25 minutes.',
	"Crema writes only the DE1's spare memory bank. Decent's apps report that the DE1 switches to it only after checking every byte, so a failed update leaves the current firmware in place.",
	'Keep this device charging and near the DE1, and keep Crema open and in front until it finishes.',
	"You'll need to switch the DE1 off and on at the end."
];

/** "Stable" / "Nightly (early access)". */
export function channelLabel(channel: DecentFirmwareChannel): string {
	switch (channel) {
		case DecentFirmwareChannel.Stable:
			return 'Stable';
		case DecentFirmwareChannel.Beta:
			return 'Beta';
		case DecentFirmwareChannel.Nightly:
			return 'Nightly (early access)';
	}
}

/** The heading for a phase. */
export function phaseLabel(phase: FlashPhase): string {
	switch (phase) {
		case FlashPhase.Preparing:
			return 'Putting the DE1 to sleep';
		case FlashPhase.Erasing:
			return 'Erasing the spare memory bank';
		case FlashPhase.Uploading:
			return 'Sending firmware';
		case FlashPhase.Verifying:
			return 'DE1 is checking the firmware';
		case FlashPhase.AwaitingPowerCycle:
			return 'Switch the DE1 off and on';
		case FlashPhase.Succeeded:
			return 'Firmware updated';
		case FlashPhase.Failed:
			return 'Update stopped';
		case FlashPhase.Cancelled:
			return 'Update cancelled';
	}
}

/** "About 14 min left" / "About 40 s left" / measured vs estimated. */
export function etaText(status: FlashStatus): string {
	const s = status.etaSeconds;
	if (s == null) return '';
	const about = status.etaIsEstimate ? 'About ' : '';
	if (s >= 90) return `${about}${Math.round(s / 60)} min left`;
	return `${about}${Math.max(1, s)} s left`;
}

/** Why the flow can't start. */
export function blockerText(b: FlashBlocker): string {
	switch (b.type) {
		case 'NotConnected':
			return 'Connect your DE1.';
		case 'NotReady':
			return 'Wait for the DE1 to finish connecting.';
		case 'ReadOnlyMirror':
			return 'This device mirrors another Crema. Update from the primary device.';
		case 'FlashInProgress':
			return 'A firmware update is already running.';
		case 'BuildUnknown':
			return "The DE1 hasn't reported its firmware build yet.";
		case 'ModelUnknown':
			return "The DE1 hasn't reported its model yet.";
		case 'UnsupportedModel':
			return b.content.model >= 128
				? "Bengle firmware can't be updated from Crema yet."
				: `Crema doesn't recognise this DE1 model (${b.content.model}), so it won't update it.`;
		case 'StateUnknown':
			return "The DE1 hasn't reported its state yet.";
		case 'MachineBusy':
			return 'Wait until the DE1 is idle or asleep.';
		case 'Refilling':
			return 'Refill the water tank first.';
		case 'MachineError':
			return 'The DE1 reports an error. Clear it first.';
		case 'ShotInProgress':
			return 'Wait for the shot, steam or hot water to finish.';
		case 'GuidedBrewActive':
			return 'Finish the guided brew first.';
		case 'ProfileUploading':
			return 'Wait for the profile upload to finish.';
		case 'MaintenanceRunning':
			return 'Wait for the cleaning or descaling cycle to finish.';
		case 'LowPower':
			return 'Plug this device in, or charge it above 50 %.';
	}
}

function hex24(n: number): string {
	return `0x${n.toString(16).padStart(6, '0')}`;
}

/** What went wrong, in one sentence. */
export function failureText(f: FlashFailure): string {
	switch (f.type) {
		case 'LinkLost':
			return 'The DE1 disconnected during the update.';
		case 'WriteFailed':
			return `A Bluetooth write failed (${f.content.message}).`;
		case 'AckTimeout':
			return 'The DE1 stopped answering.';
		case 'EraseTimeout':
			return "The DE1 didn't finish erasing within a minute.";
		case 'VerifyFailed':
			return `The DE1 found a damaged block at ${hex24(f.content.first_error)} and kept its current firmware.`;
	}
}

/** Under every failure before the verify request. */
export const STILL_SAFE =
	"Decent's apps report that the DE1 stays on its previous firmware when an update stops before it is checked. The DE1 was left asleep. You can retry; the update starts again from the beginning.";

/** When the verify outcome was lost. */
export const UNCONFIRMED =
	'The connection dropped or the DE1 didn\'t answer while it was checking the firmware. The DE1 may be running either build. Switch it off and on and Crema will read which.';

/** While an interrupted erase may still be running. */
export const ERASE_SETTLING =
	'The DE1 may still be erasing. Crema keeps other commands away from it until it finishes (up to a minute).';

/** The cancel confirmation. */
export function cancelQuestion(installedBuild: number | null): string {
	return installedBuild === null
		? 'Stop the update? Your DE1 stays on its current build.'
		: `Stop the update? Your DE1 stays on build ${installedBuild}.`;
}

/** The exit confirmation on the power-cycle step. */
export const EXIT_QUESTION =
	"Exit without confirming the build? Switch the DE1 off and on before using it, and don't start another update until it has restarted.";

/** Under the power-cycle steps: what Crema sees right now. */
export function waitingText(s: FlashStatus): string {
	const old = s.restartedOnBuild;
	let now: string;
	if (s.foreignMachine) {
		now = `A different DE1 is connected. Crema only accepts the build from the DE1 it updated: disconnect this one and connect that one, or exit.`;
	} else if (old !== null && old !== undefined && s.verifyUnconfirmed) {
		now = `The DE1 reports build ${old}, not ${s.targetBuild}. If it still says ${old} after you switch it off and on, the update didn't take: your DE1 is fine on build ${old}, and you can exit and try again.`;
	} else if (old !== null && old !== undefined) {
		now = `The DE1 reconnected but reports build ${old}, not ${s.targetBuild}. Switch it off at the back, wait 10 seconds and switch it on again.`;
	} else if (s.machineRestarted && s.linkUp) {
		now = 'The DE1 is connected. Reading its build…';
	} else if (s.machineRestarted) {
		now = 'The DE1 is offline. Waiting for it to come back and report its build…';
	} else {
		now = 'Waiting for the DE1 to be switched off…';
	}
	const unsure =
		s.identityUnknown && !s.foreignMachine
			? " Crema can't confirm this is the DE1 it updated (no serial number to compare)."
			: '';
	return `${now}${unsure} Crema keeps other commands away from the DE1 until then.`;
}

/**
 * Which link control the power-cycle step offers (audit r7 M-1 / L-1, r8
 * L-1). The modal covers the app while the flash holds the DE1, so the step
 * carries them: Disconnect when a different DE1 is connected; Connect
 * when no DE1 is linked; a quieter Connect now while an auto-reconnect loops
 * (it may be chasing the wrong DE1, and a connect replaces it); nothing
 * while a connect the user started is still settling.
 */
export function powerCycleLinkAction(
	s: FlashStatus,
	de1State: De1State
): 'connect' | 'connect-now' | 'disconnect' | null {
	if (de1State === 'ready') return s.foreignMachine ? 'disconnect' : null;
	if (de1State === 'connecting' || de1State === 'subscribing') return null;
	return de1State === 'reconnecting' ? 'connect-now' : 'connect';
}
