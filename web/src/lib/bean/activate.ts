/**
 * `activateBean` — selecting a bag on Brew or Beans, as **one change** (#488,
 * review F6): the core resolves everything the selection changes besides the
 * bag (the linked profile, the starred grinder, its grind) and its inverse,
 * and the words: one message ("Ethiopia Guji: Blooming profile, Niche Zero at
 * 14") with one Undo that reverses all of it, and any notices. The shell
 * shows them as the core wrote them. Nothing besides the bag changed: no
 * message.
 *
 * Call sites are the EXPLICIT activations only (Beans page "Use", the Brew
 * strip / header picker, the editor's activate paths, re-buy); boot-time
 * `activeBeanId` restore and sync pulls go straight to `setActiveBean` and
 * never resolve anything.
 *
 * `selectGrinder` is the grinder pick: the pointer, and the grind by the pick
 * order (the bag's saved grind for it, else its newest shot with it, else the
 * grind stays).
 */
import { getBeanStore } from './store.svelte';
import { getProfileStore } from '#lib/profiles/index.js';
import { toast } from '#lib/components/shared/toast.svelte.js';
import { getBrewGrind } from '#lib/brew/grind-session.svelte.js';
import { getHistoryReads, lastGrinds } from '#lib/history/reads.svelte.js';
import {
	bagSelectionUndo,
	resolveBagSelection,
	resolveGrinderSwitch,
	type LastGrind
} from './grinder';
import type { SelectionChange } from '#lib/core/crema-core.js';

/** The bag's newest grind per grinder; none when the history can't say yet. */
async function bagLastGrinds(beanId: string): Promise<LastGrind[]> {
	try {
		return await getHistoryReads().answer(lastGrinds(beanId));
	} catch {
		return [];
	}
}

/** Apply one side of a resolved selection: the profile, grinder and grind parts it names. */
function applyChange(c: SelectionChange): void {
	if (c.profileId) getProfileStore().setActive(c.profileId.value ?? null);
	if (c.grinderId) getBeanStore().setActiveGrinder(c.grinderId.value ?? null);
	if (c.grind) getBrewGrind().set(c.grind.value ?? null);
}

/**
 * Whether a shot is running: a value read at the pick, or a getter, so the
 * Undo (which can come seconds later) reads it again.
 */
export type ShotInProgress = boolean | (() => boolean);

/**
 * What runs at a pick: a DE1 shot, and a guided brew on this device (armed,
 * running or paused). Either holds the linked profile back; the core's notice
 * names the brew ("Brew in progress") when one runs.
 */
export interface PickBusy {
	shotInProgress?: ShotInProgress;
	brewInProgress?: ShotInProgress;
}

function shotRunning(s: ShotInProgress | undefined): boolean {
	return typeof s === 'function' ? s() : s === true;
}

/**
 * Select bag `id`. Resolves once the change (if any) is applied. With a shot
 * or a guided brew in progress the core leaves the linked profile out
 * (`setActive` uploads to the DE1), and its notice says so. Picking the bag
 * that is already selected changes nothing (FINAL N7): an unsaved grind on
 * Brew stays.
 *
 * Undo puts back only the parts still as the pick set them (the core's
 * `bag_selection_undo`): a dial, grinder or profile change made since
 * stands, and with a shot or a brew in progress the profile stays (FINAL S9).
 */
export async function activateBean(id: string, opts: PickBusy = {}): Promise<void> {
	const library = getBeanStore();
	const before = library.activeBeanId;
	library.setActiveBean(id);

	const bean = library.beans.find((b) => b.id === id);
	if (bean == null) return;
	const profiles = getProfileStore();
	const linkedId = bean.linkedProfileId ?? null;
	const linked = linkedId ? profiles.get(linkedId) : undefined;
	const linkedMissing = linkedId != null && !linked;
	const shotInProgress = shotRunning(opts.shotInProgress);
	const brewInProgress = shotRunning(opts.brewInProgress);

	const history = await bagLastGrinds(id);
	// Another bag picked meanwhile: that selection is the one that counts.
	if (library.activeBeanId !== id) return;

	const brewGrind = getBrewGrind().value;
	const sel = resolveBagSelection({
		bag: library.getBean(id) ?? bean,
		activeBeanId: before ?? undefined,
		activeProfileId: profiles.activeId ?? undefined,
		linkedProfileMissing: linkedMissing,
		linkedProfileName: linked?.name,
		shotInProgress,
		brewInProgress,
		activeGrinderId: library.activeGrinderId ?? undefined,
		grind: brewGrind ?? undefined,
		grinders: library.allGrinders,
		lastGrinds: history
	});

	// The core's words, verbatim (REVIEW-FINAL-2 SF4): the one message with
	// its Undo, then the passing notices (a missing linked profile, a profile
	// not loaded mid-shot). A re-pick changes nothing and says nothing.
	if (sel.changed) {
		applyChange(sel.change);
		toast.action(sel.message ?? bean.name, 'Undo', () =>
			applyChange(
				bagSelectionUndo(sel, {
					profileId: profiles.activeId ?? undefined,
					grinderId: library.activeGrinderId ?? undefined,
					grind: getBrewGrind().value ?? undefined,
					// A guided brew holds the profile as a shot does.
					shotInProgress: shotRunning(opts.shotInProgress) || shotRunning(opts.brewInProgress)
				})
			)
		);
	}
	for (const n of sel.notices ?? []) toast.info(n.text);
}

/**
 * Pick grinder `id` (`null` = no grinder) on Brew: the grind follows the
 * pick order for the selected bag; with nothing found it stays.
 */
export async function selectGrinder(id: string | null): Promise<void> {
	const library = getBeanStore();
	library.setActiveGrinder(id);
	const bag = library.activeBean;
	if (!bag) return;
	const history = await bagLastGrinds(bag.id);
	if (library.activeGrinderId !== id || library.activeBean?.id !== bag.id) return;
	const grind = resolveGrinderSwitch({
		bag: library.getBean(bag.id) ?? bag,
		grinderId: id ?? undefined,
		grinders: library.allGrinders,
		lastGrinds: history
	});
	if (grind !== null) getBrewGrind().set(grind);
}
