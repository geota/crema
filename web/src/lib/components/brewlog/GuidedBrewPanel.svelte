<script lang="ts">
	/**
	 * `GuidedBrewPanel` — the Scale page's Brew segment (issue #10
	 * Phase 2): pick a method + recipe, run the guided session against
	 * the core's `BrewSessionMonitor`, and land the finished brew in the
	 * Log-brew form with the measured numbers + weight curve attached.
	 *
	 * One number hierarchy: the clock largest, the current step's
	 * progress second, everything else meta. Three controls while
	 * running — Finish, Pause/Resume, Skip. Fully usable scale-less:
	 * pour steps become tap-to-advance, timed steps run on the clock.
	 */
	import BellIcon from 'phosphor-svelte/lib/BellIcon';
	import BellSlashIcon from 'phosphor-svelte/lib/BellSlashIcon';
	import PauseIcon from 'phosphor-svelte/lib/PauseIcon';
	import PlayIcon from 'phosphor-svelte/lib/PlayIcon';
	import { brewCueSoundOn, getSettingsStore } from '$lib/settings';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { getGuidedBrewStore } from '$lib/brew/session.svelte';
	import { defaultRecipeFor, getRecipeStore } from '$lib/brew/recipes.svelte';
	import { primeBrewCues } from '$lib/brew/cues';
	import {
		BREW_METHOD_PRESETS,
		lastUsedMethod,
		methodLabel,
		type LogBrewPrefill
	} from '$lib/brew/methods';
	import type { BrewRecipe, BrewStep } from '$lib/core/crema-core';
	import { BrewStepKind, StepAdvance } from '$lib/core/crema-core';
	import { getBeanStore } from '$lib/bean';
	import { toast } from '$lib/components/shared/toast.svelte';
	import BrewSessionChart from '$lib/components/history/BrewSessionChart.svelte';
	import LogBrewDialog from './LogBrewDialog.svelte';
	import MethodMark from './MethodMark.svelte';
	import RecipeEditor from './RecipeEditor.svelte';

	let {
		connected = false,
		weightG = null,
		flowGs = null
	}: {
		/** Whether a scale is connected. */
		connected?: boolean;
		/** Live net scale weight, grams, or `null` without a scale. */
		weightG?: number | null;
		/** Live pour rate, g/s, when the scale reports one. */
		flowGs?: number | null;
	} = $props();

	const ctx = getCremaAppContext();
	const app = $derived(ctx().app);
	const session = getGuidedBrewStore();
	const recipes = getRecipeStore();
	const library = getBeanStore();
	const settings = getSettingsStore();

	// ── Setup state ──────────────────────────────────────────────
	let method = $state(lastUsedMethod());
	let recipe = $state<BrewRecipe>(resolveRecipe(lastUsedMethod()));
	let startOnPour = $state(true);
	let logOpen = $state(false);

	function resolveRecipe(m: string): BrewRecipe {
		return getRecipeStore().lastUsedFor(m) ?? defaultRecipeFor(m);
	}

	function pickMethod(id: string): void {
		method = id;
		recipe = resolveRecipe(id);
	}

	// ── Recipe editor, in place ──────────────────────────────────
	// The library lives in Profiles (its own editor entry), but "Edit
	// recipe" here opens the same editor as a modal over this setup and
	// saving lands back on it, the edited recipe still selected.
	let editing = $state<BrewRecipe | null>(null);

	function editRecipe(): void {
		editing = recipe;
	}

	function saveEdited(r: BrewRecipe): void {
		recipes.upsert(r);
		// Editing is choosing: the saved recipe becomes the method's
		// remembered one, so the setup (and the next visit) opens on it.
		recipes.touch(r);
		method = r.method;
		recipe = recipes.get(r.id) ?? r;
		editing = null;
	}

	// ── Cue sound (the bell) ─────────────────────────────────────
	// The same shared setting as Settings → Display → Guided brew sounds.
	const soundOn = $derived(brewCueSoundOn(settings.current));

	function toggleSound(): void {
		const next = !soundOn;
		settings.set('brewCueSound', next);
		if (next) primeBrewCues();
	}

	// ── Visual cues (always on) ──────────────────────────────────
	// The step card flashes at every step change and pour-target cue,
	// independent of the sound / haptics settings.
	let flash = $state<string | null>(null);
	// svelte-ignore state_referenced_locally
	let seenCueSeq = session.cueSeq;
	$effect(() => {
		const seq = session.cueSeq;
		if (seq === seenCueSeq) return;
		seenCueSeq = seq;
		const kind = session.cueKind;
		flash = null;
		// Re-add the class on the next frame so the animation replays.
		const raf = requestAnimationFrame(() => (flash = kind));
		const t = setTimeout(() => (flash = null), 1000);
		return () => {
			cancelAnimationFrame(raf);
			clearTimeout(t);
		};
	});

	// ── The display clock ────────────────────────────────────────
	// Shell + core share performance.now(), so the panel can render the
	// session clock locally between events.
	let nowMs = $state(typeof performance !== 'undefined' ? performance.now() : 0);
	$effect(() => {
		if (session.phase === 'running' || session.phase === 'paused' || session.phase === 'armed') {
			const t = setInterval(() => (nowMs = performance.now()), 200);
			return () => clearInterval(t);
		}
	});

	const elapsedMs = $derived(session.elapsedMs(nowMs));
	const stepElapsedMs = $derived(session.stepElapsedMs(nowMs));

	function clock(ms: number): string {
		const total = Math.floor(ms / 1000);
		return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
	}

	// ── Step presentation ────────────────────────────────────────
	const KIND_LABEL: Record<string, string> = {
		[BrewStepKind.Bloom]: 'Bloom',
		[BrewStepKind.Pour]: 'Pour',
		[BrewStepKind.Wait]: 'Wait',
		[BrewStepKind.Steep]: 'Steep',
		[BrewStepKind.Stir]: 'Stir',
		[BrewStepKind.Press]: 'Press',
		[BrewStepKind.Drawdown]: 'Drawdown',
		[BrewStepKind.Other]: 'Step'
	};

	function stepLabel(step: BrewStep | undefined): string {
		if (!step) return '';
		return step.label?.trim() || (KIND_LABEL[step.kind] ?? 'Step');
	}

	function stepSpec(step: BrewStep | undefined): string {
		if (!step) return '';
		const parts: string[] = [];
		if (step.targetWaterG != null) parts.push(`to ${Math.round(step.targetWaterG)} g`);
		if (step.durationS != null) parts.push(clock(step.durationS * 1000));
		if (parts.length === 0) parts.push('until you tap');
		return parts.join(' · ');
	}

	const liveRecipe = $derived(session.recipe ?? recipe);
	const steps = $derived(liveRecipe.steps ?? []);
	const currentStep = $derived(steps[session.stepIndex]);
	const nextStep = $derived(steps[session.stepIndex + 1]);
	/** Recipe total time, ms, for the "of about m:ss" line — sum of the
	 *  step durations; pour steps count a nominal 30 s each. */
	const nominalTotalMs = $derived(
		steps.reduce(
			(acc, s) => acc + (s.durationS != null ? s.durationS * 1000 : s.targetWaterG != null ? 30_000 : 0),
			0
		)
	);

	/** The current step's 0..1 progress — live weight against a pour
	 *  target when a scale reports, else the countdown. `null` = open. */
	const stepProgress = $derived.by<number | null>(() => {
		const s = currentStep;
		if (!s) return null;
		if (s.targetWaterG != null && connected && weightG != null) {
			return Math.min(1, Math.max(0, weightG / s.targetWaterG));
		}
		if (s.durationS != null) {
			return Math.min(1, stepElapsedMs / (s.durationS * 1000));
		}
		return null;
	});

	// ── Commands ─────────────────────────────────────────────────
	async function start(): Promise<void> {
		const a = app;
		if (!a) {
			toast.info('App is still loading — try again in a moment.');
			return;
		}
		primeBrewCues();
		recipes.touch(recipe);
		const armOnPour = startOnPour && connected;
		await a.brewSessionArm(recipe, armOnPour);
		if (!armOnPour) await a.brewSessionBegin();
	}

	async function beginNow(): Promise<void> {
		await app?.brewSessionBegin();
	}

	async function cancel(): Promise<void> {
		await app?.brewSessionCancel();
	}

	async function togglePause(): Promise<void> {
		if (session.phase === 'paused') await app?.brewSessionResume();
		else await app?.brewSessionPause();
	}

	async function skip(): Promise<void> {
		await app?.brewSessionSkip();
	}

	async function finish(): Promise<void> {
		await app?.brewSessionFinish();
	}

	function discard(): void {
		session.reset();
	}

	/** The finished session, pre-shaped for the log form. */
	const logPrefill = $derived.by<LogBrewPrefill | undefined>(() => {
		const s = session.summary;
		if (!s) return undefined;
		return {
			method: s.method,
			recipeName: s.recipeName,
			beanId: library.activeBeanId,
			dose: liveRecipe.doseG > 0 ? liveRecipe.doseG : null,
			waterG: s.finalWeightG ?? (liveRecipe.waterG > 0 ? liveRecipe.waterG : null),
			tempC: liveRecipe.tempC ?? null,
			durationMs: s.durationMs,
			brewSeries: s.series
		};
	});
