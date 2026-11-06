<script lang="ts">
	/**
	 * `PhoneShotRow` — one row of the phone shot log (Android `PhoneShotRow`):
	 * the spark, name and age, bean and grind, a note, then stars and the mono
	 * figures, with a cloud status glyph at the end.
	 */
	import CheckCircleIcon from 'phosphor-svelte/lib/CheckCircleIcon';
	import CircleIcon from 'phosphor-svelte/lib/CircleIcon';
	import CloudArrowUpIcon from 'phosphor-svelte/lib/CloudArrowUpIcon';
	import CloudCheckIcon from 'phosphor-svelte/lib/CloudCheckIcon';
	import CloudWarningIcon from 'phosphor-svelte/lib/CloudWarningIcon';
	import DeviceMobileIcon from 'phosphor-svelte/lib/DeviceMobileIcon';
	import MethodMark from '#lib/components/brewlog/MethodMark.svelte';
	import StarRating from '#lib/components/common/StarRating.svelte';
	import { shotTitle } from '#lib/brew/methods.js';
	import {
		flatSamplesOf,
		grindLabel,
		isBrewLog,
		isManualLog,
		methodOf,
		peaksOf,
		ratioLabel,
		yieldOf,
		type StoredShot
	} from '#lib/history/index.js';
	import { convertWeight, getSettingsStore } from '#lib/settings/index.js';
	import { relativeAgo } from '#lib/utils/relative-time.js';
	import MiniBrewChart from './MiniBrewChart.svelte';
	import MiniShotChart from './MiniShotChart.svelte';

	let {
		shot,
		selecting = false,
		picked = false,
		dimmed = false,
		syncPip,
		syncTitle,
		onclick
	}: {
		shot: StoredShot;
		/** Compare mode: a leading check, and a tap picks the row. */
		selecting?: boolean;
		picked?: boolean;
		/** Compare is full and this row isn't one of the picks. */
		dimmed?: boolean;
		syncPip: 'uploaded' | 'partial' | 'pending' | 'failed' | 'local';
		syncTitle: string;
		onclick: () => void;
	} = $props();

	const settings = getSettingsStore();
	const method = $derived(methodOf(shot));
	const hasCurve = $derived(
		shot.record.samples.length > 0 || (shot.brewSeries?.samples.length ?? 0) > 0
	);
	const beanLine = $derived.by(() => {
		const bean = [shot.bean?.roasterName?.trim(), shot.bean?.name?.trim()].filter(Boolean);
		return [...bean, grindLabel(shot)].filter(Boolean).join(' · ') || '—';
	});
	const note = $derived(
		(shot.metadata.nextPlan?.trim() || shot.metadata.notes?.trim() || '').replace(/\s+/g, ' ')
	);
	/** Filter brews speak water-in where espresso speaks yield. */
	const out = $derived.by(() => {
		const water = method && method !== 'espresso' ? shot.metadata.waterG : null;
		const grams = water != null && water > 0 ? water : yieldOf(shot, peaksOf(shot));
		if (grams == null) return '—';
		const m = convertWeight(grams, settings.current.weightUnit);
		return `${m.value}${m.unit}`;
	});
	const duration = $derived.by(() => {
		const total = Math.round(shot.record.duration / 1000);
		if (total <= 0) return '—';
		if (total < 90) return `${total}s`;
		return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
	});
	const spark = $derived(flatSamplesOf(shot));
	const pipIcon = $derived(
		{
			uploaded: CloudCheckIcon,
			partial: CloudArrowUpIcon,
			pending: CloudArrowUpIcon,
			failed: CloudWarningIcon,
			local: DeviceMobileIcon
		}[syncPip]
	);
</script>

<button
	type="button"
	class={['psr', { 'is-picked': picked, 'is-dimmed': dimmed }]}
	data-scroll-key={shot.id}
	aria-pressed={selecting ? picked : undefined}
	disabled={selecting && dimmed}
	{onclick}
