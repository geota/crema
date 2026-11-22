/**
 * `useStepper` — the shared numeric core behind the two stepper chromes
 * (`brew/QuickStepper`, `settings/StStepper`). Both owned the same clamp +
 * canonical↔display conversion + grid-snapped `inc` + click-to-type state
 * machine under different presentation — their `inc` comments even
 * cross-referenced each other. This centralises that logic; each component keeps
 * only its own markup + styles.
 *
 * Call it once at the top of a stepper's `<script>`, passing the reactive props
 * as **getters** (so the core tracks them) plus a couple of behavioural knobs.
 * Returns the effective unit/decimals, the `inc` stepper, and the click-to-type
 * state — bind the inline input with `bind:value={stepper.draft}` and
 * `bind:this={stepper.inputEl}` (both are getter/setter lvalues).
 *
 * `value` / `step` / `min` / `max` are always canonical (g / °C / ml / bar).
 * With a `dimension` the core converts to the user's unit on display and back on
 * commit, and steps in display units so each click moves a visible digit — see
 * {@link Stepper.inc}.
 */
import {
	canonicalToDisplay,
	displayDecimals,
	displayToCanonical,
	unitLabel,
	type Dimension
} from '#lib/settings/format.js';
import { getSettingsStore } from '#lib/settings/store.svelte.js';

export interface UseStepperOptions {
	/** Current value, canonical units (reactive getter). */
	value: () => number;
	/** Commit a clamped canonical value (a ± press or an accepted edit). */
	commit: (next: number) => void;
	/** Step per ± press, canonical (reactive). */
	step: () => number;
	/** Lower clamp bound, canonical, or `undefined` for unbounded (reactive). */
	min?: () => number | undefined;
	/** Upper clamp bound, canonical, or `undefined` for unbounded (reactive). */
	max?: () => number | undefined;
	/** Unit-aware dimension; converts display↔canonical when set (reactive). */
	dimension?: () => Dimension | undefined;
	/** Fallback unit label when no `dimension` (reactive). Default `''`. */
	unit?: () => string;
	/** Fallback decimal places when no `dimension` (reactive). Default `0`. */
	decimals?: () => number;
	/**
	 * Decimals used to trim float error after `value + dir*step` in the
	 * non-dimension `inc` path. QuickStepper used 2, StStepper 4. Default 4.
	 */
	incPrecision?: number | (() => number);
	/** Whether click-to-type is allowed (reactive). Default: always. */
	canEdit?: () => boolean;
	/**
	 * Whether a typed value is clamped into `min`..`max` (reactive). Default
	 * true. The grind (#488) keeps a typed value outside its scale as typed:
	 * values are never clamped or rewritten, only warned about.
	 */
	clampTyped?: () => boolean;
	/**
	 * Whether the value is blank (reactive): not set, shown empty (Brew's
	 * grind, DESIGN "No grind set is its own state"). The first ± from blank
	 * commits `value()` itself (the caller's start-from-blank), not a step
	 * from it, and click-to-type starts with an empty draft.
	 */
	blank?: () => boolean;
	/**
	 * Reads click-to-type's draft as a number (display units), `null` when it
	 * isn't one (reactive). Default: `Number`. The grind reads through the
	 * core's `grind_number`, so a typed "4⅓" or "4 1/3" is a number (#488).
	 */
	parse?: () => ((text: string) => number | null) | undefined;
	/**
	 * Click-to-type's starting text for a value in display units (reactive).
	 * Default: the value at `decimals`. The grind starts from its own text
	 * ("4⅓").
	 */
	editText?: () => ((display: number) => string) | undefined;
	/**
	 * Takes a typed draft that {@link parse} doesn't read as a number
	 * (trimmed, never blank), reactive. Without it such a draft is no edit.
	 * Brew's grind keeps it as typed: "6 + a tooth" is its text grind (#28,
	 * the core's text-grind rule: no dial, no chips).
	 */
	commitText?: () => ((text: string) => void) | undefined;
	/**
	 * Click-to-type's starting text while {@link blank}: empty by default;
	 * Brew's text grind starts from itself (reactive).
	 */
	blankDraft?: () => string;
}

