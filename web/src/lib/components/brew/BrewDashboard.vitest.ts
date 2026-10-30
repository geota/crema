/**
 * The Brew dashboard's wiring to the app-level dial (F7 validation P2): the
 * dial seeds from `app.brewOverridesFor(active)` when it mounts and reports
 * the active profile's overrides to `app.setBrewOverrides`, which makes them
 * the core's desired profile. A dashboard that seeds from nothing, or stops
 * reporting, fails here.
 */

import '#lib/testing/dom-stubs.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import BrewDashboard from './BrewDashboard.svelte';
import { brewTempRange, getProfileStore, type CremaProfile } from '#lib/profiles/index.js';
import { getCremaUiState } from '#lib/state/ui-state.svelte.js';
import type { CremaApp } from '#lib/state/index.js';

describe('BrewDashboard and the app-level dial', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	it('seeds the dial from the app and reports the active profile with it', async () => {
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		const profile = profiles.all.find(
			(p) => brewTempRange(p).max >= brewTempRange(p).anchor + 2
		);
		if (!profile) throw new Error('no suitable built-in profile');
		profiles.setActive(profile.id);
		// The dial was left at +2 °C on this profile (another screen, before
		// this mount).
		const dialled = { brewTemp: brewTempRange(profile).anchor + 2 };
		const setBrewOverrides = vi.fn<(p: CremaProfile, qc: object) => void>();
		const app = {
			brewOverridesFor: (p: CremaProfile | null) => (p?.id === profile.id ? dialled : {}),
			setBrewOverrides,
			state: getCremaUiState()
		} as unknown as CremaApp;

		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(WithAppContext, {
			target,
			props: { app, component: BrewDashboard, props: { ui: getCremaUiState().current } }
		});
		flushSync();
		await tick();

		expect(setBrewOverrides, 'the dashboard reports the dial').toHaveBeenCalled();
		const [reported, qc] = setBrewOverrides.mock.lastCall!;
		expect(reported.id).toBe(profile.id);
		expect(qc, 'with the overrides it seeded from the app').toEqual(dialled);
	});
});
