<script lang="ts">
	/**
	 * `StValueChip` — a mono value chip with a pencil affordance. In the design
	 * it is a tap target for editing a read-only-looking value; here it is wired
	 * for real where the value is an app preference (Brew defaults, sound
	 * volume): clicking turns the chip into an inline number input that commits
	 * on blur / Enter, so the change persists through `lib/settings`.
	 *
	 * When `onCommit` is omitted the chip is purely presentational (the design's
	 * original behaviour) — used for values the shell cannot yet edit.
	 */
	let {
		value,
		suffix = '',
		step = 1,
		min,
		max,
		decimals = 0,
		onCommit
	}: {
		/** The current numeric value. */
		value: number;
		/** A unit suffix appended after the number (e.g. ` g`, ` °C`). */
		suffix?: string;
		/** Increment granularity for the inline input. */
		step?: number;
		/** Optional lower bound. */
		min?: number;
		/** Optional upper bound. */
		max?: number;
		/** Decimal places for display. */
		decimals?: number;
		/** Called with the committed value; omit for a read-only chip. */
		onCommit?: (next: number) => void;
	} = $props();

	/** Whether the chip is currently in its inline-edit state. */
	let editing = $state(false);
	/** The draft string while editing. */
	let draft = $state('');
	let inputEl = $state<HTMLInputElement | null>(null);

	const display = $derived(value.toFixed(decimals) + suffix);

	function begin(): void {
		if (!onCommit) return;
		draft = String(value);
		editing = true;
		// Focus + select once the input is in the DOM.
		queueMicrotask(() => {
			inputEl?.focus();
			inputEl?.select();
		});
	}

	function commit(): void {
		if (!editing) return;
		editing = false;
		let next = Number(draft);
		if (!Number.isFinite(next)) return;
		if (min !== undefined) next = Math.max(min, next);
		if (max !== undefined) next = Math.min(max, next);
		if (next !== value) onCommit?.(next);
	}

	function onKey(e: KeyboardEvent): void {
		if (e.key === 'Enter') {
			e.preventDefault();
			commit();
		} else if (e.key === 'Escape') {
			e.preventDefault();
			editing = false;
		}
	}
</script>

{#if editing}
	<input
		bind:this={inputEl}
		class="st-valuechip-input"
		type="number"
		{step}
		{min}
		{max}
		bind:value={draft}
		onblur={commit}
		onkeydown={onKey}
	/>
{:else}
	<button type="button" class="st-valuechip" onclick={begin}>
		<span>{display}</span>
		<i class="ph ph-pencil-simple-line" aria-hidden="true"></i>
	</button>
{/if}
