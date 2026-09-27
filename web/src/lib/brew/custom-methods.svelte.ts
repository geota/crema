/**
 * `$lib/brew/custom-methods` — the user's own brewing methods ("my ORB",
 * issue #10 feedback).
 *
 * The core owns the shape and every rule (`CustomBrewMethod`, the style
 * seeds, the merged picker list, label validation, the style-shaped first
 * recipe); this store only persists the list — `localStorage`, beside the
 * recipe library, soft-deleted with tombstones so a deleted method's past
 * brews still resolve (and seed "Log again") — and backs it up on the
 * `kind:"brewMethod"` lines.
 */

import type {
	BrewMethodPreset,
	BrewRecipe,
	CustomBrewMethod,
	CustomMethodLabelCheck
} from '$lib/core/crema-core';
import { BrewMethodStyle, CustomMethodLabelError } from '$lib/core/crema-core';
import { readJson, writeJsonChecked } from '$lib/utils/storage';
import {
	blankRecipeForStyleJson,
	brewMethodStyleSeedsJson,
	newCustomMethodId as wasmNewCustomMethodId,
	validateCustomMethodLabelJson
} from '$lib/wasm/de1_wasm';
import { PRESET_METHOD_LABELS } from './method-labels';

const METHODS_KEY = 'crema.brewMethods.custom.v1';

/** The reserved id namespace (core `CUSTOM_METHOD_ID_PREFIX`). */
export const CUSTOM_METHOD_PREFIX = 'custom:';

/** The longest label, characters (core `CUSTOM_METHOD_LABEL_MAX_CHARS`). */
export const CUSTOM_METHOD_LABEL_MAX = 40;

/** Whether a stored method string names a user-defined method. */
export function isCustomMethodId(method: string | null | undefined): boolean {
	return !!method && method.trim().toLowerCase().startsWith(CUSTOM_METHOD_PREFIX);
}

/** The four styles, in display order, with their picker copy. */
export const METHOD_STYLES: readonly { id: BrewMethodStyle; label: string; hint: string }[] = [
	{ id: BrewMethodStyle.Percolation, label: 'Pour-over', hint: 'Water poured through a bed' },
	{ id: BrewMethodStyle.Immersion, label: 'Immersion', hint: 'Steep, then separate' },
	{ id: BrewMethodStyle.Pressure, label: 'Pressure', hint: 'Steep, then press' },
	{ id: BrewMethodStyle.Cold, label: 'Cold', hint: 'Long cold steep' }
];

/**
 * The shared icon keys a custom method may wear — the same eight strings on
 * Android, so a backup renders alike on both shells. `MethodMark` maps each
 * to a Phosphor glyph.
 */
export const METHOD_ICON_KEYS = [
	'funnel',
	'coffee',
	'cylinder',
	'snowflake',
	'drop',
	'flask',
	'fire',
	'leaf'
] as const;
export type MethodIconKey = (typeof METHOD_ICON_KEYS)[number];

/** Each style's default icon key. */
export const STYLE_DEFAULT_ICON: Readonly<Record<BrewMethodStyle, MethodIconKey>> = {
	[BrewMethodStyle.Percolation]: 'funnel',
	[BrewMethodStyle.Immersion]: 'coffee',
	[BrewMethodStyle.Pressure]: 'cylinder',
	[BrewMethodStyle.Cold]: 'snowflake'
};

/** The icon key a method renders with: its own pick, else its style's default. */
export function methodIconKey(m: Pick<CustomBrewMethod, 'icon' | 'style'>): MethodIconKey {
	const own = m.icon as MethodIconKey | undefined;
	return own && METHOD_ICON_KEYS.includes(own)
		? own
		: STYLE_DEFAULT_ICON[m.style ?? BrewMethodStyle.Percolation];
}

/** The seeds a style prefills the add-method dialog with (core `brew_method_style_seeds`). */
export function styleSeeds(style: BrewMethodStyle): BrewMethodPreset {
	return JSON.parse(brewMethodStyleSeedsJson(style)) as BrewMethodPreset;
}

/** Mint a `custom:<uuid-v7>` id via the core, with a boot fallback. */
export function customMethodId(): string {
	try {
		return wasmNewCustomMethodId();
	} catch {
		const rnd =
			typeof crypto !== 'undefined' && 'randomUUID' in crypto
				? crypto.randomUUID()
				: Math.random().toString(36).slice(2) + Date.now().toString(36);
		return `${CUSTOM_METHOD_PREFIX}${rnd}`;
	}
}

/** The copy for a refused label. */
export function labelErrorText(error: CustomMethodLabelError | null | undefined): string | null {
	switch (error) {
		case CustomMethodLabelError.Empty:
			return 'Give the method a name.';
		case CustomMethodLabelError.TooLong:
			return `Keep it to ${CUSTOM_METHOD_LABEL_MAX} characters.`;
		case CustomMethodLabelError.Duplicate:
			return 'That name is already taken by a method.';
		default:
			return null;
	}
}

/** What the add / edit dialog hands back. */
export interface CustomMethodDraft {
	label: string;
	style: BrewMethodStyle;
	icon: string | null;
	seedDoseG: number | null;
	seedWaterG: number | null;
	seedTempC: number | null;
}

/**
 * The custom-method list — a Svelte 5 `$state` class; obtain the
 * singleton with {@link getCustomMethodStore}.
 */
