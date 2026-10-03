/**
 * `$lib/profiles/library-filter.vitest` — the `/profiles` rail's two axes
 * (geota/crema#124): the Hidden status composes with the roast / beverage /
 * tag facet and the search, and every count is given the other selections.
 * Run: `pnpm test:vitest`.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { initTestWasm } from '$lib/testing/test-init';
import { blankProfile, type CremaProfile } from './model.ts';
import {
	filterProfiles,
	profileFacetCount,
	profileStatusCounts,
	type ProfileFilterInput
} from './library-filter.ts';

beforeAll(async () => {
	await initTestWasm();
});

function profile(id: string, patch: Partial<CremaProfile> = {}): CremaProfile {
	return { ...blankProfile(), id, name: id, ...patch };
}

const visible = [
	profile('v-light', { roast: 'light', pinned: true }),
	profile('v-dark', { roast: 'dark', tags: ['daily'] })
];
const hidden = [
	profile('h-light', { roast: 'light', tags: ['daily'] }),
	profile('h-dark', { roast: 'dark' }),
	profile('h-tea', { roast: null, beverageType: 'manual', author: 'Ana' })
];
const input = (patch: Partial<ProfileFilterInput> = {}): ProfileFilterInput => ({
	visible,
	hidden,
	status: 'all',
	facet: null,
	query: '',
	...patch
});
const ids = (ps: CremaProfile[]) => ps.map((p) => p.id);

describe('profile library filter', () => {
	it('Hidden composes with a roast facet', () => {
		expect(ids(filterProfiles(input({ status: 'hidden', facet: 'light' })))).toEqual(['h-light']);
	});

	it('Hidden composes with a tag facet, a beverage facet and the search', () => {
		expect(ids(filterProfiles(input({ status: 'hidden', facet: 't:daily' })))).toEqual(['h-light']);
		expect(ids(filterProfiles(input({ status: 'hidden', facet: 'b:manual' })))).toEqual(['h-tea']);
		expect(ids(filterProfiles(input({ status: 'hidden', query: 'ana' })))).toEqual(['h-tea']);
	});

	it('counts each axis given the other', () => {
		const lit = input({ facet: 'light' });
		expect(profileStatusCounts(lit)).toEqual({ all: 1, pinned: 1, hidden: 1 });
		const hid = input({ status: 'hidden' });
		expect(profileFacetCount(hid, 'light')).toBe(1);
		expect(profileFacetCount(hid, 'dark')).toBe(1);
		expect(profileFacetCount(input(), 'dark')).toBe(1);
	});

	it('the default view still leaves the hidden built-ins out', () => {
		expect(ids(filterProfiles(input()))).toEqual(['v-light', 'v-dark']);
		expect(ids(filterProfiles(input({ status: 'pinned' })))).toEqual(['v-light']);
	});
});