</script>

<div class="gb">
	{#if session.phase === 'idle'}
		<!-- ── Setup ─────────────────────────────────────────── -->
		<div class="gb-setup">
		<div class="gb-chips" role="radiogroup" aria-label="Brew method">
			{#each BREW_METHOD_PRESETS as p (p.id)}
				<button
					type="button"
					class="gb-chip"
					class:is-on={method === p.id}
					role="radio"
					aria-checked={method === p.id}
					onclick={() => pickMethod(p.id)}
				>
					<MethodMark method={p.id} size={13} />
					{p.label}
				</button>
			{/each}
		</div>

		<div class="gb-recipe">
			<div class="gb-recipe-head">
				<div>
					<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">
						Recipe · {methodLabel(method)}
					</div>
					<div class="gb-recipe-name">{recipe.name}</div>
					<div class="gb-recipe-meta">
						{recipe.doseG} g · {recipe.waterG} g water{#if recipe.tempC}
							· {Math.round(recipe.tempC)} °C{/if}
					</div>
				</div>
				<div class="gb-recipe-actions">
					{#if recipes.forMethod(method).length > 1}
						<select
							class="gb-select"
							aria-label="Choose recipe"
							onchange={(e) => {
								const found = recipes.get(e.currentTarget.value);
								if (found) recipe = found;
							}}
						>
							{#each recipes.forMethod(method) as r (r.id)}
								<option value={r.id} selected={r.id === recipe.id}>{r.name}</option>
							{/each}
						</select>
					{/if}
					<button class="gb-ghost" onclick={editRecipe}>Edit recipe</button>
				</div>
			</div>
			<ol class="gb-steps">
				{#each steps as step, i (i)}
					<li>
						<span class="gb-step-n">{i + 1}</span>
						<span class="gb-step-body"
							><b>{stepLabel(step)}</b>
							<span class="gb-step-spec">— {stepSpec(step)}</span></span
						>
						<span class="gb-step-adv">{step.advance === StepAdvance.Manual ? 'TAP' : 'AUTO'}</span>
					</li>
				{/each}
			</ol>
		</div>

		<div class="gb-setup-row">
			<span class="gb-scale-chip">
				<span
					class="gb-dot"
					style="background:{connected ? 'var(--success)' : 'rgba(var(--tint-rgb), 0.25)'}"
				></span>
				{connected ? 'Scale connected — targets are live' : 'No scale — steps run on timers and taps'}
			</span>
			{#if connected}
				<button class="gb-ghost" onclick={() => void app?.tareScale()}>Tare</button>
				<label class="gb-pour-toggle">
					<input type="checkbox" bind:checked={startOnPour} />
					Start on first pour
				</label>
			{/if}
		</div>

		<div class="gb-start-row">
			<button class="gb-start" onclick={() => void start()}>Start brew</button>
			<button
				type="button"
				class="gb-bell"
				class:is-on={soundOn}
				aria-pressed={soundOn}
				aria-label={soundOn ? 'Cue sound on' : 'Cue sound off'}
				title={soundOn ? 'Cue sound on — tap to mute' : 'Cue sound off — tap to chime at steps'}
				onclick={toggleSound}
			>
				{#if soundOn}
					<BellIcon size={18} weight="fill" aria-hidden="true" />
				{:else}
					<BellSlashIcon size={18} aria-hidden="true" />
				{/if}
			</button>
		</div>
		</div>
	{:else if session.phase === 'armed'}
		<!-- ── Armed: waiting for the first pour ─────────────── -->
		<div class="gb-live">
			<div class="gb-live-head">
				<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">
					Guided brew · {methodLabel(liveRecipe.method)}
				</div>
				<div class="gb-live-name">{liveRecipe.name}</div>
			</div>
			<div class="gb-clock">0:00</div>
			<div class="gb-armed-hint">Pour to start — the clock begins at the first water.</div>
			<div class="gb-controls">
				<button class="gb-text-btn" onclick={() => void cancel()}>Cancel</button>
				<button class="gb-main-btn" onclick={() => void beginNow()} aria-label="Start now">
					<PlayIcon size={22} weight="fill" aria-hidden="true" />
				</button>
				<span class="gb-text-spacer"></span>
			</div>
		</div>
	{:else if session.phase === 'running' || session.phase === 'paused'}
		<!-- ── Live session — never scrolls: the clock and the three
		     controls always stay on screen. Two columns (session +
		     live weight chart) at ≥1100px, one below. ── -->
		<div class="gb-live-grid">
		<div class="gb-live">
			<div class="gb-live-head">
				<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">
					Guided brew · {methodLabel(liveRecipe.method)}
				</div>
				<div class="gb-live-name">{liveRecipe.name}</div>
				{#if connected && weightG != null}
					<span class="gb-scale-chip gb-scale-chip-live">
						<span class="gb-dot" style="background:var(--success)"></span>
						{weightG.toFixed(1)} g
					</span>
				{/if}
			</div>
			<div class="gb-clock" class:is-paused={session.phase === 'paused'}>
				{clock(elapsedMs)}
			</div>
			{#if nominalTotalMs > 0}
				<div class="gb-clock-sub">of about {clock(nominalTotalMs)}</div>
			{/if}

			<div
				class="gb-step-card"
				class:is-cue-step={flash === 'step'}
				class:is-cue-approach={flash === 'approach'}
				class:is-cue-boundary={flash === 'boundary'}
				data-cue={flash ?? undefined}
			>
				<div class="gb-step-card-head">
					<span class="gb-step-eyebrow">
						Step {session.stepIndex + 1} of {steps.length} · {stepLabel(currentStep)}
					</span>
					<span class="gb-step-target">{stepSpec(currentStep)}</span>
				</div>
				{#if currentStep?.targetWaterG != null && connected && weightG != null}
					<div class="gb-step-big">
						{Math.max(0, weightG).toFixed(0)}<span class="gb-step-of">
							/ {Math.round(currentStep.targetWaterG)} g</span
						>
					</div>
				{:else if currentStep?.durationS != null}
					<div class="gb-step-big">
						{clock(Math.max(0, currentStep.durationS * 1000 - stepElapsedMs))}<span
							class="gb-step-of"
						>
							left</span
						>
					</div>
				{:else}
					<div class="gb-step-open">Until you tap — Skip moves on</div>
				{/if}
				{#if stepProgress != null}
					<div class="gb-bar">
						<div class="gb-bar-fill" style="width:{(stepProgress * 100).toFixed(1)}%"></div>
					</div>
				{/if}
			</div>

			<div class="gb-under-row">
				<span>{nextStep ? `Next · ${stepLabel(nextStep)} ${stepSpec(nextStep)}` : 'Last step'}</span>
				{#if connected && flowGs != null && flowGs > 0.05}
					<span class="gb-flow">pour rate {flowGs.toFixed(1)} g/s</span>
				{/if}
			</div>

			<div class="gb-controls">
				<button class="gb-text-btn" onclick={() => void finish()}>Finish</button>
				<button
					class="gb-main-btn"
					onclick={() => void togglePause()}
					aria-label={session.phase === 'paused' ? 'Resume' : 'Pause'}
				>
					{#if session.phase === 'paused'}
						<PlayIcon size={22} weight="fill" aria-hidden="true" />
					{:else}
						<PauseIcon size={22} weight="fill" aria-hidden="true" />
					{/if}
				</button>
				<button class="gb-text-btn" onclick={() => void skip()}>Skip ›</button>
			</div>
		</div>
		<div class="gb-live-chart">
			<div class="gb-live-chart-head">
				<span class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Weight</span>
				{#if !connected}
					<span class="gb-live-chart-note">No scale — stage bands only</span>
				{/if}
			</div>
			<div class="gb-live-chart-plot">
				<BrewSessionChart series={session.liveSeries} extentMs={elapsedMs} height={360} />
			</div>
		</div>
		</div>
	{:else if session.phase === 'done' && session.summary}
		<!-- ── Summary ───────────────────────────────────────── -->
		<div class="gb-done">
			<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Brew finished</div>
			<div class="gb-done-name">{session.summary.recipeName}</div>
			<div class="gb-done-stats">
				<span class="gb-done-stat"
					><b>{clock(session.summary.durationMs)}</b><em>time</em></span
				>
				{#if session.summary.finalWeightG != null}
					<span class="gb-done-stat"
						><b>{Math.round(session.summary.finalWeightG)} g</b><em>water</em></span
					>
				{/if}
				<span class="gb-done-stat"
					><b>{session.summary.series.stageMarks.length}</b><em>steps</em></span
				>
			</div>
			<div class="gb-done-actions">
				<button class="gb-ghost" onclick={discard}>Discard</button>
				<button class="gb-start gb-save" onclick={() => (logOpen = true)}>Save brew…</button>
			</div>
		</div>
	{/if}
</div>

{#if editing}
	<RecipeEditor recipe={editing} onSave={saveEdited} onClose={() => (editing = null)} />
{/if}

{#if logOpen && logPrefill}
	<LogBrewDialog
		prefill={logPrefill}
		onClose={() => (logOpen = false)}
		onSaved={() => {
			session.reset();
			toast.success('Guided brew saved to history');
		}}
	/>
{/if}

<style>
	.gb {
		display: flex;
		flex-direction: column;
		gap: 16px;
	}
	.gb-setup {
		display: flex;
		flex-direction: column;
		gap: 16px;
		width: 100%;
		max-width: 640px;
	}
	.gb-start-row {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.gb-bell {
		width: 40px;
		height: 40px;
		border-radius: 50%;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		color: rgba(var(--tint-rgb), 0.55);
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.gb-bell:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}
	.gb-bell.is-on {
		color: var(--copper-400);
		border-color: var(--copper-600);
	}
	.gb-bell:focus-visible,
	.gb-start:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
	.gb-chips {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.gb-chip {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		border-radius: var(--radius-pill);
		padding: 6px 12px;
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		background: transparent;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12.5px;
		font-weight: 500;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.gb-chip:hover {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.gb-chip.is-on {
		background: var(--copper-500);
		border-color: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.gb-recipe {
		container-type: inline-size;
		background: rgba(var(--tint-rgb), 0.03);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-md);
		padding: 14px 16px;
	}
	.gb-recipe-head {
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		gap: 12px;
		margin-bottom: 10px;
	}
	.gb-recipe-name {
		font-family: var(--font-serif);
		font-size: 19px;
		margin-top: 4px;
	}
	.gb-recipe-meta {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 11.5px;
		color: rgba(var(--tint-rgb), 0.55);
		margin-top: 3px;
	}
	.gb-recipe-actions {
		display: flex;
		align-items: center;
		gap: 8px;
		flex: none;
	}
	.gb-select {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 6px 8px;
	}
	.gb-steps {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
	}
	.gb-steps li {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 7px 2px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.06);
		font-size: 13px;
	}
	.gb-step-n {
		font-family: var(--font-mono);
		font-size: 10.5px;
		color: rgba(var(--tint-rgb), 0.45);
		width: 14px;
		flex: none;
	}
	.gb-step-body {
		flex: 1;
		min-width: 0;
	}
	.gb-step-spec {
		color: rgba(var(--tint-rgb), 0.55);
	}
	.gb-step-adv {
		font-size: 9.5px;
		font-weight: 700;
		letter-spacing: 0.06em;
		color: var(--copper-400);
		flex: none;
	}
	/* Narrow card: the AUTO/TAP tag wraps under its step. */
	@container (max-width: 420px) {
		.gb-steps li {
			flex-wrap: wrap;
			row-gap: 2px;
		}
		.gb-step-adv {
			flex-basis: 100%;
			padding-left: 24px;
		}
	}
	.gb-setup-row {
		display: flex;
		align-items: center;
		gap: 12px;
		flex-wrap: wrap;
	}
	.gb-scale-chip {
		display: inline-flex;
		align-items: center;
		gap: 7px;
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.6);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-pill);
		padding: 5px 11px;
	}
	.gb-scale-chip-live {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
	}
	.gb-dot {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		flex: none;
	}
	.gb-pour-toggle {
		display: inline-flex;
		align-items: center;
		gap: 7px;
		font-family: var(--font-sans);
		font-size: 12.5px;
		color: var(--fg-1);
		cursor: pointer;
	}
	.gb-pour-toggle input {
		accent-color: var(--copper-500);
	}
	.gb-ghost {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12.5px;
		font-weight: 500;
		padding: 7px 13px;
		border-radius: var(--radius-pill);
		cursor: pointer;
	}
	.gb-ghost:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.gb-start {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		border: 0;
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 600;
		padding: 12px 20px;
		border-radius: var(--radius-pill);
		cursor: pointer;
		align-self: flex-start;
		transition: background var(--dur-1) var(--ease);
	}
	.gb-start:hover {
		background: var(--copper-600);
	}

	/* ── Live session ─────────────────────────────────────── */
	.gb-live-grid {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 24px;
		align-items: stretch;
	}
	.gb-live-chart {
		display: none;
	}
	@media (min-width: 1100px) {
		.gb-live-grid {
			grid-template-columns: minmax(380px, 440px) minmax(0, 1fr);
		}
		.gb-live-chart {
			display: flex;
			flex-direction: column;
			gap: 8px;
			min-width: 0;
			background: rgba(var(--tint-rgb), 0.03);
			border: 1px solid rgba(var(--tint-rgb), 0.1);
			border-radius: var(--radius-md);
			padding: 14px 16px;
		}
	}
	.gb-live-chart-head {
		display: flex;
		justify-content: space-between;
		align-items: baseline;
		gap: 10px;
	}
	.gb-live-chart-note {
		font-family: var(--font-sans);
		font-size: 11.5px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.gb-live-chart-plot {
		flex: 1;
		min-height: 0;
		display: flex;
		align-items: center;
	}
	.gb-live {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 8px;
		padding: 8px 0 4px;
	}
	.gb-live-head {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 5px;
	}
	.gb-live-name {
		font-family: var(--font-serif);
		font-size: 18px;
	}
	.gb-clock {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		/* Scales with the viewport height so the controls stay on screen. */
		font-size: clamp(48px, 11vh, 96px);
		line-height: 1;
		letter-spacing: -0.03em;
		margin-top: 6px;
	}
	.gb-clock.is-paused {
		opacity: 0.45;
	}
	.gb-clock-sub {
		font-family: var(--font-sans);
		font-size: 11.5px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.gb-armed-hint {
		font-family: var(--font-sans);
		font-size: 13px;
		color: rgba(var(--tint-rgb), 0.6);
		margin-top: 6px;
	}
	.gb-step-card {
		width: min(420px, 100%);
		background: rgba(var(--tint-rgb), 0.03);
		border: 1px solid var(--copper-600);
		border-radius: var(--radius-md);
		padding: 14px 16px;
		margin-top: 10px;
	}
	/* Visual cues — always on, whatever the sound / haptics settings. */
	.gb-step-card.is-cue-step,
	.gb-step-card.is-cue-boundary {
		animation: gb-cue-flash 900ms var(--ease) 1;
	}
	.gb-step-card.is-cue-approach {
		animation: gb-cue-approach 900ms var(--ease) 1;
	}
	@keyframes gb-cue-flash {
		0% {
			background: rgba(var(--tint-rgb), 0.03);
			box-shadow: 0 0 0 0 var(--copper-500);
		}
		20% {
			background: color-mix(in srgb, var(--copper-500) 22%, transparent);
			box-shadow: 0 0 0 4px color-mix(in srgb, var(--copper-500) 45%, transparent);
		}
		100% {
			background: rgba(var(--tint-rgb), 0.03);
			box-shadow: 0 0 0 0 transparent;
		}
	}
	@keyframes gb-cue-approach {
		0%,
		100% {
			box-shadow: 0 0 0 0 transparent;
		}
		30% {
			box-shadow: 0 0 0 3px color-mix(in srgb, var(--tel-weight) 55%, transparent);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.gb-step-card.is-cue-step,
		.gb-step-card.is-cue-boundary,
		.gb-step-card.is-cue-approach {
			animation: none;
			border-color: var(--copper-400);
			border-width: 2px;
		}
		.gb-step-card.is-cue-approach {
			border-color: var(--tel-weight);
		}
	}
	.gb-step-card-head {
		display: flex;
		justify-content: space-between;
		align-items: baseline;
		gap: 10px;
	}
	.gb-step-eyebrow {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: var(--copper-400);
	}
	.gb-step-target {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 11.5px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.gb-step-big {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 34px;
		line-height: 1;
		margin-top: 10px;
	}
	.gb-step-of {
		font-size: 16px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.gb-step-open {
		font-family: var(--font-sans);
		font-size: 14px;
		color: rgba(var(--tint-rgb), 0.65);
		margin-top: 10px;
	}
	.gb-bar {
		height: 6px;
		border-radius: var(--radius-pill);
		background: rgba(var(--tint-rgb), 0.08);
		margin-top: 12px;
		overflow: hidden;
	}
	.gb-bar-fill {
		height: 100%;
		border-radius: var(--radius-pill);
		background: var(--tel-weight);
		transition: width 180ms linear;
	}
	.gb-under-row {
		width: min(420px, 100%);
		display: flex;
		justify-content: space-between;
		gap: 12px;
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
		padding: 2px 4px;
	}
	.gb-flow {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
	}
	.gb-controls {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 26px;
		margin-top: 12px;
	}
	/* The live controls pin to the bottom of the viewport should the page
	   ever be too short to show the whole column. */
	.gb-live .gb-controls {
		position: sticky;
		bottom: 0;
		z-index: 1;
		width: 100%;
		padding: 8px 0 max(8px, env(safe-area-inset-bottom));
		background: var(--bg-page);
	}
	.gb-text-btn {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		cursor: pointer;
		padding: 6px;
		min-width: 52px;
	}
	.gb-text-btn:hover {
		color: var(--fg-1);
	}
	.gb-text-spacer {
		min-width: 52px;
	}
	.gb-main-btn {
		width: 62px;
		height: 62px;
		border-radius: 50%;
		background: var(--copper-500);
		color: var(--fg-on-accent);
		border: 0;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		cursor: pointer;
		transition: background var(--dur-1) var(--ease);
	}
	.gb-main-btn:hover {
		background: var(--copper-600);
	}

	/* ── Summary ──────────────────────────────────────────── */
	.gb-done {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 10px;
		padding: 18px 0;
	}
	.gb-done-name {
		font-family: var(--font-serif);
		font-size: 22px;
	}
	.gb-done-stats {
		display: flex;
		gap: 26px;
		margin: 8px 0 4px;
	}
	.gb-done-stat {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 3px;
	}
	.gb-done-stat b {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 21px;
		font-weight: 500;
	}
	.gb-done-stat em {
		font-style: normal;
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.gb-done-actions {
		display: flex;
		gap: 10px;
		align-items: center;
	}
	.gb-save {
		align-self: auto;
	}
</style>
