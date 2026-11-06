/**
 * `#lib/history/brew-date.js` — when a logged brew may be dated, and the order
 * the history keeps. Android holds the same rules in `BrewDate.kt`; the core
 * takes its rows as they come, newest first (`brew_log_seeds`,
 * `brews_remaining_estimate`), so that order is what makes the newest row the
 * last brew.
 */

export const BREW_DATE_INVALID = 'Pick a date.';
export const BREW_DATE_FUTURE = 'A brew can’t be dated in the future.';

/** What's wrong with dating a brew `at`, or `null` when the date is fine. */
export function brewDateError(at: number, now: number): string | null {
	if (!(at > 0)) return BREW_DATE_INVALID;
	return at > now ? BREW_DATE_FUTURE : null;
}

/** The message for a new brew dated before everything a full history keeps. */
export function brewDateTooOld(cap: number): string {
	return `History keeps the newest ${cap} brews, and this date is older than all of them.`;
}

/**
 * Whether a new row dated `at` survives the cap. A full history drops its
 * oldest row, so a brew older than all of them would be gone as it saved.
 */
export function fitsHistory(
	shots: readonly { completedAt: number }[],
	at: number,
	cap: number
): boolean {
	return shots.length < cap || at >= (shots.at(-1)?.completedAt ?? at);
}

/** Newest first; rows with the same date keep their order, so a new row leads. */
export function newestFirst<T extends { completedAt: number }>(shots: readonly T[]): T[] {
	return [...shots].sort((a, b) => b.completedAt - a.completedAt);
}

/** `ms` as a `datetime-local` value, in local time. */
export function toLocalInput(ms: number): string {
	const d = new Date(ms);
	const pad = (n: number) => String(n).padStart(2, '0');
	return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** A `datetime-local` value as epoch ms; `NaN` for a blank or unreadable one. */
export function fromLocalInput(value: string): number {
	return new Date(value).getTime();
}
