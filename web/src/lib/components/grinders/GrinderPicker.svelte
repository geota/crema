<script lang="ts" module>
	/** One owned grinder in the Brew picker. */
	export interface GrinderPickRow {
		id: string;
		name: string;
		/** "Saved 480 μm" / "Nothing saved · last shot 32" / the range. */
		line: string;
		/** The selected bag's starred grinder. */
		starred: boolean;
	}
</script>

<script lang="ts">
	/**
	 * `GrinderPicker` — the Brew header Grinder block's dropdown (#488, boards
	 * WideBrew2, WideBrewStar): your grinders, each with what the selected bag
	 * saved for it (or its newest shot), ★ on the bag's grinder; "No grinder";
	 * the type-or-pick Add box behind "+"; footer "Edit this bag's grinds" and
	 * Devices. Shown even with one grinder. Presentational: the dashboard
	 * takes the choices.
	 */
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import PlusIcon from 'phosphor-svelte/lib/PlusIcon';
	import StarIcon from 'phosphor-svelte/lib/StarIcon';
	import { restoreFocus } from '#lib/a11y/focus.js';
	import type { Grinder, GrinderChoice } from '#lib/bean/grinder.js';
	import GrinderTypeOrPick from './GrinderTypeOrPick.svelte';

	let {
		open = false,
		rows,
		activeId = null,
		bagName = null,
		allGrinders,
		onSelect,
		onAdd,
		onEditBag,
		onDevices,
		onClose
	}: {
		open?: boolean;
		rows: readonly GrinderPickRow[];
		activeId?: string | null;
		/** The selected bag's name, for the list's heading. */
		bagName?: string | null;
		/** Every grinder record, for the Add box. */
		allGrinders: readonly Grinder[];
		/** Pick a grinder (`null` = no grinder). */
		onSelect: (id: string | null) => void;
		onAdd: (choice: GrinderChoice) => void;
		/** "Edit this bag's grinds" (only with a bag). */
		onEditBag?: () => void;
		onDevices: () => void;
		onClose?: () => void;
	} = $props();

	let adding = $state(false);
	let panelEl = $state<HTMLDivElement | null>(null);

	$effect(() => {
		if (!open) return;
		adding = false;
		const opener = document.activeElement;
		requestAnimationFrame(() => panelEl?.querySelector<HTMLElement>('.hpick-item')?.focus());
		return () => {
			const active = document.activeElement;
			if (active == null || active === document.body || panelEl?.contains(active)) restoreFocus(opener);
		};
	});

	function choose(id: string | null): void {
		onSelect(id);
		onClose?.();
	}

	function onKeydown(e: KeyboardEvent): void {
		if (e.key !== 'Escape') return;
		e.preventDefault();
		e.stopPropagation();
		onClose?.();
	}
</script>

{#if open}
	<div bind:this={panelEl} class="hpick gpick" role="dialog" aria-label="Choose grinder" tabindex="-1" onkeydown={onKeydown}>
		<div class="gpick-head">Your grinders{bagName ? ` · ${bagName}` : ''}</div>
		{#if adding}
			<div class="gpick-add">
				<GrinderTypeOrPick
					grinders={allGrinders}
					autofocus
					onPick={(c) => {
						onAdd(c);
						onClose?.();
					}}
				/>
			</div>
		{/if}
		<div class="hpick-list">
			{#each rows as r (r.id)}
				<button type="button" class="hpick-item" class:is-active={r.id === activeId} onclick={() => choose(r.id)}>
					<span class="hpick-text">
						<span class="hpick-primary gpick-name">
							{r.name}
							{#if r.starred}<StarIcon weight="fill" class="gpick-star" aria-label="this bag's grinder" />{/if}
						</span>
						<span class="hpick-secondary">{r.line}</span>
					</span>
					{#if r.id === activeId}<CheckIcon class="hpick-check" aria-hidden="true" />{/if}
				</button>
			{:else}
				<div class="hpick-empty">No grinders yet: add yours with +.</div>
			{/each}
			<button type="button" class="hpick-item" class:is-active={activeId === null} onclick={() => choose(null)}>
				<span class="hpick-text">
					<span class="hpick-primary">No grinder</span>
					<span class="hpick-secondary">The default scale, 0–20</span>
				</span>
				{#if activeId === null}<CheckIcon class="hpick-check" aria-hidden="true" />{/if}
			</button>
		</div>
		<div class="hpick-foot">
			{#if onEditBag}
				<button type="button" class="hpick-foot-lib" onclick={() => { onEditBag?.(); onClose?.(); }}>Edit this bag's grinds</button>
			{/if}
			<button type="button" class="hpick-foot-lib gpick-devices" onclick={() => { onDevices(); onClose?.(); }}>Devices</button>
			<button
				type="button"
				class="hpick-foot-icon"
				aria-label="Add a grinder"
				title="Add a grinder"
				aria-pressed={adding}
				onclick={() => (adding = !adding)}
			>
				<PlusIcon aria-hidden="true" />
			</button>
		</div>
	</div>
{/if}

<style>
	.gpick {
		overflow: visible;
	}
	.gpick-head {
		padding: 12px 14px 4px;
		font-size: 11px;
		font-weight: 500;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--fg-2);
	}
	.gpick-add {
		padding: 6px 10px 2px;
	}
	.gpick-name {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.gpick :global(.gpick-star) {
		color: var(--fg-accent);
		font-size: 13px;
		flex: none;
	}
	.gpick-devices {
		flex: 0 0 auto;
	}
</style>
