/**
 * A thin facade over the wasm relay bridge (relay v4 spec §3.6): the remote
 * side of the secure channel, for a future web couch client. No UI uses it
 * yet.
 *
 * The bridge returns `RelayStep` objects that live in wasm memory; `readStep`
 * copies one into plain JS and frees it, so callers never hold wasm handles.
 * Apply a step in order: send (in order), deliver, events, close.
 *
 * The facade takes the bridge's objects as structural types, so it works with
 * the production bundle (`#lib/wasm/de1_wasm.js`) and, in the vitest only,
 * with the test-seeds bundle (`pnpm wasm:test`). Never log sent bytes, codes,
 * keys or the channel binding.
 */
import type { RelayEvent } from '#lib/core/crema-core.js';

/** The wasm `RelayStep` methods the facade reads. */
export interface RelayStepHandle {
	sendCount(): number;
	sendIsText(i: number): boolean;
	sendBytes(i: number): Uint8Array;
	deliverCount(): number;
	deliver(i: number): Uint8Array;
	eventsJson(): string;
	closeCode(): number | undefined;
	closeReason(): string | undefined;
	free(): void;
}

/** One outgoing WebSocket message. */
export interface RelayOutgoing {
	/** Send as a text message (only the ClientHello). */
	text: boolean;
	bytes: Uint8Array;
}

/** A step, copied out of wasm memory. */
export interface RelayStepData {
	send: RelayOutgoing[];
	deliver: Uint8Array[];
	events: RelayEvent[];
	close?: { code: number; reason: string };
}

/** Copies a wasm `RelayStep` into plain JS and frees it. */
export function readStep(step: RelayStepHandle): RelayStepData {
	try {
		const send: RelayOutgoing[] = [];
		for (let i = 0; i < step.sendCount(); i++) {
			send.push({ text: step.sendIsText(i), bytes: step.sendBytes(i) });
		}
		const deliver: Uint8Array[] = [];
		for (let i = 0; i < step.deliverCount(); i++) {
			deliver.push(step.deliver(i));
		}
		const events = JSON.parse(step.eventsJson()) as RelayEvent[];
		const code = step.closeCode();
		const close = code === undefined ? undefined : { code, reason: step.closeReason() ?? '' };
		return { send, deliver, events, close };
	} finally {
		step.free();
	}
}

/** Zero-fills a buffer that held key bytes (`privateForStorage()` and its copies). */
export function wipe(bytes: Uint8Array): void {
	bytes.fill(0);
}
