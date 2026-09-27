<script lang="ts">
	/**
	 * `BeanOverdrawNote` — the quiet "more than the N g left in this bag"
	 * note, shared by the Log-brew form and the guided Brew setup. Renders
	 * nothing unless the dose exceeds the bag's remaining grams.
	 */
	import type { Bean } from '$lib/bean';
	import { beanOverdraws } from '$lib/brew/bean-pick';

	let { bean, dose }: { bean: Bean | null; dose: number | null } = $props();
</script>

{#if beanOverdraws(bean, dose)}
	<div class="bl-warn">
		More than the {Math.max(0, Math.round(bean?.remaining ?? 0))} g left in this bag — saving floors
		the bag at zero.
	</div>
{/if}

<style>
	.bl-warn {
		font-family: var(--font-sans);
		font-size: 11.5px;
		color: var(--warning);
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-sm);
		padding: 7px 10px;
		line-height: 1.4;
	}
</style>
