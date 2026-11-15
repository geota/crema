<script lang="ts">
	/**
	 * Test host: a windowed list under scroll memory, as History lays it out
	 * (#310 P5w). `header` px of content sit above the list inside the
	 * scroller: 0 for the wide list, which scrolls itself, more for the phone
	 * page, which scrolls the stats and chips with it. NOT used by the app.
	 */
	import VirtualShotList from '#lib/components/history/VirtualShotList.svelte';
	import { arrayItems } from '#lib/components/history/virtual-window.js';
	import { useScrollMemory } from '#lib/state/use-view-memory.svelte.js';

	interface Item {
		key: string;
		height: number;
		heading?: boolean;
	}

	let {
		items,
		memoryKey,
		header = 0,
		onlist
	}: {
		items: Item[];
		memoryKey: string;
		header?: number;
		onlist?: (list: ReturnType<typeof VirtualShotList<Item>>) => void;
	} = $props();

	let scroller = $state<HTMLElement>();
	let list = $state<ReturnType<typeof VirtualShotList<Item>>>();
	const rows = $derived(items.filter((i) => !i.heading).length);
	const positions = $derived.by(() => {
		const m = new Map<string, number>();
		let n = 0;
		for (const i of items) if (!i.heading) m.set(i.key, ++n);
		return m;
	});

	$effect(() => {
		if (list) onlist?.(list);
	});

	useScrollMemory({
		key: () => memoryKey,
		container: () => scroller,
		scrollToKey: (key) => list?.scrollTopForKey(key) ?? null
	});
</script>

<div bind:this={scroller} data-test-scroller data-test-header={header}>
	<div style:height="{header}px"></div>
	<VirtualShotList
		bind:this={list}
		items={arrayItems(items)}
		keyOf={(it) => it.key}
		heightOf={(it) => it.height}
		scroller={() => scroller}
		label="Shots"
		setSize={rows}
		positionOf={(it) => positions.get(it.key) ?? null}
	>
		{#snippet item(it)}
			{#if it.heading}
				<h2>{it.key}</h2>
			{:else}
				<button type="button" data-scroll-key={it.key}>{it.key}</button>
			{/if}
		{/snippet}
	</VirtualShotList>
</div>
