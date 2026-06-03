<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	/**
	 * `LibraryCardShell` — shared chrome for the `/beans` library cards.
	 *
	 * Owns the structural pieces that {@link BeanTile} and the inline
	 * `RoasterCard` markup in `/beans` were both reinventing:
	 *
	 *   ┌──────────────────────────────────┐
	 *   │ [avatar]  ┊ head slot         ★  │   row 1: avatar | head + pin
	 *   │   metrics slot (spans both)      │   row 2: full-width metrics
	 *   │   actions slot (spans both)      │   row 3: full-width actions
	 *   └──────────────────────────────────┘
	 *
	 * Why a shell:
	 *  - Both cards land on the same `avatar / head / metrics / actions`
	 *    grid; the bean tile and the roaster card had drifted into two
	 *    near-identical-but-not implementations of it.
	 *  - The roaster card in particular had its action row floating up the
	 *    middle of the card instead of pinned to the bottom — fixing it
	 *    structurally here (the grid template guarantees actions occupy the
	 *    bottom-most row and span the full card width) keeps the two cards
	 *    in lockstep.
	 *  - The top-right pin star is bean-only — `favourite={null}` skips it
	 *    so roaster cards omit the slot entirely.
	 *
	 * Snippets:
	 *   `avatar`  — left-column 88×88 content (gradient mark, badges, …).
	 *   `head`    — top-right header (roaster/name/meta for beans; name/
	 *               website/location for roasters).
	 *   `metrics` — middle full-width row (stats + burn-down for beans;
	 *               bean count / location for roasters; pass nothing if
	 *               the card has no metrics row).
	 *   `actions` — bottom full-width action row (primary button + icon
	 *               trio for both cards).
	 *
	 * Callers own the contents of each slot — including its CSS — so a
	 * bean tile keeps its `.bn-tile-stats` / `.bn-tile-burn` styling and
	 * the roaster card keeps its `.bn-roaster-*` styling. The shell only
	 * owns the outer grid, the pin button, and the action-row gap.
	 */
	import type { Snippet } from 'svelte';

	let {
		avatar,
		head,
		metrics,
		actions,
		favourite = null,
		onFavouriteClick,
		onCardClick,
		ariaLabel,
		cardKind,
		isActive = false,
		isArchived = false,
		isFrozen = false,
		minHeight = 220
	}: {
		avatar: Snippet;
		head: Snippet;
		/** Optional middle row — omit on cards with no stats / progress. */
		metrics?: Snippet;
		actions: Snippet;
		/**
		 * `true` / `false` toggles the pin star fill; `null` omits the
		 * star entirely (roaster cards don't have a favourite concept).
		 */
		favourite?: boolean | null;
		/** Required when `favourite != null`. */
		onFavouriteClick?: (e: MouseEvent) => void;
		onCardClick: () => void;
		ariaLabel: string;
		/** CSS class hook so callers can scope card-specific overrides. */
		cardKind: 'bean' | 'roaster' | string;
		isActive?: boolean;
		isArchived?: boolean;
		isFrozen?: boolean;
		/** Tile min-height — sets the baseline so a row of cards aligns. */
		minHeight?: number;
	} = $props();

	function onKey(e: KeyboardEvent): void {
		if (e.key === 'Enter' || e.key === ' ') {
			e.preventDefault();
			onCardClick();
		}
	}

	function onPinClick(e: MouseEvent): void {
		e.stopPropagation();
		onFavouriteClick?.(e);
	}
</script>

<div
	class="lcs"
	class:is-active={isActive}
	class:is-archived={isArchived}
	class:is-frozen={isFrozen}
	data-kind={cardKind}
	style="--lcs-min-h: {minHeight}px"
	role="button"
	tabindex="0"
	onclick={onCardClick}
	onkeydown={onKey}
	aria-label={ariaLabel}
