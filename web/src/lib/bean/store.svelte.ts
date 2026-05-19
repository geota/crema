/**
 * `$lib/bean/store` — the **current-bean** store.
 *
 * Holds exactly one {@link Bean}: the bag of coffee in use right now. Unlike
 * `lib/profiles` (a library of many) this is a single record — there is one
 * "current bean" at a time. Crema's web shell is a static, client-only PWA —
 * there is no server — so `localStorage` is the store, via the shared
 * `$lib/utils/storage` helpers, exactly like the profile and history stores.
 *
 * The store is a Svelte 5 `$state` class; obtain the singleton with
 * {@link getBeanStore}. It loads synchronously from `localStorage`.
 */

import { readJson, writeJson } from '$lib/utils/storage';
import { type Bean, migrateBean } from './model';

/** localStorage key for the current bean (a single `Bean`). */
const BEAN_KEY = 'crema.bean.current.v1';

/** The reactive current-bean store. One instance per app — {@link getBeanStore}. */
export class BeanStore {
	/**
	 * The current bean. Loaded from localStorage, then run through
	 * {@link migrateBean} so an old-shape payload (`{ name, roastLevel: word }`)
	 * is upgraded leniently rather than crashing.
	 */
	private bean = $state<Bean>(migrateBean(readJson<unknown>(BEAN_KEY, null)));

	/** The current bean. Reactive: any setter re-renders the bean card. */
	get current(): Bean {
		return this.bean;
	}

	/** Persist the current bean to localStorage. */
	private persist(): void {
		writeJson(BEAN_KEY, this.bean);
	}

	/** Replace the whole current bean and persist. */
	set(bean: Bean): void {
		this.bean = bean;
		this.persist();
	}

	/** Patch one or more fields of the current bean and persist. */
	update(patch: Partial<Bean>): void {
		this.bean = { ...this.bean, ...patch };
		this.persist();
	}

	/** Update the roaster (Visualizer `bean.brand`) and persist. */
	setRoaster(roaster: string): void {
		this.update({ roaster });
	}

	/** Update the bean type (Visualizer `bean.type`) and persist. */
	setType(type: string): void {
		this.update({ type });
	}

	/** Update the roast date (`yyyy-mm-dd` or `null`) and persist. */
	setRoastedOn(roastedOn: string | null): void {
		this.update({ roastedOn });
	}

	/** Update the 1..10 roast level (`null` to clear) and persist. */
	setRoastLevel(roastLevel: number | null): void {
		this.update({ roastLevel });
	}
}

/** The process-wide singleton — one current bean shared by every route. */
let store: BeanStore | undefined;

/** Get the shared {@link BeanStore}, creating it on first call. */
export function getBeanStore(): BeanStore {
	if (!store) store = new BeanStore();
	return store;
}
