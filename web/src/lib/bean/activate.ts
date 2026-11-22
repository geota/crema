/**
 * `activateBean` — selecting a bag on Brew or Beans, as **one change** (#488,
 * review F6): the core resolves everything the selection changes besides the
 * bag (the linked profile, the starred grinder, its grind) and its inverse,
 * and the shell shows one message ("Ethiopia Guji: Blooming profile, Niche
 * Zero at 14") with one Undo that reverses all of it. Nothing besides the
 * bag changed: no message.
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
	DEFAULT_GRIND_SCALE,
	formatGrind,
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
 * Select bag `id`. Resolves once the change (if any) is applied. With a shot
 * in progress the linked profile is not loaded (`setActive` uploads to the
 * DE1), and a note says so.
 */
export async function activateBean(id: string, opts: { shotInProgress?: boolean } = {}): Promise<void> {
	const library = getBeanStore();
	library.setActiveBean(id);

	const bean = library.beans.find((b) => b.id === id);
	if (bean == null) return;
	const profiles = getProfileStore();
	const linkedId = bean.linkedProfileId ?? null;
	const linked = linkedId ? profiles.get(linkedId) : undefined;
	const linkedMissing = linkedId != null && !linked;

	const history = await bagLastGrinds(id);
	// Another bag picked meanwhile: that selection is the one that counts.
	if (library.activeBeanId !== id) return;

	const brewGrind = getBrewGrind().value;
	const sel = resolveBagSelection({
		bag: library.getBean(id) ?? bean,
		activeProfileId: profiles.activeId ?? undefined,
		linkedProfileMissing: linkedMissing || opts.shotInProgress === true,
		activeGrinderId: library.activeGrinderId ?? undefined,
		grind: brewGrind ?? undefined,
		grinders: library.allGrinders,
		lastGrinds: history
	});

	const notes: string[] = [];
	if (linkedMissing) notes.push(`${bean.name}'s linked profile no longer exists.`);
	else if (opts.shotInProgress && linked && profiles.activeId !== linkedId) {
		notes.push(`Shot in progress — "${linked.name}" not loaded.`);
	}
	if (sel.changed) {
		applyChange(sel.change);
		toast.action(selectionMessage(bean.name, sel.change), 'Undo', () => applyChange(sel.undo));
	}
	for (const n of notes) toast.info(n);
}

/** "Ethiopia Guji: Blooming profile, Niche Zero at 14" — what the selection changed. */
export function selectionMessage(bagName: string, change: SelectionChange): string {
	const parts: string[] = [];
	if (change.profileId?.value) {
		const p = getProfileStore().get(change.profileId.value);
		if (p) parts.push(`${p.name} profile`);
	}
	const grinder = getBeanStore().activeGrinder;
	const scale = grinder?.scale ?? DEFAULT_GRIND_SCALE;
	const grind = change.grind?.value ?? null;
	if (change.grinderId) {
		const name = grinder?.name ?? 'No grinder';
		parts.push(grind !== null ? `${name} at ${formatGrind(grind, scale)}` : name);
	} else if (grind !== null) {
		parts.push(grinder ? `${grinder.name} at ${formatGrind(grind, scale)}` : `grind ${formatGrind(grind, scale)}`);
	}
	return `${bagName}: ${parts.join(', ')}`;
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