export interface Stepper {
	/** Effective unit suffix — the dimension's pref label, or the fallback. */
	readonly unit: string;
	/** Effective decimal places — the dimension's, or the fallback. */
	readonly decimals: number;
	/** Canonical → display-unit number (identity without a dimension). */
	toDisplay(canonical: number): number;
	/** Current value in display units, fixed to `decimals` (StStepper's text). */
	readonly display: string;
	/** Step the value by `dir × step`, clamped; commits only on a real change. */
	inc(dir: number): void;
	/** Whether the inline number input is showing. */
	readonly editing: boolean;
	/** The inline-edit draft text — bind with `bind:value`. */
	draft: string;
	/** The inline input element — bind with `bind:this`. */
	inputEl: HTMLInputElement | null;
	/** Enter edit mode (focus + select), unless `canEdit` returns false. */
	beginEdit(): void;
	/** Commit the draft (Enter / blur). */
	commit(): void;
	/** Enter commits, Escape cancels. */
	onKey(e: KeyboardEvent): void;
}

export function useStepper(opts: UseStepperOptions): Stepper {
	const settings = getSettingsStore();

	const effectiveUnit = $derived.by(() => {
		const d = opts.dimension?.();
		return d ? unitLabel(d, settings.current) : (opts.unit?.() ?? '');
	});
	const effectiveDecimals = $derived.by(() => {
		const d = opts.dimension?.();
		return d ? displayDecimals(d, settings.current) : (opts.decimals?.() ?? 0);
	});

	function toDisplay(canonical: number): number {
		const d = opts.dimension?.();
		return d ? canonicalToDisplay(d, canonical, settings.current) : canonical;
	}
	function fromDisplay(display: number): number {
		const d = opts.dimension?.();
		return d ? displayToCanonical(d, display, settings.current) : display;
	}

	function clamp(n: number): number {
		const lo = opts.min?.();
		const hi = opts.max?.();
		if (lo !== undefined) n = Math.max(lo, n);
		if (hi !== undefined) n = Math.min(hi, n);
		return n;
	}

	const precision = (): number => {
		const p = opts.incPrecision;
		return typeof p === 'function' ? p() : (p ?? 4);
	};

	function inc(dir: number): void {
		const value = opts.value();
		if (opts.blank?.()) {
			opts.commit(value);
			return;
		}
		if (opts.dimension?.()) {
			// Work in display units so every click moves the visible digit — a
			// canonical step finer than the display grid would otherwise round to
			// a no-op. Convert the step to display units, floor at one grid step
			// (10^-decimals), then snap the next value to the grid.
			const displayNow = toDisplay(value);
			const grid = Math.pow(10, -effectiveDecimals);
			const displayStep = Math.max(grid, toDisplay(value + opts.step()) - displayNow);
			const nextDisplay = Math.round((displayNow + dir * displayStep) / grid) * grid;
			const next = clamp(fromDisplay(nextDisplay));
			if (next !== value) opts.commit(next);
			return;
		}
		const next = clamp(Number((value + dir * opts.step()).toFixed(precision())));
		if (next !== value) opts.commit(next);
	}

	let editing = $state(false);
	let draft = $state('');
	let inputEl = $state<HTMLInputElement | null>(null);

	function beginEdit(): void {
		if (opts.canEdit && !opts.canEdit()) return;
		const shown = toDisplay(opts.value());
		const editText = opts.editText?.();
		draft = opts.blank?.()
			? (opts.blankDraft?.() ?? '')
			: editText
				? editText(shown)
				: String(Number(shown.toFixed(effectiveDecimals)));
		editing = true;
		queueMicrotask(() => {
			inputEl?.focus();
			inputEl?.select();
		});
	}
	function commit(): void {
		if (!editing) return;
		editing = false;
		// An emptied field is no edit (not 0).
		const text = String(draft).trim();
		if (text === '') return;
		const parse = opts.parse?.();
		const n = parse ? parse(text) : Number(draft);
		if (n === null || !Number.isFinite(n)) {
			opts.commitText?.()?.(text);
			return;
		}
		const typed = fromDisplay(n);
		const next = opts.clampTyped?.() === false ? typed : clamp(typed);
		if (opts.blank?.() || next !== opts.value()) opts.commit(next);
	}
	function onKey(e: KeyboardEvent): void {
		if (e.key === 'Enter') {
			e.preventDefault();
			commit();
		} else if (e.key === 'Escape') {
			e.preventDefault();
			editing = false;
		}
	}

	return {
		get unit() {
			return effectiveUnit;
		},
		get decimals() {
			return effectiveDecimals;
		},
		toDisplay,
		get display() {
			return toDisplay(opts.value()).toFixed(effectiveDecimals);
		},
		inc,
		get editing() {
			return editing;
		},
		get draft() {
			return draft;
		},
		set draft(v: string) {
			draft = v;
		},
		get inputEl() {
			return inputEl;
		},
		set inputEl(el: HTMLInputElement | null) {
			inputEl = el;
		},
		beginEdit,
		commit,
		onKey
	};
}
