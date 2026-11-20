<script lang="ts">
	import XIcon from 'phosphor-svelte/lib/XIcon';
	import Icon from '#lib/icons/Icon.svelte';
	/**
	 * `ToastHost` — renders the live {@link toast} queue as Crema's temporary
	 * messages (`.scratch/brew-messages/PRD.md` §2). Mounted once in the root
	 * layout (FD4). Each message auto-dismisses (the store owns the timer) or
	 * closes with its ✕; errors announce as `role="alert"` (assertive), info /
	 * success as `role="status"`.
	 *
	 * One style everywhere: a raised surface with a hairline border, an icon
	 * by kind, the message, at most one copper text action, ✕, and a 2 px
	 * copper countdown line along the bottom showing the time left (none for
	 * a pinned message). Placement is the same rule on every page: just above
	 * the brew controls, overlaying content, never pushing layout.
	 *
	 *   - Wide: a slim (about 32 px) strip from the side rail's edge to the
	 *     screen edge, sitting on top of Brew's bottom bar (registered with
	 *     `keepToastsAbove`); on other pages, at the bottom of the content area.
	 *   - Phone: a full-width card (16 px gutters, about 56 px tall, 40 px tap
	 *     targets) just above Steam / Water / Flush on Brew; on other pages,
	 *     just above the bottom navigation.
	 */
	import { getToasts, dismissToast, type ToastKind } from './toast.svelte';
	import { getToastBottomInset } from './toast-inset.svelte';

	/** The phone tab bar is on screen, so the stack sits above it. */
	let { overTabBar = false }: { overTabBar?: boolean } = $props();

	const ICON: Record<ToastKind, string> = {
		info: 'info',
		success: 'check-circle',
		error: 'warning-circle'
	};
</script>

<div
	class={['toast-host', { 'over-tabs': overTabBar }]}
	style:--toast-inset="{getToastBottomInset()}px"
>
	{#each getToasts() as t (t.id)}
		<div class={['toast', `toast-${t.kind}`]} role={t.kind === 'error' ? 'alert' : 'status'}>
			<Icon name={ICON[t.kind]} aria-hidden="true" />
			<span class="toast-msg">{t.message}</span>
			{#if t.action}
				<button
					type="button"
					class="toast-action"
					onclick={() => {
						t.action?.run();
						dismissToast(t.id);
					}}
				>
					{t.action.label}
				</button>
			{/if}
			<button
				type="button"
				class="toast-close"
				onclick={() => dismissToast(t.id)}
				aria-label="Dismiss notification"
			>
				<XIcon aria-hidden="true" />
			</button>
			{#if t.durationMs > 0}
				<!-- The time left. Reduced motion steps it once a second instead
				     of sliding (see the media query below). -->
				<span
					class="toast-countdown"
					style:--toast-ms="{t.durationMs}ms"
					style:--toast-steps={Math.max(1, Math.round(t.durationMs / 1000))}
					aria-hidden="true"
				></span>
			{/if}
		</div>
	{/each}
</div>

<style>
	/* Wide: the strip runs from the rail's edge to the screen edge and sits on
	   the top edge of the registered band (Brew's bottom bar), or the bottom
	   of the content area where there is none. */
	.toast-host {
		position: fixed;
		left: var(--sidebar-w, 0px);
		right: 0;
		bottom: max(var(--toast-inset, 0px), env(safe-area-inset-bottom, 0px));
		z-index: 1100;
		display: flex;
		flex-direction: column;
		pointer-events: none;
	}
	.toast {
		position: relative;
		pointer-events: auto;
		display: flex;
		align-items: center;
		gap: 10px;
		min-height: 32px;
		padding: 0 max(4px, env(safe-area-inset-right, 0px)) 0 28px;
		background: var(--bg-surface);
		color: var(--fg-1);
		border-top: 1px solid rgba(var(--tint-rgb), 0.14);
		box-shadow: 0 -6px 18px rgba(0, 0, 0, 0.16);
		font-size: 13px;
		line-height: 1.35;
		animation: toast-in 160ms ease-out;
	}
	.toast > :global(svg:first-child) {
		flex-shrink: 0;
		font-size: 16px;
	}
	.toast-msg {
		flex: 1;
		min-width: 0;
		padding: 6px 0;
		overflow-wrap: anywhere;
	}
	.toast-error {
		border-color: rgba(var(--danger-rgb), 0.5);
	}
	.toast-error > :global(svg:first-child) {
		color: var(--danger);
	}
	.toast-success > :global(svg:first-child) {
		color: var(--success, var(--fg-accent));
	}
	.toast-info > :global(svg:first-child) {
		color: var(--fg-accent);
	}
	/* The one action: copper text, no chrome. */
	.toast-action {
		flex: none;
		min-height: 32px;
		padding: 0 12px;
		border: 0;
		border-radius: 6px;
		background: transparent;
		color: var(--fg-accent);
		font: inherit;
		font-weight: 600;
		cursor: pointer;
	}
	.toast-action:hover {
		background: rgba(var(--copper-rgb), 0.12);
	}
	.toast-close {
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 32px;
		height: 32px;
		padding: 0;
		border: none;
		border-radius: 6px;
		background: transparent;
		color: var(--fg-3);
		font-size: 15px;
		cursor: pointer;
	}
	.toast-close:hover {
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.08);
	}
	.toast-countdown {
		position: absolute;
		left: 0;
		right: 0;
		bottom: 0;
		height: 2px;
		background: var(--copper-500);
		transform-origin: left center;
		animation: toast-countdown var(--toast-ms) linear forwards;
	}

	/* Phone width: a full-width card with 16 px gutters, clear of the home
	   indicator and the tab bar (or Brew's foot, whichever is higher). */
	@media (max-width: 600px) {
		.toast-host {
			left: 16px;
			right: 16px;
			bottom: calc(8px + max(var(--toast-inset, 0px), env(safe-area-inset-bottom, 0px)));
			gap: 8px;
		}
		.toast-host.over-tabs {
			bottom: calc(8px + max(var(--toast-inset, 0px), var(--ptab-clear)));
		}
		.toast {
			min-height: 56px;
			padding: 8px 8px 8px 14px;
			border: 1px solid rgba(var(--tint-rgb), 0.14);
			border-radius: 12px;
			overflow: hidden;
			box-shadow:
				0 12px 28px rgba(0, 0, 0, 0.32),
				0 2px 8px rgba(0, 0, 0, 0.2);
		}
		.toast-error {
			border-color: rgba(var(--danger-rgb), 0.5);
		}
		.toast > :global(svg:first-child) {
			font-size: 18px;
		}
		.toast-msg {
			padding: 0;
		}
		.toast-action {
			min-height: 40px;
		}
		.toast-close {
			width: 40px;
			height: 40px;
			border-radius: 8px;
			font-size: 16px;
		}
	}

	/* The app-wide reduced-motion rule collapses every animation to an
	   instant; the countdown keeps its real length but moves in one-second
	   steps instead of sliding. */
	@media (prefers-reduced-motion: reduce) {
		.toast-countdown {
			animation-duration: var(--toast-ms) !important;
			animation-timing-function: steps(var(--toast-steps), end) !important;
		}
	}

	@keyframes toast-in {
		from {
			opacity: 0;
			transform: translateY(6px);
		}
		to {
			opacity: 1;
			transform: translateY(0);
		}
	}
	@keyframes toast-countdown {
		from {
			transform: scaleX(1);
		}
		to {
			transform: scaleX(0);
		}
	}
</style>
