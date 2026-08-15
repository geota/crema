<script lang="ts">
	/**
	 * `RecipeEditor` — edit a guided-brew recipe (issue #10 Phase 2).
	 * A legible list, not a curve editor: each step is one line — kind,
	 * cumulative water target, duration, auto/tap advance — with a
	 * running "250 g planned · matches water" check against the recipe's
	 * water total. Saving hands the recipe back to the caller (the panel
	 * upserts it into the RecipeStore).
	 */
	import TrashIcon from 'phosphor-svelte/lib/TrashIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
	import type { BrewRecipe, BrewStep } from '$lib/core/crema-core';
	import { BrewStepKind, StepAdvance } from '$lib/core/crema-core';
	import { defaultRecipeFor, recipeId } from '$lib/brew/recipes.svelte';
	import { BREW_METHOD_PRESETS, methodLabel } from '$lib/brew/methods';

	let {
		recipe,
		heading = 'Edit recipe',
		reseedOnMethodChange = false,
		onSave,
		onClose
	}: {
		/** The recipe to edit — treated as a template; saving mints a copy
		 *  with the same id (an edit) unless the name changed from a
		 *  default template, in which case the id is kept anyway: recipes
		 *  are per-user rows, not shared. */
		recipe: BrewRecipe;
		/** Dialog title — "Edit recipe" / "New recipe". */
		heading?: string;
		/** New-recipe mode: switching the method swaps in that method's
		 *  classic template (name, numbers, steps) so "New recipe →
		 *  AeroPress" starts from the AeroPress plan, not V60 steps. */
		reseedOnMethodChange?: boolean;
		onSave: (recipe: BrewRecipe) => void;
		onClose: () => void;
	} = $props();

	// svelte-ignore state_referenced_locally
	const base = recipe;

	// Draft state — committed on Save only.
	let method = $state(base.method);
	let name = $state(base.name);
	let doseG = $state(base.doseG);
	let waterG = $state(base.waterG);
	let tempC = $state<number | null>(base.tempC ?? null);
	let steps = $state<BrewStep[]>((base.steps ?? []).map((s) => ({ ...s })));

	/** The method options — the preset chips, plus the recipe's own
	 *  free-text method when it isn't a curated one. */
	const methodOptions = $derived.by(() => {
		const opts = BREW_METHOD_PRESETS.map((p) => ({ id: p.id, label: p.label }));
		if (!opts.some((o) => o.id === method)) opts.push({ id: method, label: methodLabel(method) });
		return opts;
	});

	function pickMethod(next: string): void {
		method = next;
		if (!reseedOnMethodChange) return;
		const seed = defaultRecipeFor(next);
		name = seed.name;
		doseG = seed.doseG;
		waterG = seed.waterG;
		tempC = seed.tempC ?? null;
		steps = (seed.steps ?? []).map((s) => ({ ...s }));
	}

	const KINDS: { id: BrewStepKind; label: string }[] = [
		{ id: BrewStepKind.Bloom, label: 'Bloom' },
		{ id: BrewStepKind.Pour, label: 'Pour' },
		{ id: BrewStepKind.Wait, label: 'Wait' },
		{ id: BrewStepKind.Steep, label: 'Steep' },
		{ id: BrewStepKind.Stir, label: 'Stir' },
		{ id: BrewStepKind.Press, label: 'Press' },
		{ id: BrewStepKind.Drawdown, label: 'Drawdown' }
	];

	/** The largest cumulative pour target across steps, for the check line. */
	const plannedTotal = $derived(
		steps.reduce((m, s) => Math.max(m, s.targetWaterG ?? 0), 0)
	);
	const totalMatches = $derived(plannedTotal > 0 && Math.abs(plannedTotal - waterG) < 0.5);

	function addStep(): void {
		steps = [
			...steps,
			{
				kind: BrewStepKind.Pour,
				label: undefined,
				targetWaterG: waterG,
				durationS: undefined,
				advance: StepAdvance.Auto
			}
		];
	}

	function removeStep(i: number): void {
		steps = steps.filter((_, idx) => idx !== i);
	}

	function save(): void {
		const trimmed = name.trim();
		onSave({
			...base,
			id: base.id || recipeId(),
			method,
			name: trimmed || base.name,
			doseG,
			waterG,
			tempC: tempC ?? undefined,
			steps: steps.map((s) => ({
				...s,
				targetWaterG: s.targetWaterG != null && s.targetWaterG > 0 ? s.targetWaterG : undefined,
				durationS: s.durationS != null && s.durationS > 0 ? s.durationS : undefined
			}))
		});
	}

	function onKey(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			onClose();
		}
	}
