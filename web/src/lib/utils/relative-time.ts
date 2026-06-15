/**
 * `$lib/utils/relative-time` — the one relative "ago" label.
 *
 * Both the History list (per recorded shot) and the Profile library (a
 * profile's `lastUsed`) need a compact `Nm / Nh / Nd / Nw / Nmo / Ny ago`
 * label from a past timestamp. One implementation, matching the Android shell's
 * `relativeAgo` (`coffee.crema.ui.Format`) thresholds exactly — so the two
 * shells read identically.
 *
 * NOTE — known parity quirk: days in [360, 364] format as `0y ago` (months
 * reaches 12 and falls through to years = `floor(days / 365)` = 0). This is
 * shared with the Android shell on purpose; both must change together if it is
 * ever "fixed".
 */

/**
 * Compact relative-past label for a timestamp [ms] (epoch millis), measured
 * against [asOf] (defaults to now). Future timestamps clamp to `just now`.
 */
export function relativeAgo(ms: number, asOf: number = Date.now()): string {
	const deltaMs = Math.max(0, asOf - ms);
	const min = Math.floor(deltaMs / 60_000);
	if (min < 1) return 'just now';
	if (min < 60) return `${min}m ago`;
	const hours = Math.floor(min / 60);
	if (hours < 24) return `${hours}h ago`;
	const days = Math.floor(hours / 24);
	if (days < 7) return `${days}d ago`;
	const weeks = Math.floor(days / 7);
	if (weeks < 5) return `${weeks}w ago`;
	const months = Math.floor(days / 30);
	if (months < 12) return `${months}mo ago`;
	return `${Math.floor(days / 365)}y ago`;
}
