/**
 * `$lib/bean/roaster-mark` — derive a deterministic two-letter mark and a
 * tone colour for a roaster, so the avatars on the bean tile, drawer hero
 * and roaster card all look the same whatever surface renders them.
 *
 * The {@link Roaster} type does not (yet) store a mark or tone — the
 * design's mock data baked these in by hand. To avoid bloating the schema
 * for a derived value we compute them here from `roaster.name`:
 *
 * - **mark** is the initials of the first one or two name words (skipping
 *   the / `Coffee` / `Roasters` boilerplate the bag labels are full of).
 *   `"Onyx Coffee Lab"` → `OL`, `"April"` → `A`, `"Sey Coffee"` → `S`.
 *
 * - **tone** is a hash-bucketed pick from a fixed palette of warm,
 *   muted colours that read against both the dark and light Crema
 *   surfaces. Same name → same colour, every render, every device.
 *
 * Callers that want to override either value can store the result in
 * `roaster.metadata` and pass it through — this module is just the
 * deterministic default.
 */

import type { Roaster } from './model';

/**
 * The fixed tone palette. Picked to harmonise with the copper accent:
 * each colour is muted, warm-ish, and high-enough contrast against both
 * the light paper and the dark espresso surfaces. Order matters — the
 * hash maps modulo `TONES.length`, so an append-safe addition lives at
 * the tail of the list.
 */
const TONES = [
	'#C44E3F', // brick red
	'#4A6FA5', // dusty blue
	'#6B8C5F', // sage
	'#D89030', // amber
	'#C7763B', // copper (matches brand)
	'#8A5C3F', // walnut
	'#3A4C5E', // slate
	'#A35538', // brick orange
	'#5A6F5C', // forest
	'#9A6B8C' // muted plum
];

/**
 * Words to drop when picking the mark — common bag-label boilerplate
 * that would otherwise eat the actual roastery name in the initials.
 * Lowercased for case-insensitive matching.
 */
const STOPWORDS = new Set([
	'coffee',
	'coffees',
	'cafe',
	'café',
	'roasters',
	'roaster',
	'roasting',
	'roastery',
	'roastworks',
	'co',
	'co.',
	'inc',
	'inc.',
	'lab',
	'labs',
	'company',
	'the',
	'and',
	'&'
]);

/**
 * Two-letter (or one-letter, for single-word names) initialled mark.
 * Falls back to the first character of the trimmed name; empty / blank
 * input returns `'?'`. Always uppercase.
 */
export function roasterMark(name: string): string {
	const trimmed = (name ?? '').trim();
	if (!trimmed) return '?';
	const words = trimmed
		.split(/\s+/)
		.map((w) => w.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, ''))
		.filter((w) => w.length > 0 && !STOPWORDS.has(w.toLowerCase()));
	if (words.length === 0) {
		// Every word was a stopword (e.g. "Coffee Co") — fall back to the
		// first character of the original trimmed string.
		return trimmed.charAt(0).toUpperCase();
	}
	if (words.length === 1) {
		return words[0].charAt(0).toUpperCase();
	}
	return (words[0].charAt(0) + words[1].charAt(0)).toUpperCase();
}

/**
 * Deterministic tone-from-name. A tiny djb2 hash bucketed into the
 * tone palette. Empty / blank input returns the copper accent.
 */
export function roasterTone(name: string): string {
	const trimmed = (name ?? '').trim().toLowerCase();
	if (!trimmed) return 'var(--copper-500)';
	let hash = 5381;
	for (let i = 0; i < trimmed.length; i += 1) {
		hash = ((hash << 5) + hash + trimmed.charCodeAt(i)) | 0;
	}
	const idx = Math.abs(hash) % TONES.length;
	return TONES[idx];
}

/**
 * Combined helper — returns `{ mark, tone }` for a roaster. Safe to call
 * with `null` (returns a neutral `'?'` / copper pair so the avatar still
 * renders for a roasterless bag).
 */
export function roasterMarkTone(roaster: Roaster | null): {
	mark: string;
	tone: string;
} {
	if (!roaster) return { mark: '?', tone: 'var(--copper-500)' };
	return { mark: roasterMark(roaster.name), tone: roasterTone(roaster.name) };
}
