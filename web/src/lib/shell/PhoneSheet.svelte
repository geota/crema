<script lang="ts">
	/**
	 * `PhoneSheet` — the phone modal bottom sheet (Android `ModalBottomSheet`):
	 * a scrim, a grab handle, a head with the title and Close, a scrolling body
	 * and an optional foot that stays on screen.
	 *
	 * It sits above the top bar and the tab bar, below the app's confirm dialog
	 * and toasts, so a sheet action can still prompt. Focus moves in on open,
	 * Tab stays inside, Escape, the scrim, Close, a drag down on the handle and
	 * system Back all dismiss it, and focus returns to whatever opened it.
	 */
	import type { Snippet } from 'svelte';
	import { fade, fly } from 'svelte/transition';
	import XIcon from 'phosphor-svelte/lib/XIcon';
	import { beforeNavigate } from '$app/navigation';
	import { restoreFocus, trapTab } from '#lib/a11y/focus.js';

	let {
		open,
		onClose,
		title,
		subtitle,
		compact = false,
		foot,
		children
	}: {
		open: boolean;
		onClose: () => void;
		/** The sheet's name: the head's title, and the dialog's accessible name. */
		title: string;
		subtitle?: string;
		/** A small muted title, as the overflow sheet's context line. */
		compact?: boolean;
		/** Stays pinned under the scrolling body. */
		foot?: Snippet;
		children: Snippet;
	} = $props();

	const titleId = $props.id();
	/** A drag past this many pixels dismisses the sheet. */
	const DISMISS_DRAG_PX = 96;
	const motion = () =>
		typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
			? 0
			: 220;

	let panelEl = $state<HTMLDivElement>();
	let dragStartY: number | null = null;
	let dragY = $state(0);

	$effect(() => {
		if (!open) return;
		const opener = document.activeElement;
		panelEl?.focus({ preventScroll: true });
		return () => {
			// Never steal focus from where the user went (a route, a dialog).
			const active = document.activeElement;
			if (active == null || active === document.body) restoreFocus(opener);
		};
	});

	// System Back closes the sheet, as it does on Android, not the screen under it.
	beforeNavigate((nav) => {
		if (!open || nav.type !== 'popstate') return;
		nav.cancel();
		onClose();
	});

	function onKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			e.stopPropagation();
			onClose();
			return;
		}
		if (panelEl) trapTab(e, panelEl);
	}

	function onGrabDown(e: PointerEvent): void {
		dragStartY = e.clientY;
		(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
	}
	function onGrabMove(e: PointerEvent): void {
		if (dragStartY !== null) dragY = Math.max(0, e.clientY - dragStartY);
	}
	function onGrabEnd(): void {
		if (dragY > DISMISS_DRAG_PX) onClose();
		dragStartY = null;
		dragY = 0;
	}
</script>

{#if open}
	<!-- A pointer-only dismiss target: Escape and Close serve the keyboard. -->
	<button
		type="button"
		class="psheet-scrim"
		tabindex="-1"
		aria-hidden="true"
		onclick={onClose}
		transition:fade={{ duration: motion() }}
	></button>
	<div
		bind:this={panelEl}
		class="psheet"
		role="dialog"
		aria-modal="true"
		aria-labelledby={titleId}
		tabindex="-1"
		style:transform={dragY > 0 ? `translateY(${dragY}px)` : undefined}
		onkeydown={onKeydown}
		transition:fly={{ y: 320, opacity: 1, duration: motion() }}
	>
		<div
			class="psheet-grab"
			aria-hidden="true"
			onpointerdown={onGrabDown}
			onpointermove={onGrabMove}
			onpointerup={onGrabEnd}
			onpointercancel={onGrabEnd}
		>
			<span></span>
		</div>
		<div class={['psheet-head', { 'is-compact': compact }]}>
			<div class="psheet-titles">
				<h2 class="psheet-title" id={titleId}>{title}</h2>
				{#if subtitle}<p class="psheet-sub">{subtitle}</p>{/if}
			</div>
			<button type="button" class="psheet-close" aria-label="Close" onclick={onClose}>
				<XIcon aria-hidden="true" />
			</button>
		</div>
		<div class="psheet-body">
			{@render children()}
		</div>
		{#if foot}
			<div class="psheet-foot">{@render foot()}</div>
		{/if}
	</div>
{/if}

<style>
	.psheet-scrim {
		position: fixed;
		inset: 0;
		z-index: 1000;
		border: 0;
		padding: 0;
		background: rgba(0, 0, 0, 0.5);
	}
	.psheet {
		position: fixed;
		left: 0;
		right: 0;
		bottom: 0;
		z-index: 1001;
		display: flex;
		flex-direction: column;
		max-height: calc(100dvh - env(safe-area-inset-top, 0px) - 8px);
		padding: 0 env(safe-area-inset-right, 0px) env(safe-area-inset-bottom, 0px)
			env(safe-area-inset-left, 0px);
		border-radius: 28px 28px 0 0;
		background: var(--bg-surface-2);
		outline: none;
		box-shadow: var(--shadow-lg);
	}
	/* Android's handle is 32 × 4; the strip around it is what takes the drag. */
	.psheet-grab {
		flex: none;
		display: flex;
		justify-content: center;
		padding: 10px 0 8px;
		cursor: grab;
		touch-action: none;
	}
	.psheet-grab span {
		width: 32px;
		height: 4px;
		border-radius: 2px;
		background: var(--fg-muted);
	}
	.psheet-head {
		flex: none;
		display: flex;
		align-items: flex-start;
		gap: 8px;
		padding: 0 8px 12px 16px;
	}
	.psheet-titles {
		flex: 1 1 auto;
		min-width: 0;
	}
	.psheet-title {
		margin: 0;
		font-family: var(--font-serif);
		font-size: 21px;
		font-weight: 400;
		line-height: 1.25;
		color: var(--fg-1);
	}
	.psheet-sub {
		margin: 2px 0 0;
		font-family: var(--font-sans);
		font-size: 12px;
		line-height: 1.35;
		color: var(--fg-3);
	}
	.psheet-head.is-compact {
		align-items: center;
		padding-bottom: 0;
	}
	.is-compact .psheet-title {
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 500;
		line-height: 1.4;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.psheet-close {
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 44px;
		height: 44px;
		margin-top: -6px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		color: var(--fg-3);
		font-size: 20px;
		cursor: pointer;
	}
	.is-compact .psheet-close {
		margin-top: 0;
	}
	.psheet-close:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.psheet-body {
		flex: 1 1 auto;
		min-height: 0;
		overflow-y: auto;
		overscroll-behavior: contain;
		padding: 0 16px 16px;
	}
	.psheet-foot {
		flex: none;
		padding: 8px 16px 12px;
	}
</style>
