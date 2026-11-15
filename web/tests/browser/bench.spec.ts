/**
 * W0 size and speed (#310 PLAN W0, B1): the history wasm stays under C4's
 * 1 MB gzip; and in Chrome, through the Worker, B1's browser bench
 * (`bench/history-db.html`) at 5,000, 20,000 and 100,000 rows meets B1's
 * budgets for the first page: "Open to first page painted" and "`count` +
 * first page, any sort". Every other line of B1's table is recorded, not
 * gated: the budgets are the reference device's, so a desktop under them
 * says little about a tablet, and the tablet run is on the owner's checklist.
 *
 * Also checks that `bench/rows.ts` is the native benchmark's generator: its
 * 300 rows are the fixture's `rows.web.jsonl`.
 *
 * `W0_BENCH_ROWS` picks the sizes (comma-separated); `W0_BENCH_STRICT=1` gates
 * every budgeted line, not just the first page.
 */
import fs from 'node:fs';
import zlib from 'node:zlib';
import { rows } from '../../bench/rows.ts';
import { HISTORY_JOURNAL } from '../../src/lib/history-db/journal.ts';
import { BASE, expect, test } from './fixtures.ts';

const SIZES = (process.env.W0_BENCH_ROWS ?? '5000,20000,100000').split(',').map(Number);
const GATED = new Set(['open', 'first']);

test('the history wasm is under 1 MB gzip', async ({ browserName }, testInfo) => {
	test.skip(browserName !== 'chromium', 'once is enough');
	const wasm = fs.readFileSync(new URL('../../src/lib/history-db/wasm/de1_history_wasm_bg.wasm', import.meta.url));
	const gz = zlib.gzipSync(wasm, { level: 9 }).length;
	console.log(`W0 size de1_history_wasm_bg.wasm ${wasm.length} B raw, ${gz} B gzip -9`);
	await testInfo.attach('wasm-size', { contentType: 'application/json', body: JSON.stringify({ raw: wasm.length, gzip: gz }) });
	expect(gz).toBeLessThan(1024 * 1024);
});

test('bench/rows.ts reproduces the native generator (the 300-row fixture)', async ({ browserName }) => {
	test.skip(browserName !== 'chromium', 'once is enough');
	const fixture = fs
		.readFileSync(new URL('../../../core/de1-history/tests/fixtures/rows.web.jsonl', import.meta.url), 'utf8')
		.split('\n')
		.filter((l) => l.trim())
		.map((l) => JSON.parse(l));
	expect(rows(300)).toEqual(fixture);
});

for (const n of SIZES) {
	test(`B1 in the browser at ${n.toLocaleString('en')} rows: the first page within budget`, async ({
		profile,
		browserName
	}, testInfo) => {
		test.skip(browserName !== 'chromium', 'B1 in the browser is Chrome through the Worker');
		test.setTimeout(20 * 60_000);
		const page = await profile.newPage();
		await page.goto(`${BASE}/bench/history-db.html`);
		await page.waitForFunction(() => !!window.bench);
		const journal = (process.env.W0_JOURNAL ?? HISTORY_JOURNAL) as 'truncate' | 'exclusiveWal';
		const result = await page.evaluate(([n, j]) => window.bench.run(n, j), [n, journal] as const);
		const md = await page.evaluate((r) => window.bench.markdown(r), result);
		console.log(`\n${md}\n`);
		await testInfo.attach(`bench-${n}`, { contentType: 'text/markdown', body: md });
		if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${md}\n\n`);
		const strict = process.env.W0_BENCH_STRICT === '1';
		const over = result.lines.filter(
			(l) => l.ms !== undefined && l.budgetMs !== undefined && l.ms > l.budgetMs && (strict || GATED.has(String(l.key)))
		);
		expect(over, md).toEqual([]);
	});
}
