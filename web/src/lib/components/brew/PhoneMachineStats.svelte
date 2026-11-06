<script lang="ts">
	/**
	 * `PhoneMachineStats` — the compact group / steam / tank / scale row on the
	 * phone Brew screen (Android `MStat`). A tile's glyph turns green when that
	 * part is ready; the Scale tile tares when `onTare` is set.
	 */
	import CloudIcon from 'phosphor-svelte/lib/CloudIcon';
	import DropHalfIcon from 'phosphor-svelte/lib/DropHalfIcon';
	import ScalesIcon from 'phosphor-svelte/lib/ScalesIcon';
	import ThermometerIcon from 'phosphor-svelte/lib/ThermometerIcon';

	type Stat = { value: string; ok: boolean };

	let {
		group,
		steam,
		tank,
		scale,
		onTare,
		tarePulse = false
	}: {
		group: Stat;
		steam: Stat;
		tank: Stat;
		scale: Stat;
		/** Tap-to-tare: set only while a scale is connected and no shot pulls. */
		onTare?: () => void;
		tarePulse?: boolean;
	} = $props();
</script>

{#snippet tile(label: string, Icon: typeof CloudIcon, stat: Stat)}
	<span class="pmstat-l">{label}</span>
	<span class="pmstat-v">
		<span class={['pmstat-i', { 'is-ok': stat.ok }]}><Icon aria-hidden="true" /></span>
		<span>{stat.value}</span>
	</span>
{/snippet}

<div class="pmstats">
	<div class="pmstat">{@render tile('Group', ThermometerIcon, group)}</div>
	<div class="pmstat">{@render tile('Steam', CloudIcon, steam)}</div>
	<div class="pmstat">{@render tile('Tank', DropHalfIcon, tank)}</div>
	{#if onTare}
		<button
			type="button"
			class={['pmstat', { 'is-pulse': tarePulse }]}
			aria-label="Tare scale, {scale.value}"
			onclick={onTare}
		>
			{@render tile('Scale', ScalesIcon, scale)}
		</button>
	{:else}
		<div class="pmstat">{@render tile('Scale', ScalesIcon, scale)}</div>
	{/if}
</div>

<style>
	.pmstats {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: 8px;
	}
	.pmstat {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 3px;
		min-width: 0;
		min-height: 44px;
		padding: 8px 2px;
		border: 0;
		border-radius: 11px;
		background: var(--bg-surface);
		font: inherit;
		transition: box-shadow var(--dur-1) var(--ease);
	}
	button.pmstat {
		cursor: pointer;
	}
	button.pmstat:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.pmstat.is-pulse {
		box-shadow: inset 0 0 0 1.5px var(--copper-500);
	}
	.pmstat-l {
		font-family: var(--font-sans);
		font-size: 8.5px;
		font-weight: 600;
		letter-spacing: 0.07em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	.pmstat-v {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		min-width: 0;
		font-family: var(--font-mono);
		font-size: clamp(11px, 3.2vw, 12.5px);
		font-variant-numeric: tabular-nums;
		font-weight: 500;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.pmstat-i {
		display: inline-flex;
		font-size: 15px;
		color: var(--fg-3);
	}
	.pmstat-i.is-ok {
		color: var(--success);
	}
	@media (max-width: 359px) {
		.pmstat-v {
			gap: 2px;
		}
		.pmstat-i {
			font-size: 13px;
		}
	}
</style>
