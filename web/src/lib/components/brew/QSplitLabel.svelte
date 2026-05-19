<script lang="ts" module>
	/** One option in a split-pill label — `id` is the value, `label` the text. */
	export interface SplitOption {
		/** The value this option selects. */
		id: string;
		/** The visible label. */
		label: string;
		/** Optional dim caption shown after the label when active (e.g. "log only"). */
		meta?: string;
	}
</script>

<script lang="ts">
	/**
	 * `QSplitLabel` — the "DOSE | grind" split-pill label, ported from the
	 * design's `QSplitLabel` in `quick-controls-v2.jsx`. Tapping the dim side
	 * switches which parameter the stepper below edits; the copper underline
	 * tracks the active option.
	 *
	 * It is the shell's one label primitive, so all three pieces are optional:
	 *
	 *  - `prefix` — a non-clickable category word (e.g. "Steam", "Target").
	 *  - `options` — the split toggle; omit it for a plain label.
	 *  - `dot` — a leading on/off dot that enables / disables an optional
	 *    group (the segment editor's Volume / Exit / Max).
	 *
	 * With only a `prefix` it renders as a plain label; with `prefix` +
	 * `options` as the classic split label; `dot` adds the enable switch.
	 */
	let {
		prefix,
		options,
		value,
		onChange,
		dot = false,
		dotOn = false,
		onDot
	}: {
		/** Optional non-clickable category word before the toggle group. */
		prefix?: string;
		/** The toggle options. Omit for a plain (toggle-less) label. */
		options?: SplitOption[];
		/** The currently selected option id. */
		value?: string;
		/** Called with the chosen option id. */
		onChange?: (next: string) => void;
		/** Whether to render a leading on/off dot. */
		dot?: boolean;
		/** The on/off dot's state. */
		dotOn?: boolean;
		/** Called when the on/off dot is clicked. */
		onDot?: () => void;
	} = $props();
</script>

<div class="qsplit">
	{#if dot}
		<button
			class="qsplit-dot"
			class:on={dotOn}
			type="button"
			aria-pressed={dotOn}
			aria-label={prefix ? `Toggle ${prefix}` : 'Toggle'}
			onclick={onDot}
		></button>
	{/if}
	{#if prefix}
		<span class="qsplit-prefix">{prefix}</span>
	{/if}
	{#if options && options.length > 0}
		<span class="qsplit-toggle">
			{#each options as option, i (option.id)}
				{#if i > 0}
					<span class="qsplit-sep">|</span>
				{/if}
				<button
					class="qsplit-opt"
					class:is-active={value === option.id}
					onclick={() => onChange?.(option.id)}
				>
					{option.label}{#if option.meta && value === option.id}<span class="qsplit-meta"
							>{option.meta}</span
						>{/if}
				</button>
			{/each}
		</span>
	{/if}
</div>

<style>
	/* The on/off dot — copper when enabled, hollow when disabled. The other
	   `.qsplit*` classes come from the global design CSS; only the dot, which
	   the design's QSplitLabel never had, is styled here. */
	.qsplit-dot {
		width: 7px;
		height: 7px;
		flex: 0 0 7px;
		padding: 0;
		border-radius: 50%;
		border: 1px solid rgba(244, 237, 224, 0.35);
		background: transparent;
		box-sizing: border-box;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.qsplit-dot.on {
		background: var(--copper-500);
		border-color: var(--copper-500);
	}
</style>
