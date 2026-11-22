/**
 * `#lib/brew/quick-save.js` — what Quick Controls' **Save** writes
 * (save-boundaries review F1, 2026-11-21; the rule Android's `QuickSave.kt`
 * follows): each changed part goes to its owner, and only once the user
 * confirms.
 *
 *  - The grind dial differs from the active bag's saved grind → the grind
 *    goes to the bag. No profile copy and no name prompt.
 *  - The recipe dials (dose, yield, brew temp, pre-infusion) differ from the
 *    active profile → a **custom** profile is updated in place (Undo in the
 *    message, and "Save as new…" beside Save); a **built-in** is read-only,
 *    so it is copied under a name the user gives, and the copy made active.
 *  - Nothing differs → Save is off. With no profile, Save still saves the
 *    grind.
 *
 * Pure apart from the stores it is handed, so the rule is tested without
 * the dashboard.
 */

import type { Bean } from '#lib/bean/index.js';
import type { CremaProfile, ProfileFingerprintOverrides } from '#lib/profiles/index.js';
import { sameRecord } from '#lib/utils/same-record.js';

/** The kind of profile Save would write to. */
export type QuickSaveProfileKind = 'none' | 'custom' | 'builtin';

/** What Save would write, for the state on screen. */
export interface QuickSavePlan {
	readonly beanId: string | null;
	readonly beanName: string | null;
	/** The dial as the bag stores it, when it differs from the bag's; else `null`. */
	readonly grind: string | null;
	readonly profileId: string | null;
	readonly profileName: string | null;
	readonly profileKind: QuickSaveProfileKind;
	/** The recipe dials differ from the active profile's own. */
	readonly recipeChanged: boolean;
}

/** The bag's form of the grind dial: whole numbers without a decimal. */
export function grindSettingOf(dial: number): string {
	return Number.isInteger(dial) ? String(dial) : dial.toFixed(1);
}

/** A bag's saved grind as a number, or `null` when it has none (or not a number). */
function savedGrind(bean: Bean): number | null {
	const raw = (bean.grinderSetting ?? '').trim().replace(',', '.');
	const n = raw ? Number.parseFloat(raw) : Number.NaN;
	return Number.isFinite(n) ? n : null;
}

/**
 * The plan for the active `bean`, the grind `dial`, the active `profile` and
 * its `overrides` — normalized by the core's lifecycle rule
 * (`normalizeBrewOverrides`), so a dial back at the profile's own value is
 * no change. A bag with no saved grind and the dial at its untouched
 * `dialDefault` is no change either (the dial carries nothing then, as the
 * shot history reads it).
 */
export function quickSavePlan(input: {
	bean: Bean | null;
	dial: number;
	dialDefault?: number;
	profile: CremaProfile | null;
	overrides: ProfileFingerprintOverrides;
}): QuickSavePlan {
	const { bean, dial, profile } = input;
	let grind: string | null = null;
	if (bean && Number.isFinite(dial)) {
		const saved = savedGrind(bean);
		const untouched = saved === null && dial === input.dialDefault;
		if (saved !== dial && !untouched) grind = grindSettingOf(dial);
	}
	return {
		beanId: bean?.id ?? null,
		beanName: bean?.name ?? null,
		grind,
		profileId: profile?.id ?? null,
		profileName: profile?.name ?? null,
		profileKind: profile == null ? 'none' : profile.source === 'custom' ? 'custom' : 'builtin',
		recipeChanged: profile != null && Object.keys(input.overrides).length > 0
	};
}

/** Save writes the recipe to a profile (there is one to write to). */
export function writesRecipe(plan: QuickSavePlan): boolean {
	return plan.recipeChanged && plan.profileKind !== 'none';
}

/** Save has something to write. */
export function saveEnabled(plan: QuickSavePlan): boolean {
	return plan.grind !== null || writesRecipe(plan);
}

/** Save asks for a name first: a built-in is read-only, so it's a copy. */
export function needsName(plan: QuickSavePlan): boolean {
	return saveEnabled(plan) && writesRecipe(plan) && plan.profileKind === 'builtin';
}

/** "Save as new…" is offered: a custom profile would otherwise be updated in place. */
export function offersSaveAsNew(plan: QuickSavePlan): boolean {
	return saveEnabled(plan) && writesRecipe(plan) && plan.profileKind === 'custom';
}

/** The name a copy's prompt starts from. */
export function copyNameSeed(plan: QuickSavePlan): string {
	return plan.profileName ? `${plan.profileName} (copy)` : '';
}

/** The stores and core rules a Save writes through. */
export interface QuickSaveDeps {
	readonly beans: {
		getBean(id: string): Bean | null;
		updateBean(id: string, patch: Partial<Bean>): void;
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
	readonly profileId: string;
	readonly profileName: string;
	/** The profile's body before the Save. */
	readonly previous: CremaProfile;
	/** The body the Save wrote: Undo restores only over this (REVIEW-496 M1). */
	readonly written: CremaProfile;
	/** The bag grind the same Save wrote, and the one it replaced. */
	readonly grind: { readonly beanId: string; readonly before: string; readonly written: string } | null;
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
	if (!saveEnabled(plan)) return null;
	const recipe = writesRecipe(plan);
	const copying = recipe && (opts.asNew === true || plan.profileKind === 'builtin');
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
				profileId: profile.id,
				profileName: profile.name,
				previous: plain(profile),
				written: plain(deps.profiles.get(profile.id) ?? baked),
				grind: null
			};
			parts.push(`“${profile.name}” updated`);
		}
	}
	if (plan.grind !== null && plan.beanId !== null) {
		const before = deps.beans.getBean(plan.beanId)?.grinderSetting ?? '';
		deps.beans.updateBean(plan.beanId, { grinderSetting: plan.grind });
		parts.push(`${parts.length === 0 ? 'Grind' : 'grind'} ${plan.grind} saved to ${plan.beanName}`);
		if (undo) undo = { ...undo, grind: { beanId: plan.beanId, before, written: plan.grind } };
	}
	return { message: parts.join(' · '), undo };
}

/**
 * Undo an in-place Save: the profile's body comes back only while it is
 * still the body the Save wrote (a later pin, rename or edit stands), and
 * the bag's grind only while it is still the one written.
 */
export function undoQuickSave(
	u: QuickSaveUndo,
	deps: Pick<QuickSaveDeps, 'beans' | 'profiles'>
): { profileRestored: boolean; message: string } {
	const now = deps.profiles.get(u.profileId);
	const profileRestored = now !== undefined && sameRecord(now, u.written);
	if (profileRestored) deps.profiles.save(u.previous);
	if (u.grind && deps.beans.getBean(u.grind.beanId)?.grinderSetting === u.grind.written) {
		deps.beans.updateBean(u.grind.beanId, { grinderSetting: u.grind.before });
	}
	return {
		profileRestored,
		message: profileRestored ? 'Save undone' : `“${u.profileName}” changed since; not undone`
	};
}

/** A plain copy of a (possibly reactive) row, as stored. */
function plain<T>(row: T): T {
	return JSON.parse(JSON.stringify(row)) as T;
}
