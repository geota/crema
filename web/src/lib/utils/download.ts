/**
 * `$lib/utils/download` — browser-side download helpers.
 *
 * Pattern: build a `Blob`, hand it to the browser via an anchor
 * `<a download>` click, revoke the object URL once the click has been
 * dispatched. The 6 copies across the shell that did this inline now
 * share this helper.
 */

/**
 * Trigger a browser download for `blob` with the given filename.
 *
 * Defensive sequencing: attach the anchor to the DOM before clicking
 * (Firefox in particular requires this for the download to actually
 * fire), then remove it; revoke the object URL on a short timeout so
 * the browser has a moment to queue the download from the click event
 * (most browsers fire it synchronously, a few don't). 100 ms is far
 * more than enough for any browser to have started the download.
 * No-op outside the browser.
 */
export function downloadBlob(filename: string, blob: Blob): void {
	if (typeof window === 'undefined') return;
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = filename;
	document.body.appendChild(a);
	a.click();
	document.body.removeChild(a);
	setTimeout(() => URL.revokeObjectURL(url), 100);
}

/**
 * Format a `Date` as `YYYYMMDDTHHMM` — the timestamp prefix the shell
 * uses on every exported filename. `new Date()` (now) by default.
 */
export function filenameStamp(d: Date = new Date()): string {
	const pad = (n: number, w = 2): string => n.toString().padStart(w, '0');
	return (
		`${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}` +
		`T${pad(d.getHours())}${pad(d.getMinutes())}`
	);
}
