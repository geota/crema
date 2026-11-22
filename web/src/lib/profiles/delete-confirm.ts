/**
 * `#lib/profiles/delete-confirm.js` — what the profile delete confirm says
 * (bean-profile-pin "Deletion cascade", REVIEW-FINAL-2 N3): deleting a custom
 * profile clears every bag's preference for it, and can't be undone, so the
 * question says how many bags it unlinks.
 */
import type { Bean } from '#lib/bean/model.js';

/** The bags (not deleted) that prefer profile `profileId`. */
export function bagsPreferring(beans: readonly Bean[], profileId: string): number {
	return beans.filter((b) => b.deletedAt == null && b.linkedProfileId === profileId).length;
}

/**
 * `Delete "Name"? 2 beans prefer this profile; their preference will be
 * cleared. This cannot be undone.` The middle sentence only when a bag
 * prefers it.
 */
export function profileDeleteMessage(name: string, preferring: number): string {
	const links =
		preferring === 1
			? ' 1 bean prefers this profile; its preference will be cleared.'
			: preferring > 1
				? ` ${preferring} beans prefer this profile; their preference will be cleared.`
				: '';
	return `Delete "${name}"?${links} This cannot be undone.`;
}
