/**
 * `$lib/firmware/flash-copy` — English copy for the firmware-update flow:
 * phases, blockers, failures and the ETA. The Android shell's
 * `FirmwareFlashCopy.kt` carries the same strings.
 */

import {
	DecentFirmwareChannel,
	FlashPhase,
	type FlashBlocker,
	type FlashFailure,
	type FlashStatus
} from '$lib/core';

/** The alpha warning, one point per line. */
export const ALPHA_POINTS: readonly string[] = [
	'Firmware updates in Crema are alpha. Updating takes about 15 to 25 minutes.',
	'If anything fails, your DE1 keeps its current firmware: Crema only writes the spare memory bank, and the DE1 switches to it only after checking every byte.',
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
				: "Crema can't update this DE1 model yet (it follows Decent's list: DE1PRO, DE1XL, DE1XXL, DE1XXXL).";
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
		case 'VerifyTimeout':
			return "The DE1 didn't report a verification result.";
		case 'VerifyUnconfirmed':
			return `The connection dropped while the DE1 was checking, and it came back on build ${f.content.reported_build}.`;
	}
}

/** The reassurance under every failure. */
export const STILL_SAFE =
	'Your DE1 is still running its previous firmware. You can retry; the update starts again from the beginning.';