>
	{#if favourite !== null}
		<button
			type="button"
			class="lcs-pin"
			class:lcs-pin-off={!favourite}
			onclick={onPinClick}
			aria-label={favourite ? 'Unpin' : 'Pin'}
			title={favourite ? 'Pinned' : 'Pin'}
		>
			<Icon
				cls={favourite ? 'ph-fill ph-star' : 'ph ph-star'}
				aria-hidden="true"
			 />
		</button>
	{/if}

	<div class="lcs-avatar">{@render avatar()}</div>
	<div class="lcs-head" class:lcs-head-no-pin={favourite === null}>
		{@render head()}
	</div>
	{#if metrics}
		<div class="lcs-metrics">{@render metrics()}</div>
	{/if}
	<div class="lcs-actions">{@render actions()}</div>
</div>

<style>
	/* Outer grid — three named rows, two columns. The bottom two rows span
	   both columns so metrics + actions get the full card width regardless
	   of avatar size. `auto 1fr auto` lets the metrics row absorb any extra
	   vertical space so the action row stays pinned to the bottom. */
	.lcs {
		position: relative;
		display: grid;
		grid-template-columns: 88px 1fr;
		grid-template-areas:
			'avatar head'
			'metrics metrics'
			'actions actions';
		grid-template-rows: auto 1fr auto;
		column-gap: 16px;
		row-gap: 12px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-lg, 14px);
		padding: 14px 16px 14px 14px;
		min-height: var(--lcs-min-h, 220px);
		text-align: left;
		color: var(--fg-1);
		font: inherit;
		cursor: pointer;
		transition:
			background var(--dur-1) var(--ease),
			border-color var(--dur-1) var(--ease),
			transform var(--dur-1) var(--ease);
		outline: 0;
	}
	.lcs:hover {
		background: rgba(var(--tint-rgb), 0.06);
		border-color: rgba(var(--tint-rgb), 0.16);
	}
	.lcs:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
	.lcs.is-active {
		border-color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.07);
	}
	.lcs.is-archived {
		opacity: 0.55;
	}
	.lcs.is-frozen {
		background: linear-gradient(
			180deg,
			rgba(125, 160, 205, 0.04),
			rgba(var(--tint-rgb), 0.04) 60%
		);
	}

	/* Pin star — top-right (matches `/profiles` `.pp-card-pin`). Only
	   rendered when `favourite != null`, so roaster cards skip it. */
	.lcs-pin {
		position: absolute;
		top: 8px;
		right: 8px;
		z-index: 2;
		background: transparent;
		border: 0;
		color: var(--copper-400);
		font-size: 18px;
		cursor: pointer;
		padding: 4px;
		border-radius: 6px;
		display: inline-flex;
		transition: all var(--dur-1) var(--ease);
	}
	.lcs-pin:hover {
		background: rgba(var(--tint-rgb), 0.05);
	}
	.lcs-pin.lcs-pin-off {
		color: rgba(var(--tint-rgb), 0.25);
	}
	.lcs-pin.lcs-pin-off:hover {
		color: rgba(var(--tint-rgb), 0.6);
	}

	/* Slot wrappers — purely structural; the snippet contents own their
	   own internal layout + styling. */
	.lcs-avatar {
		grid-area: avatar;
		min-width: 0;
	}
	.lcs-head {
		grid-area: head;
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
		min-height: 0;
		/* Clears the absolutely-positioned star which only overlaps the top
		   row. Cards without a star (favourite === null) drop this padding
		   so the header text uses the full width. */
		padding-right: 28px;
	}
	.lcs-head.lcs-head-no-pin {
		padding-right: 0;
	}
	.lcs-metrics {
		grid-area: metrics;
		display: flex;
		flex-direction: column;
		gap: 8px;
		min-width: 0;
		align-self: end;
	}
	.lcs-actions {
		grid-area: actions;
		display: flex;
		align-items: center;
		gap: 6px;
	}

	/* Action-row controls — `:global` so callers can drop the same class
	   names inside the `actions` snippet without redeclaring the rules
	   per card. Mirrors `/profiles` `ProfileCard`'s `.pp-card-actions`:
	   one primary button that fills, plus a trio of 30px icon squares. */
	.lcs-actions :global(.lcs-action) {
		flex: 1 1 auto;
		min-width: 0;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 6px;
		padding: 7px 12px;
		border-radius: var(--radius-sm);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		background: rgba(var(--tint-rgb), 0.03);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 500;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.lcs-actions :global(.lcs-action > span) {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.lcs-actions :global(.lcs-action:hover) {
		background: rgba(var(--tint-rgb), 0.07);
		border-color: rgba(var(--tint-rgb), 0.18);
	}
	.lcs-actions :global(.lcs-action-primary.is-on) {
		background: rgba(193, 116, 75, 0.12);
		border-color: var(--copper-500);
		color: var(--copper-400);
		cursor: default;
	}
	.lcs-actions :global(.lcs-action-primary.is-on i) {
		color: var(--copper-400);
	}
	.lcs-actions :global(.lcs-action-icon) {
		width: 30px;
		height: 30px;
		flex: 0 0 30px;
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		background: rgba(var(--tint-rgb), 0.03);
		border-radius: var(--radius-sm);
		color: rgba(var(--tint-rgb), 0.6);
		cursor: pointer;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		font-size: 13px;
		transition: all var(--dur-1) var(--ease);
		padding: 0;
	}
	.lcs-actions :global(.lcs-action-icon:hover:not(:disabled)) {
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.07);
	}
	.lcs-actions :global(.lcs-action-icon:disabled) {
		opacity: 0.35;
		cursor: not-allowed;
	}
	.lcs-actions :global(.lcs-action-icon-danger) {
		color: var(--danger);
	}
	.lcs-actions :global(.lcs-action-icon-danger:hover:not(:disabled)) {
		color: var(--danger);
		background: rgba(var(--danger-rgb), 0.12);
	}
</style>
