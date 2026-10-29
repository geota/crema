/**
 * An explicit selection: a shot the user was sent to (`?shot=` deep link) or
 * just saved (Log brew), together with the filter scope that was active when
 * it was made ({@link filterScopeKey}).
 */
export interface SelectionPin {
	readonly id: string;
	readonly scope: string;
}

/**
 * A stable key for the History list's filter inputs. A pin holds only while
 * this key is unchanged, so touching any filter turns an explicit selection
 * back into an ordinary one.
 */
export function filterScopeKey(inputs: {
	readonly q: string;
	readonly profile: string;
	readonly bean: string;
	readonly method: string;
	readonly tags: readonly string[];
	readonly range: string;
}): string {
	return JSON.stringify([
		inputs.q.trim(),
		inputs.profile,
		inputs.bean,
		inputs.method,
		[...inputs.tags],
		inputs.range
	]);
}

/**
 * The shot the History detail pane shows: the picked one while it is still in
 * the filtered list, else the first filtered shot. A search or a filter that
 * hides a *browsed* pick hands the pane to the first match instead of keeping
 * a shot the list no longer shows. Mirrors the tablet's rule in
 * `HistoryScreen.kt` (`shots.firstOrNull { it.id == selectedId } ?: shots.firstOrNull()`).
 *
 * An *explicit* pick ({@link SelectionPin}: a deep link, a just-saved brew
 * log) wins over a filter remembered from an earlier visit: while the filter
 * scope is the one it was made under, the pinned shot shows even when that
 * filter hides it (resolved through `lookup`, the whole store). Changing any
 * filter afterwards ends the pin.
 */
export function selectedShot<T extends { id: string }>(
	filtered: readonly T[],
	selectedId: string | null,
	explicit?: {
		readonly pin: SelectionPin | null;
		readonly scope: string;
		readonly lookup: (id: string) => T | undefined;
	}
): T | undefined {
	if (selectedId) {
		const inList = filtered.find((s) => s.id === selectedId);
		if (inList) return inList;
		const pin = explicit?.pin;
		if (pin && pin.id === selectedId && pin.scope === explicit.scope) {
			const pinned = explicit.lookup(selectedId);
			if (pinned) return pinned;
		}
	}
	return filtered[0];
}
