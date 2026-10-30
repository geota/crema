/**
 * `#lib/history/crema-jsonl` — the shot-history export (Settings → Sharing)
 * as the core's Crema `crema/v1` JSONL, and its import. Android writes and
 * reads the same file (F10), so a history moves between the shells; the
 * core's `exportCremaJsonl` / `importCremaJsonl` own the format.
 */

import { exportCremaJsonl, importCremaJsonl } from '#lib/wasm/de1_wasm.js';
import type { StoredShot } from './model';

/** `shots` as a `crema/v1` JSONL: a header line, then one `shot` line each. */
export function shotHistoryJsonl(
	shots: readonly StoredShot[],
	exportedAt: number,
	cremaVersion: string
): string {
	return exportCremaJsonl(
		JSON.stringify({ beans: [], roasters: [], shots }),
		exportedAt,
		cremaVersion
	);
}

/** True when the first non-blank line of `text` is a `crema/v1` header. */
export function isCremaJsonl(text: string): boolean {
	const header = text.split('\n').find((line) => line.trim() !== '') ?? '';
	return header.includes('"crema/v1"');
}

/** The shots of a `crema/v1` JSONL — either shell's history export — parsed by the core. */
export function shotsFromCremaJsonl(text: string): StoredShot[] {
	const plan = JSON.parse(importCremaJsonl(text)) as { shots?: { storedShot: StoredShot }[] };
	return (plan.shots ?? []).map((s) => s.storedShot);
}
