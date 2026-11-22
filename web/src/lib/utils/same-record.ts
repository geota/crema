/**
 * `#lib/utils/same-record.js` — value equality for stored rows (JSON-shaped).
 */

/**
 * Whether two rows hold the same values, whatever their key order (an
 * absent key and `undefined` count as the same, as in their stored JSON).
 */
export function sameRecord(a: unknown, b: unknown): boolean {
	if (a === b) return true;
	if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return false;
	if (Array.isArray(a) !== Array.isArray(b)) return false;
	if (Array.isArray(a) && Array.isArray(b)) {
		return a.length === b.length && a.every((v, i) => sameRecord(v, b[i]));
	}
	const ra = a as Record<string, unknown>;
	const rb = b as Record<string, unknown>;
	const keys = new Set([...Object.keys(ra), ...Object.keys(rb)]);
	for (const k of keys) if (!sameRecord(ra[k], rb[k])) return false;
	return true;
}
