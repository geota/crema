<script lang="ts">
	import Icon from '#lib/icons/Icon.svelte';

	let {
		rating,
		interactive = false,
		onRate,
		size,
		fillColor = 'var(--copper-400)',
		emptyColor = 'rgba(var(--tint-rgb), 0.2)',
		label = 'Rating',
		labelledby
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
		/** Accessible group name for the interactive rating (default "Rating"). */
		label?: string;
		/** Id of a visible label for the interactive rating (wins over `label`). */
		labelledby?: string;
	} = $props();

	const value = $derived(Math.max(0, Math.min(5, Math.round(rating ?? 0))));

	/*
	 * Interactive stars are a radio group (cohort review 07 #8): the set
	 * rating is `aria-checked`, one star is in the tab order (roving
	 * tabindex) and the arrow keys move and set the rating.
	 */
	function onStarKey(e: KeyboardEvent & { currentTarget: HTMLButtonElement }, n: number): void {
		let next = n;
		if (e.key === 'ArrowRight' || e.key === 'ArrowUp') next = Math.min(5, n + 1);
		else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') next = Math.max(1, n - 1);
		else if (e.key === 'Home') next = 1;
		else if (e.key === 'End') next = 5;
		else return;
		e.preventDefault();
		onRate?.(next);
		(e.currentTarget.parentElement?.children[next - 1] as HTMLElement | undefined)?.focus();
	}
</script>

<!--
	The single star-rating renderer (issue 16): one Phosphor-SVG implementation for
	every "N of 5 stars" across History (rows, compare, detail) and the bean library
	(tiles, drawer, editor), replacing the divergent inline loops + the two Unicode
	`★` paths. `interactive` turns each star into a button.
-->
{#if interactive}
	<span
		class="star-rating"
		class:is-unrated={value <= 0}
		style={size === undefined ? undefined : `font-size: ${size}px`}
		role="radiogroup"
		aria-label={labelledby ? undefined : label}
		aria-labelledby={labelledby}
	>
		{#each [1, 2, 3, 4, 5] as n (n)}
			<button
				type="button"
				class="star-btn"
				role="radio"
				aria-checked={n === value}
				tabindex={n === Math.max(1, value) ? 0 : -1}
				onclick={() => onRate?.(n)}
				onkeydown={(e) => onStarKey(e, n)}
				aria-label="{n} star{n === 1 ? '' : 's'}"
			>
				<Icon
					cls={n <= value ? 'ph-fill ph-star' : 'ph ph-star'}
					color={n <= value ? fillColor : emptyColor}
					aria-hidden="true"
				/>
			</button>
		{/each}
	</span>
{:else}
	<!-- Read-only: one image with the rating as its name (a roleless span's
	     aria-label is not announced). -->
	<span
		class="star-rating"
		class:is-unrated={value <= 0}
		style={size === undefined ? undefined : `font-size: ${size}px`}
		role="img"
		aria-label="{value} of 5 stars"
	>
		{#each [1, 2, 3, 4, 5] as n (n)}
			<Icon
				cls={n <= value ? 'ph-fill ph-star' : 'ph ph-star'}
				color={n <= value ? fillColor : emptyColor}
				aria-hidden="true"
			/>
		{/each}
	</span>
{/if}

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
