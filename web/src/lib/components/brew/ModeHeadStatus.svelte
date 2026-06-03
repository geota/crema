<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	import StopIcon from 'phosphor-svelte/lib/StopIcon';
	/**
	 * `ModeHeadStatus` — the colored pill that appears in the dashboard
	 * header while a Steam / Hot-water / Flush mode is running. Sits in
	 * `.crema-dash-head` between the profile-info block (left) and the
	 * Edit / Switch buttons (right). Ported from
	 * `mode-controls.jsx:ModeHeadStatus`.
	 *
	 * Renders nothing for `idle` — caller wraps with `{#if}`.
	 */

	type ModeState = 'steaming' | 'dispensing' | 'flushing';

	let {
		state,
		nameLabel,
		metaLabel,
		progressPct,
		onCancel
	}: {
		/** Which mode is running. */
		state: ModeState;
		/** Big "Steaming" / "Hot water" / "Flushing" label. */
		nameLabel: string;
		/** Mono meta line, e.g. `'4.6 / 8.0 s · 148 °C'`. */
		metaLabel: string;
		/** Fill percentage 0–100 for the inline progress bar. */
		progressPct?: number;
		/** Tap Stop → returns DE1 to Idle. */
		onCancel?: () => void;
	} = $props();

	const kindClass = $derived(
		state === 'steaming' ? 'is-steam' : state === 'dispensing' ? 'is-water' : 'is-flush'
	);
	const iconName = $derived(
		state === 'steaming' ? 'cloud' : state === 'dispensing' ? 'drop' : 'sparkle'
	);
	const fillPct = $derived(Math.max(0, Math.min(100, progressPct ?? 0)));
</script>

<div class={`mc-head-status ${kindClass}`}>
	<span class="mc-head-status-icon">
		<Icon cls={`ph-duotone ph-${iconName}`} aria-hidden="true" />
	</span>
	<span class="mc-head-status-text">
		<span class="mc-head-status-name">{nameLabel}</span>
		<span class="mc-head-status-meta">{metaLabel}</span>
	</span>
	<span class="mc-head-status-bar">
		<div style="width: {fillPct}%"></div>
	</span>
	<button
		type="button"
		class="mc-head-status-cancel"
		onclick={() => onCancel?.()}
		aria-label="Stop {nameLabel}"
	>
		<StopIcon weight="fill" aria-hidden="true" /> Stop
	</button>
</div>
