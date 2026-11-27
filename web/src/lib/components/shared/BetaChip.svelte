<script lang="ts">
	/**
	 * `BetaChip` — the "Beta" tag on a feature no one has run against real
	 * hardware yet (the connected grinder's card, its Devices row). `small`
	 * sits beside a small caps label.
	 */
	let { small = false }: { small?: boolean } = $props();
</script>

<span class={['beta-chip', { small }]}>Beta</span>

<style>
	.beta-chip {
		font-size: 11px;
		font-weight: 600;
		letter-spacing: 0.08em;
		text-transform: uppercase;
		color: var(--fg-accent);
		border: 1px solid currentColor;
		border-radius: var(--radius-pill);
		padding: 2px 8px;
	}
	.beta-chip.small {
		font-size: 9px;
		line-height: 1.2;
		padding: 1px 6px;
	}
</style>
