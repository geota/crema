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
	 * An optional `prefix` renders a non-clickable grey noun before the toggle
	 * (e.g. "Steam   time | flow").
	 */
	let {
		prefix,
		options,
		value,
		onChange
	}: {
		/** Optional non-clickable category word before the toggle group. */
		prefix?: string;
		/** The two (or more) toggle options. */
		options: SplitOption[];
		/** The currently selected option id. */
		value: string;
		/** Called with the chosen option id. */
		onChange?: (next: string) => void;
	} = $props();
</script>

<div class="qsplit">
	{#if prefix}
		<span class="qsplit-prefix">{prefix}</span>
	{/if}
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
</div>
