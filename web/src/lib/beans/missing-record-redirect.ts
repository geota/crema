/**
 * `#lib/beans/missing-record-redirect.js` — when an editor route should bounce
 * an unknown id back to the list (review 03 P3-6).
 *
 * The bean and roaster edit routes redirect to the list when their id does not
 * resolve. Done as "redirect whenever the record is missing", the redirect
 * also fired after the editor deleted the record itself, racing the editor's
 * own exit: a deep-linked editor leaves with `goto(returnTo)` (e.g. the
 * roaster-filtered shelf), the route's later `goto('/beans')` superseded it,
 * and the filter was lost; from the list it raced `history.back()`.
 *
 * The rule: redirect only while the id has never resolved in this view. Once
 * the record was shown, a later miss means it was deleted, and whoever
 * deleted it navigates.
 */

/** Make a guard for one route instance; call it with the current id and whether it resolved. */
export function missingRecordRedirect(): (id: string, found: boolean) => boolean {
	let shown: string | null = null;
	return (id, found) => {
		if (!id) return false;
		if (found) {
			shown = id;
			return false;
		}
		return shown !== id;
	};
}
