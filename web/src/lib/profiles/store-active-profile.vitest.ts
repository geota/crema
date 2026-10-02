/**
 * The profile store hands the ACTIVE profile to the core (`setActiveProfile`)
 * on boot, select and edit — independently of the upload.
 *
 * Regression cover for issue 11's gap: the core learned a profile's per-step
 * weight exits only from a completed upload. After a page reload the DE1 still
 * holds the profile, `CremaApp.ensureLoadedMatches` / `syncActiveProfile` skip
 * the upload on the fingerprint match, and the exits silently never fired. Now
 * the store's boot push carries the weights to the core with no upload at all.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Profile } from './core-types';

/** Every core call the store makes, recorded; nothing ever uploads here. */
const core = {
	setActiveProfile: vi.fn(async (_json: string | undefined) => {}),
	setWeightTargetDisabled: vi.fn(async (_v: boolean) => {}),
	setProfileTargetWeight: vi.fn(async (_v: number | undefined) => {}),
	setProfileVolumeLimit: vi.fn(async (_v: number | undefined) => {}),
	uploadProfile: vi.fn(async () => ({ commands: [], events: [] })),
	stopTargetsProjection: vi.fn(async () => ({
		armed: false,
		weight: null,
		volume: null,
		maxTime: null,
		weightConfigured: null,
		weightBlocked: null
	}))
};

vi.mock('$lib/core', async (importOriginal) => ({
	...(await importOriginal<typeof import('$lib/core')>()),
	loadCore: async () => core
}));

const ACTIVE_KEY = 'crema.profiles.activeId.v1';

/** The step weights in the last `setActiveProfile` call (`undefined` = cleared). */
function lastPushedWeights(): (number | null)[] | undefined {
	const json = core.setActiveProfile.mock.calls.at(-1)?.[0];
	if (json === undefined) return undefined;
	return (JSON.parse(json) as Profile).steps.map((s) => s.weight ?? null);
}

/** A fresh store (a page reload) with `activeId` persisted, fully loaded. */
async function reloadWithActive(activeId: string | null) {
	vi.resetModules();
	await (await import('$lib/testing/test-init')).initTestWasm();
	localStorage.setItem(ACTIVE_KEY, JSON.stringify(activeId));
	const { getProfileStore } = await import('./store.svelte');
	const store = getProfileStore();
	await store.ensureLoaded();
	await vi.waitFor(() => expect(core.setActiveProfile).toHaveBeenCalled());
	return store;
}

/** The first built-in whose steps carry a weight exit (an A-Flow). */
async function weightedBuiltin() {
	await (await import('$lib/testing/test-init')).initTestWasm();
	const { builtinCremaProfiles } = await import('./model');
	const p = builtinCremaProfiles().find((b) => b.segments.some((s) => (s.weight ?? 0) > 0));
	if (!p) throw new Error('no built-in with a step weight');
	return p;
}

beforeEach(() => {
	localStorage.clear();
	vi.clearAllMocks();
});

describe('active profile → core step weights', () => {
	it('a reload delivers the step weights with no upload (fingerprint-skip path)', async () => {
		const aflow = await weightedBuiltin();
		await reloadWithActive(aflow.id);
		const expected = aflow.segments.map((s) => s.weight ?? null);
		expect(expected.some((w) => w !== null)).toBe(true);
		expect(lastPushedWeights()).toEqual(expected);
		// The store never uploads; whether `CremaApp` uploads (fingerprint
		// miss) or skips (match), the core already has the weights.
		expect(core.uploadProfile).not.toHaveBeenCalled();
	});

	it('editing the active profile replaces the weights', async () => {
		const aflow = await weightedBuiltin();
		const store = await reloadWithActive(null);
		const custom = { ...aflow, id: 'custom-aflow', source: 'custom' as const };
		store.save(custom);
		store.setActive(custom.id);
		await vi.waitFor(() => expect(lastPushedWeights()).toEqual(custom.segments.map((s) => s.weight ?? null)));
		// Edit: the weighted step's target moves to 9 g.
		const i = custom.segments.findIndex((s) => (s.weight ?? 0) > 0);
		const edited = {
			...custom,
			segments: custom.segments.map((s, j) => (j === i ? { ...s, weight: 9 } : s))
		};
		store.save(edited);
		await vi.waitFor(() => expect(lastPushedWeights()?.[i]).toBe(9));
	});

	it('deselecting clears the active profile in the core', async () => {
		const aflow = await weightedBuiltin();
		const store = await reloadWithActive(aflow.id);
		core.setActiveProfile.mockClear();
		store.setActive(null);
		await vi.waitFor(() => expect(core.setActiveProfile).toHaveBeenCalledWith(undefined));
	});
});
