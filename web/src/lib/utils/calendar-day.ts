/**
 * `#lib/utils/calendar-day` — show a stored calendar day (`YYYY-MM-DD`).
 *
 * Bean dates (roasted, opened, frozen, defrosted) are calendar days with no
 * time zone. `new Date('2026-11-08')` reads one as midnight UTC, which west
 * of Greenwich is the evening before, so the day showed one short. A bare day
 * is read as local midnight here; anything else (a full timestamp) is left to
 * `Date`.
 */

const BARE_DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

/** The day as a local `Date`, or `null` when it can't be read. */
export function calendarDay(s: string): Date | null {
	const m = BARE_DAY.exec(s);
	const d = m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : new Date(s);
	return Number.isNaN(d.getTime()) ? null : d;
}

/** `Nov 8, 2026` for a stored day, or `—` when there is none or it can't be read. */
export function formatCalendarDay(s: string | null | undefined): string {
	if (!s) return '—';
	const d = calendarDay(s);
	return d ? d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' }) : '—';
}
