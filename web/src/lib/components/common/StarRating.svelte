<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';

	let {
		rating,
		interactive = false,
		onRate,
		size,
		fillColor = 'var(--copper-400)',
		emptyColor = 'rgba(var(--tint-rgb), 0.2)'
	}: {
		/** Current rating, 0–5 (rounded + clamped). */
		rating: number | null | undefined;
		/** When true, each star is a button that calls [onRate]. */
		interactive?: boolean;
		/** Set the rating (1–5). Interactive only. */
		onRate?: (n: number) => void;
		/** Star size in px. Omit to inherit the container's font-size (the SVG is 1em). */
		size?: number;
		/** Filled / empty star colour — theme tokens. */
		fillColor?: string;
		emptyColor?: string;
	} = $props();

	const value = $derived(Math.max(0, Math.min(5, Math.round(rating ?? 0))));
</script>

<!--
	The single star-rating renderer (issue 16): one Phosphor-SVG implementation for
	every "N of 5 stars" across History (rows, compare, detail) and the bean library
	(tiles, drawer, editor), replacing the divergent inline loops + the two Unicode
	`★` paths. `interactive` turns each star into a button.
-->
<span
	class="star-rating"
	class:is-unrated={value <= 0}
	style={size === undefined ? undefined : `font-size: ${size}px`}
	aria-label="{value} of 5 stars"
>
	{#each [1, 2, 3, 4, 5] as n (n)}
		{#if interactive}
			<button
				type="button"
				class="star-btn"
				onclick={() => onRate?.(n)}
				aria-label="{n} star{n === 1 ? '' : 's'}"
			>
				<Icon
					cls={n <= value ? 'ph-fill ph-star' : 'ph ph-star'}
					color={n <= value ? fillColor : emptyColor}
					aria-hidden="true"
				/>
			</button>
		{:else}
			<Icon
				cls={n <= value ? 'ph-fill ph-star' : 'ph ph-star'}
				color={n <= value ? fillColor : emptyColor}
				aria-hidden="true"
			/>
		{/if}
	{/each}
</span>

<style>
	.star-rating {
		display: inline-flex;
		align-items: center;
		gap: 2px;
		line-height: 1;
	}
	.star-btn {
		display: inline-flex;
		align-items: center;
		padding: 0;
		margin: 0;
		background: none;
		border: 0;
		cursor: pointer;
		color: inherit;
		line-height: 1;
		transition: transform var(--dur-1, 120ms) var(--ease, ease);
	}
	.star-btn:hover {
		transform: scale(1.12);
	}
</style>
