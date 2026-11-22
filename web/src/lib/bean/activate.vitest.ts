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
	stopTargetsProjection: vi.fn(async () => ({
		armed: false,
		weight: null,
		volume: null,
		maxTime: null,
		weightConfigured: null,
		weightBlocked: null,
		weightBlockedNote: null
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

		await t.activateBean('bean-1');

		expect(t.profiles.activeId).toBe(t.builtin.id);
		// The DE1 upload hook and the core both get the profile, not null.
		expect(t.activated).toEqual([t.builtin.id]);
		await vi.waitFor(() => expect(core.setActiveProfile).toHaveBeenCalledOnce());
		expect(core.setActiveProfile.mock.calls[0][0]).toContain(t.builtin.id);
		// One message for the whole selection (#488, review F6).
		expect(t.toasts()).toEqual([`Gesha: ${t.builtin.name} profile`]);
	});

	it('still reports a dangling link to a deleted custom profile', async () => {
		const t = await setup();
		const { getBeanStore } = await import('./store.svelte');
		getBeanStore().upsertBean({ ...blankBean('bean-2'), name: 'Kenya', linkedProfileId: 'gone' });
		await t.activateBean('bean-2');
		expect(t.activated).toEqual([]);
		expect(t.toasts()).toEqual(["Kenya's linked profile no longer exists."]);
	});
});

describe('selecting a bag is one change (#488, review F6)', () => {
	async function grinderSetup() {
		const t = await setup();
		const { getBeanStore } = await import('./store.svelte');
		const { getBrewGrind } = await import('#lib/brew/grind-session.svelte.js');
		const { getToasts } = await import('#lib/components/shared/toast.svelte.js');
		const { quickSavePlan, runQuickSave } = await import('#lib/brew/quick-save.js');
		const { grindView } = await import('#lib/brew/grind-view.js');
		const unsaved = (bag: unknown, grinderId: string, brewGrind: string | null) =>
			grindView({ bag: bag as never, grinder: beans.getGrinder(grinderId), brewGrind }).marker !== null;
		const { effectiveCremaProfile, newProfileId } = await import('#lib/profiles/index.js');
		const beans = getBeanStore();
		const niche = beans.addGrinder('Niche Zero')!;
		const df64 = beans.addGrinder('DF64')!;
		beans.setActiveGrinder(niche.id);
		const row = (id: string, name: string, grind: string, starred = false) => ({ grinderId: id, grinderName: name, grind, starred });
		beans.upsertBean({ ...blankBean('bean:a'), name: 'A', grinds: [row(niche.id, niche.name, '10')] });
		beans.upsertBean({
			...blankBean('bean:b'),
			name: 'B',
			grinds: [row(niche.id, niche.name, '14'), row(df64.id, df64.name, '30', true)]
		});
		const grind = getBrewGrind();
		const actions = () => getToasts().filter((x) => x.action);
		return { ...t, beans, niche, df64, grind, actions, quickSavePlan, runQuickSave, unsaved, effectiveCremaProfile, newProfileId };
	}

	it('dial on bag A, select bag B: the grind becomes B\'s, the marker follows B, Save never writes A\'s dial to B', async () => {
		const t = await grinderSetup();
		t.beans.upsertBean({ ...t.beans.getBean('bean:b')!, grinds: [{ grinderId: t.niche.id, grinderName: t.niche.name, grind: '14', starred: false }] });
		await t.activateBean('bean:a');
		expect(t.grind.value).toBe('10');
		t.grind.set('11'); // dialled on A, not saved
		expect(t.unsaved(t.beans.activeBean, t.niche.id, t.grind.value)).toBe(true);

		await t.activateBean('bean:b');
		expect(t.grind.value, "B's saved grind for the active grinder").toBe('14');
		expect(t.unsaved(t.beans.activeBean, t.niche.id, t.grind.value), 'the marker reflects B').toBe(false);
		const plan = t.quickSavePlan({
			bean: t.beans.activeBean,
			grinder: t.beans.activeGrinder,
			brewGrind: t.grind.value,
			profile: null,
			overrides: {}
		});
		expect(plan.grind, 'nothing to save on B').toBeNull();
		expect(t.beans.getBean('bean:b')!.grinds![0].grind).toBe('14');
		expect(t.beans.getBean('bean:a')!.grinds![0].grind, "A's dial was never saved").toBe('10');
	});

	it('a starred bag switches grinder and grind with one message and one Undo', async () => {
		const t = await grinderSetup();
		t.grind.set('12');
		await t.activateBean('bean:b');
		expect(t.beans.activeGrinder?.id).toBe(t.df64.id);
		expect(t.grind.value).toBe('30');
		const [msg] = t.actions();
		expect(t.actions()).toHaveLength(1);
		expect(msg.message).toBe('B: DF64 at 30');
		msg.action!.run();
		expect(t.beans.activeGrinder?.id, 'Undo puts the grinder back').toBe(t.niche.id);
		expect(t.grind.value, 'and the grind').toBe('12');
	});

	it('nothing besides the bag changed: no message', async () => {
		const t = await grinderSetup();
		t.grind.set('10');
		await t.activateBean('bean:a');
		expect(t.actions()).toEqual([]);
	});

	it('a grinder switch takes the pick order: the saved grind, else the grind stays', async () => {
		const t = await grinderSetup();
		const { selectGrinder } = await import('./activate');
		await t.activateBean('bean:a');
		t.grind.set('9');
		await selectGrinder(t.df64.id);
		expect(t.grind.value, 'A has nothing for the DF64: the grind stays').toBe('9');
		t.beans.setActiveBean('bean:b');
		await selectGrinder(t.df64.id);
		expect(t.grind.value).toBe('30');
	});
	it('a saved grind that isn\'t a plain number is Brew\'s grind after a pick (review N2)', async () => {
		const t = await grinderSetup();
		t.beans.upsertBean({
			...t.beans.getBean('bean:b')!,
			grinds: [{ grinderId: t.niche.id, grinderName: t.niche.name, grind: '2.1.3', starred: false }]
		});
		t.grind.set('12');
		await t.activateBean('bean:b');
		expect(t.grind.value, 'the pick sets the text grind, not a stale number').toBe('2.1.3');
		expect(t.actions()[0].message).toBe('B: Niche Zero at 2.1.3');
		expect(t.unsaved(t.beans.activeBean, t.niche.id, t.grind.value), 'it matches the bag: no marker').toBe(false);
	});
});

