/**
 * #420 FC4-N1 (probe L): a daily copy cut short at any byte is never counted
 * as a whole copy with a row missing. The gzip must end, and the rows must
 * number what the header says.
 */
import { expect, it } from 'vitest';
import { listRowsSnapshots, readRowsSnapshot, writeRowsSnapshot, type SnapshotRow } from './rows-snapshot';
import { memoryFs } from './archive-test-kit';

it('a 250-row copy cut at each of its byte lengths reads as a copy only with all 250 rows', async () => {
	const rows = Array.from(
		{ length: 250 },
		(_, i) =>
			({
				id: `shot:${i}`,
				completedAt: i,
				record: { duration: 1, samples: [] },
				summary: { v: 1, sampleCount: 0, brewSampleCount: 0, peakPressure: 0, peakTemp: 0 },
				note: 'x'.repeat(i % 40)
			}) as unknown as SnapshotRow
	);
	const fs = memoryFs();
	expect(await writeRowsSnapshot(rows, Date.UTC(2026, 10, 14, 12), fs.fsRoot, null)).toBe(true);
	const dir = await (await fs.fsRoot()).getDirectoryHandle('crema-history-snapshot');
	const name = (await listRowsSnapshots(fs.fsRoot))[0].name;
	const whole = new Uint8Array(await (await (await dir.getFileHandle(name)).getFile()).arrayBuffer());
	let counted = 0;
	const short: number[] = [];
	for (let cut = 0; cut < whole.length; cut++) {
		const w = await (await dir.getFileHandle(name)).createWritable();
		await w.write(whole.slice(0, cut));
		await w.close();
		const got = await readRowsSnapshot(fs.fsRoot, name).catch(() => null);
		if (!got) continue;
		counted += 1;
		if (got.rows.length !== 250) short.push(cut);
	}
	expect(short, 'cuts counted whole with a row missing').toEqual([]);
	// Only cuts inside the gzip trailer can read at all, and those hold every row.
	expect(counted).toBeLessThan(16);
}, 120_000);
