/**
 * Activating a bean loads its linked profile even when that profile is a
 * built-in the user has hidden (core/shell review 2026-10-30 §2.2). Hiding
 * only takes a built-in out of the library list; Android's linked-profile
 * auto-load (`LibraryController.maybeLoadLinkedProfile`) still finds it, while
 * web said "no longer exists".
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { blankBean } from './model';

const core = {
	setActiveProfile: vi.fn(async (_json: string | undefined) => {}),
	setWeightTargetDisabled: vi.fn(async () => {}),
	setProfileTargetWeight: vi.fn(async () => {}),
	setProfileVolumeLimit: vi.fn(async () => {}),
	stopTargetsProjection: vi.fn(async () => ({
		armed: false,
		weight: null,
		volume: null,
		maxTime: null,
		weightConfigured: null,
		weightBlocked: null
	}))
};

vi.mock('#lib/core/index.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/core/index.js')>()),
	loadCore: async () => core
}));

async function setup() {
	await (await import('#lib/testing/test-init.js')).initTestWasm();
	const { getProfileStore } = await import('#lib/profiles/index.js');
	const { getBeanStore } = await import('./store.svelte');
	const { activateBean } = await import('./activate');
	// After `vi.resetModules`, the toast store `activate` uses is this fresh one.
	const { getToasts } = await import('#lib/components/shared/toast.svelte.js');
	const toasts = () => getToasts().map((x) => x.message);
	const profiles = getProfileStore();
	await profiles.ensureLoaded();
	// Let the boot-time push (no active profile) land before the test acts.
	await vi.waitFor(() => expect(core.setActiveProfile).toHaveBeenCalledWith(undefined));
	core.setActiveProfile.mockClear();
	const builtin = profiles.all.find((p) => p.source === 'builtin')!;
	const beans = getBeanStore();
	beans.upsertBean({ ...blankBean('bean-1'), name: 'Gesha', linkedProfileId: builtin.id });
	const activated: (string | null)[] = [];
	profiles.onActivate = (p) => activated.push(p?.id ?? null);
	return { profiles, builtin, activateBean, activated, toasts };
}

beforeEach(() => {
	vi.resetModules();
	localStorage.clear();
	vi.clearAllMocks();
});

describe('activateBean with a hidden built-in linked profile', () => {
	it('AC-F18c loads a hidden built-in like Android', async () => {
		const t = await setup();
		t.profiles.delete(t.builtin.id); // hide it
		expect(t.profiles.isHidden(t.builtin.id)).toBe(true);
		expect(t.profiles.all.some((p) => p.id === t.builtin.id)).toBe(false);

		t.activateBean('bean-1');

		expect(t.profiles.activeId).toBe(t.builtin.id);
		// The DE1 upload hook and the core both get the profile, not null.
		expect(t.activated).toEqual([t.builtin.id]);
		await vi.waitFor(() => expect(core.setActiveProfile).toHaveBeenCalledOnce());
		expect(core.setActiveProfile.mock.calls[0][0]).toContain(t.builtin.id);
		expect(t.toasts()).toEqual([
			`Loaded "${t.builtin.name}" — linked to Gesha`
		]);
	});

	it('still reports a dangling link to a deleted custom profile', async () => {
		const t = await setup();
		const { getBeanStore } = await import('./store.svelte');
		getBeanStore().upsertBean({ ...blankBean('bean-2'), name: 'Kenya', linkedProfileId: 'gone' });
		t.activateBean('bean-2');
		expect(t.activated).toEqual([]);
		expect(t.toasts()).toEqual(["Kenya's linked profile no longer exists."]);
	});
});
