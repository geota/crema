<script lang="ts">
	/**
	 * `RoastSlider` — the 1..10 roast-level selector that's the *one*
	 * design element from the Claude-AI canvas we keep verbatim.
	 *
	 * Two modes:
	 *
	 * - **Read-only** (`editMode={false}`, default) — flat track with a
	 *   knob at the value and a `n/10 · Band` readout. Used in the
	 *   library tile (drawer hero) and read-only contexts.
	 * - **Edit** (`editMode={true}`) — interactive native range input
	 *   layered over the track, ticks under every integer position, and
	 *   five band labels (Light / Med-light / Medium / Med-dark / Dark)
	 *   under the ticks. Used in the Bean editor's Roast block.
	 *
	 * The visual treatment is copy-on-paste from the design: a copper-
	 * gradient fill, a knob centred at the `(value - 1) / 9` percent
	 * position, a single 11px row of band words spread across the
	 * track. All tokens are Crema's (`--copper-400/500`, `--tint-rgb`,
	 * `--hairline`, etc.) — the design's espresso/ink tokens map cleanly
	 * to Crema's via the surface-level CSS-var pass.
	 */
	import { roastBand5 } from '$lib/bean';

	let {
		value,
		editMode = false,
		onChange
	}: {
		/** Current roast level on the 1..10 scale, or `null` for "unset". */
		value: number | null;
		/** Show ticks + band labels + accept input when true. */
		editMode?: boolean;
		/** Called on every change in edit mode with the new 1..10 value. */
		onChange?: (next: number) => void;
	} = $props();

	/** Effective value used to position the knob — defaults to 5 (mid). */
	const v = $derived(value ?? 5);
	/** 0..100 percent along the track. */
	const pct = $derived(((v - 1) / 9) * 100);
	const bandLabel = $derived(roastBand5(value));

	function onInput(e: Event): void {
		const n = Math.round((e.currentTarget as HTMLInputElement).valueAsNumber);
		if (Number.isFinite(n)) onChange?.(Math.max(1, Math.min(10, n)));
	}
</script>

<div class="bn-rlevel" class:bn-rlevel-edit={editMode} class:is-unset={value == null}>
	<div class="bn-rlevel-track">
		<div class="bn-rlevel-fill" style="width: {pct}%"></div>
		<div class="bn-rlevel-knob" style="left: {pct}%"></div>
		{#if editMode}
			{#each [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] as n}
				<span class="bn-rlevel-tick" style="left: {((n - 1) / 9) * 100}%"></span>
			{/each}
			<input
				class="bn-rlevel-input"
				type="range"
				min="1"
				max="10"
				step="1"
				value={v}
				aria-label="Roast level, 1 light to 10 dark"
				oninput={onInput}
			/>
		{/if}
	</div>

	{#if editMode}
		<div class="bn-rlevel-bands">
			<span>Light</span>
			<span>Med-light</span>
			<span>Medium</span>
			<span>Med-dark</span>
			<span>Dark</span>
		</div>
	{/if}

	<div class="bn-rlevel-meta">
		<span class="bn-rlevel-val"
			>{value == null ? '—' : value}<em>{value == null ? '' : '/10'}</em></span
		>
		<span class="bn-rlevel-band">{bandLabel}</span>
	</div>
</div>

<style>
	.bn-rlevel {
		display: flex;
		flex-direction: column;
		gap: 6px;
		width: 100%;
		min-width: 220px;
	}
	.bn-rlevel-track {
		position: relative;
		height: 6px;
		background: rgba(var(--tint-rgb), 0.08);
		border-radius: 999px;
	}
	.bn-rlevel-fill {
		position: absolute;
		inset: 0 auto 0 0;
		background: linear-gradient(90deg, var(--copper-300), var(--copper-500));
		border-radius: 999px;
		transition: width var(--dur-1) var(--ease);
	}
	.bn-rlevel-knob {
		position: absolute;
		top: 50%;
		width: 14px;
		height: 14px;
		border-radius: 50%;
		background: var(--copper-400);
		border: 2px solid var(--bg-page);
		box-shadow: 0 1px 2px rgba(0, 0, 0, 0.25);
		transform: translate(-50%, -50%);
		transition: left var(--dur-1) var(--ease);
		pointer-events: none;
	}
	.is-unset .bn-rlevel-knob {
		background: rgba(var(--tint-rgb), 0.3);
	}
	.is-unset .bn-rlevel-fill {
		opacity: 0.35;
	}
	.bn-rlevel-tick {
		position: absolute;
		top: -3px;
		width: 1px;
		height: 12px;
		background: rgba(var(--tint-rgb), 0.18);
		transform: translateX(-50%);
		pointer-events: none;
	}
	.bn-rlevel-input {
		position: absolute;
		inset: -8px -8px -8px -8px;
		width: calc(100% + 16px);
		height: calc(100% + 16px);
		margin: 0;
		opacity: 0;
		cursor: pointer;
		-webkit-appearance: none;
		appearance: none;
	}
	.bn-rlevel-input::-webkit-slider-thumb {
		-webkit-appearance: none;
		appearance: none;
		width: 24px;
		height: 24px;
		background: transparent;
	}
	.bn-rlevel-input::-moz-range-thumb {
		width: 24px;
		height: 24px;
		background: transparent;
		border: 0;
	}
	.bn-rlevel-bands {
		display: grid;
		grid-template-columns: repeat(5, 1fr);
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 500;
		color: rgba(var(--tint-rgb), 0.45);
		text-align: center;
		letter-spacing: 0.02em;
	}
	.bn-rlevel-meta {
		display: inline-flex;
		align-items: baseline;
		gap: 8px;
		font-family: var(--font-sans);
	}
	.bn-rlevel-val {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 14px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.bn-rlevel-val em {
		font-style: normal;
		color: rgba(var(--tint-rgb), 0.45);
		font-size: 11px;
		margin-left: 1px;
	}
	.bn-rlevel-band {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
	}
</style>
