/**
 * `$lib/beans/editor-nav` — how the bean and roaster editors are entered and
 * left, so browser Back never walks back into an editor you just saved or
 * cancelled (#86).
 *
 * The Beans list opens an editor with {@link openEditor}, which tags the new
 * history entry. The editor leaves with {@link leaveEditor}: from a tagged
 * entry it steps BACK to the list entry it came from (the editor entry is
 * then forward history, not something Back returns to); from an untagged one
 * — a deep link, a reload, another page's "new bean" button — there is no
 * list entry behind it, so it replaces itself with the list instead.
 */

import { goto } from '$app/navigation';
import { page } from '$app/state';
import type { ResolvedPathname } from '$app/types';

/** A URL search string `resolve()` accepts after a path: empty, or `?…`. */
export type Query = '' | `?${string}`;

/** `?k=v&…` for the non-empty entries of `params`, else `''`. */
export function toQuery(params: URLSearchParams): Query {
	const qs = params.toString();
	return qs ? `?${qs}` : '';
}

/** Open an editor from the Beans list. `href` comes from `resolve(...)`. */
export function openEditor(href: ResolvedPathname): Promise<void> {
	return goto(href, { state: { fromBeansList: true } });
}

/** Leave an editor for `fallback` (a `resolve(...)`d list URL). */
export function leaveEditor(fallback: ResolvedPathname): void {
	if (page.state.fromBeansList) history.back();
	else void goto(fallback, { replaceState: true });
}
