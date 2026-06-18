/**
 * `brew-params` — the Quick Sheet's local parameter model.
 *
 * Backing state for the Quick Sheet steppers / chips — a Svelte 5 `$state`
 * class, the web mirror of the design's `useParamsV2` hook in
 * `quick-controls-v2.jsx`. Mode flags + per-card values live here; the
 * orchestrator reads / pushes them through the relevant core setters when
 * the user mutates a control.
 *
 * Wiring status (kept here so changes stay coherent with the orchestrator):
 *   - `yield` — wired (core SAW target; see `applyShotTargetWeight`).
 *   - `brewTemp`, `preinf` — wired as profile QC overrides at activation.
 *   - `dose` — recorded only (no DE1 control; logged on `ShotCompleted`).
 *   - `grind` — log-only.
 *   - steam (`steamTime` / `steamFlow` / `steamTemp`), hot water
 *     (`waterTemp` / `waterVolume`) — local UI only; no DE1 control yet.
 */

/** The full mode-aware Quick Sheet parameter set — mirrors `DEFAULT_PARAMS_V2`. */
export interface BrewParams {
	/** Which of Dose / Grind the combined card is editing. */
	doseGrindMode: 'dose' | 'grind';
	/** Dose, grams in. */
	dose: number;
	/** Grinder click setting (log only — never reaches the machine). */
	grind: number;
	/** Stop-on-weight yield target, grams. */
	yield: number;
	/** Which of Temp / Pre-infuse the Brew bucket is editing. */
	brewMode: 'temp' | 'preinf';
	/** Brew water temperature, °C. */
	brewTemp: number;
	/** Pre-infusion duration, seconds. */
	preinf: number;
	/** Which of Time / Flow / Temp the Steam bucket is editing. */
	steamMode: 'time' | 'flow' | 'temp';
	/** Steam duration, seconds. */
	steamTime: number;
	/** Steam flow rate, ml/s. */
	steamFlow: number;
	/** Steam boiler target temperature, °C. */
	steamTemp: number;
	/** Which of Temp / Volume the Hot Water card is editing. */
	waterMode: 'temp' | 'volume';
	/** Hot-water temperature, °C. */
	waterTemp: number;
	/** Hot-water volume, ml. */
	waterVolume: number;
	/** Which of Time / Temp the Flush bucket is editing. */
	flushMode: 'time' | 'temp';
	/** Group-flush duration, seconds. */
	flushTime: number;
	/** Group-flush target temperature, °C. */
	flushTemp: number;
	/** Stop the shot when the yield target is reached. */
	stopOnWeight: boolean;
	/** Tare the scale automatically at shot start. */
	autoTare: boolean;
}

/** The default parameter set — matches the design's `DEFAULT_PARAMS_V2`. */
export const DEFAULT_BREW_PARAMS: BrewParams = {
	doseGrindMode: 'dose',
	dose: 18.0,
	grind: 4.2,
	yield: 36.4,
	brewMode: 'temp',
	brewTemp: 93.0,
	preinf: 8,
	steamMode: 'time',
	steamTime: 12,
	steamFlow: 1.2,
	steamTemp: 148.0,
	waterMode: 'volume',
	waterTemp: 80,
	waterVolume: 150,
	flushMode: 'time',
	flushTime: 4,
	flushTemp: 95.0,
	stopOnWeight: true,
	autoTare: true
};

/**
 * The brew-target subset a profile (or the Settings brew defaults) seeds — the
 * fields the active profile / D2 fallback owns. The remaining `BrewParams`
 * fields (mode toggles, steam, hot water) are pure UI defaults the seed never
 * touches.
 */
export type BrewParamSeed = Pick<BrewParams, 'dose' | 'yield' | 'brewTemp' | 'preinf'>;

/**
 * The four QC keys that genuinely affect the profile-upload bytes — dose,
 * yield, brew temp, pre-infusion. Drift italics in the Quick Sheet, the
 * profile-sync fingerprint, and the shot-start lazy re-upload all agree on
 * this set. Mode toggles (`doseGrindMode` etc.) are pure UI affordance;
 * steam / hot-water / flush fields the profile doesn't carry have no
 * "default" to drift from.
 */
export type BrewParamSeedKey = keyof BrewParamSeed;

/**
 * The reactive Quick Sheet parameter store — a `$derived`-backed `BrewParams`.
 *
 * It is constructed with a *seed* getter (the active profile's targets, or the
 * Settings brew defaults when no profile is active). `current` is a `$derived`
 * mirror of that seed merged onto {@link DEFAULT_BREW_PARAMS}; a stepper edit
 * (`set` / `reset`) reassigns the `$derived` to a plain value, after which the
 * params are the component's own state. When the seed inputs genuinely change
 * — a different profile, an edited Settings default — the `$derived` re-runs
 * and re-seeds, exactly the reassignable-`$derived` pattern `ProfileEditor`'s
 * `draft` uses. This is why there is no hand-rolled "already seeded" sentinel:
 * the seed is a pure `$derived`, so it cannot go stale or stop tracking after
 * the first seed the way the old `lastSeededId`-gated `$effect` did.
 */
