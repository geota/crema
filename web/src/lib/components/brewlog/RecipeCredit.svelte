<script lang="ts">
	/**
	 * `RecipeCredit` — a recipe's attribution line: who it's by, plus a
	 * link to the primary source (opens in a new tab). Small, secondary
	 * text — attribution only, never an endorsement. Renders nothing for
	 * an uncredited recipe. The link only renders for an http(s) URL: a
	 * restored backup can carry any string.
	 */
	import ArrowSquareOutIcon from 'phosphor-svelte/lib/ArrowSquareOutIcon';

	let {
		credit = null,
		sourceUrl = null,
		compact = false
	}: {
		credit?: string | null;
		sourceUrl?: string | null;
		/** One line, ellipsized (cards); otherwise it wraps. */
		compact?: boolean;
	} = $props();

	const href = $derived(sourceUrl && /^https?:\/\//i.test(sourceUrl.trim()) ? sourceUrl.trim() : null);
</script>

{#if credit?.trim() || href}
	<div class="rc" class:is-compact={compact}>
		{#if credit?.trim()}<span class="rc-credit" title={credit}>{credit}</span>{/if}
		{#if href}
			<a
				class="rc-link"
				{href}
				target="_blank"
				rel="noopener noreferrer"
				aria-label="Open the recipe source in a new tab"
				onclick={(e) => e.stopPropagation()}
			>
				Source <ArrowSquareOutIcon size={11} aria-hidden="true" />
			</a>
		{/if}
	</div>
{/if}

<style>
	.rc {
		display: flex;
		align-items: baseline;
		flex-wrap: wrap;
		gap: 2px 8px;
		font-family: var(--font-sans);
		font-size: 11.5px;
		line-height: 1.35;
		color: rgba(var(--tint-rgb), 0.55);
		min-width: 0;
	}
	.rc.is-compact {
		flex-wrap: nowrap;
	}
	.rc.is-compact .rc-credit {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		min-width: 0;
	}
	.rc-link {
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 3px;
		color: rgba(var(--tint-rgb), 0.7);
		text-decoration: underline;
		text-decoration-color: rgba(var(--tint-rgb), 0.3);
		text-underline-offset: 2px;
		border-radius: var(--radius-sm);
	}
	.rc-link:hover {
		color: var(--copper-400);
		text-decoration-color: currentColor;
	}
	.rc-link:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
</style>
