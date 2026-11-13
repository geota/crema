/**
 * `#lib/capture/limit.js` — how many raw captures this device keeps (#311).
 *
 * A raw capture can't be rebuilt, so the rule is the core's
 * (`captures_to_drop`): the newest N captured shots, plus every pinned shot's,
 * whatever its age. `off` records none. Device-local: not in backups, not
 * relayed, like the Drive sign-in.
 *
 * Existing installs keep today's limit (captures followed the 300-shot history,
 * so 300); a new install starts at 50. The first read stores whichever applies.
 */

import type { CaptureKeep } from '#lib/core/crema-core.js';
import { readJson, writeJson } from '../utils/storage.ts';

/** localStorage key (cleared by a factory reset with every other `crema.*` key). */
export const CAPTURE_KEEP_KEY = 'crema.captures.keep.v1';

/** The choices the setting offers. */
export const CAPTURE_KEEP_CHOICES = ['off', '10', '50', '100', '300', 'all'] as const;
export type CaptureKeepChoice = (typeof CAPTURE_KEEP_CHOICES)[number];

/** What a new install starts with. */
export const NEW_INSTALL_CAPTURE_KEEP: CaptureKeepChoice = '50';
/** What an install that recorded shots before the setting keeps: today's limit. */
export const EXISTING_CAPTURE_KEEP: CaptureKeepChoice = '300';

const isChoice = (v: unknown): v is CaptureKeepChoice =>
	typeof v === 'string' && (CAPTURE_KEEP_CHOICES as readonly string[]).includes(v);

/**
 * The limit this device keeps. `existingInstall`: the history held rows before
 * the setting existed (the caller knows; the history key is the history's).
 */
export function readCaptureKeep(existingInstall: () => boolean): CaptureKeepChoice {
	const stored = readJson<CaptureKeepChoice | null>(CAPTURE_KEEP_KEY, null, {
		valid: (v): v is CaptureKeepChoice | null => v === null || isChoice(v)
	});
	if (stored) return stored;
	const first = existingInstall() ? EXISTING_CAPTURE_KEEP : NEW_INSTALL_CAPTURE_KEEP;
	writeJson(CAPTURE_KEEP_KEY, first);
	return first;
}

export function writeCaptureKeep(choice: CaptureKeepChoice): void {
	writeJson(CAPTURE_KEEP_KEY, choice);
}

/** The core's form of a choice. */
export function coreCaptureKeep(choice: CaptureKeepChoice): CaptureKeep {
	if (choice === 'off') return { kind: 'Off' };
	if (choice === 'all') return { kind: 'All' };
	return { kind: 'Newest', details: { count: Number(choice) } };
}

/** The setting's label for a choice. */
export function captureKeepLabel(choice: CaptureKeepChoice): string {
	return choice === 'off' ? 'Off' : choice === 'all' ? 'All' : choice;
}
