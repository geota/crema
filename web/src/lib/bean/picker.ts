/**
 * `#lib/bean/picker.js` — what a bean picker lists, delegated to the core.
 *
 * Which bags show, in what order, and how a search reorders them live in
 * `de1_domain::bean_picker` (geota/crema#301), the same function Android
 * calls, so the two apps agree. This module carries the call across wasm.
 */

import { beanPicker as wasmBeanPicker } from '#lib/wasm/de1_wasm.js';
import type { BeanPickerQuery, BeanPickerResult } from '#lib/core/crema-core.js';
import type { Bean, Roaster } from './model';
import { libraryBeansJson, libraryRoastersJson } from './search';

/** One picker's rows, in display order. */
export interface BeanPickerRows {
	beans: Bean[];
	/** Whether "Include archived" could add anything; the toggle hides otherwise. */
	hasArchived: boolean;
}

/** What the picker is showing. */
export interface BeanPickerSelection {
	/** The bag the picker opened on: it leads, and stays listed when archived. */
	currentId: string | null;
	includeArchived: boolean;
	search: string;
}

function toRows(beans: readonly Bean[], json: string): BeanPickerRows {
	const result = JSON.parse(json) as BeanPickerResult;
	const byId = new Map(beans.map((b) => [b.id, b]));
	return {
		beans: result.ids.flatMap((id) => byId.get(id) ?? []),
		hasArchived: result.hasArchived
	};
}

function toQuery({ currentId, includeArchived, search }: BeanPickerSelection): string {
	const query: BeanPickerQuery = { currentId: currentId ?? undefined, includeArchived, search };
	return JSON.stringify(query);
}

/**
 * The rows for `selection`. A failing call degrades to the library unsearched
 * and unranked: a picker must never make the beans vanish.
 */
export function beanPickerRows(
	beans: readonly Bean[],
	roasters: readonly Roaster[],
	selection: BeanPickerSelection
): BeanPickerRows {
	try {
		const json = wasmBeanPicker(
			libraryBeansJson(beans),
			libraryRoastersJson(roasters),
			toQuery(selection)
		);
		return toRows(beans, json);
	} catch (err) {
		console.error('Bean picker failed', err);
		const { currentId, includeArchived } = selection;
		const live = beans.filter((b) => b.deletedAt == null);
		return {
			beans: live.filter((b) => includeArchived || b.archivedAt == null || b.id === currentId),
			hasArchived: live.some((b) => b.archivedAt != null && b.id !== currentId)
		};
	}
}

/**
 * The core's picker order over a list the caller already filtered: `activeId`
 * first, then favourites, then the rest oldest first. Stringifies `beans`
 * itself: a filtered list is a new array each time, and would only evict the
 * library from the shared memo.
 */
export function rankBeansForPicker(beans: readonly Bean[], activeId: string | null): Bean[] {
	const query = toQuery({ currentId: activeId, includeArchived: true, search: '' });
	try {
		return toRows(beans, wasmBeanPicker(JSON.stringify(beans), '[]', query)).beans;
	} catch (err) {
		console.error('Bean ranking failed', err);
		return [...beans];
	}
}
