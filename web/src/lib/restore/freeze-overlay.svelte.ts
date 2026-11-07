/**
 * `#lib/restore/freeze-overlay` — what the frozen-tab overlay shows: that a
 * restore runs in another tab, and a shot this tab couldn't keep, if any.
 */

let shown = $state(false);
let unsaved = $state.raw<Record<string, unknown> | null>(null);

export const freezeOverlay = {
	get shown(): boolean {
		return shown;
	},
	/** A shot finished here that couldn't be kept for the reload. */
	get unsaved(): Record<string, unknown> | null {
		return unsaved;
	},
	show(): void {
		shown = true;
	},
	keep(shot: Record<string, unknown>): void {
		unsaved = shot;
	},
	/** The user has the shot: the reload may go ahead. */
	saved(): void {
		unsaved = null;
	},
	resetForTest(): void {
		shown = false;
		unsaved = null;
	}
};
