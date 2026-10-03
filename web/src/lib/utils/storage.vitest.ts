/**
 * Crash safety of the shared localStorage helpers: an unreadable value is
 * kept aside (never read as empty and then overwritten), a value that can't
 * be kept aside is never overwritten, and a quota failure is reported.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BeanLibraryStore } from '$lib/bean/store.svelte';
import { RecipeStore } from '$lib/brew/recipes.svelte';
import { corruptMessage, keptAsideBundle } from '$lib/shell/storage-notices';
import {
	corruptAtKeyOf,
	corruptKeyOf,
	isJsonObject,
	onStorageNotice,
	readJson,
	readStored,
	resetStorageStateForTest,
	storageNoticeMessage,
	takeStorageNotices,
	writeJson,
	writeJsonChecked,
	type StorageNotice
} from './storage';

const KEY = 'crema.test.v1';

beforeEach(() => {
	localStorage.clear();
	resetStorageStateForTest();
	vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => vi.restoreAllMocks());

describe('readStored', () => {
	it('classifies absent, loaded and corrupt', () => {
		expect(readStored(KEY)).toEqual({ kind: 'absent' });
		localStorage.setItem(KEY, '[1,2]');
		expect(readStored(KEY)).toEqual({ kind: 'loaded', value: [1, 2] });
		localStorage.setItem(KEY, '[1,2');
		expect(readStored(KEY)).toMatchObject({ kind: 'corrupt', raw: '[1,2' });
	});
});

describe('readJson', () => {
	it('keeps a truncated value aside and the next save does not destroy it', () => {
		localStorage.setItem(KEY, '[{"id":"a"},{"id":"b"');
		expect(readJson<unknown[]>(KEY, [], { what: 'shot history' })).toEqual([]);
		expect(localStorage.getItem(corruptKeyOf(KEY))).toBe('[{"id":"a"},{"id":"b"');
		expect(localStorage.getItem(corruptAtKeyOf(KEY))).toMatch(/^\d{4}-\d\d-\d\dT/);
		expect(localStorage.getItem(KEY)).toBeNull();

		writeJson(KEY, []);
		expect(localStorage.getItem(corruptKeyOf(KEY))).toBe('[{"id":"a"},{"id":"b"');
		expect(takeStorageNotices()).toEqual([
			{ kind: 'corrupt', key: KEY, what: 'shot history', kept: true }
		]);
	});

	it('treats a parsed value of the wrong shape as corrupt', () => {
		localStorage.setItem(KEY, '{"not":"a list"}');
		expect(readJson(KEY, [], { valid: Array.isArray })).toEqual([]);
		expect(JSON.parse(localStorage.getItem(corruptKeyOf(KEY))!)).toEqual({ not: 'a list' });
		expect(takeStorageNotices()).toEqual([]); // no `what`: kept aside silently
	});

	it('passes good values through untouched', () => {
		localStorage.setItem(KEY, '{"a":1}');
		expect(readJson(KEY, {}, { valid: isJsonObject, what: 'settings' })).toEqual({ a: 1 });
		expect(localStorage.getItem(corruptKeyOf(KEY))).toBeNull();
		expect(takeStorageNotices()).toEqual([]);
	});

	it('never overwrites a corrupt value it could not keep aside', () => {
		localStorage.setItem(KEY, '{broken');
		const real = localStorage.setItem.bind(localStorage);
		const spy = vi.spyOn(localStorage, 'setItem').mockImplementation((k: string, v: string) => {
			if (k === corruptKeyOf(KEY)) throw new DOMException('full', 'QuotaExceededError');
			real(k, v);
		});
		expect(readJson(KEY, {}, { what: 'settings' })).toEqual({});
		spy.mockRestore();

		writeJson(KEY, {});
		expect(writeJsonChecked(KEY, {})).toBe(false);
		expect(localStorage.getItem(KEY)).toBe('{broken');
		const [n] = takeStorageNotices();
		expect(n).toEqual({ kind: 'corrupt', key: KEY, what: 'settings', kept: false });
		expect(storageNoticeMessage(n)).toContain('won’t be overwritten');
	});
});

describe('quota', () => {
	function fillUp() {
		vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
			throw new DOMException('full', 'QuotaExceededError');
		});
	}

	it('a swallowed write is reported once per key', () => {
		fillUp();
		const seen: StorageNotice[] = [];
		onStorageNotice((n) => seen.push(n));
		writeJson(KEY, { a: 1 });
		writeJson(KEY, { a: 2 });
		expect(seen).toEqual([{ kind: 'quota', key: KEY }]);
		expect(storageNoticeMessage(seen[0])).toMatch(/storage is full/i);
	});

	it('writeJsonChecked reports failure and can leave the message to the caller', () => {
		fillUp();
		expect(writeJsonChecked(KEY, 1, false)).toBe(false);
		expect(takeStorageNotices()).toEqual([]);
		expect(writeJsonChecked(KEY, 1)).toBe(false);
		expect(takeStorageNotices()).toEqual([{ kind: 'quota', key: KEY }]);
	});
});

describe('stores', () => {
	it('a damaged bean library is kept aside, not replaced by an empty one', () => {
		localStorage.setItem('crema.beans.v1', '{"schemaVersion":1,"beans":[{"id":"bean:1"');
		const store = new BeanLibraryStore();
		expect(store.beans).toEqual([]);
		store.clearAll(); // any save
		expect(localStorage.getItem(corruptKeyOf('crema.beans.v1'))).toBe(
			'{"schemaVersion":1,"beans":[{"id":"bean:1"'
		);
		expect(takeStorageNotices().map((n) => n.kind === 'corrupt' && n.what)).toEqual([
			'bean library'
		]);
	});

	it('a recipe list of the wrong shape is kept aside', () => {
		localStorage.setItem('crema.brewRecipes.v1', '"oops"');
		new RecipeStore();
		expect(localStorage.getItem(corruptKeyOf('crema.brewRecipes.v1'))).toBe('"oops"');
	});
});

describe('notices', () => {
	it('folds several unreadable stores into one line and bundles the kept copies', () => {
		const a = { kind: 'corrupt', key: 'k1', what: 'shot history', kept: true } as const;
		const b = { kind: 'corrupt', key: 'k2', what: 'bean library', kept: true } as const;
		expect(corruptMessage([a])).toBe(
			'Couldn’t read your shot history; the damaged data was kept aside.'
		);
		expect(corruptMessage([a, b])).toBe(
			'Couldn’t read your shot history and bean library; the damaged data was kept aside.'
		);
		localStorage.setItem(corruptKeyOf('k1'), '[1,');
		expect(JSON.parse(keptAsideBundle(['k1']))).toEqual({ 'k1.corrupt': '[1,' });
	});
});
