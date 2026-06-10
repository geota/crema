/**
 * `activateBean` — the user-act bean activation that fires the linked-profile
 * auto-load. Call sites are the EXPLICIT activations only (Beans page "Use",
 * the Brew strip / header picker, the editor's activate paths, re-buy);
 * boot-time `activeBeanId` restore and sync pulls go straight to
 * `setActiveBean` and never auto-load.
 */
import { getBeanStore } from './store.svelte';
import { getProfileStore } from '$lib/profiles';
import { toast } from '$lib/components/shared/toast.svelte';

export function activateBean(id: string, opts: { shotInProgress?: boolean } = {}): void {
	const library = getBeanStore();
	library.setActiveBean(id);

	const bean = library.beans.find((b) => b.id === id);
	const linkedId = bean?.linkedProfileId ?? null;
	if (bean == null || linkedId == null) return;

	const profiles = getProfileStore();
	// Already the active profile — quiet no-op (no toast spam).
	if (profiles.activeId === linkedId) return;
	const profile = profiles.get(linkedId);
	if (!profile) {
		// Dangling link (profile deleted / device-local custom from an import).
		toast.info(`${bean.name}'s linked profile no longer exists.`);
		return;
	}
	if (opts.shotInProgress) {
		// setActive eagerly pushes recipe targets to the core and uploads to
		// the DE1 — the one genuinely unsafe moment. Skip, don't defer.
		toast.info(`Shot in progress — "${profile.name}" not loaded.`);
		return;
	}
	profiles.setActive(linkedId);
	toast.success(`Loaded "${profile.name}" — linked to ${bean.name}`);
}
