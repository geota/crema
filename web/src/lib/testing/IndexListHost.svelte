<script lang="ts">
	/**
	 * Test host: the History list over the stage-1 index (#310 W2), wired as the
	 * page wires it: an `IndexList` window for `query`, the windowed list
	 * finding keys through `index_of` and loading the pages it shows, under
	 * scroll memory. NOT used by the app.
	 */
	import { untrack } from 'svelte';
	import VirtualShotList from '#lib/components/history/VirtualShotList.svelte';
	import { useScrollMemory } from '#lib/state/use-view-memory.svelte.js';
	import { IndexList } from '#lib/history/index-list.svelte.js';
	import { fallbackRowHeights, rowHeight, ROW_GAP, type ListItem } from '#lib/history/list-items.js';
	import type { HistoryQuery, IndexReads } from '#lib/history-db/window.svelte.js';
	import type { WindowAnswer } from '#lib/history-db/window.svelte.js';
	import type { ShotRow } from '#lib/history/model.js';

	let {
		reads,
		query,
		memoryKey,
		edges = null,
		onlist,
		onanswer
	}: {
		reads: IndexReads;
		query: HistoryQuery;
		memoryKey: string;
		edges?: number[] | null;
		onlist?: (list: ReturnType<typeof VirtualShotList<ListItem>>, index: IndexList) => void;
		onanswer?: (a: WindowAnswer<ShotRow>) => void;
	} = $props();

	let scroller = $state<HTMLElement>();
	let vl = $state<ReturnType<typeof VirtualShotList<ListItem>>>();
	let index = $state.raw<IndexList | null>(null);
	const heights = fallbackRowHeights();

	$effect(() => {
		const q = query;
		const e = edges;
		const l = untrack(
			() => new IndexList({ query: q, reads, headings: e !== null, edges: e, current: (r) => r, onAnswer: onanswer })
		);
		index = l;
		return () => l.destroy();
	});

	$effect(() => {
		if (vl && index) onlist?.(vl, index);
	});

	useScrollMemory({
		key: () => memoryKey,
		container: () => scroller,
		scrollToKey: (key) => vl?.scrollTopForKey(key) ?? null
	});
</script>

<div bind:this={scroller} data-test-scroller>
	{#if index}
		<VirtualShotList
			bind:this={vl}
			items={index.items}
			keyOf={(it) => it.key}
			heightOf={(it) => rowHeight(it, edges !== null, heights)}
			scroller={() => scroller}
			label="Shots"
			setSize={index.count ?? 0}
			positionOf={(it) => (it.kind === 'head' ? null : it.pos)}
			gap={ROW_GAP}
			locate={(key) => index!.locate(key)}
			onrange={(start, end) => index?.visibleItems(start, end)}
		>
			{#snippet item(it)}
				{#if it.kind === 'head'}
					<h2>{it.label}</h2>
				{:else if it.kind === 'skeleton'}
					<div data-skeleton={it.pos}></div>
				{:else}
					<button type="button" data-scroll-key={it.key}>{it.key}</button>
				{/if}
			{/snippet}
		</VirtualShotList>
	{/if}
</div>
