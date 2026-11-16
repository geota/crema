/**
 * `#lib/history-source/stage2.js` — W5 switches the backup seam (#310 PLAN
 * R1w, W5): on stage 2, backups and restores read and write `history.db`
 * through the SQLite source, with its walk, its delete hold and its
 * merge-then-remove restore (S6, M3). P3's source stays for stage 1.
 *
 * - **`apartFromRestores`** guards each hold's release: it runs only while no
 *   restore runs in any tab, since a restore's upsert of a held row ends the
 *   hold (REVIEW-468 W5-1).
 * - **`settleHolds()` at boot**: a walk whose tab closed left rows held and
 *   hidden; the first boot that can releases them (rows first, then the
 *   details of exactly those, S6's order since R1w).
 * - Details are written without `crema-history`'s `meta`: it is the old copy
 *   on stage 2 (rule 3).
 */
import { bootHistoryDb } from '#lib/history-db/start.js';
import { HistoryDb } from '#lib/history/history-db.js';
import { summarize } from '#lib/history/summary.js';
import type { LockManagerLike } from '#lib/restore/tab-safety.js';
import { idbDetails } from './details';
import { apartFromRestores } from './guards';
import { clientPort } from './port';
import { setHistorySource } from './source';
import { SqliteSource } from './sqlite';

/** How long the boot's release waits for `history.db` to answer. */
const SETTLE_WAIT_MS = 30_000;

/** Switches the seam when this tab is on stage 2; resolves to whether it did. */
export async function useStage2Seam(): Promise<boolean> {
	const booted = await bootHistoryDb();
	if (!booted || booted.choice.stage !== 2 || !booted.client) return false;
	const { client } = booted;
	const locks = typeof navigator !== 'undefined' ? (navigator.locks as unknown as LockManagerLike | undefined) : undefined;
	const source = new SqliteSource({
		db: clientPort(client),
		details: idbDetails(new HistoryDb({ expectExisting: () => true }), { bump: false }),
		summarize,
		apartFromRestores: apartFromRestores(locks)
	});
	setHistorySource(source);
	// The holds a closed tab's walk left: released once the owner answers.
	const ok = await new Promise<boolean>((resolve) => {
		if (client.status.health === 'ok') return resolve(true);
		const t = setTimeout(() => {
			off();
			resolve(false);
		}, SETTLE_WAIT_MS);
		const off = client.onStatus((s) => {
			if (s.health !== 'ok') return;
			clearTimeout(t);
			off();
			resolve(true);
		});
	});
	if (ok) await source.settleHolds().catch(() => 0);
	return true;
}