>
	{#if selecting}
		<span class="psr-check" aria-hidden="true">
			{#if picked}<CheckCircleIcon weight="fill" />{:else}<CircleIcon />{/if}
		</span>
	{/if}
	<span class={['psr-spark', { 'is-mark': !hasCurve }]}>
		{#if shot.record.samples.length > 0}
			<MiniShotChart series={spark} width={58} height={36} />
		{:else if shot.brewSeries && shot.brewSeries.samples.length > 0}
			<MiniBrewChart series={shot.brewSeries} width={58} height={36} />
		{:else}
			<MethodMark {method} size={16} />
		{/if}
	</span>
	<span class="psr-main">
		<span class="psr-top">
			<span class="psr-name">
				{shotTitle(shot)}{#if isManualLog(shot)}<span class="psr-logged"> · logged</span>{/if}
			</span>
			<span class="psr-ago">{relativeAgo(shot.completedAt)}</span>
		</span>
		<span class="psr-bean">{beanLine}</span>
		{#if note}<span class="psr-note">{note}</span>{/if}
		<span class="psr-foot">
			<span class="psr-stars"><StarRating
				rating={shot.metadata.rating ?? 0}
				size={11}
				emptyColor="rgba(var(--tint-rgb), 0.3)"
			/></span>
			<span>{ratioLabel(shot)}</span>
			<span>{out}</span>
			<span>{duration}</span>
		</span>
	</span>
	{#if !isBrewLog(shot)}
		{@const PipIcon = pipIcon}
		<span class={['psr-pip', `is-${syncPip}`]} title={syncTitle} role="img" aria-label={syncTitle}>
			<PipIcon aria-hidden="true" />
		</span>
	{/if}
</button>

<style>
	.psr {
		display: flex;
		align-items: center;
		gap: 12px;
		width: 100%;
		min-height: 64px;
		padding: 9px 6px;
		border: 0;
		border-radius: 12px;
		background: transparent;
		color: var(--fg-1);
		font-family: var(--font-sans);
		text-align: left;
		cursor: pointer;
	}
	.psr:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.psr.is-picked {
		background: rgba(var(--copper-rgb), 0.12);
		box-shadow: inset 0 0 0 1.5px var(--copper-500);
	}
	.psr.is-dimmed {
		opacity: 0.4;
		cursor: not-allowed;
	}
	.psr-check {
		flex: none;
		display: inline-flex;
		font-size: 22px;
		color: var(--fg-3);
	}
	.is-picked .psr-check {
		color: var(--copper-500);
	}
	.psr-spark {
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 58px;
		height: 36px;
		overflow: hidden;
		border-radius: 8px;
		background: var(--bg-surface);
	}
	/* No curve to draw: a bare mark, so the name keeps the chart's width. */
	.psr-spark.is-mark {
		width: 20px;
		background: none;
		color: var(--fg-accent);
		opacity: 0.8;
	}
	.psr-main {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.psr-top {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 8px;
	}
	.psr-name {
		flex: 1 1 auto;
		min-width: 0;
		overflow: hidden;
		font-size: 14px;
		font-weight: 500;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.psr-logged {
		font-weight: 400;
		font-size: 11px;
		color: var(--fg-3);
	}
	.psr-ago {
		flex: none;
		font-family: var(--font-mono);
		font-size: 11px;
		color: var(--fg-3);
	}
	.psr-bean,
	.psr-note {
		overflow: hidden;
		font-size: 12px;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.psr-note {
		font-style: italic;
		color: rgba(var(--tint-rgb), 0.65);
	}
	.psr-foot {
		display: flex;
		align-items: center;
		gap: 10px;
		font-family: var(--font-mono);
		font-size: 11px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-3);
	}
	.psr-stars {
		display: inline-flex;
	}
	.psr-pip {
		flex: none;
		display: inline-flex;
		font-size: 16px;
		color: rgba(var(--tint-rgb), 0.35);
	}
	.psr-pip.is-uploaded {
		color: var(--success);
	}
	.psr-pip.is-partial,
	.psr-pip.is-pending {
		color: #dba764;
	}
	.psr-pip.is-failed {
		color: var(--danger);
	}
</style>
