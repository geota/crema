<script lang="ts">
	/**
	 * `HeaderBlock` — the Brew header's `.bh-block` anatomy (eyebrow row with
	 * an optional trailing chip, serif title, then meta / spec / tags line
	 * tiers), extracted so the History shot detail renders the SAME component
	 * over a shot's frozen snapshot instead of a hand-rolled lookalike
	 * (issue #16 round 4 — mirrors Android's shared `CremaHeaderBlock`).
	 *
	 * Brew feeds it live library data and anchors its `HeaderPicker` popups
	 * via the `picker` snippet; History feeds it the snapshot, passes the
	 * pull `stamp` as the profile block's eyebrow trailing (the one
	 * deliberate difference from Brew), and marks the profile block
	 * non-interactive (a shot's profile is a historical fact).
	 *
	 * All CSS lives in `web-kit.css` (`.bh-*`) — this component is markup
	 * plus interaction wiring only.
	 */
	import type { Snippet } from 'svelte';

	let {
		cls = '',
		variant = 'profile',
		eyebrow,
		stamp = null,
		freshLabel = null,
		freshColor = 'rgba(var(--tint-rgb), 0.4)',
		title,
		meta = '',
		spec = '',
		tags = '',
		interactive = true,
		open = false,
		tapLabel = '',
		onTap,
		titleExtra,
		footer,
		picker,
		el = $bindable(null)
	}: {
		/** Extra class(es) on the block root (`bh-block-profile` / `bh-block-bean`). */
		cls?: string;
		/** Bean blocks use the bean line tiers (`bh-bean-title` / `-meta` / `-prep`). */
		variant?: 'profile' | 'bean';
		eyebrow: string;
		/** Plain trailing text on the eyebrow row — History's pull timestamp. */
		stamp?: string | null;
		/** "4d off roast" freshness chip (wins over `stamp` when both given). */
		freshLabel?: string | null;
		freshColor?: string;
		title: string;
		/** Line 1 under the title (`.bh-meta`). */
		meta?: string;
		/** Line 2 under the title (`.bh-spec` / `.bh-bean-prep`). */
		spec?: string;
		/** Line 3 — tags (faint). */
		tags?: string;
		/** Non-interactive blocks render statically (no hover, no button role). */
		interactive?: boolean;
		/** Open-state tint while an anchored picker is up. */
		open?: boolean;
		/** Accessible label for the tap target when interactive. */
		tapLabel?: string;
		onTap?: () => void;
		/** Extra inline content after the title (Brew's sync chip). */
		titleExtra?: Snippet;
		/** Extra lines after the tiers (Brew's loaded subline). */
		footer?: Snippet;
		/** Anchored picker content, rendered inside the block root. */
		picker?: Snippet;
		/** The block root element — Brew's outside-click close reads it. */
		el?: HTMLElement | null;
	} = $props();

	function tapKey(e: KeyboardEvent): void {
		if (e.key === 'Enter' || e.key === ' ') {
			e.preventDefault();
			onTap?.();
		}
	}
</script>

<div class="bh-block {cls}" bind:this={el}>
	{#if interactive}
		<div
			class="bh-block-tap"
			class:is-open={open}
			role="button"
			tabindex="0"
			aria-haspopup="dialog"
			aria-expanded={open}
			aria-label={tapLabel || undefined}
			onclick={() => onTap?.()}
			onkeydown={tapKey}
		>
			{@render inner()}
		</div>
	{:else}
		<div class="bh-block-tap is-static">
			{@render inner()}
		</div>
	{/if}
	{#if picker}{@render picker()}{/if}
</div>

{#snippet inner()}
	<div class="bh-bean-eyebrow">
		<span class="t-eyebrow">{eyebrow}</span>
		{#if freshLabel}
			<span class="bh-fresh" style="color:{freshColor}">
				<span class="bh-fresh-dot" style="background:{freshColor}"></span>{freshLabel}
			</span>
		{:else if stamp}
			<span class="bh-stamp">{stamp}</span>
		{/if}
	</div>
	<div class="bh-title-row">
		<span class="bh-title" class:bh-bean-title={variant === 'bean'}>{title}</span>
		{#if titleExtra}{@render titleExtra()}{/if}
	</div>
	{#if meta}<span class="bh-meta" class:bh-bean-meta={variant === 'bean'}>{meta}</span>{/if}
	{#if spec}
		<span
			class="bh-meta"
			class:bh-spec={variant === 'profile'}
			class:bh-bean-prep={variant === 'bean'}>{spec}</span
		>
	{/if}
	{#if tags}<span class="bh-tags">{tags}</span>{/if}
	{#if footer}{@render footer()}{/if}
{/snippet}
