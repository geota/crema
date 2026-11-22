/**
 * `#lib/beans/unsaved-guard.js` — ask before an editor with unsaved changes
 * is left (save-boundaries review F2): the bag and roaster editors keep a
 * draft until Save, so leaving any other way (Back, a tab, a link) would drop
 * it silently.
 *
 * A navigation inside the app is held and the user asked "Discard unsaved
 * changes?"; Discard then repeats it. Closing or reloading the tab gets the
 * browser's own prompt. The editor's own Save, Cancel and Delete leave
 * through {@link UnsavedGuard.release} first, so they are never asked twice.
 */

import { beforeNavigate, goto } from '$app/navigation';
import { confirmDialog } from '#lib/components/shared/confirm-dialog.svelte.js';

export { sameRecord } from '#lib/utils/same-record.js';

/** The question an editor asks before its draft is dropped. */
export const DISCARD_CHANGES = { message: 'Discard unsaved changes?', confirmLabel: 'Discard' } as const;

export interface UnsavedGuard {
	/** The editor is leaving on purpose (Save, a confirmed Cancel, Delete). */
	release(): void;
}

/**
 * Guard the editor being initialised (call from its `<script>`): `isDirty`
 * says whether there is anything to lose; `onDiscard` runs when the user
 * discards by leaving.
 */
export function guardUnsavedChanges(isDirty: () => boolean, onDiscard?: () => void): UnsavedGuard {
	let released = false;
	beforeNavigate((nav) => {
		if (released || !isDirty()) return;
		nav.cancel();
		// The document unloads: cancelling it is what shows the browser's prompt.
		if (nav.willUnload) return;
		void (async () => {
			if (!(await confirmDialog(DISCARD_CHANGES))) return;
			released = true;
			onDiscard?.();
			if (nav.type === 'popstate') history.go(nav.delta);
			else if (nav.to) void goto(nav.to.url);
		})();
	});
	return {
		release: () => {
			released = true;
		}
	};
}

