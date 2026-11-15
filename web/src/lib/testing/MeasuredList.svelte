<script lang="ts">
	/**
	 * Test host: a windowed list whose item heights come from measured samples,
	 * as History's do (#423). Two kinds, `row` and `note`, start at 60 and 78
	 * px; each sample's measured height replaces its kind's. The test gives the
	 * `data-test-view` element its viewport. NOT used by the app.
	 */
	import VirtualShotList from '#lib/components/history/VirtualShotList.svelte';
	import { arrayItems } from '#lib/components/history/virtual-window.js';

	interface Item {
		key: string;
		kind: 'row' | 'note';
	}

	let {
		items,
		ontextwidth
	}: {
		items: Item[];
		ontextwidth?: (width: number) => void;
	} = $props();

	const heights = $state({ row: 60, note: 78 });
	const samples: Item[] = [
		{ key: 'sample:row', kind: 'row' },
		{ key: 'sample:note', kind: 'note' }
	];
	let scroller = $state<HTMLElement>();
</script>

<div bind:this={scroller} data-test-view>
	<VirtualShotList
		items={arrayItems(items)}
		keyOf={(it) => it.key}
		heightOf={(it) => heights[it.kind]}
		scroller={() => scroller}
		label="Shots"
		setSize={items.length}
		positionOf={(_, i) => i + 1}
		gap={2}
		{samples}
		onmeasure={(s, h) => (heights[s.kind] = h)}
		{ontextwidth}
	>
		{#snippet item(it)}
			<button type="button" data-kind={it.kind}>{it.key}</button>
		{/snippet}
	</VirtualShotList>
</div>
