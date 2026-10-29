/**
 * `#lib/beans/editor-path.js` — the bean editor's route, with the id encoded.
 * Bean ids can come from imports and other apps, so they may hold `/`, `?` or
 * `#`; `resolve('/beans/[id]/edit', { id })` inserts a parameter raw (and
 * throws on a slash), so every caller builds the path through here.
 */

/** A URL search string appended to the path: empty, or `?…`. */
export type EditorQuery = '' | `?${string}`;

/** `beans/<encoded id>/edit<query>`, ready for `resolve()`. */
export function beanEditPath<Q extends EditorQuery = ''>(
	id: string,
	query: Q = '' as Q
): `beans/${string}/edit${Q}` {
	return `beans/${encodeURIComponent(id)}/edit${query}`;
}
