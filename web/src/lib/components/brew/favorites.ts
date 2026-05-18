/**
 * `favorites` — the Quick Sheet's pinned-profile sample data.
 *
 * PLACEHOLDER DATA. The core does not yet expose a profile library to the web
 * shell, so the favorites strip shows the design's own sample set (the
 * `FAVORITES` array from `quick-controls.jsx`). When the core gains a profile
 * model — a later porting step — this list is replaced by the real library.
 */

import type { SparkShape } from './QSparkline.svelte';

/** One favorite profile chip. */
export interface FavoriteProfile {
	/** Stable id. */
	readonly id: string;
	/** Display name (serif). */
	readonly name: string;
	/** Brew ratio label, e.g. "1:2.4". */
	readonly ratio: string;
	/** Dose, grams — shown in the profile meta line. */
	readonly dose: number;
	/** Which sparkline curve the chip draws. */
	readonly shape: SparkShape;
	/** Bean / origin, for the profile meta line. */
	readonly bean: string;
	/** Pre-infusion seconds, for the header meta line. */
	readonly preinf: number;
	/** Yield target, grams, for the header meta line. */
	readonly yield: number;
	/** Brew temperature, °C, for the header meta line. */
	readonly brewTemp: number;
}

/**
 * The sample favorites — design placeholder data until the core exposes a
 * real profile library.
 */
export const SAMPLE_FAVORITES: readonly FavoriteProfile[] = [
	{
		id: 'rao80',
		name: 'Rao 80%',
		ratio: '1:2.4',
		dose: 18,
		shape: 'rao',
		bean: 'Onyx Monarch',
		preinf: 8,
		yield: 36.4,
		brewTemp: 93.0
	},
	{
		id: 'blooming',
		name: 'Blooming',
		ratio: '1:2.0',
		dose: 18,
		shape: 'blooming',
		bean: 'Heart Stereo',
		preinf: 12,
		yield: 36.0,
		brewTemp: 94.5
	},
	{
		id: 'decline',
		name: 'Decline',
		ratio: '1:2.3',
		dose: 18,
		shape: 'decline',
		bean: 'Onyx Monarch',
		preinf: 6,
		yield: 41.4,
		brewTemp: 92.0
	},
	{
		id: 'classic',
		name: 'Classic 9 bar',
		ratio: '1:2.0',
		dose: 18,
		shape: 'classic',
		bean: 'Verve Ethiopia',
		preinf: 4,
		yield: 36.0,
		brewTemp: 93.0
	},
	{
		id: 'turbo',
		name: 'Turbo',
		ratio: '1:3.0',
		dose: 14,
		shape: 'turbo',
		bean: 'Sey Kenya',
		preinf: 0,
		yield: 42.0,
		brewTemp: 91.0
	},
	{
		id: 'cold',
		name: 'Cold extract',
		ratio: '1:5.0',
		dose: 16,
		shape: 'cold',
		bean: 'Sey Geisha',
		preinf: 0,
		yield: 80.0,
		brewTemp: 88.0
	}
];
