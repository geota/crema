/**
 * `#lib/brew/quick-save.js` — what Quick Controls' **Save** writes
 * (save-boundaries review F1, 2026-11-21). The rule is the core's
 * (`de1_domain::quick_save`, REVIEW-CORE M2), so web and Android read one
 * plan:
 *
 *  - Brew's grind differs from the active bag's saved grind for the active
 *    grinder (#488) → the grind goes to that (bag, grinder) row, through the
 *    core's `save_bag_grind` (added if missing, the star never changes). No
 *    profile copy and no name prompt.
 *  - The recipe dials (dose, yield, brew temp, pre-infusion) differ from the
 *    active profile → a **custom** profile is updated in place (Undo in the
 *    message, and "Save as new…" beside Save); a **built-in** is read-only,
 *    so it is copied under a name the user gives, and the copy made active.
 *  - Nothing differs → Save is off. With no profile, Save still saves the
 *    grind.
 *
 * The Undo of an in-place Save is the core's too (`quick_save_undo`): each
 * part comes back only while it still holds what the Save wrote. The shell
 * keeps the name prompt, the store writes, the baked profile and the
 * message wording.
 */

import type { Bean } from '#lib/bean/index.js';
import {
	DEFAULT_GRIND_SCALE,
	formatGrind,
	NO_GRINDER_ID,
	type BagGrind,
	type Grinder,
	type GrindSave
} from '#lib/bean/grinder.js';
import {
	QuickSaveProfileKind,
	QuickSaveProfileWrite,
	QuickSaveUndoOutcome,
	type QuickSavePlan as CorePlan,
	type QuickSaveUndo as CoreUndo,
	type QuickSaveUndoPlan
} from '#lib/core/crema-core.js';
import { quickSavePlanJson, quickSaveUndoJson } from '#lib/wasm/de1_wasm.js';
import type { CremaProfile, ProfileFingerprintOverrides } from '#lib/profiles/index.js';

export { QuickSaveProfileKind, QuickSaveProfileWrite, QuickSaveUndoOutcome };

/** What Save would write, for the state on screen: the core's plan, with the grinder it names. */
export type QuickSavePlan = Omit<CorePlan, 'grind'> & {
	/** Brew's grind as the bag stores it, when it differs from the bag's for the active grinder; else `null`. */
	readonly grind: string | null;
	/** The active grinder the grind is saved for (`null` = the "no grinder" slot). */
	readonly grinder: Grinder | null;
};

/**
 * The plan for the active `bean`, the active `grinder`, Brew's grind
 * (`brewGrind` as text, `null` = not set), the active `profile` and its
 * `overrides` — normalized by the core's lifecycle rule
 * (`normalizeBrewOverrides`), so a dial back at the profile's own value is
 * no change. No grind on Brew is no grind change either.
 */
export function quickSavePlan(input: {
	bean: Bean | null;
	grinder: Grinder | null;
	brewGrind: string | null;
	profile: CremaProfile | null;
	overrides: ProfileFingerprintOverrides;
}): QuickSavePlan {
	const { bean, grinder, brewGrind, profile } = input;
	const core = JSON.parse(
		quickSavePlanJson(
			JSON.stringify({
				bag: bean ?? undefined,
				grinder: grinder ?? undefined,
				brewGrind: brewGrind ?? undefined,
				profile: profile
					? {
							id: profile.id,
							name: profile.name,
							kind: profile.source === 'custom' ? QuickSaveProfileKind.Custom : QuickSaveProfileKind.Builtin
						}
					: undefined,
				recipeOverrides: profile ? input.overrides : {}
			})
		)
	) as CorePlan;
	return { ...core, grind: core.grind ?? null, grinder };
}

/** The stores and core rules a Save writes through. */
export interface QuickSaveDeps {
	readonly beans: {
		getBean(id: string): Bean | null;
		/** The core's grind save onto the (bag, grinder) row; stored only when it changed. */
		saveBagGrind(beanId: string, grinder: Grinder | null, grind: string): GrindSave | null;
		/** Store a bag the core produced (an Undo's row put back), as it set `updatedAt`. */
		storeBagFromCore(bean: Bean): void;
	};
	readonly profiles: {
		get(id: string): CremaProfile | undefined;
		save(profile: CremaProfile): void;
		setActive(id: string | null): void;
	};
	/** The dial baked into a profile exactly as a shot runs it (`effectiveCremaProfile`). */
	readonly bake: (profile: CremaProfile, qc: ProfileFingerprintOverrides) => CremaProfile;
	readonly newId: () => string;
}

/** What an in-place Save replaced, for its Undo. */
export interface QuickSaveUndo {
	/** What the core compares: the body and the grind row the Save wrote. */
	readonly core: CoreUndo;
	/** The profile's body before the Save: what Undo stores back. */
	readonly previous: CremaProfile;
	/**
	 * The bag had no saved grinds at all before the Save (`grinds` null: not
	 * migrated yet). Undo puts that back, not an empty list, so the migration
	 * still runs for it (PLAN follow-up 13).
	 */
	readonly grindsWereNull: boolean;
}

export interface QuickSaveResult {
	readonly message: string;
	/** Set for an in-place profile update: the message offers Undo. */
	readonly undo: QuickSaveUndo | null;
}

/**
 * Run Save for `plan`. The recipe goes to the active `profile`: in place for
 * a custom one unless `asNew`; a built-in, or `asNew`, becomes a copy named
 * `copyName`, made active. A copy without a name writes nothing (the prompt
 * asks first), nor does a plan with nothing to save: `null`.
 */
