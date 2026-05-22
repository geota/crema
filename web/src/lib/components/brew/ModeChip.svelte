<script lang="ts">
	/**
	 * `ModeChip` — single Steam / Hot-water / Flush mode chip on the Brew
	 * page action row. Ported from `mode-controls.jsx:ModeChip` in the
	 * `crema-chips` handoff.
	 *
	 * Class-driven states:
	 * - default (idle)      — neutral tint
	 * - `is-active is-<kind>` — mode running; mode-colored border + icon,
	 *   `×` cancel chip on the right
	 * - `is-disabled`       — DE1 not ready; greyed, `cursor: not-allowed`
	 *
	 * The chip is a single button. The HANDOFF spec says "tap to start,
	 * again to cancel" — the same click handler fires from any state, the
	 * orchestrator decides whether to request the mode or return to Idle.
	 */
	import type { Snippet } from 'svelte';

	type Kind = 'steam' | 'water' | 'flush';

	let {
		kind,
		active = false,
		ready = true,
		icon,
		label,
		sub,
		onTap
	}: {
		/** Which mode this chip represents. Drives the active-state colour. */
		kind: Kind;
		/** Whether the DE1 is currently in *this* chip's mode. */
		active?: boolean;
		/** Whether the DE1 is ready to accept the mode-request write. */
		ready?: boolean;
		/** Phosphor icon name (e.g. `'cloud'`, `'drop'`, `'sparkle'`). */
		icon: string;
		/** Top label inside the chip (e.g. `'Steam'`). */
		label: string;
		/** Sub-label (e.g. `'148 °C · 8 s'` or `'In progress'`). */
		sub?: string;
		/** Click handler. Caller decides start-vs-cancel from `active`. */
		onTap?: () => void;
	} = $props();
</script>

<button
	type="button"
	class="mc-chip"
	class:is-active={active}
	class:is-steam={active && kind === 'steam'}
	class:is-water={active && kind === 'water'}
	class:is-flush={active && kind === 'flush'}
	class:is-disabled={!ready}
	disabled={!ready}
	aria-label={active ? `Cancel ${label}` : `Start ${label}`}
	onclick={() => onTap?.()}
>
	<i class={`ph-duotone ph-${icon}`} aria-hidden="true"></i>
	<span class="mc-chip-text">
		<span>{label}</span>
		{#if sub}
			<span class="mc-chip-sub">{sub}</span>
		{/if}
	</span>
	{#if active}
		<span class="mc-chip-cancel" aria-hidden="true">
			<i class="ph ph-x"></i>
		</span>
	{/if}
</button>
