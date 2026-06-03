<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	import CaretDownIcon from 'phosphor-svelte/lib/CaretDownIcon';
	/**
	 * `SplitButton` — primary action + caret-revealed menu of variants.
	 *
	 * The /beans library uses this for Export (Crema vs Beanconqueror);
	 * /history uses it for bulk Export and per-shot Download (community
	 * v2 vs replay capture). Same shape, same geometry, one styling
	 * source so they don't drift.
	 *
	 * Geometry mirrors `.bn-btn` from `/beans`: pill radius, ghost
	 * border, the caret tucked seamlessly against the main button via
	 * a 1px negative margin. The wrapper stops bubble-up clicks from
	 * the host page's outside-click handlers; the menu's own outside
	 * dismissal lives here via `svelte:window`.
	 */
	import type { Snippet } from 'svelte';

	export interface SplitButtonItem {
		/** Phosphor icon class — e.g. `ph-duotone ph-file-text`. */
		icon: string;
		title: string;
		sub: string;
		onclick: () => void;
		/** Disable this menu item — dims it and swallows clicks. */
		disabled?: boolean;
	}

	interface Props {
		/** Phosphor icon class on the primary button (left half). */
		icon: string;
		/** Visible label on the primary button. Omit for an icon-only button. */
		label?: string;
		/** Accessible name for the primary button when it's icon-only. */
		primaryAriaLabel?: string;
		/** Native `title` tooltip on the primary button. */
		title?: string;
		/** `md` (default) for toolbar buttons, `sm` for compact in-card controls. */
		size?: 'sm' | 'md';
		/** Fired when the primary half is clicked. */
		onPrimary: () => void;
		/** Items shown in the dropdown — caret half toggles the menu. */
		items: SplitButtonItem[];
		/** Heading above the menu items — e.g. `"Export as"`. */
		menuHead: string;
		/** Aria-label for the caret button (disambiguates from primary). */
		caretAriaLabel?: string;
		/** Disable both buttons. */
		disabled?: boolean;
		/**
		 * Variant: `ghost` for secondary actions, `primary` for the page's CTA,
		 * `danger` for destructive actions (red text + icon).
		 */
		variant?: 'ghost' | 'primary' | 'danger';
		/** Slot override for the primary button content. Defaults to icon + label. */
		primarySlot?: Snippet;
	}

	let {
		icon,
		label,
		primaryAriaLabel,
		title,
		size = 'md',
		onPrimary,
		items,
		menuHead,
		caretAriaLabel = 'Choose format',
		disabled = false,
		variant = 'ghost',
		primarySlot
	}: Props = $props();

	let open = $state(false);

	function closeOnDocClick(): void {
		open = false;
	}

	function pickItem(it: SplitButtonItem): void {
		if (it.disabled) return;
		open = false;
		it.onclick();
	}
</script>

<svelte:window onclick={closeOnDocClick} />

<!-- svelte-ignore a11y_click_events_have_key_events -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
	class="sb"
	class:is-danger={variant === 'danger'}
	class:sb-sm={size === 'sm'}
	onclick={(e) => e.stopPropagation()}
