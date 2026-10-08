/**
 * The rail's DE1 / scale status buttons — state → look → tap action.
 *
 * Pure so the policy is unit-testable without mounting the rail:
 * - **disconnected** (idle / disconnected / failed): dot off, tap connects;
 * - **connecting** (connecting / subscribing): dot amber, tap does nothing;
 * - **reconnecting**: dot amber, tap = Retry now (never ends the attempts);
 * - **connected** (ready): dot green, tap opens a small menu whose
 *   Disconnect item is the only way to disconnect from the rail.
 */

import type { BleConnectionState } from '$lib/ble/connection-state';

export type RailDevice = 'de1' | 'scale';

export type RailStatus = 'disconnected' | 'connecting' | 'reconnecting' | 'connected';

export type RailAction = 'connect' | 'none' | 'retry' | 'menu';

/** Collapse the seven link states to the rail's four. */
export function railStatus(state: BleConnectionState): RailStatus {
	switch (state) {
		case 'ready':
			return 'connected';
		case 'connecting':
		case 'subscribing':
			return 'connecting';
		case 'reconnecting':
			return 'reconnecting';
		case 'idle':
		case 'disconnected':
		case 'failed':
			return 'disconnected';
	}
}

/** What a tap on the status button does. */
export function railAction(status: RailStatus): RailAction {
	switch (status) {
		case 'disconnected':
			return 'connect';
		case 'connecting':
			return 'none';
		case 'reconnecting':
			return 'retry';
		case 'connected':
			return 'menu';
	}
}

/** The status dot's modifier class: green, amber or off. */
export function railDotClass(status: RailStatus): 'is-on' | 'is-busy' | 'off' {
	if (status === 'connected') return 'is-on';
	if (status === 'disconnected') return 'off';
	return 'is-busy';
}

/** Phosphor name of the small corner badge. */
export function railCtaIcon(status: RailStatus): string {
	switch (status) {
		case 'connected':
			return 'check';
		case 'connecting':
			return 'spinner-gap';
		case 'reconnecting':
			return 'arrows-clockwise';
		case 'disconnected':
			return 'bluetooth';
	}
}

/** Tooltip + accessible name for the status button. */
export function railLabel(device: RailDevice, status: RailStatus): string {
	const Name = device === 'de1' ? 'DE1' : 'Scale';
	const name = device === 'de1' ? 'DE1' : 'scale';
	switch (status) {
		case 'disconnected':
			return `Click to connect ${name}`;
		case 'connecting':
			return `${Name} connecting…`;
		case 'reconnecting':
			return `${Name} reconnecting — click to retry now`;
		case 'connected':
			return `${Name} connected — click for options`;
	}
}