</script>

<div
	class="re-scrim"
	onclick={onClose}
	onkeydown={onKey}
	role="button"
	tabindex="-1"
	aria-label="Close recipe editor"
></div>

<div class="re" role="dialog" aria-modal="true" aria-labelledby="re-title" tabindex="-1" onkeydown={onKey}>
	<header class="re-head">
		<div>
			<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Recipe</div>
			<h2 class="re-title" id="re-title">{heading}</h2>
		</div>
		<button class="re-x" onclick={onClose} aria-label="Close">
			<XIcon aria-hidden="true" />
		</button>
	</header>

	<div class="re-body">
		<div class="re-duo">
			<label class="re-fld">
				<span class="re-label">Method</span>
				<select
					class="re-input"
					value={method}
					onchange={(e) => pickMethod(e.currentTarget.value)}
				>
					{#each methodOptions as o (o.id)}
						<option value={o.id}>{o.label}</option>
					{/each}
				</select>
			</label>
			<label class="re-fld">
				<span class="re-label">Name</span>
				<input class="re-input" bind:value={name} />
			</label>
		</div>
		<div class="re-triple">
			<label class="re-fld">
				<span class="re-label">Dose g</span>
				<input class="re-input re-num" type="number" min="0" step="0.5" bind:value={doseG} />
			</label>
			<label class="re-fld">
				<span class="re-label">Water g</span>
				<input class="re-input re-num" type="number" min="0" step="10" bind:value={waterG} />
			</label>
			<label class="re-fld">
				<span class="re-label">Temp °C</span>
				<input class="re-input re-num" type="number" min="0" max="100" step="1" bind:value={tempC} />
			</label>
		</div>

		<div class="re-steps">
			{#each steps as step, i (i)}
				<div class="re-step">
					<span class="re-step-n">{i + 1}</span>
					<select class="re-input re-kind" bind:value={step.kind} aria-label="Step type">
						{#each KINDS as k (k.id)}
							<option value={k.id}>{k.label}</option>
						{/each}
					</select>
					<label class="re-step-fld">
						<span>to g</span>
						<input
							class="re-input re-num"
							type="number"
							min="0"
							step="5"
							bind:value={step.targetWaterG}
							placeholder="—"
						/>
					</label>
					<label class="re-step-fld">
						<span>secs</span>
						<input
							class="re-input re-num"
							type="number"
							min="0"
							step="5"
							bind:value={step.durationS}
							placeholder="—"
						/>
					</label>
					<button
						type="button"
						class="re-adv"
						class:is-tap={step.advance === StepAdvance.Manual}
						onclick={() =>
							(step.advance =
								step.advance === StepAdvance.Manual ? StepAdvance.Auto : StepAdvance.Manual)}
						title="Auto advances at the target/countdown; Tap holds for you"
					>
						{step.advance === StepAdvance.Manual ? 'TAP' : 'AUTO'}
					</button>
					<button
						type="button"
						class="re-del"
						onclick={() => removeStep(i)}
						aria-label="Remove step"
					>
						<TrashIcon aria-hidden="true" />
					</button>
				</div>
			{/each}
		</div>

		<div class="re-check-row">
			<button type="button" class="re-add" onclick={addStep}>＋ Add step</button>
			{#if plannedTotal > 0}
				<span class="re-check" class:is-ok={totalMatches}>
					{Math.round(plannedTotal)} g planned{totalMatches
						? ' · matches water ✓'
						: ` · water is ${Math.round(waterG)} g`}
				</span>
			{/if}
		</div>
	</div>

	<footer class="re-foot">
		<button class="re-btn" onclick={onClose}>Cancel</button>
		<button class="re-btn re-btn-primary" onclick={save}>Save recipe</button>
	</footer>
</div>

<style>
	.re-scrim {
		position: fixed;
		inset: 0;
		background: rgba(var(--scrim-rgb, 0, 0, 0), 0.55);
		z-index: 72;
	}
	.re {
		position: fixed;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		width: min(560px, calc(100vw - 32px));
		max-height: calc(100vh - 64px);
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: var(--radius-lg);
		z-index: 73;
		display: flex;
		flex-direction: column;
		overflow: hidden;
		box-shadow: var(--shadow-lg);
	}
	.re-head {
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		gap: 16px;
		padding: 20px 22px 10px;
	}
	.re-title {
		font-family: var(--font-serif);
		font-size: 22px;
		font-weight: 500;
		margin: 4px 0 0;
	}
	.re-x {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		font-size: 16px;
		padding: 4px;
		cursor: pointer;
		border-radius: var(--radius-sm);
	}
	.re-x:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}
	.re-body {
		padding: 6px 22px 16px;
		display: flex;
		flex-direction: column;
		gap: 12px;
		overflow-y: auto;
	}
	.re-fld {
		display: flex;
		flex-direction: column;
		gap: 5px;
		min-width: 0;
	}
	.re-label {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.re-input {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 8px 10px;
		outline: 0;
		width: 100%;
		box-sizing: border-box;
	}
	.re-input:focus {
		border-color: var(--copper-400);
	}
	.re-num {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
	}
	.re-duo {
		display: grid;
		grid-template-columns: 150px 1fr;
		gap: 10px;
	}
	.re-triple {
		display: grid;
		grid-template-columns: 1fr 1fr 1fr;
		gap: 10px;
	}
	.re-steps {
		display: flex;
		flex-direction: column;
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-sm);
		overflow: hidden;
	}
	.re-step {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 10px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.06);
	}
	.re-step:first-child {
		border-top: 0;
	}
	.re-step-n {
		font-family: var(--font-mono);
		font-size: 10.5px;
		color: rgba(var(--tint-rgb), 0.45);
		width: 14px;
		flex: none;
	}
	.re-kind {
		width: 110px;
		flex: none;
	}
	.re-step-fld {
		display: flex;
		align-items: center;
		gap: 6px;
		flex: 1;
		min-width: 0;
	}
	.re-step-fld span {
		font-family: var(--font-sans);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.5);
		flex: none;
	}
	.re-adv {
		background: transparent;
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: var(--radius-pill);
		color: var(--copper-400);
		font-size: 9.5px;
		font-weight: 700;
		letter-spacing: 0.06em;
		padding: 4px 9px;
		cursor: pointer;
		flex: none;
	}
	.re-adv.is-tap {
		color: rgba(var(--tint-rgb), 0.55);
	}
	.re-del {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.4);
		cursor: pointer;
		padding: 4px;
		flex: none;
	}
	.re-del:hover {
		color: var(--danger);
	}
	.re-check-row {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 12px;
	}
	.re-add {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		font-family: var(--font-sans);
		font-size: 12.5px;
		cursor: pointer;
		padding: 4px 0;
	}
	.re-add:hover {
		color: var(--fg-1);
	}
	.re-check {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 11.5px;
		color: var(--warning);
	}
	.re-check.is-ok {
		color: var(--success);
	}
	.re-foot {
		display: flex;
		gap: 8px;
		justify-content: flex-end;
		padding: 12px 22px 18px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.re-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 9px 16px;
		border-radius: var(--radius-pill);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		background: rgba(var(--tint-rgb), 0.04);
		color: var(--fg-1);
	}
	.re-btn-primary {
		background: var(--copper-500);
		border-color: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.re-btn-primary:hover {
		background: var(--copper-600);
	}
</style>
