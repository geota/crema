/**
 * `#lib/history/phone-list.js` — the phone shot log's day groups (Android
 * `dayLabel`). Rows are grouped under Today / Yesterday / This week / Earlier
 * only under the date sort: rating or name orders interleave days, so a group
 * would repeat.
 */

const DAY_MS = 24 * 60 * 60 * 1000;

/** Which group a shot from `at` falls in, as of `now`. */
export function dayGroup(at: number, now: Date): string {
	const start = new Date(now);
	start.setHours(0, 0, 0, 0);
	const today = start.getTime();
	if (at >= today) return 'Today';
	if (at >= today - DAY_MS) return 'Yesterday';
	if (at >= today - 6 * DAY_MS) return 'This week';
	return 'Earlier';
}

export interface ShotGroup<T> {
	/** Group heading, or `null` when the sort has no groups. */
	label: string | null;
	shots: T[];
}

/** Split an ordered list into runs of the same day group. */
export function groupShots<T extends { completedAt: number }>(
	shots: readonly T[],
	byDate: boolean,
	now: Date
): ShotGroup<T>[] {
	if (!byDate) return shots.length > 0 ? [{ label: null, shots: [...shots] }] : [];
	const groups: ShotGroup<T>[] = [];
	for (const shot of shots) {
		const label = dayGroup(shot.completedAt, now);
		const last = groups.at(-1);
		if (last?.label === label) last.shots.push(shot);
		else groups.push({ label, shots: [shot] });
	}
	return groups;
}