>
	<button
		class="sb-btn sb-{variant} sb-main"
		{disabled}
		onclick={onPrimary}
		{title}
		aria-label={primaryAriaLabel}
	>
		{#if primarySlot}{@render primarySlot()}{:else}
			<Icon cls={icon} aria-hidden="true" />{#if label}<span>{label}</span>{/if}
		{/if}
	</button>
	<button
		class="sb-btn sb-{variant} sb-caret-btn"
		{disabled}
		onclick={(e) => {
			e.stopPropagation();
			open = !open;
		}}
		aria-haspopup="menu"
		aria-expanded={open}
		aria-label={caretAriaLabel}
	>
		<CaretDownIcon class="sb-caret" aria-hidden="true" />
	</button>
	{#if open}
		<div class="sb-menu" role="menu">
			<div class="sb-menu-head">{menuHead}</div>
			{#each items as it (it.title)}
				<button
					class="sb-menu-item"
					role="menuitem"
					disabled={it.disabled}
					onclick={() => pickItem(it)}
				>
					<Icon cls={it.icon} aria-hidden="true" />
					<div class="sb-menu-text">
						<div class="sb-menu-title">{it.title}</div>
						<div class="sb-menu-sub">{it.sub}</div>
					</div>
				</button>
			{/each}
		</div>
	{/if}
</div>

<style>
	.sb {
		position: relative;
		display: inline-flex;
	}
	.sb-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		/* Matches `.st-btn` (settings-page.css) so the split-button
		   reads as the same size as plain `.st-btn` neighbours
		   (Import, Compare, Load on Brew, …). */
		padding: 7px 14px;
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 500;
		cursor: pointer;
		border: 1px solid transparent;
		transition: all var(--dur-1) var(--ease);
		white-space: nowrap;
	}
	.sb-btn :global(svg) {
		font-size: 13px;
	}
	.sb-btn > span {
		display: inline-flex;
	}
	/* Compact size — for in-card action rows (icon-only trash, tiny caret). */
	.sb-sm .sb-btn {
		padding: 6px 8px;
		font-size: 11px;
		gap: 4px;
	}
	.sb-sm .sb-btn :global(svg) {
		font-size: 13px;
	}
	.sb-sm .sb-caret-btn {
		padding-left: 6px;
		padding-right: 7px;
	}
	/* Squared corners (not pill) so the compact split sits flush with the
	   `--radius-sm` icon buttons on the library cards. */
	.sb-sm .sb-main {
		border-radius: var(--radius-sm) 0 0 var(--radius-sm);
	}
	.sb-sm .sb-caret-btn {
		border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
	}
	.sb-sm .sb-menu {
		min-width: 248px;
	}
	.sb-btn:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
	.sb-ghost {
		background: rgba(var(--tint-rgb), 0.04);
		border-color: rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
	}
	.sb-ghost:hover:not(:disabled) {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.sb-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.sb-primary:hover:not(:disabled) {
		background: var(--copper-600);
	}
	/* Destructive variant — red text + icon over a faint red wash. */
	.sb-danger {
		background: rgba(var(--danger-rgb), 0.06);
		border-color: rgba(var(--danger-rgb), 0.28);
		color: var(--danger);
	}
	.sb-danger:hover:not(:disabled) {
		background: rgba(var(--danger-rgb), 0.12);
	}
	/* Tint the menu's leading icons red too when the trigger is destructive. */
	.is-danger .sb-menu-item :global(svg) {
		color: var(--danger);
	}
	.sb-main {
		border-radius: var(--radius-pill) 0 0 var(--radius-pill);
	}
	.sb-caret-btn {
		border-radius: 0 var(--radius-pill) var(--radius-pill) 0;
		padding-left: 8px;
		padding-right: 10px;
		margin-left: -1px;
	}
	.sb-caret {
		margin-left: 4px;
		font-size: 11px;
		opacity: 0.75;
	}
	.sb-menu {
		position: absolute;
		top: calc(100% + 6px);
		right: 0;
		z-index: 60;
		min-width: 320px;
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: var(--radius-md);
		padding: 8px;
		box-shadow: var(--shadow-lg);
	}
	.sb-menu-head {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
		padding: 6px 8px 4px;
	}
	.sb-menu-item {
		display: flex;
		gap: 10px;
		align-items: flex-start;
		width: 100%;
		text-align: left;
		background: transparent;
		border: 0;
		padding: 8px;
		border-radius: var(--radius-sm);
		cursor: pointer;
		color: var(--fg-1);
		font: inherit;
	}
	.sb-menu-item:hover:not(:disabled) {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.sb-menu-item:disabled {
		opacity: 0.45;
		cursor: not-allowed;
	}
	.sb-menu-item :global(svg) {
		font-size: 18px;
		color: var(--copper-400);
		margin-top: 2px;
	}
	.sb-menu-text {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.sb-menu-title {
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.sb-menu-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		line-height: 1.4;
	}
</style>
