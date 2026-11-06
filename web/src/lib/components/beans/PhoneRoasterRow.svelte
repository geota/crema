<script lang="ts">
	/**
	 * `PhoneRoasterRow` — a roastery in the phone directory (Android
	 * `PhoneRoasterRow`): tap for its shelf, the pencil edits. Android's trailing
	 * caret gives way to a ⋮, because the web's duplicate, website and delete
	 * have no other home here.
	 */
	import DotsThreeVerticalIcon from 'phosphor-svelte/lib/DotsThreeVerticalIcon';
	import PencilSimpleIcon from 'phosphor-svelte/lib/PencilSimpleIcon';
	import { roasterMarkTone, type Roaster } from '#lib/bean/index.js';

	let {
		roaster,
		count,
		archivedCount = 0,
		dupOf,
		onOpen,
		onEdit,
		onUnmerge,
		onMenu
	}: {
		roaster: Roaster;
		count: number;
		archivedCount?: number;
		dupOf: Roaster | null;
		onOpen: (id: string) => void;
		onEdit: (id: string) => void;
		onUnmerge: (id: string) => void;
		onMenu: (id: string) => void;
	} = $props();

	const mark = $derived(roasterMarkTone(roaster));
	let logoFailed = $state(false);
	const location = $derived([roaster.city, roaster.country].filter((p) => p?.trim()).join(', '));
	const bags = $derived(
		`${count} bag${count === 1 ? '' : 's'}${archivedCount > 0 ? ` · ${archivedCount} archived` : ''}`
	);
</script>

<article class={['prr', { 'is-dup': dupOf }]} data-scroll-key={roaster.id}>
	<button
		type="button"
		class="prr-open"
		aria-label="Open {roaster.name}"
		onclick={() => onOpen(roaster.id)}
	></button>
	<span class="prr-mark" style:--tone={mark.tone}>
		{#if roaster.imageUrl && !logoFailed}
			<img src={roaster.imageUrl} alt="" onerror={() => (logoFailed = true)} />
		{:else}
			{mark.mark}
		{/if}
	</span>
	<div class="prr-text">
		<div class="prr-name">{roaster.name}</div>
		<div class="prr-meta">{[location, bags].filter(Boolean).join(' · ')}</div>
		{#if dupOf}
			<div class="prr-dup">
				<span>Duplicate of {dupOf.name}</span>
				<button type="button" onclick={() => onUnmerge(roaster.id)}>Un-merge</button>
			</div>
		{/if}
	</div>
	<button
		type="button"
		class="prr-icon"
		aria-label="Edit {roaster.name}"
		onclick={() => onEdit(roaster.id)}
	>
		<PencilSimpleIcon aria-hidden="true" />
	</button>
	<button
		type="button"
		class="prr-icon"
		aria-label="More actions for {roaster.name}"
		aria-haspopup="dialog"
		onclick={() => onMenu(roaster.id)}
	>
		<DotsThreeVerticalIcon weight="bold" aria-hidden="true" />
	</button>
</article>

<style>
	.prr {
		position: relative;
		display: flex;
		align-items: center;
		gap: 13px;
		padding: 9px 4px 9px 14px;
		border-radius: 16px;
		background: var(--bg-surface);
		font-family: var(--font-sans);
	}
	.prr.is-dup {
		opacity: 0.7;
	}
	.prr-open {
		position: absolute;
		inset: 0;
		border: 0;
		border-radius: 16px;
		background: transparent;
		cursor: pointer;
	}
	.prr-open:focus-visible,
	.prr-icon:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.prr-mark {
		flex: none;
		display: flex;
		align-items: center;
		justify-content: center;
		width: 42px;
		height: 42px;
		overflow: hidden;
		border-radius: 12px;
		background: var(--tone);
		color: #fff;
		font-size: 16px;
		font-weight: 500;
		pointer-events: none;
	}
	.prr-mark img {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.prr-text {
		flex: 1 1 auto;
		min-width: 0;
		pointer-events: none;
	}
	.prr-name {
		overflow: hidden;
		font-size: 16px;
		font-weight: 500;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.prr-meta {
		overflow: hidden;
		font-size: 12px;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.prr-dup {
		display: flex;
		align-items: center;
		gap: 8px;
		margin-top: 2px;
		font-size: 12px;
		color: var(--warning);
	}
	.prr-dup button {
		position: relative;
		min-height: 44px;
		padding: 0 8px;
		margin: -12px 0;
		border: 0;
		background: transparent;
		color: var(--fg-accent);
		font: inherit;
		font-weight: 500;
		cursor: pointer;
		pointer-events: auto;
	}
	.prr-icon {
		position: relative;
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 44px;
		height: 44px;
		margin-left: -9px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		color: var(--fg-3);
		font-size: 18px;
		cursor: pointer;
	}
</style>
