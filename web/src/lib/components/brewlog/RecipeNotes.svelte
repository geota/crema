<script lang="ts">
	/**
	 * `RecipeNotes` — a recipe's free-text setup notes (filter, water
	 * recipe, grinder …), styled like the credit line: small secondary
	 * text. `lines` clamps it (cards); unclamped it wraps in full. An
	 * optional `label` prefixes it ("Recipe notes"). Renders nothing for a
	 * recipe without notes.
	 */
	let {
		notes = null,
		lines = 0,
		label = null
	}: {
		notes?: string | null;
		/** Clamp to this many lines (0 = show it all). */
		lines?: number;
		label?: string | null;
	} = $props();

	const text = $derived(notes?.trim() ?? '');
</script>

{#if text}
	<div
		class="rn"
		class:is-clamped={lines > 0}
		style:-webkit-line-clamp={lines > 0 ? lines : undefined}
		style:line-clamp={lines > 0 ? lines : undefined}
		title={lines > 0 ? text : undefined}
	>
		{#if label}<span class="rn-label">{label}</span>{/if}{text}
	</div>
{/if}

<style>
	.rn {
		font-family: var(--font-sans);
		font-size: 11.5px;
		line-height: 1.35;
		color: rgba(var(--tint-rgb), 0.55);
		white-space: pre-line;
		overflow-wrap: anywhere;
		min-width: 0;
	}
	.rn.is-clamped {
		display: -webkit-box;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.rn-label {
		font-weight: 600;
		color: rgba(var(--tint-rgb), 0.65);
		margin-right: 6px;
	}
</style>
