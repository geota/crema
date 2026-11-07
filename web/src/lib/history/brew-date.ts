/**
 * `#lib/history/brew-date.js` — when a logged brew may be dated, and the order
 * the history keeps. Android holds the same rules in `BrewDate.kt`; the core
 * takes its rows as they come, newest first (`brew_log_seeds`,
 * `brews_remaining_estimate`), so that order is what makes the newest row the
 * last brew.
 */

const HOUR_MS = 60 * 60 * 1000;

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
 * Whether a new row dated `at` survives the cap, given `live` rows newest
 * first. Only the newest `cap` stay, and a new row leads its ties, so it has to
 * reach the row at the cut. A list can be over the cap, so that isn't the last.
 */
export function fitsHistory(
	live: readonly { completedAt: number }[],
	at: number,
	cap: number
): boolean {
	const cut = live[cap - 1];
	return cut == null || at >= cut.completedAt;
}

/**
 * `shots`, newest first, cut to `cap` live rows. Tombstones don't count and
 * always stay: they carry a remote delete that hasn't gone out yet. `keepId`, a
 * row just made here, stays whatever its date, for a device whose clock is
 * behind its history: the oldest other row goes instead.
 */
export function cappedHistory<T extends { id: string; deletedAt?: unknown }>(
	shots: readonly T[],
	cap: number,
	keepId?: string
): T[] {
	const room = keepId == null ? cap : cap - 1;
	let live = 0;
	return shots.filter((s) => s.deletedAt != null || s.id === keepId || ++live <= room);
}

/** How the cap ended a restore or an import. */
export interface HistoryInsert<T> {
	/** The added rows still in the history, newest first. */
	kept: T[];
	/** Rows of any kind the cap dropped, existing ones included. */
	dropped: number;
}

/** What to say when the cap dropped rows, or nothing when it didn't. */
export function historyDroppedNotice(dropped: number, cap: number): string {
	return dropped > 0
		? ` History keeps the newest ${cap}, so ${dropped} older shot${dropped === 1 ? ' was' : 's were'} dropped.`
		: '';
}

/**
 * What to say when a full history refused rows a pull brought. The next Sync
 * won't look for them, because the cursor has moved past them: Re-sync does.
 */
export function historyFullNotice(refused: number, cap: number): string {
	return `History is full at ${cap} shots, so ${refused} shot${refused === 1 ? '' : 's'} from Visualizer ${refused === 1 ? 'wasn\u2019t' : 'weren\u2019t'} added. After freeing up space, use Re-sync shots to fetch ${refused === 1 ? 'it' : 'them'}.`;
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

/**
 * A `datetime-local` value as epoch ms; `NaN` for a blank or unreadable one.
 * In the hour an autumn clock change repeats, the browser reads the first pass;
 * with `near`, the value's old date, an edit stays on the pass it was on.
 */
export function fromLocalInput(value: string, near?: number): number {
	const first = new Date(value).getTime();
	const second = first + HOUR_MS;
	const repeated = toLocalInput(second) === value;
	const samePass =
		near != null && new Date(near).getTimezoneOffset() === new Date(second).getTimezoneOffset();
	return repeated && samePass ? second : first;
}
