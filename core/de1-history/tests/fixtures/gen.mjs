// The History filter fixture (#310 C1): 300 real-shaped rows in both shells'
// row shapes, the live bean library they resolve through, and the queries the
// shells' own filters and the core's reference rules all run.
//
//   node core/de1-history/tests/fixtures/gen.mjs
//
// Deterministic: the same files every run. After a change here, regenerate the
// shells' goldens (see `reference-golden.svelte.vitest.ts` and
// `HistoryReferenceGoldenTest.kt`).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const NOW = 1_790_000_000_000;
const HOUR = 3_600_000;
const DAY = 24 * HOUR;

// mulberry32
let seed = 0x5eed_c1;
function rnd() {
	seed |= 0;
	seed = (seed + 0x6d2b79f5) | 0;
	let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
	t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
	return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const pick = (xs) => xs[Math.floor(rnd() * xs.length)];
const chance = (p) => rnd() < p;
const round1 = (x) => Math.round(x * 10) / 10;
const hex = (n, len) => n.toString(16).padStart(len, '0').slice(-len);
const rhex = (len) => Array.from({ length: len }, () => hex(Math.floor(rnd() * 16), 1)).join('');

const LIVE = {
	beans: [
		{ beanId: 'bean:a', name: 'Gesha Village', roaster: 'Sey', tags: ['fruity', 'comp'] },
		{ beanId: 'bean:b', name: 'Etiopía Guji', roaster: 'Onyx', tags: ['floral'] },
		{ beanId: 'bean:c', name: 'Etiopía Guji', roaster: 'Onyx', tags: [] },
		{ beanId: 'bean:d', name: 'Colômbia El Paraíso', roaster: 'Tim Wendelboe', tags: ['chocolate'] },
		{ beanId: 'bean:e', name: 'Red Brick', roaster: 'Square Mile', tags: ['milk'] },
		// Renamed since its shots: the live name decides the coffee.
		{ beanId: 'bean:f', name: 'Kenya Kiambu', roaster: 'Sey', tags: [] }
	],
	methods: []
};

/** The bean snapshot a shot froze, by bag. */
const SNAPSHOTS = {
	'bean:a': { name: 'Gesha Village', roasterName: 'Sey', tags: ['fruity', 'comp'] },
	'bean:b': { name: 'Etiopía Guji', roasterName: 'Onyx', tags: ['floral'] },
	'bean:c': { name: 'Etiopía Guji', roasterName: 'Onyx' },
	'bean:d': { name: 'Colômbia El Paraíso', roasterName: 'Tim Wendelboe', tags: ['chocolate'] },
	'bean:e': { name: 'Red Brick', roasterName: 'Square Mile', tags: ['milk'] },
	'bean:f': { name: 'Kenya AA', roasterName: 'Sey' },
	// Gone from the library: the snapshot decides.
	'bean:g': { name: 'Ruby', roasterName: 'Prodigal' }
};
const BAGS = ['bean:a', 'bean:a', 'bean:b', 'bean:c', 'bean:d', 'bean:e', 'bean:f', 'bean:g'];

const PROFILES = ['Adaptive v3', 'Best Practice', 'Turbo Bloom', 'Londinium', 'Café Crème', 'blooming espresso', 'Extractamundo Dos!'];
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
const BREWS = [
	{ method: 'pourover', recipe: 'Hoffmann V60' },
	{ method: 'pourover', recipe: null },
	{ method: 'aeropress', recipe: 'Inverted' },
	{ method: 'french_press', recipe: null },
	{ method: 'karlsbad_kanne', recipe: null }
];

const shots = [];
let at = NOW - 2 * HOUR;
for (let i = 0; i < 300; i++) {
	// Two runs of equal times, as two shots logged in the same millisecond.
	if (i !== 21 && i !== 101 && i !== 102) at -= Math.floor(9 * HOUR + rnd() * 5 * HOUR);
	const brew = chance(0.18) ? pick(BREWS) : null;
	const bagRoll = rnd();
	const bag = bagRoll < 0.1 ? null : bagRoll < 0.14 ? 'legacy' : pick(BAGS);
	let bean = null;
	if (bag === 'legacy') bean = { name: 'Market Blend', roasterName: null };
	else if (bag) bean = { beanId: bag, ...SNAPSHOTS[bag], ...(chance(0.3) ? { grinder: 'Niche Zero' } : {}) };
	const rating = chance(0.25) ? null : chance(0.06) ? 0 : 1 + Math.floor(rnd() * 5);
	const dose = brew ? 15 : chance(0.08) ? null : round1(17.5 + rnd() * 2);
	const finalWeight = brew ? null : round1(32 + rnd() * 8);
	const yieldOut = brew ? (chance(0.5) ? round1(200 + rnd() * 30) : null) : chance(0.15) ? null : finalWeight;
	const peakWeight = finalWeight == null ? null : round1(finalWeight + rnd() * 1.5);
	const manual = brew != null && chance(0.4);
	const duration = brew ? (manual ? 0 : Math.round(180_000 + rnd() * 80_000)) : Math.round(25_000 + rnd() * 20_000);
	const sampleCount = brew ? 0 : 100 + Math.floor(rnd() * 120);
	const s = {
		id: `shot:${hex(at, 12).slice(0, 8)}-${hex(at, 12).slice(8)}-7${rhex(3)}-8${rhex(3)}-${rhex(12)}`,
		completedAt: at,
		profileName: brew ? null : chance(0.05) ? null : pick(PROFILES),
		brewMethod: brew?.method ?? null,
		recipeName: brew?.recipe ?? null,
		rating,
		dose,
		yieldOut,
		waterG: brew ? 250 : null,
		notes: chance(0.6) ? pick(NOTES) : null,
		nextPlan: chance(0.2) ? pick(['go finer', 'Dose 18.5', 'Grind +2']) : null,
		barista: chance(0.7) ? pick(['Sam', 'Zoë', 'Ana']) : null,
		drinker: chance(0.2) ? 'Ben' : null,
		bean,
		// An older row's legacy flat bean label (web only).
		legacyBeans: i > 240 && bean ? [bean.roasterName, bean.name].filter(Boolean).join(' · ') : null,
		grinderModel: chance(0.75) ? pick(['Niche Zero', 'EK43', 'Lagom P64']) : null,
		tags: chance(0.35) ? pick(TAGS) : [],
		pinned: chance(0.06),
		visualizerId: !brew && chance(0.5) ? `${rhex(8)}-${rhex(4)}-${rhex(4)}-${rhex(4)}-${rhex(12)}` : null,
		duration,
		summary: {
			v: 1,
			sampleCount,
			brewSampleCount: 0,
			peakWeight,
			finalWeight,
			peakPressure: brew ? 0 : round1(8 + rnd() * 2),
			peakTemp: brew ? 0 : round1(90 + rnd() * 4),
			spark: null
		}
	};
	shots.push(s);
}
// The store's order: newest first, ties by id descending.
shots.sort((a, b) => b.completedAt - a.completedAt || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0));

