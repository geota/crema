<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	import CaretDownIcon from 'phosphor-svelte/lib/CaretDownIcon';
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	/**
	 * `SortPill` — a two-half pill control for picking a sort field + direction.
	 *
	 *   ┌───────────┬──────────────────────┐
	 *   │  ↑ / ↓    │  Roast date  ▾       │
	 *   └───────────┴──────────────────────┘
	 *
	 * The **left half** toggles `direction` (`asc` / `desc`), swapping the
	 * `ph-arrow-up` / `ph-arrow-down` icon. The **right half** is a dropdown
	 * trigger that lists the sort fields. Each field collapses both directions
	 * into a single option — direction lives on the left half.
	 *
	 * The component is fully self-contained: it owns the dropdown open/closed
	 * state and dismisses on outside-click via a window listener. Callers wire
	 * `value` + `onchange` in the usual controlled-component shape.
	 *
	 * Used by:
	 *   - `/beans` Bags + Roasters tab
	 *   - `/profiles` library
	 *   - anywhere a sort-field+direction pair is exposed
	 */
	interface SortPillOption {
		field: string;
		label: string;
		/** Optional Phosphor icon name (without the `ph-` prefix). */
		icon?: string;
	}

	interface SortPillValue {
		field: string;
		direction: 'asc' | 'desc';
	}

	let {
		value,
		options,
		onchange,
		ascLabel = 'Ascending',
		descLabel = 'Descending'
	}: {
		value: SortPillValue;
		options: SortPillOption[];
		onchange: (next: SortPillValue) => void;
		ascLabel?: string;
		descLabel?: string;
	} = $props();

	let open = $state(false);
	let rootEl = $state<HTMLDivElement | null>(null);

	const currentOption = $derived(
		options.find((o) => o.field === value.field) ?? options[0]
	);

	function flipDirection(): void {
		onchange({
			field: value.field,
			direction: value.direction === 'asc' ? 'desc' : 'asc'
		});
	}

	function pickField(field: string): void {
		open = false;
		if (field === value.field) return;
		onchange({ field, direction: value.direction });
	}

	// Outside-click dismissal — register the window listener ONLY while the
	// menu is open, and only AFTER the click that opened it has fully bubbled
	// through. The previous always-on `<svelte:window onclick>` racing with a
	// stopPropagation'd toggle was brittle: any layout that swallowed the
	// stopped event (or any extra wrapper that bubbled before window) could
	// see `open=true`, then immediately flip it back to `false` on the same
	// gesture. Using `mousedown` (capture phase) attached one microtask after
	// the opening click guarantees the opening gesture can never also close
	// the menu.
	$effect(() => {
		if (!open) return;
		let attached = false;
		const handler = (e: MouseEvent) => {
			if (!rootEl) return;
			if (!rootEl.contains(e.target as Node)) open = false;
		};
		queueMicrotask(() => {
			window.addEventListener('mousedown', handler, true);
			attached = true;
		});
		return () => {
			if (attached) window.removeEventListener('mousedown', handler, true);
		};
	});
</script>

<div class="sortpill" bind:this={rootEl}>
	<button
		type="button"
		class="sortpill-dir"
		onclick={flipDirection}
		title={value.direction === 'asc' ? descLabel : ascLabel}
		aria-label={value.direction === 'asc' ? ascLabel : descLabel}
	>
		<Icon
			cls={value.direction === 'asc' ? 'ph ph-arrow-up' : 'ph ph-arrow-down'}
			aria-hidden="true"
		 />
	</button>
	<div class="sortpill-sep" aria-hidden="true"></div>
	<button
		type="button"
		class="sortpill-field"
		onclick={(e) => {
			e.stopPropagation();
			open = !open;
		}}
		aria-haspopup="menu"
		aria-expanded={open}
	>
		{#if currentOption?.icon}
			<Icon cls={`ph ph-${currentOption.icon}`} aria-hidden="true" />
		{/if}
		<span class="sortpill-label">{currentOption?.label ?? 'Sort'}</span>
		<CaretDownIcon class="sortpill-chev" aria-hidden="true" />
	</button>
	{#if open}
		<div class="sortpill-menu" role="menu">
			{#each options as o (o.field)}
				<button
					type="button"
					class="sortpill-menu-item"
					class:is-active={o.field === value.field}
					role="menuitemradio"
					aria-checked={o.field === value.field}
					onclick={() => pickField(o.field)}
				>
					{#if o.icon}
						<Icon cls={`ph ph-${o.icon}`} aria-hidden="true" />
					{/if}
					<span>{o.label}</span>
					{#if o.field === value.field}
						<CheckIcon aria-hidden="true" />
					{/if}
				</button>
			{/each}
		</div>
	{/if}
</div>

<style>
	.sortpill {
		position: relative;
		display: inline-flex;
		align-items: stretch;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-pill);
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-1);
		/* Note: NO `overflow: hidden` here. The dropdown menu is positioned
		   absolute relative to this pill and `overflow: hidden` would clip
		   it invisibly — which is exactly what hid the dropdown previously.
		   The pill halves themselves stay inside the rounded border via the
		   button's own hover background being inside the border, so the
		   visual seam still reads clean without parent clipping. */
	}
	.sortpill-dir,
	.sortpill-field {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: transparent;
		border: 0;
		color: inherit;
		font: inherit;
		cursor: pointer;
		padding: 7px 12px;
		transition:
			background var(--dur-1) var(--ease),
			color var(--dur-1) var(--ease);
	}
	/* Half-rounding the two pill halves replaces the removed
	   `overflow: hidden` on `.sortpill` so hover backgrounds still hug the
	   pill's rounded outline. */
	.sortpill-dir {
		padding: 7px 10px;
		color: rgba(var(--tint-rgb), 0.7);
		border-top-left-radius: var(--radius-pill);
		border-bottom-left-radius: var(--radius-pill);
	}
	.sortpill-field {
		border-top-right-radius: var(--radius-pill);
		border-bottom-right-radius: var(--radius-pill);
	}
	.sortpill-dir :global(svg) {
		font-size: 13px;
	}
	.sortpill-dir:hover {
		background: rgba(var(--tint-rgb), 0.06);
		color: var(--fg-1);
	}
	.sortpill-sep {
		width: 1px;
		background: rgba(var(--tint-rgb), 0.1);
	}
	.sortpill-field:hover {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.sortpill-field :global(svg) {
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.sortpill-label {
		font-weight: 500;
		color: var(--fg-1);
	}
	:global(.sortpill-chev) {
		font-size: 11px !important;
		opacity: 0.6;
	}
	.sortpill-menu {
		position: absolute;
		top: calc(100% + 6px);
		right: 0;
		z-index: 60;
		min-width: 220px;
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: var(--radius-md);
		padding: 6px;
		box-shadow: var(--shadow-lg);
		display: flex;
		flex-direction: column;
		gap: 1px;
	}
	.sortpill-menu-item {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		background: transparent;
		border: 0;
		color: var(--fg-1);
		font: inherit;
		font-size: 12px;
		cursor: pointer;
		text-align: left;
		padding: 7px 9px;
		border-radius: var(--radius-sm);
		transition: background var(--dur-1) var(--ease);
	}
	.sortpill-menu-item:hover {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.sortpill-menu-item.is-active {
		background: rgba(var(--copper-rgb), 0.1);
		color: var(--copper-300);
	}
	.sortpill-menu-item :global(svg) {
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.sortpill-menu-item.is-active :global(svg) {
		color: var(--copper-400);
	}
	.sortpill-menu-item span {
		flex: 1 1 auto;
	}
</style>
