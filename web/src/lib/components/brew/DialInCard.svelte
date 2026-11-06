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
	import { getSettingsStore, convertWeight } from '#lib/settings/index.js';
	import { formatRatio } from '#lib/utils/ratio.js';
	import { effectiveGrindSetting, yieldOf, type StoredShot } from '#lib/history/index.js';
	import StarRating from '#lib/components/common/StarRating.svelte';
	import ArrowCounterClockwiseIcon from 'phosphor-svelte/lib/ArrowCounterClockwiseIcon';
	import CaretRightIcon from 'phosphor-svelte/lib/CaretRightIcon';
	import { relativeAgo } from '#lib/brew/phone-brew.js';
	import { phoneLayout } from '#lib/shell/phone.svelte.js';
	import PhoneStat from './PhoneStat.svelte';

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

{#if phoneLayout.current}
	<section class="pcard pdi" aria-label="Where you left off">
		<button
			type="button"
			class="pdi-head"
			aria-label="Open this shot in History"
			onclick={() => onOpen(shot)}
		>
			<span class="pdi-head-text">
				<span class="pdi-eyebrow">Where you left off · {relativeAgo(shot.completedAt)}</span>
				{#if shot.profileName}<span class="pdi-profile">{shot.profileName}</span>{/if}
			</span>
			{#if rating > 0}<span class="pdi-stars"><StarRating {rating} size={11} /></span>{/if}
			<CaretRightIcon aria-hidden="true" />
		</button>
		<div class="pdi-grid">
			<PhoneStat label="Grind" value={grind ?? '—'} />
			<PhoneStat label="Yield" value={yieldM?.value ?? '—'} unit={yieldM?.unit ?? ''} />
			<PhoneStat label="Ratio" value={ratio ?? '—'} />
			<PhoneStat label="Time" value={time} unit="s" />
		</div>
		{#if plan}<p class="pdi-plan">{plan}</p>{/if}
		<button type="button" class="pdi-load" onclick={() => onStart(shot)}>
			<ArrowCounterClockwiseIcon aria-hidden="true" /> Load setup
		</button>
	</section>
{:else}
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
	<button class="di-start" onclick={() => onStart(shot)}>
		<ArrowCounterClockwiseIcon aria-hidden="true" /> Load setup
	</button>
</div>
{/if}

<style>
	/* The phone card (Android `PhoneDialInCard`). */
	.pdi {
		display: flex;
		flex-direction: column;
		gap: 10px;
		padding: 12px 16px;
	}
	.pdi-head {
		display: flex;
		align-items: center;
		gap: 8px;
		width: 100%;
		min-height: 44px;
		margin: -6px 0;
		padding: 0;
		border: 0;
		border-radius: 8px;
		background: transparent;
		color: var(--fg-3);
		font: inherit;
		text-align: left;
		cursor: pointer;
	}
	.pdi-head:focus-visible,
	.pdi-load:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.pdi-head > :global(svg) {
		flex: none;
		font-size: 16px;
	}
	.pdi-head-text {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.pdi-eyebrow {
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	.pdi-profile {
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 12px;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.pdi-stars {
		flex: none;
		display: inline-flex;
	}
	.pdi-grid {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: 6px;
	}
	.pdi-plan {
		display: -webkit-box;
		margin: 0;
		padding-left: 8px;
		overflow: hidden;
		border-left: 1px solid var(--fg-accent);
		font-family: var(--font-sans);
		font-size: 12px;
		font-style: italic;
		line-height: 1.4;
		color: var(--fg-1);
		-webkit-box-orient: vertical;
		-webkit-line-clamp: 4;
		line-clamp: 4;
	}
	.pdi-load {
		position: relative;
		align-self: flex-end;
		display: inline-flex;
		align-items: center;
		gap: 8px;
		min-height: 34px;
		margin: 2px 0;
		padding: 0 12px;
		border: 1px solid rgba(var(--tint-rgb), 0.16);
		border-radius: var(--radius-pill);
		background: transparent;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
	}
	.pdi-load::before {
		content: '';
		position: absolute;
		inset: -5px 0;
	}
	.pdi-load :global(svg) {
		font-size: 16px;
		color: var(--fg-accent);
	}

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
		color: var(--fg-3);
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
		color: var(--fg-muted);
		margin-left: 2px;
	}
	.di-l {
		font-family: var(--font-sans);
		font-size: 8.5px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: var(--fg-muted);
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
		color: var(--fg-muted);
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
	/* End-aligned compact pill — matches the Android DialInApplyChip ("Load
	   setup"): the card's whole surface reads as content, the one action sits
	   bottom-right like a card action. */
	.di-start {
		align-self: flex-end;
		display: inline-flex;
		align-items: center;
		gap: 5px;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 600;
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.12);
		border: 1px solid rgba(var(--tint-rgb), 0.25);
		border-radius: var(--radius-pill);
		padding: 5px 10px;
		cursor: pointer;
		transition: background var(--dur-1) var(--ease);
	}
	.di-start :global(svg) {
		font-size: 12px;
		color: var(--fg-accent);
	}
	.di-start:hover {
		background: rgba(var(--tint-rgb), 0.2);
	}
</style>
