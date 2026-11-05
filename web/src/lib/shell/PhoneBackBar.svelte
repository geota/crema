<script lang="ts">
	/**
	 * `PhoneBackBar` — a pushed phone screen's bar: ← plus the screen's name
	 * (Android `CremaPhoneBackBar`). Sticks to the top of the page's scroller.
	 *
	 * The title is the screen's `<h1>` and takes focus on mount, so a screen
	 * reader announces where you landed. Only rendered at phone width.
	 */
	import CaretLeftIcon from 'phosphor-svelte/lib/CaretLeftIcon';
	import { focusOnMount } from './phone-nav';

	let {
		title,
		backLabel,
		onBack
	}: { title: string; backLabel: string; onBack: () => void } = $props();
</script>

<div class="pbar">
	<button type="button" class="pbar-back" onclick={onBack} aria-label={backLabel}>
		<CaretLeftIcon aria-hidden="true" />
	</button>
	<h1 class="pbar-title" tabindex="-1" {@attach focusOnMount}>{title}</h1>
</div>

<style>
	.pbar {
		position: sticky;
		top: 0;
		z-index: 5;
		display: flex;
		align-items: center;
		gap: 4px;
		min-height: 52px;
		margin: 0 calc(-1 * var(--page-pad-x));
		padding: 4px var(--page-pad-x) 4px 4px;
		background: var(--bg-page);
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.06);
	}
	.pbar-back {
		display: inline-flex;
		flex: none;
		align-items: center;
		justify-content: center;
		width: 44px;
		height: 44px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		color: var(--fg-1);
		font-size: 20px;
		cursor: pointer;
	}
	.pbar-back:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.pbar-title {
		margin: 0;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-family: var(--font-serif);
		font-size: 20px;
		font-weight: 400;
		letter-spacing: -0.01em;
		color: var(--fg-1);
	}
	/* Focused on open for screen readers; a heading shows no ring. */
	.pbar-title:focus,
	.pbar-title:focus-visible {
		outline: none;
		box-shadow: none;
	}
</style>