/** The web's `ShotRow`: the core's stored-shot shape without heavy parts, plus its summary. */
function webRow(s) {
	return {
		formatVersion: 3,
		id: s.id,
		completedAt: s.completedAt,
		profileName: s.profileName,
		stopReason: s.brewMethod ? null : 'Weight',
		metadata: {
			dose: s.dose,
			yieldOut: s.yieldOut,
			beans: s.legacyBeans,
			grinderSetting: null,
			notes: s.notes,
			nextPlan: s.nextPlan,
			rating: s.rating,
			tds: null,
			extractionYield: null,
			waterG: s.waterG,
			...(s.barista ? { barista: s.barista } : {}),
			...(s.drinker ? { drinker: s.drinker } : {})
		},
		record: { duration: s.duration },
		...(s.bean ? { bean: s.bean } : {}),
		...(s.grinderModel ? { grinderModel: s.grinderModel } : {}),
		...(s.tags.length ? { tags: s.tags } : {}),
		visualizerId: s.visualizerId,
		deletedAt: null,
		...(s.pinned ? { pinned: true } : {}),
		...(s.brewMethod ? { brewMethod: s.brewMethod } : {}),
		...(s.recipeName ? { recipeName: s.recipeName } : {}),
		summary: s.summary
	};
}

/** Android's `row_json`: the Kotlin `StoredShot`'s flat shape without heavy parts, plus its summary. */
function androidRow(s) {
	const row = {
		id: s.id,
		completedAtMs: s.completedAt,
		durationMs: s.duration,
		yieldG: s.yieldOut ?? s.summary.finalWeight,
		doseG: s.dose,
		peakPressure: s.summary.peakPressure,
		peakTemp: s.summary.peakTemp,
		peakWeightG: s.summary.peakWeight,
		profileName: s.profileName,
		bean: s.bean,
		rating: s.rating,
		notes: s.notes,
		nextPlan: s.nextPlan,
		barista: s.barista,
		drinker: s.drinker,
		grinderModel: s.grinderModel,
		tags: s.tags,
		visualizerId: s.visualizerId,
		pinned: s.pinned,
		brewMethod: s.brewMethod,
		recipeName: s.recipeName,
		waterG: s.waterG,
		summary: s.summary
	};
	for (const k of Object.keys(row)) if (row[k] == null) delete row[k];
	return row;
}

