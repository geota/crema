<script lang="ts">
	/**
	 * `CustomMethodDialog` — add or edit one of the user's own brewing
	 * methods ("my ORB", issue #10 feedback). A small modal above whatever
	 * picker opened it (log form, recipe editor, Brew setup, Profiles):
	 * Name (required), Style (four, each with its mark), an optional icon
	 * from the shared set, and optional Dose / Water / Temp prefilled from
	 * the style. Saving creates (or updates) the method and hands it back —
	 * the caller selects it.
	 */
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
	import type { CustomBrewMethod } from '$lib/core/crema-core';
	import { BrewMethodStyle } from '$lib/core/crema-core';
	import {
		CUSTOM_METHOD_LABEL_MAX,
		METHOD_ICON_KEYS,
		METHOD_STYLES,
		STYLE_DEFAULT_ICON,
		getCustomMethodStore,
		labelErrorText,
		styleSeeds
	} from '$lib/brew/custom-methods.svelte';
	import MethodMark from './MethodMark.svelte';

	let {
		editing = null,
		initialLabel = '',
		onSave,
		onClose
	}: {
		/** The method to edit; `null` = add a new one. */
		editing?: CustomBrewMethod | null;
		/** A name to start from (the "Save 'X' as a method?" door). */
		initialLabel?: string;
		onSave: (method: CustomBrewMethod) => void;
		onClose: () => void;
	} = $props();

	const store = getCustomMethodStore();

	// One-shot seeds: the host `{#if}`s the dialog per open.
	// svelte-ignore state_referenced_locally
	const base = editing;
	// svelte-ignore state_referenced_locally
	const startLabel = base?.label ?? initialLabel;

	const startStyle = base?.style ?? BrewMethodStyle.Percolation;
	const startSeeds = styleSeeds(startStyle);
	let label = $state(startLabel);
	let style = $state<BrewMethodStyle>(startStyle);
	let icon = $state<string>(base?.icon ?? STYLE_DEFAULT_ICON[startStyle]);
	let iconTouched = $state(base?.icon != null);
	let dose = $state<number | null>(base?.seedDoseG ?? startSeeds.seedDoseG);
	let water = $state<number | null>(base?.seedWaterG ?? startSeeds.seedWaterG ?? null);
	let temp = $state<number | null>(base?.seedTempC ?? startSeeds.seedTempC ?? null);
	let attempted = $state(false);
	/** Seed fields the user typed in — a style change doesn't overwrite them. */
	const dirty = new Set<string>(
		base
			? [
					...(base.seedDoseG != null ? ['dose'] : []),
					...(base.seedWaterG != null ? ['water'] : []),
					...(base.seedTempC != null ? ['temp'] : [])
				]
			: []
	);

	const check = $derived(store.validate(label, base?.id));
	const error = $derived(labelErrorText(check.error));

	function pickStyle(next: BrewMethodStyle): void {
		style = next;
		const s = styleSeeds(next);
		if (!dirty.has('dose')) dose = s.seedDoseG;
		if (!dirty.has('water')) water = s.seedWaterG ?? null;
		if (!dirty.has('temp')) temp = s.seedTempC ?? null;
		if (!iconTouched) icon = STYLE_DEFAULT_ICON[next];
	}

	function num(raw: string): number | null {
		const n = Number(raw);
		return raw.trim() && Number.isFinite(n) && n > 0 ? n : null;
	}

	function save(): void {
		attempted = true;
		if (check.error) return;
		// A seed equal to the style default is stored blank, so it keeps
		// following the style.
		const s = styleSeeds(style);
		const draft = {
			label,
			style,
			icon,
			seedDoseG: dose != null && dose !== s.seedDoseG ? dose : null,
			seedWaterG: water != null && water !== (s.seedWaterG ?? null) ? water : null,
			seedTempC: temp != null && temp !== (s.seedTempC ?? null) ? temp : null
		};
		const saved = base ? store.update(base.id, draft) : store.create(draft);
		if (saved) onSave(saved);
	}

	function onKey(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			e.stopPropagation();
			onClose();
		} else if (e.key === 'Enter' && (e.target as HTMLElement)?.tagName === 'INPUT') {
			e.preventDefault();
			save();
		}
	}
