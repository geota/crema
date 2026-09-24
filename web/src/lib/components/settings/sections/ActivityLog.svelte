<script lang="ts">
	/**
	 * Recent cloud activity — one log across every destination (Visualizer
	 * pushes / pulls / deletes, Decent uploads), each entry tagged with where
	 * it went. Rendered by the Sharing section whenever any destination is
	 * connected; was Visualizer-only inside `BeanSyncSection`.
	 */
	import { onMount } from 'svelte';
	import Icon from '$lib/icons/Icon.svelte';
	import { onSyncConfigChange, readSyncConfig } from '$lib/visualizer';
	import type { SyncLogEntry } from '$lib/visualizer/sync-config';
	import { syncTimeLabel } from '$lib/utils/relative-time';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';

	let config = $state(readSyncConfig());
	let collapsed = $state(true);
	onMount(() => onSyncConfigChange((next) => (config = next)));

	/**
	 * A key that can never collide (Svelte 5 throws `each_key_duplicate`):
	 * the same shot can be logged by two destinations, or pushed and then
	 * pulled, within the same millisecond — so destination, direction and
	 * entity are part of it, and the position breaks any remaining tie.
	 */
	function entryKey(entry: SyncLogEntry, index: number): string {
		return `${entry.destination ?? 'visualizer'}|${entry.direction}|${entry.entity}|${entry.id}|${entry.at}|${index}`;
	}
	function arrow(direction: string): string {
		if (direction === 'push') return '↑';
		if (direction === 'pull') return '↓';
		if (direction === 'delete') return '✕';
		return '·';
	}
	function entityLabel(entity: string): string {
		if (entity === 'bean') return 'Bag';
		if (entity === 'roaster') return 'Roaster';
		return 'Shot';
	}
	function destLabel(d: string | undefined): string {
		return d === 'decent' ? 'Decent' : 'Visualizer';
	}
</script>

<StGroup title="Recent activity" sub="Every upload, pull and delete across your destinations.">
	<StRow
		title="Activity log"
		sub={`${config.log.length} entr${config.log.length === 1 ? 'y' : 'ies'} logged.`}
	>
		{#snippet control()}
			<button type="button" class="al-btn" onclick={() => (collapsed = !collapsed)}>
				<Icon cls={collapsed ? 'ph ph-caret-down' : 'ph ph-caret-up'} aria-hidden="true" />
				{collapsed ? 'Show log' : 'Hide log'}
			</button>
		{/snippet}
	</StRow>
	{#if !collapsed && config.log.length > 0}
		<ul class="al-log">
			{#each config.log as entry, i (entryKey(entry, i))}
				<li class={entry.error ? 'al-err' : ''}>
					<span class="al-arrow">{arrow(entry.direction)}</span>
					<span class="al-dest">{destLabel(entry.destination)}</span>
					<span class="al-kind">{entityLabel(entry.entity)}</span>
					<span class="al-name">"{entry.name}"</span>
					<span class="al-time">{syncTimeLabel(entry.at)}</span>
					{#if entry.error}
						<span class="al-msg">— {entry.error}</span>
					{/if}
				</li>
			{/each}
		</ul>
	{/if}
</StGroup>

<style>
	.al-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: transparent;
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-pill);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 11px;
		padding: 5px 10px;
		cursor: pointer;
	}
	.al-log {
		list-style: none;
		padding: 0 18px 8px;
		margin: 0;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.65);
		max-height: 280px;
		overflow-y: auto;
	}
	.al-log li {
		display: flex;
		align-items: baseline;
		gap: 6px;
		padding: 4px 0;
		border-bottom: 1px dashed rgba(var(--tint-rgb), 0.06);
	}
	.al-log li:last-child {
		border-bottom: 0;
	}
	.al-err {
		color: rgba(var(--danger-rgb, 204 76 76), 0.95);
	}
	.al-arrow {
		font-family: var(--font-mono);
		color: var(--copper-400);
		width: 12px;
		text-align: center;
	}
	.al-dest {
		font-family: var(--font-mono);
		font-size: 10px;
		color: var(--copper-400);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 4px;
		padding: 0 5px;
	}
	.al-kind {
		color: rgba(var(--tint-rgb), 0.5);
		min-width: 40px;
	}
	.al-name {
		color: var(--fg-1);
		max-width: 240px;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.al-time {
		color: rgba(var(--tint-rgb), 0.4);
		margin-left: auto;
	}
	.al-msg {
		color: rgba(var(--danger-rgb, 204 76 76), 0.85);
	}
</style>