const QUERIES = [];
const q = (name, over) =>
	QUERIES.push({ name, range: 'all', text: '', profile: null, bean: null, method: null, tags: [], pinnedOnly: false, sort: { key: 'date', dir: 'desc' }, ...over });
for (const key of ['date', 'rating', 'profile', 'bean', 'yield', 'time']) {
	for (const dir of ['desc', 'asc']) q(`sort-${key}-${dir}`, { sort: { key, dir } });
}
q('range-7d', { range: '7d' });
q('range-30d-rating-desc', { range: '30d', sort: { key: 'rating', dir: 'desc' } });
q('profile-turbo', { profile: 'Turbo Bloom' });
q('pinned', { pinnedOnly: true });
q('method-espresso', { method: 'espresso' });
q('method-pourover-time-asc', { method: 'pourover', sort: { key: 'time', dir: 'asc' } });
q('bag-b', { bean: 'bean:b' });
q('coffee-guji', { bean: 'coffee:onyx|etiopía guji' });
q('coffee-guji-rating-asc', { bean: 'coffee:onyx|etiopía guji', sort: { key: 'rating', dir: 'asc' } });
q('coffee-renamed', { bean: 'coffee:sey|kenya kiambu' });
q('coffee-renamed-old-name', { bean: 'coffee:sey|kenya aa' });
q('coffee-gone', { bean: 'coffee:prodigal|ruby' });
q('coffee-no-bag-id', { bean: 'coffee:|market blend' });
q('tag-dial-in', { tags: ['dial-in'] });
q('tag-bag-fruity', { tags: ['fruity'] });
q('tags-dial-in-sour', { tags: ['dial-in', 'sour'] });
q('text-sour', { text: 'sour' });
q('text-sey-upper', { text: ' SEY ' });
q('text-cafe-accented', { text: 'café' });
q('text-cafe-plain', { text: 'cafe' });
q('text-zoe-plain', { text: 'zoe' });
q('text-two-chars', { text: 'go' });
q('text-dial', { text: 'dial' });
q('text-bag-tag-floral', { text: 'floral' });
q('text-legacy-label', { text: 'sey · kenya' });
q('text-niche', { text: 'niche' });
q('text-recipe', { text: 'hoffmann' });
q('text-method-label', { text: 'v60' });
q('text-free-method', { text: 'karlsbad' });
q('text-cjk', { text: '苦いが' });
q('text-roaster-profile-sort', { text: 'onyx', sort: { key: 'profile', dir: 'asc' } });
q('combo-30d-espresso-yield-desc', { range: '30d', method: 'espresso', sort: { key: 'yield', dir: 'desc' } });
q('combo-pinned-bean-asc', { pinnedOnly: true, sort: { key: 'bean', dir: 'asc' } });

const jsonl = (rows) => rows.map((r) => JSON.stringify(r)).join('\n') + '\n';
fs.writeFileSync(path.join(DIR, 'rows.web.jsonl'), jsonl(shots.map(webRow)));
fs.writeFileSync(path.join(DIR, 'rows.android.jsonl'), jsonl(shots.map(androidRow)));
fs.writeFileSync(path.join(DIR, 'live.json'), JSON.stringify(LIVE, null, '\t') + '\n');
fs.writeFileSync(path.join(DIR, 'queries.json'), JSON.stringify({ now: NOW, queries: QUERIES }, null, '\t') + '\n');
console.log(`${shots.length} rows, ${QUERIES.length} queries`);
