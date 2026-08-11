<script lang="ts">
	/**
	 * `DialInCard` — "pick up where you left off" for the active bag.
	 *
	 * Shown on the idle Brew dashboard when the active bean has at least
	 * one stored shot: that bag's most recent shot (grind · dose→yield ·
	 * time · rating) with its forward-looking "next time" plan rendered
	 * prominently. One tap re-applies the whole setup (`onStart` →
	 * `applyDialIn`); the header link opens the shot in History.
	 *
	 * Yields the floor to `LastShotCard` right after a pull — the caller
	 * gates the two so the same shot is never described twice.
	 */
	import { getSettingsStore, convertWeight } from '$lib/settings';
	import { formatRatio } from '$lib/utils/ratio';
	import { effectiveGrindSetting, yieldOf, type StoredShot } from '$lib/history';
	import StarRating from '$lib/components/common/StarRating.svelte';

	let {
		shot,
		onStart,
		onOpen
	}: {
		/** The active bean's most recent stored shot. */
		shot: StoredShot;
		/** Re-apply this shot's setup: profile + grind + dial targets. */
		onStart: (shot: StoredShot) => void;
		/** Open the shot in History (detail selected). */
		onOpen: (shot: StoredShot) => void;
	} = $props();

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);

	/** Relative "when" — matches the history rows' coarse buckets. */
	const ago = $derived.by(() => {
		const mins = Math.max(0, Math.round((Date.now() - shot.completedAt) / 60_000));
		if (mins < 60) return `${mins}m ago`;
		const hours = Math.round(mins / 60);
		if (hours < 48) return `${hours}h ago`;
		return `${Math.round(hours / 24)}d ago`;
	});

	const grind = $derived(effectiveGrindSetting(shot));
	const time = $derived(`${Math.round(shot.record.duration / 1000)}`);
	const shotYield = $derived(yieldOf(shot));
	const yieldM = $derived(shotYield != null ? convertWeight(shotYield, prefs.weightUnit) : null);
	const ratio = $derived(
		shot.metadata.dose != null && shotYield != null
			? formatRatio(shot.metadata.dose, shotYield)
			: null
	);
	const rating = $derived(shot.metadata.rating ?? 0);
	const plan = $derived(shot.metadata.nextPlan?.trim() || null);
</script>

<div class="crema-target di-card">
	<div class="di-head">
		<span class="t-eyebrow">Where you left off</span>
		<button class="di-open" onclick={() => onOpen(shot)} title="Open this shot in History">
			{ago}
		</button>
	</div>
	<div class="di-grid">
		<div class="di-stat">
			<span class="di-v">{grind ?? '—'}</span>
			<span class="di-l">Grind</span>
		</div>
		<div class="di-stat">
			<span class="di-v">
				{#if yieldM}{yieldM.value}<em>{yieldM.unit}</em>{:else}—{/if}
			</span>
			<span class="di-l">Yield</span>
		</div>
		<div class="di-stat">
			<span class="di-v">{ratio ?? '—'}</span>
			<span class="di-l">Ratio</span>
		</div>
		<div class="di-stat">
			<span class="di-v">{time}<em>s</em></span>
			<span class="di-l">Time</span>
		</div>
	</div>
	{#if rating > 0}
		<div class="di-stars"><StarRating {rating} /></div>
	{/if}
	{#if plan}
		<div class="di-plan">
			<span class="di-plan-l">Next time</span>
			<span class="di-plan-t">{plan}</span>
		</div>
	{/if}
	<button class="di-start" onclick={() => onStart(shot)}>Use these settings</button>
</div>

<style>
	/* `.crema-target` (web-kit.css) gives the card chrome, matching the
	   sibling Yield / Ratio / Last-shot cards in the idle column. */
	.di-card {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.di-head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 8px;
	}
	.di-open {
		background: transparent;
		border: 0;
		padding: 0;
		cursor: pointer;
		font-family: var(--font-sans);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.55);
		text-decoration: underline dotted;
		text-underline-offset: 2px;
	}
	.di-open:hover {
		color: var(--fg-1);
	}
	.di-grid {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: 8px 10px;
	}
	.di-stat {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.di-v {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 15px;
		line-height: 1.1;
		color: var(--fg-1);
	}
	.di-v em {
		font-style: normal;
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.5);
		margin-left: 2px;
	}
	.di-l {
		font-family: var(--font-sans);
		font-size: 8.5px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.42);
	}
	.di-stars {
		display: flex;
	}
	.di-plan {
		display: flex;
		flex-direction: column;
		gap: 2px;
		padding: 6px 8px;
		border-left: 2px solid rgba(var(--tint-rgb), 0.35);
		background: rgba(var(--tint-rgb), 0.06);
		border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
	}
	.di-plan-l {
		font-family: var(--font-sans);
		font-size: 8.5px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.42);
	}
	.di-plan-t {
		font-family: var(--font-sans);
		font-size: 12px;
		font-style: italic;
		color: var(--fg-1);
		/* Keep long plans to a readable clamp — the full text lives in
		   the shot detail. */
		display: -webkit-box;
		-webkit-line-clamp: 3;
		line-clamp: 3;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.di-start {
		align-self: flex-start;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 600;
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.12);
		border: 1px solid rgba(var(--tint-rgb), 0.25);
		border-radius: var(--radius-sm);
		padding: 5px 10px;
		cursor: pointer;
		transition: background var(--dur-1) var(--ease);
	}
	.di-start:hover {
		background: rgba(var(--tint-rgb), 0.2);
	}
</style>
