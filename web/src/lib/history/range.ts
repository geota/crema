/**
 * `#lib/history/range.js` — History's date ranges, the same four as Android
 * (`filterAndSortShots` in `ShotFilter.kt`, labels in `HistoryScreen.kt` and
 * `PhoneHistoryScreen.kt`). Both shells hold this logic; it is not in the core.
 */

const DAY_MS = 24 * 60 * 60 * 1000;
const MINUTE_MS = 60 * 1000;

export const HISTORY_RANGES = [
	{ id: 'all', label: 'All time' },
	{ id: '30d', label: '30 days' },
	{ id: '7d', label: '7 days' },
	{ id: 'today', label: 'Today' }
] as const;

export type HistoryRange = (typeof HISTORY_RANGES)[number]['id'];

/**
 * The earliest `completedAt` a range keeps, or `null` for no limit. `today`
 * starts at local midnight; `7d` and `30d` are rolling windows ending now, so
 * `7d` reaches back to this time of day a week ago.
 */
export function rangeCutoff(range: HistoryRange, now: number): number | null {
	switch (range) {
		case 'today': {
			const start = new Date(now);
			start.setHours(0, 0, 0, 0);
			return start.getTime();
		}
		case '7d':
			return now - 7 * DAY_MS;
		case '30d':
			return now - 30 * DAY_MS;
		default:
			return null;
	}
}

/** `now` floored to the minute, Android's `minuteBucket`. */
export function minuteBucket(now: number): number {
	return now - (now % MINUTE_MS);
}

/** Milliseconds from `now` to the next minute; never 0, so a timer always moves on. */
export function msToNextMinute(now: number): number {
	return MINUTE_MS - (now % MINUTE_MS);
}