export function runQuickSave(
	plan: QuickSavePlan,
	deps: QuickSaveDeps,
	opts: {
		profile: CremaProfile | null;
		overrides: ProfileFingerprintOverrides;
		copyName?: string | null;
		asNew?: boolean;
	}
): QuickSaveResult | null {
	if (!plan.enabled) return null;
	const recipe = plan.writesRecipe;
	const copying =
		recipe && (plan.profileWrite === QuickSaveProfileWrite.Copy || (opts.asNew === true && plan.offersSaveAsNew));
	const name = opts.copyName?.trim() || null;
	if (copying && name === null) return null;
	const profile = opts.profile;
	if (recipe && (profile == null || profile.id !== plan.profileId)) return null;
	// Everything that can fail first: nothing is written unless all of it can be.
	const baked = recipe && profile ? deps.bake(profile, opts.overrides) : null;

	const parts: string[] = [];
	let undo: QuickSaveUndo | null = null;
	if (baked && profile) {
		if (copying) {
			const copy: CremaProfile = {
				...baked,
				source: 'custom',
				id: deps.newId(),
				name: name!,
				pinned: false,
				lastUsed: null
			};
			deps.profiles.save(copy);
			deps.profiles.setActive(copy.id);
			parts.push(`Saved “${name}”`);
		} else {
			deps.profiles.save(baked);
			undo = {
				core: {
					profileId: profile.id,
					profileName: profile.name,
					writtenProfile: plain(deps.profiles.get(profile.id) ?? baked)
				},
				previous: plain(profile),
				grindsWereNull: false
			};
			parts.push(`“${profile.name}” updated`);
		}
	}
	if (plan.grind !== null && plan.beanId != null) {
		const before = deps.beans.getBean(plan.beanId);
		const beforeRow = before ? plain(rowFor(before, plan.grinderId)) : undefined;
		const saved = deps.beans.saveBagGrind(plan.beanId, plan.grinder, plan.grind);
		// A message only when the saved value changed.
		if (saved?.changed) {
			const where = plan.grinder ? `${plan.beanName} · ${plan.grinder.name}` : plan.beanName;
			const value = formatGrind(plan.grind, plan.grinder?.scale ?? DEFAULT_GRIND_SCALE);
			parts.push(`${parts.length === 0 ? 'Grind' : 'grind'} ${value} saved to ${where}`);
			if (undo) {
				undo = {
					...undo,
					core: {
						...undo.core,
						grind: {
							beanId: plan.beanId,
							grinderId: plan.grinderId ?? NO_GRINDER_ID,
							before: beforeRow,
							written: plain(rowFor(saved.bean, plan.grinderId)),
							grindsWereNone: before?.grinds == null
						}
					},
					grindsWereNull: before?.grinds == null
				};
			}
		}
	}
	if (parts.length === 0) return null;
	return { message: parts.join(' · '), undo };
}

/**
 * Undo an in-place Save through the core's `quick_save_undo`: the profile's
 * body comes back only while it is still the body the Save wrote (a later
 * pin, rename or edit stands), and the bag's grind only while that one
 * (bag, grinder) row is still the one written.
 */
export function undoQuickSave(
	u: QuickSaveUndo,
	deps: Pick<QuickSaveDeps, 'beans' | 'profiles'>,
	nowMs = Date.now()
): { profileRestored: boolean; outcome: QuickSaveUndoOutcome; message: string } {
	const nowProfile = deps.profiles.get(u.core.profileId);
	const nowBag = u.core.grind ? deps.beans.getBean(u.core.grind.beanId) : null;
	const out = JSON.parse(
		quickSaveUndoJson(
			JSON.stringify({ undo: u.core, nowProfile: nowProfile ?? null, nowBag: nowBag ?? undefined }),
			nowMs
		)
	) as QuickSaveUndoPlan;
	if (out.restoreProfile) deps.profiles.save(u.previous);
	if (out.restoreBean) {
		const bean = out.restoreBean as Bean;
		// The row the Save added is gone again: a bag that had no saved grinds goes back to none.
		const back = u.grindsWereNull && (bean.grinds ?? []).length === 0 ? { ...bean, grinds: null } : bean;
		deps.beans.storeBagFromCore(back);
	}
	return {
		profileRestored: out.restoreProfile,
		outcome: out.outcome,
		message: undoMessage(out.outcome, u.core.profileName)
	};
}

/** The Undo's message for how it came out. */
function undoMessage(outcome: QuickSaveUndoOutcome, name: string): string {
	switch (outcome) {
		case QuickSaveUndoOutcome.Undone:
			return 'Save undone';
		case QuickSaveUndoOutcome.ProfileOnly:
			return `“${name}” put back; the grind changed since, not undone`;
		case QuickSaveUndoOutcome.GrindOnly:
			return `Grind put back; “${name}” changed since, not undone`;
		case QuickSaveUndoOutcome.NotUndone:
			return `“${name}” changed since; not undone`;
	}
}

/** The bag's row for `grinderId` (`undefined` = the "no grinder" slot), or `undefined` (the core's absent). */
function rowFor(bean: { grinds?: BagGrind[] | null }, grinderId: string | undefined): BagGrind | undefined {
	const id = grinderId ?? NO_GRINDER_ID;
	return bean.grinds?.find((g) => g.grinderId === id) ?? undefined;
}

/** A plain copy of a (possibly reactive) row, as stored. */
function plain<T>(row: T): T {
	return row === undefined ? row : (JSON.parse(JSON.stringify(row)) as T);
}
