/**
 * A shot records the profile it started with by id (save-boundaries review
 * F4/F9): the live shot's start freezes the active profile's id next to the
 * bag snapshot, and the completion hands it to the core as the
 * `ShotCaptureContext.profileId` fallback.
 */
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getActiveShotStore } from '#lib/state/active-shot.svelte.js';
import type { ShotCaptureContext } from '#lib/core/crema-core.js';

vi.mock('#lib/bean/bag-empty-prompt.js', () => ({ promptBagEmpty: () => {} }));

const { commitShotCompletion } = await import('./shot-persistence');
type Event = Parameters<typeof commitShotCompletion>[0];

beforeAll(async () => {
	await initTestWasm();
});

describe('the shot records its profile id', () => {
	it('the completion passes the id frozen at shot start to the core', () => {
		getActiveShotStore().set({
			bean: null,
			profileName: 'Mine',
			profileId: 'profile:mine',
			dose: 18,
			brewParams: null,
			grinderModel: null,
			source: 'live'
		});
		const takeStoredShot = vi.fn((_ctx: ShotCaptureContext) => null);
		commitShotCompletion({ content: { duration: 28_000, disposition: 'Record' } } as unknown as Event, {
			snapshot: { activeProfileName: null },
			shotStartedAtMs: null,
			lastNotificationAtMs: null,
			sliceJsonl: async () => '',
			fireWebhook: () => {},
			takeStoredShot,
			runtime: null
		} as unknown as Parameters<typeof commitShotCompletion>[1]);
		expect(takeStoredShot).toHaveBeenCalled();
		expect(takeStoredShot.mock.lastCall![0]).toMatchObject({ profileName: 'Mine', profileId: 'profile:mine' });
	});

	it('the live shot start freezes the active profile id', async () => {
		const { loadCore } = await import('#lib/core/index.js');
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const { getProfileStore } = await import('#lib/profiles/index.js');
		const core = await loadCore();
		core.reset();
		const app = new CremaApp(core, null);
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		const [first] = profiles.all;
		profiles.setActive(first.id);
		const frozen = (app as unknown as { buildLiveActiveShot(): { profileId?: string | null } }).buildLiveActiveShot();
		expect(frozen.profileId).toBe(first.id);
	});
});
