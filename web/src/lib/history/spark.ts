/**
 * `#lib/history/spark.js` — a row's spark, read back (#310 P4w, SPEC §4).
 *
 * The core writes each row's chart into its summary as base64 of a few bytes
 * (`de1_domain::shot_summary`, whose module docs give the layout): a version,
 * flags, a tick count, then 32 points each of pressure, flow and weight, each
 * `0..=254` for 0 to 1 of its scale or 255 for a gap, then the stage ticks,
 * each a place `0..=255` in the shot. The row charts draw from this alone, so
 * a list loads no samples and makes no wasm call per row.
 */

/** Points per channel. */
export const SPARK_POINTS = 32;
/** A point with no value. */
const SPARK_GAP = 255;
/** The format this build reads. */
const SPARK_VERSION = 1;
/** Flag: drawn from the machine's samples. */
const SPARK_MACHINE = 1;
/** Flag: some point has a weight. */
const SPARK_WEIGHT = 2;
/** Flag: drawn from a brew's scale series. */
const SPARK_BREW = 4;

/** A spark, read back: each point 0 to 1 of its channel's scale, or `null` for a gap. */
export interface Spark {
	/** Drawn from the machine's samples. */
	readonly machine: boolean;
	/** Drawn from a brew's scale series (no pressure; flow in g/s). */
	readonly brew: boolean;
	/** Some point has a weight. */
	readonly hasWeight: boolean;
	readonly pressure: readonly (number | null)[];
	readonly flow: readonly (number | null)[];
	readonly weight: readonly (number | null)[];
	/** Each stage tick's place in the brew, 0 to 1. */
	readonly ticks: readonly number[];
}

function bytesOf(text: string): Uint8Array | null {
	try {
		const bin = atob(text);
		const out = new Uint8Array(bin.length);
		for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
		return out;
	} catch {
		return null;
	}
}

/** Read a spark back, or `null` when there is none or it isn't one this build reads. */
export function decodeSpark(spark: string | null | undefined): Spark | null {
	if (!spark) return null;
	const bytes = bytesOf(spark);
	if (!bytes || bytes.length < 3 || bytes[0] !== SPARK_VERSION) return null;
	const flags = bytes[1];
	const tickCount = bytes[2];
	if (bytes.length !== 3 + 3 * SPARK_POINTS + tickCount) return null;
	const channel = (i: number): (number | null)[] =>
		Array.from(bytes.subarray(3 + i * SPARK_POINTS, 3 + (i + 1) * SPARK_POINTS), (v) => (v === SPARK_GAP ? null : v / 254));
	return {
		machine: (flags & SPARK_MACHINE) !== 0,
		brew: (flags & SPARK_BREW) !== 0,
		hasWeight: (flags & SPARK_WEIGHT) !== 0,
		pressure: channel(0),
		flow: channel(1),
		weight: channel(2),
		ticks: Array.from(bytes.subarray(3 + 3 * SPARK_POINTS), (v) => v / 255)
	};
}
