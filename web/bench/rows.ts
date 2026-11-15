/**
 * Real-shaped history rows for the browser benchmark and the W0 browser tests
 * (#310 PLAN B1, W0). A TypeScript port of `core/de1-history/benches/history/seed.rs`,
 * itself a port of `core/de1-history/tests/fixtures/gen.mjs`: the same RNG
 * (mulberry32), seed and draws in the same order, so `rows(n)` here is the
 * native benchmark's `rows(n)`, and `rows(300)` is the fixture's
 * `rows.web.jsonl` (the browser job checks that).
 *
 * As in `seed.rs`, the gap between shots shrinks so a large history spans at
 * most about four years, and the runs of equal times repeat every 300 rows.
 */

const NOW = 1_790_000_000_000;
const HOUR = 3_600_000;
const SPAN_HOURS = 4 * 365 * 24;
const MEAN_GAP_HOURS = 11.5;

type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
export type Row = { [k: string]: Json };

function rng(seed: number) {
	let s = seed | 0;
	const next = (): number => {
		s = (s + 0x6d2b79f5) | 0;
		let t = Math.imul(s ^ (s >>> 15), 1 | s);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
	const below = (n: number): number => Math.min(Math.floor(next() * n), n - 1);
	return {
		next,
		below,
		pick: <T>(xs: readonly T[]): T => xs[below(xs.length)],
		chance: (p: number): boolean => next() < p,
		hex: (len: number): string =>
			Array.from({ length: len }, () => below(16).toString(16)).join('')
	};
}

const round1 = (x: number): number => Math.round(x * 10) / 10;
const hex12 = (n: number): string => n.toString(16).padStart(12, '0').slice(-12);

const SNAPSHOTS: Record<string, [string, string, string[] | null]> = {
	'bean:a': ['Gesha Village', 'Sey', ['fruity', 'comp']],
	'bean:b': ['Etiopía Guji', 'Onyx', ['floral']],
	'bean:c': ['Etiopía Guji', 'Onyx', null],
	'bean:d': ['Colômbia El Paraíso', 'Tim Wendelboe', ['chocolate']],
	'bean:e': ['Red Brick', 'Square Mile', ['milk']],
	'bean:f': ['Kenya AA', 'Sey', null]
};
const snapshot = (bag: string): [string, string, string[] | null] =>
	SNAPSHOTS[bag] ?? ['Ruby', 'Prodigal', null];

const BAGS = ['bean:a', 'bean:a', 'bean:b', 'bean:c', 'bean:d', 'bean:e', 'bean:f', 'bean:g'];
const PROFILES = [
	'Adaptive v3',
	'Best Practice',
	'Turbo Bloom',
	'Londinium',
	'Café Crème',
	'blooming espresso',
	'Extractamundo Dos!'
];
const NOTES = [
	'Too sour, go finer',
	'Lovely, sweet finish',
	'Café-style milk drink',
	'Bitter end, pulled long',
	'Zuckersüß',
	'苦いが美味しい',
	'Naïve fruit bomb',
	'channeling?',
	''
];
const TAGS = [['dial-in'], ['milk'], ['dial-in', 'sour'], ['guest'], ['Café']];
const BREWS: [string, string | null][] = [
	['pourover', 'Hoffmann V60'],
	['pourover', null],
	['aeropress', 'Inverted'],
	['french_press', null],
	['karlsbad_kanne', null]
];

/** `n` rows in the web's `ShotRow` shape, newest first (ties by id descending). */
export function rows(n: number): Row[] {
	const r = rng(0x005eedc1);
	const squeeze = Math.min(SPAN_HOURS / (n * MEAN_GAP_HOURS), 1);
	let at = NOW - 2 * HOUR;
	const out: { at: number; id: string; row: Row }[] = [];
	for (let i = 0; i < n; i++) {
		const m = i % 300;
		if (m !== 21 && m !== 101 && m !== 102) {
			let gap = 9 * HOUR + r.next() * 5 * HOUR;
			if (squeeze < 1) gap *= squeeze;
			at -= Math.floor(gap);
		}
		const brew = r.chance(0.18) ? r.pick(BREWS) : null;
		const bagRoll = r.next();
		const bag = bagRoll < 0.1 ? null : bagRoll < 0.14 ? 'legacy' : r.pick(BAGS);
		let bean: [string, string | null, Row] | null = null;
		if (bag === 'legacy') {
			bean = ['Market Blend', null, { name: 'Market Blend', roasterName: null }];
		} else if (bag !== null) {
			const [name, roaster, tags] = snapshot(bag);
			const b: Row = { beanId: bag, name, roasterName: roaster };
			if (tags) b.tags = tags;
			if (r.chance(0.3)) b.grinder = 'Niche Zero';
			bean = [name, roaster, b];
		}
		const rating = r.chance(0.25) ? null : r.chance(0.06) ? 0 : 1 + r.below(5);
		const dose = brew ? 15 : r.chance(0.08) ? null : round1(17.5 + r.next() * 2);
		const finalWeight = brew ? null : round1(32 + r.next() * 8);
		const yieldOut = brew
			? r.chance(0.5)
				? round1(200 + r.next() * 30)
				: null
			: r.chance(0.15)
				? null
				: finalWeight;
		const peakWeight = finalWeight === null ? null : round1(finalWeight + r.next() * 1.5);
		const manual = brew !== null && r.chance(0.4);
		const duration = brew
			? manual
				? 0
				: Math.round(180_000 + r.next() * 80_000)
			: Math.round(25_000 + r.next() * 20_000);
		const sampleCount = brew ? 0 : 100 + r.below(120);
		const h = hex12(at);
		const id = `shot:${h.slice(0, 8)}-${h.slice(8)}-7${r.hex(3)}-8${r.hex(3)}-${r.hex(12)}`;
		const profileName = brew || r.chance(0.05) ? null : r.pick(PROFILES);
		const notes = r.chance(0.6) ? r.pick(NOTES) : null;
		const nextPlan = r.chance(0.2) ? r.pick(['go finer', 'Dose 18.5', 'Grind +2']) : null;
		const barista = r.chance(0.7) ? r.pick(['Sam', 'Zoë', 'Ana']) : null;
		const drinker = r.chance(0.2) ? 'Ben' : null;
		let legacyBeans: string | null = null;
		if (bean && i > 240) {
			legacyBeans = [bean[1], bean[0]].filter((s): s is string => !!s).join(' · ');
		}
		const grinderModel = r.chance(0.75) ? r.pick(['Niche Zero', 'EK43', 'Lagom P64']) : null;
		const tags = r.chance(0.35) ? r.pick(TAGS) : [];
		const pinned = r.chance(0.06);
		const visualizerId =
			!brew && r.chance(0.5)
				? `${r.hex(8)}-${r.hex(4)}-${r.hex(4)}-${r.hex(4)}-${r.hex(12)}`
				: null;
		const [peakPressure, peakTemp] = brew
			? [0, 0]
			: [round1(8 + r.next() * 2), round1(90 + r.next() * 4)];

		const metadata: Row = {
			dose,
			yieldOut,
			beans: legacyBeans,
			grinderSetting: null,
			notes,
			nextPlan,
			rating,
			tds: null,
			extractionYield: null,
			waterG: brew ? 250 : null
		};
		if (barista) metadata.barista = barista;
		if (drinker) metadata.drinker = drinker;
		const row: Row = {
			formatVersion: 3,
			id,
			completedAt: at,
			profileName,
			stopReason: brew ? null : 'Weight',
			metadata,
			record: { duration },
			visualizerId,
			deletedAt: null,
			summary: {
				v: 1,
				sampleCount,
				brewSampleCount: 0,
				peakWeight,
				finalWeight,
				peakPressure,
				peakTemp,
				spark: null
			}
		};
		if (bean) row.bean = bean[2];
		if (grinderModel) row.grinderModel = grinderModel;
		if (tags.length) row.tags = tags;
		if (pinned) row.pinned = true;
		if (brew) {
			row.brewMethod = brew[0];
			if (brew[1]) row.recipeName = brew[1];
		}
		out.push({ at, id, row });
	}
	out.sort((a, b) => b.at - a.at || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0));
	return out.map((o) => o.row);
}