describe('the bag-pick Undo keeps later changes (REVIEW-CORE H4, FINAL S9)', () => {
	async function undoSetup() {
		const t = await setup();
		const { getBeanStore } = await import('./store.svelte');
		const { getBrewGrind } = await import('#lib/brew/grind-session.svelte.js');
		const { getToasts } = await import('#lib/components/shared/toast.svelte.js');
		const beans = getBeanStore();
		const niche = beans.addGrinder('Niche Zero')!;
		const df64 = beans.addGrinder('DF64')!;
		beans.setActiveGrinder(niche.id);
		beans.upsertBean({
			...blankBean('bean:b'),
			name: 'B',
			grinds: [{ grinderId: df64.id, grinderName: df64.name, grind: '30', starred: true }]
		});
		const grind = getBrewGrind();
		const undo = () => {
			const [msg] = getToasts().filter((x) => x.action);
			msg.action!.run();
		};
		return { ...t, beans, niche, df64, grind, undo };
	}

	it('a dial change after the pick stands; the grinder the pick set goes back', async () => {
		const t = await undoSetup();
		t.grind.set('12');
		await t.activateBean('bean:b');
		expect(t.grind.value).toBe('30');
		t.grind.set('31'); // dialled after the pick
		t.undo();
		expect(t.beans.activeGrinder?.id, 'still the DF64 the pick set: it goes back').toBe(t.niche.id);
		expect(t.grind.value, 'the later dial stands').toBe('31');
	});

	it('a grinder picked after the pick stands', async () => {
		const t = await undoSetup();
		const other = t.beans.addGrinder('Comandante')!;
		await t.activateBean('bean:b');
		t.beans.setActiveGrinder(other.id);
		t.undo();
		expect(t.beans.activeGrinder?.id).toBe(other.id);
	});

	it('a profile picked after the pick stands', async () => {
		const t = await undoSetup();
		const other = t.profiles.all.find((p) => p.id !== t.builtin.id)!;
		await t.activateBean('bean-1'); // loads its linked built-in
		expect(t.profiles.activeId).toBe(t.builtin.id);
		t.profiles.setActive(other.id);
		t.undo();
		expect(t.profiles.activeId, 'the profile picked since stays').toBe(other.id);
	});

	it('with a shot in progress at Undo the profile stays (no upload mid-shot)', async () => {
		const t = await undoSetup();
		let shot = false;
		await t.activateBean('bean-1', { shotInProgress: () => shot });
		expect(t.profiles.activeId).toBe(t.builtin.id);
		shot = true;
		t.activated.length = 0;
		t.undo();
		expect(t.profiles.activeId).toBe(t.builtin.id);
		expect(t.activated, 'nothing sent to the DE1').toEqual([]);
	});

	it('re-picking the selected bag changes nothing (FINAL N7)', async () => {
		const t = await undoSetup();
		await t.activateBean('bean:b');
		const { getToasts } = await import('#lib/components/shared/toast.svelte.js');
		const before = getToasts().length;
		t.grind.set('31'); // unsaved on Brew
		await t.activateBean('bean:b');
		expect(t.grind.value, 'the unsaved grind stays').toBe('31');
		expect(getToasts(), 'no second message').toHaveLength(before);
	});
});