export class CustomMethodStore {
	private methods = $state<CustomBrewMethod[]>([]);

	constructor() {
		this.methods = readJson<CustomBrewMethod[]>(METHODS_KEY, []);
	}

	/** Live methods, oldest first (the order they join the pickers in). */
	get live(): CustomBrewMethod[] {
		return this.methods
			.filter((m) => m.deletedAt == null)
			.sort((a, b) => a.createdAt - b.createdAt);
	}

	/** Every stored method, tombstones included — seeding and labels resolve these. */
	get everything(): readonly CustomBrewMethod[] {
		return this.methods;
	}

	/** A method by id, tombstoned or not. */
	get(id: string | null | undefined): CustomBrewMethod | undefined {
		const key = id?.trim().toLowerCase();
		return key ? this.methods.find((m) => m.id === key) : undefined;
	}

	/** Whether `id` is a live (pickable) method. */
	isLive(id: string | null | undefined): boolean {
		return this.get(id)?.deletedAt == null && this.get(id) != null;
	}

	/**
	 * Check a name (core `validate_custom_method_label`): trimmed, 1–40
	 * characters, unique against the presets and the live methods except
	 * `editingId`.
	 */
	validate(label: string, editingId?: string | null): CustomMethodLabelCheck {
		return JSON.parse(
			validateCustomMethodLabelJson(
				JSON.stringify({
					label,
					editingId: editingId ?? undefined,
					customMethods: this.methods,
					presetLabels: Object.values(PRESET_METHOD_LABELS)
				})
			)
		) as CustomMethodLabelCheck;
	}

	/** Create a method from a (validated) draft and persist it. Returns it, or `null` when the label is refused. */
	create(draft: CustomMethodDraft): CustomBrewMethod | null {
		const check = this.validate(draft.label);
		if (check.error) return null;
		const now = Date.now();
		const method: CustomBrewMethod = {
			id: customMethodId(),
			label: check.label,
			style: draft.style,
			...clean(draft),
			createdAt: now,
			updatedAt: now
		};
		this.methods = [...this.methods, method];
		this.persist();
		return method;
	}

	/** Rename / change defaults. Returns the stored method, or `null` when refused. */
	update(id: string, draft: CustomMethodDraft): CustomBrewMethod | null {
		const current = this.get(id);
		if (!current) return null;
		const check = this.validate(draft.label, id);
		if (check.error) return null;
		const next: CustomBrewMethod = {
			id: current.id,
			label: check.label,
			style: draft.style,
			...clean(draft),
			createdAt: current.createdAt,
			updatedAt: Date.now(),
			deletedAt: current.deletedAt
		};
		this.methods = this.methods.map((m) => (m.id === current.id ? next : m));
		this.persist();
		return next;
	}

	/**
	 * Tombstone a method. Past brews keep their label (the method still
	 * resolves, and each row carries a snapshot); user recipes that use it
	 * are kept, still runnable and labelled.
	 */
	remove(id: string): void {
		const now = Date.now();
		this.methods = this.methods.map((m) =>
			m.id === id && m.deletedAt == null ? { ...m, deletedAt: now, updatedAt: now } : m
		);
		this.persist();
	}

	/** A custom method's first recipe (core `blank_recipe_for_style`), named "<label> recipe". */
	blankRecipe(id: string, recipeId: string): BrewRecipe | null {
		const m = this.get(id);
		if (!m) return null;
		const r = JSON.parse(blankRecipeForStyleJson(JSON.stringify(m), recipeId, Date.now())) as BrewRecipe;
		return { ...r, name: `${m.label} recipe` };
	}

	// ── Backup ───────────────────────────────────────────────────

	/** Every stored method, tombstones included (old rows still resolve after a restore). */
	backupMethods(): CustomBrewMethod[] {
		return [...this.methods];
	}

	/**
	 * Apply a backup's methods. `wipe` replaces the list; a merge adds ids
	 * not already known (local edits win). Returns how many were added.
	 */
	applyBackup(methods: CustomBrewMethod[], wipe: boolean): number {
		const base = wipe ? [] : this.methods;
		const known = new Set(base.map((m) => m.id));
		const added = methods.filter((m) => isCustomMethodId(m.id) && !known.has(m.id));
		this.methods = [...base, ...added];
		this.persist();
		return added.length;
	}

	private persist(): void {
		writeJsonChecked(METHODS_KEY, this.methods);
	}
}

/** Drop blank / non-positive seeds and an icon equal to the style default. */
function clean(d: CustomMethodDraft): Partial<CustomBrewMethod> {
	const pos = (v: number | null) => (v != null && Number.isFinite(v) && v > 0 ? v : undefined);
	const out: Partial<CustomBrewMethod> = {};
	const icon = d.icon && d.icon !== STYLE_DEFAULT_ICON[d.style] ? d.icon : undefined;
	if (icon) out.icon = icon;
	const dose = pos(d.seedDoseG);
	if (dose != null) out.seedDoseG = dose;
	const water = pos(d.seedWaterG);
	if (water != null) out.seedWaterG = water;
	const temp = pos(d.seedTempC);
	if (temp != null) out.seedTempC = temp;
	return out;
}

let store: CustomMethodStore | null = null;
export function getCustomMethodStore(): CustomMethodStore {
	store ??= new CustomMethodStore();
	return store;
}