export class BrewParamState {
	/**
	 * The live parameter set. Starts as a `$derived` over the seed getter; a
	 * `set` / `reset` reassigns it (Svelte 5.25+ permits reassigning a
	 * `$derived`), after which it tracks the component, not the seed, until a
	 * seed input changes and the `$derived` body re-runs.
	 */
	current = $derived.by<BrewParams>(() => ({
		...DEFAULT_BREW_PARAMS,
		...this.qcSeed?.(),
		...this.seed()
	}));

	/**
	 * @param seed A getter for the current brew-target seed. Read inside the
	 *   `current` `$derived`, so it must touch reactive state to track changes.
	 * @param onWrite Optional per-key write hook fired when {@link set} mutates
	 *   the given key — used by `BrewDashboard` to route steam / hot-water /
	 *   flush steppers through to the machine (RMW + Settings persistence).
	 * @param qcSeed Optional getter for the persisted steam / hot-water / flush
	 *   values (issue 14). Merged *under* the profile seed so the Quick Sheet
	 *   seeds those machine params from the user's last choice rather than the
	 *   hardcoded {@link DEFAULT_BREW_PARAMS}. Not part of the profile-drift
	 *   logic (`isOverridden` / `qcOverrides` stay on the four seed keys).
	 */
	constructor(
		private readonly seed: () => BrewParamSeed,
		private readonly onWrite?: <K extends keyof BrewParams>(
			key: K,
			value: BrewParams[K]
		) => void,
		private readonly qcSeed?: () => Partial<BrewParams>
	) {}

	/** Set one parameter — the steppers' `onChange`. */
	set<K extends keyof BrewParams>(key: K, value: BrewParams[K]): void {
		// Most keys are local-only UI; the machine-facing keys (steam
		// temp/time/flow, hot-water temp/volume, flush time/temp) route
		// through `onWrite` to push the value onto the DE1. Reassigning the
		// `$derived` overrides the seed with a local edit either way.
		this.current = { ...this.current, [key]: value };
		this.onWrite?.(key, value);
	}

	/** Restore the params to the current seed — the sheet's "Reset" action. */
	reset(): void {
		this.current = { ...DEFAULT_BREW_PARAMS, ...this.qcSeed?.(), ...this.seed() };
	}

	/**
	 * Whether the live value for `key` differs from the active seed — the
	 * "drift" indicator the Quick Sheet steppers use to italicise +
	 * copper-tint an overridden field. Reads both `current` and `seed` so
	 * a re-seed (different profile, edited Settings default) re-renders
	 * the indicator without a manual reset.
	 *
	 * Numeric comparison uses strict equality — the stepper increments
	 * and seed values are both in canonical units, so the only drift
	 * source is a real user edit; no float-rounding fuzz is needed in
	 * practice.
	 */
	isOverridden(key: BrewParamSeedKey): boolean {
		return this.current[key] !== this.seed()[key];
	}

	/**
	 * The active seed value for `key` — the per-profile / per-settings
	 * default the current value would snap back to on Reset. Steppers
	 * read this to format the drift tooltip ("Overriding default 93 °C")
	 * in the user's pref unit; no component should imperatively cache
	 * the seed itself because the underlying `$derived` chain
	 * re-evaluates when the active profile or Settings default changes.
	 */
	seedOf(key: BrewParamSeedKey): number {
		return this.seed()[key];
	}

	/**
	 * The current QC override snapshot — the four seed-tracking fields
	 * tagged with whether each one was edited away from the seed. Fed
	 * into `profileFingerprint` at shot start so the lazy re-upload
	 * triggers exactly when the user's intent diverges from what's on
	 * the DE1.
	 *
	 * Returns the *override*-only subset: a field whose `current` still
	 * matches the seed is left absent. The fingerprint helper then falls
	 * back to the profile's own value, which keeps two paths in
	 * agreement — a "dose stays at the profile's 18 g" hash equals a
	 * "dose dial untouched" hash.
	 */
	qcOverrides(): Partial<BrewParamSeed> {
		const cur = this.current;
		const seed = this.seed();
		const out: Partial<BrewParamSeed> = {};
		if (cur.dose !== seed.dose) out.dose = cur.dose;
		if (cur.yield !== seed.yield) out.yield = cur.yield;
		if (cur.brewTemp !== seed.brewTemp) out.brewTemp = cur.brewTemp;
		if (cur.preinf !== seed.preinf) out.preinf = cur.preinf;
		return out;
	}
}
