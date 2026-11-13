import { afterEach, describe, expect, it, vi } from 'vitest';
import { getActiveDialog, resolveActive } from '#lib/components/shared/confirm-dialog.svelte.js';
import { getActiveShotStore, type ActiveShotData } from './active-shot.svelte';

/**
 * A user Disconnect of the DE1 (#320): it clears the in-flight shot's context,
 * and while the machine pours it asks first, on the core's word.
 */

const LIVE_SHOT: ActiveShotData = {
	bean: null,
	profileName: 'Default',
	dose: 18,
	brewParams: null,
	grinderModel: null,
	source: 'live'
};

async function makeApp(pouring: boolean) {
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const { De1Manager } = await import('#lib/ble/index.js');
	const de1Disconnect = vi.spyOn(De1Manager.prototype, 'disconnect').mockResolvedValue();
	// Every other core call resolves to an empty output.
	const core = new Proxy(
		{},
		{
			get: (_target, key) =>
				key === 'disconnectNeedsConfirm' ? async () => pouring : async () => ({})
		}
	);
	const app = new CremaApp(core as never, null);
	return { app, de1Disconnect };
}

afterEach(() => {
	resolveActive(false);
	getActiveShotStore().clear();
	vi.restoreAllMocks();
});

describe('disconnectDe1', () => {
	it('clears the active shot, so the next shot cannot bill its bag', async () => {
		const { app, de1Disconnect } = await makeApp(false);
		getActiveShotStore().set(LIVE_SHOT);
		expect(await app.disconnectDe1()).toBe(true);
		expect(getActiveDialog()).toBeNull();
		expect(de1Disconnect).toHaveBeenCalledOnce();
		expect(getActiveShotStore().current).toBeNull();
	});

	it('asks first while the machine pours; Cancel keeps the link and the shot', async () => {
		const { app, de1Disconnect } = await makeApp(true);
		getActiveShotStore().set(LIVE_SHOT);
		const done = app.disconnectDe1();
		await vi.waitFor(() => expect(getActiveDialog()).not.toBeNull());
		expect(getActiveDialog()?.options.message).toBe(
			"The machine is pouring. Disconnect, and Crema's stops won't run?"
		);
		resolveActive(false);
		expect(await done).toBe(false);
		expect(de1Disconnect).not.toHaveBeenCalled();
		expect(getActiveShotStore().current).toEqual(LIVE_SHOT);
	});

	it('disconnects once the user confirms', async () => {
		const { app, de1Disconnect } = await makeApp(true);
		const done = app.disconnectDe1();
		await vi.waitFor(() => expect(getActiveDialog()).not.toBeNull());
		resolveActive(true);
		expect(await done).toBe(true);
		expect(de1Disconnect).toHaveBeenCalledOnce();
	});
});
