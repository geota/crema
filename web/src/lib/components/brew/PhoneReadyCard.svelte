<script lang="ts">
	/**
	 * `PhoneReadyCard` — the phone Brew hero at rest (Android `RestingBody`):
	 * readiness + group temperature, the dose → yield target with its ratio, the
	 * loaded profile's plan curve, and the four shot parameters.
	 */
	import ArrowRightIcon from 'phosphor-svelte/lib/ArrowRightIcon';
	import type { BrewReadiness } from '#lib/brew/phone-brew.js';
	import type { ProfileSegment } from '#lib/profiles/index.js';
	import ProfilePreview from '#lib/components/profiles/ProfilePreview.svelte';
	import PhoneStat from './PhoneStat.svelte';

	type Measure = { value: string; unit: string };

	let {
		readiness,
		group,
		dose,
		yieldOut,
		ratio,
		segments,
		preinf,
		temp,
		preinfSeconds,
		pressure,
		estTime
	}: {
		readiness: BrewReadiness;
		/** `Group 72.5 °C`, or `Group —`. */
		group: string;
		dose: Measure;
		yieldOut: Measure;
		ratio: string;
		segments: ProfileSegment[] | undefined;
		/** The core's pre-infusion seconds for the plan curve. */
		preinf: number | undefined;
		temp: Measure;
		preinfSeconds: number;
		pressure: Measure | null;
		estTime: number | null;
	} = $props();

	const BADGE: Record<BrewReadiness, { text: string; tone: string }> = {
		'not-connected': { text: 'Not connected', tone: 'var(--fg-3)' },
		syncing: { text: 'Syncing with the DE1…', tone: 'var(--fg-3)' },
		asleep: { text: 'Asleep', tone: 'var(--fg-3)' },
		heating: { text: 'Heating…', tone: 'var(--warning)' },
		ready: { text: 'Ready to brew', tone: 'var(--success)' }
	};
	const badge = $derived(BADGE[readiness]);
</script>

<section class="pcard pready" aria-label="Next shot">
	<div class="pready-top">
		<span class="pready-badge" style:color={badge.tone}><span></span>{badge.text}</span>
		<span class="pready-group">{group}</span>
	</div>
	<div class="pready-target">
		<span class="pready-num">{dose.value}<em>{dose.unit}</em></span>
		<ArrowRightIcon aria-hidden="true" />
		<span class="pready-num">{yieldOut.value}<em>{yieldOut.unit}</em></span>
		<span class="pready-ratio">{ratio}</span>
	</div>
	<div class="pready-plan">
		{#if segments && segments.length > 0}
			<ProfilePreview {segments} {preinf} plan />
		{:else}
			<p>No profile loaded — pick one above.</p>
		{/if}
	</div>
	<div class="pready-params">
		<PhoneStat label="Temp" value={temp.value} unit={temp.unit} />
		<PhoneStat label="Pre-inf" value={String(preinfSeconds)} unit="s" />
		<PhoneStat label="Pressure" value={pressure?.value ?? '—'} unit={pressure?.unit ?? ''} />
		<PhoneStat
			label="Est. time"
			value={estTime != null ? `~${estTime}` : '—'}
			unit={estTime != null ? 's' : ''}
		/>
	</div>
</section>

<style>
	.pready {
		display: flex;
		flex-direction: column;
		gap: 11px;
		padding: 14px 16px 12px;
	}
	.pready-top {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
	}
	.pready-badge {
		display: inline-flex;
		align-items: center;
		gap: 7px;
		min-width: 0;
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
	}
	.pready-badge span {
		flex: none;
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: currentColor;
	}
	.pready-group {
		flex: none;
		font-family: var(--font-mono);
		font-size: 12px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-3);
	}
	.pready-target {
		display: flex;
		align-items: flex-end;
		gap: 12px;
		color: var(--fg-3);
	}
	.pready-target > :global(svg) {
		flex: none;
		margin-bottom: 8px;
		font-size: 18px;
	}
	.pready-num {
		font-family: var(--font-mono);
		font-size: clamp(30px, 10.2vw, 40px);
		font-variant-numeric: tabular-nums;
		font-weight: 500;
		letter-spacing: -1.5px;
		line-height: 1;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.pready-num em {
		margin-left: 1px;
		font-family: var(--font-sans);
		font-size: 16px;
		font-style: normal;
		font-weight: 400;
		letter-spacing: 0;
		color: var(--fg-3);
	}
	.pready-ratio {
		margin-left: auto;
		margin-bottom: 6px;
		font-family: var(--font-mono);
		font-size: 20px;
		font-variant-numeric: tabular-nums;
		font-weight: 500;
		color: var(--fg-accent);
	}
	.pready-plan {
		position: relative;
		height: 108px;
		overflow: hidden;
		border-radius: var(--radius-md);
		background: var(--bg-page);
	}
	.pready-plan p {
		display: flex;
		align-items: center;
		justify-content: center;
		height: 100%;
		margin: 0;
		padding: 0 12px;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-3);
	}
	.pready-params {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: 6px;
	}
</style>
