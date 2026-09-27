<script lang="ts">
	/**
	 * `BrewBeanRow` — the "Bean" row shared by the Log-brew form and the
	 * guided Brew setup (issue #10): the chosen bag with its roaster and
	 * grams left, or "No bean", opening the library's `BeanPicker`
	 * (archived bags excluded). Choosing here never changes the app's
	 * active bag.
	 */
	import { getBeanStore, type Bean } from '$lib/bean';
	import BeanPicker from '$lib/components/history/BeanPicker.svelte';

	let {
		beanId,
		onPick
	}: {
		/** The chosen bag, or `null` for No bean. */
		beanId: string | null;
		/** A bag was picked (`null` = No bean). */
		onPick: (id: string | null) => void;
	} = $props();

	const library = getBeanStore();
	let picking = $state(false);

	const bean = $derived(beanId ? library.getBean(beanId) : null);
	const roaster = $derived(bean?.roasterId ? library.getRoaster(bean.roasterId) : null);
</script>

<button type="button" class="bl-bean" onclick={() => (picking = true)}>
	<div class="bl-bean-main">
		<span class="bl-label">Bean</span>
		{#if bean}
			<span class="bl-bean-name">
				{roaster ? `${roaster.name} · ` : ''}{bean.name}
			</span>
		{:else}
			<span class="bl-bean-name bl-bean-none">No bean — inventory untouched</span>
		{/if}
	</div>
	<span class="bl-bean-side">
		{#if bean}
			<span class="bl-bean-left">{Math.max(0, Math.round(bean.remaining))} g left</span>
		{/if}
		<span aria-hidden="true">▾</span>
	</span>
</button>

{#if picking}
	<BeanPicker
		currentBeanId={beanId}
		onPick={(b: Bean) => {
			onPick(b.id);
			picking = false;
		}}
		onClear={() => {
			onPick(null);
			picking = false;
		}}
		onClose={() => (picking = false)}
	/>
{/if}

<style>
	.bl-bean {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
		width: 100%;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		padding: 8px 12px;
		cursor: pointer;
		text-align: left;
		color: var(--fg-1);
	}
	.bl-bean:hover {
		border-color: var(--copper-400);
	}
	.bl-bean-main {
		display: flex;
		flex-direction: column;
		gap: 3px;
		min-width: 0;
	}
	.bl-label {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.bl-bean-name {
		font-family: var(--font-sans);
		font-size: 13.5px;
		font-weight: 600;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.bl-bean-none {
		font-weight: 400;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.bl-bean-side {
		display: flex;
		align-items: center;
		gap: 8px;
		color: rgba(var(--tint-rgb), 0.55);
		font-size: 12px;
		flex: none;
	}
	.bl-bean-left {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 11.5px;
	}
</style>