</script>

<div
	class="cm-scrim"
	onclick={onClose}
	onkeydown={onKey}
	role="button"
	tabindex="-1"
	aria-label="Close"
></div>

<div
	class="cm-dialog"
	role="dialog"
	aria-modal="true"
	aria-labelledby="cm-title"
	tabindex="-1"
	onkeydown={onKey}
>
	<header class="cm-head">
		<div>
			<div class="t-eyebrow">Your methods</div>
			<h2 class="cm-title" id="cm-title">{base ? 'Edit method' : 'Add a method'}</h2>
		</div>
		<button class="cm-x" onclick={onClose} aria-label="Close">
			<XIcon aria-hidden="true" />
		</button>
	</header>

	<div class="cm-body">
		<label class="cm-field">
			<span class="cm-label">Name</span>
			<!-- svelte-ignore a11y_autofocus -->
			<input
				class="cm-input"
				bind:value={label}
				maxlength={CUSTOM_METHOD_LABEL_MAX + 10}
				placeholder="e.g. ORB"
				autofocus
				class:is-invalid={attempted && !!error}
				aria-invalid={attempted && !!error}
				aria-describedby="cm-error"
			/>
			{#if attempted && error}
				<span class="cm-error" id="cm-error" role="alert">{error}</span>
			{/if}
		</label>

		<div class="cm-field">
			<span class="cm-label">Style</span>
			<div class="cm-styles" role="radiogroup" aria-label="Style">
				{#each METHOD_STYLES as s (s.id)}
					<button
						type="button"
						class="cm-style"
						class:is-on={style === s.id}
						role="radio"
						aria-checked={style === s.id}
						onclick={() => pickStyle(s.id)}
					>
						<MethodMark iconKey={STYLE_DEFAULT_ICON[s.id]} size={18} />
						<span class="cm-style-name">{s.label}</span>
						<span class="cm-style-hint">{s.hint}</span>
					</button>
				{/each}
			</div>
		</div>

		<div class="cm-field">
			<span class="cm-label">Icon</span>
			<div class="cm-icons" role="radiogroup" aria-label="Icon">
				{#each METHOD_ICON_KEYS as k (k)}
					<button
						type="button"
						class="cm-icon"
						class:is-on={icon === k}
						role="radio"
						aria-checked={icon === k}
						aria-label={k}
						title={k}
						onclick={() => {
							icon = k;
							iconTouched = true;
						}}
					>
						<MethodMark iconKey={k} size={16} />
					</button>
				{/each}
			</div>
		</div>

		<div class="cm-seeds">
			<label class="cm-field">
				<span class="cm-label">Dose · g</span>
				<input
					class="cm-input cm-mono"
					inputmode="decimal"
					value={dose ?? ''}
					oninput={(e) => {
						dirty.add('dose');
						dose = num(e.currentTarget.value);
					}}
				/>
			</label>
			<label class="cm-field">
				<span class="cm-label">Water · g</span>
				<input
					class="cm-input cm-mono"
					inputmode="decimal"
					value={water ?? ''}
					oninput={(e) => {
						dirty.add('water');
						water = num(e.currentTarget.value);
					}}
				/>
			</label>
			<label class="cm-field">
				<span class="cm-label">Temp · °C</span>
				<input
					class="cm-input cm-mono"
					inputmode="decimal"
					value={temp ?? ''}
					placeholder="—"
					oninput={(e) => {
						dirty.add('temp');
						temp = num(e.currentTarget.value);
					}}
				/>
			</label>
		</div>
		<p class="cm-hint">Optional — the first log of this method opens with these.</p>
	</div>

	<footer class="cm-foot">
		<button class="cm-btn cm-btn-ghost" onclick={onClose}>Cancel</button>
		<button class="cm-btn cm-btn-primary" onclick={save}>
			<CheckIcon aria-hidden="true" />
			{base ? 'Save' : 'Add method'}
		</button>
	</footer>
</div>

<style>
	.cm-scrim {
		position: fixed;
		inset: 0;
		background: rgba(var(--scrim-rgb, 0, 0, 0), 0.45);
		z-index: 90;
	}
	.cm-dialog {
		position: fixed;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		width: min(440px, calc(100vw - 32px));
		max-height: calc(100dvh - 48px);
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: var(--radius-lg);
		z-index: 91;
		display: flex;
		flex-direction: column;
		overflow: hidden;
		box-shadow: var(--shadow-lg);
	}
	.cm-head {
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		gap: 16px;
		padding: 18px 20px 10px;
		flex-shrink: 0;
	}
	.cm-title {
		font-family: var(--font-serif);
		font-size: 20px;
		font-weight: 500;
		margin: 4px 0 0;
		color: var(--fg-1);
	}
	.cm-x {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		padding: 4px;
		cursor: pointer;
		border-radius: var(--radius-sm);
	}
	.cm-x:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}
	.cm-body {
		padding: 4px 20px 14px;
		display: flex;
		flex-direction: column;
		gap: 12px;
		overflow-y: auto;
		min-height: 0;
	}
	.cm-field {
		display: flex;
		flex-direction: column;
		gap: 5px;
		min-width: 0;
	}
	.cm-label {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.cm-input {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 14px;
		padding: 8px 10px;
		outline: 0;
		width: 100%;
		box-sizing: border-box;
	}
	.cm-input:focus {
		border-color: var(--copper-400);
	}
	.cm-input.is-invalid {
		border-color: var(--danger);
	}
	.cm-mono {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
	}
	.cm-error {
		font-size: 12px;
		color: var(--danger);
	}
	.cm-styles {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 6px;
	}
	.cm-style {
		display: grid;
		grid-template-columns: auto 1fr;
		grid-template-rows: auto auto;
		column-gap: 8px;
		align-items: center;
		text-align: left;
		padding: 8px 10px;
		border-radius: var(--radius-sm);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		background: transparent;
		color: var(--fg-1);
		cursor: pointer;
		font-family: var(--font-sans);
	}
	.cm-style :global(svg) {
		grid-row: span 2;
		color: var(--fg-accent, var(--copper-500));
	}
	.cm-style:hover {
		background: rgba(var(--tint-rgb), 0.05);
	}
	.cm-style.is-on {
		border-color: var(--copper-500);
		background: rgba(var(--tint-rgb), 0.06);
		box-shadow: inset 0 0 0 1px var(--copper-500);
	}
	.cm-style-name {
		font-size: 13px;
		font-weight: 600;
	}
	.cm-style-hint {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.cm-icons {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.cm-icon {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 34px;
		height: 34px;
		border-radius: var(--radius-sm);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		background: transparent;
		color: var(--fg-2, var(--fg-1));
		cursor: pointer;
	}
	.cm-icon.is-on {
		background: var(--copper-500);
		border-color: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.cm-seeds {
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		gap: 8px;
	}
	.cm-hint {
		margin: -4px 0 0;
		font-size: 11.5px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.cm-foot {
		flex-shrink: 0;
		display: flex;
		gap: 8px;
		justify-content: flex-end;
		padding: 12px 20px 16px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.cm-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 9px 16px;
		border-radius: var(--radius-pill);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		border: 1px solid transparent;
	}
	.cm-btn-ghost {
		background: rgba(var(--tint-rgb), 0.04);
		border-color: rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
	}
	.cm-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.cm-btn-primary:hover {
		background: var(--copper-600);
	}
</style>
