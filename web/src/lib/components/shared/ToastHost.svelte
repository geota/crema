<script lang="ts">
	import XIcon from 'phosphor-svelte/lib/XIcon';
	import Icon from '#lib/icons/Icon.svelte';
	/**
	 * `ToastHost` — renders the live {@link toast} queue. Mounted once in the
	 * root layout (FD4). Bottom-right stack; each toast auto-dismisses (the
	 * store owns the timer) or can be closed with its × button. Errors announce
	 * as `role="alert"` (assertive); info / success as `role="status"`.
	 *
	 * The stack lifts above any band registered with `keepToastsAbove` (the
	 * Brew foot), so a pinned toast never covers the Coffee button (final
	 * cohort review 07, new finding 1). Its buttons have 44 px hit areas.
	 */
	import { getToasts, dismissToast, type ToastKind } from './toast.svelte';
	import { getToastBottomInset } from './toast-inset.svelte';

	const ICON: Record<ToastKind, string> = {
		info: 'info',
		success: 'check-circle',
		error: 'warning-circle'
	};
</script>

<div class="toast-host" style:--toast-inset="{getToastBottomInset()}px">
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
		</div>
	{/each}
</div>

<style>
	.toast-host {
		position: fixed;
		bottom: calc(16px + var(--toast-inset, 0px));
		right: 16px;
		z-index: 1100;
		display: flex;
		flex-direction: column;
		gap: 8px;
		max-width: min(420px, calc(100vw - 32px));
		pointer-events: none;
	}
	.toast {
		pointer-events: auto;
		display: flex;
		align-items: flex-start;
		gap: 10px;
		padding: 12px 14px;
		border-radius: 10px;
		background: var(--bg-surface);
		color: var(--fg-1);
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		box-shadow:
			0 12px 28px rgba(0, 0, 0, 0.32),
			0 2px 8px rgba(0, 0, 0, 0.2);
		font-size: 13px;
		line-height: 1.4;
		animation: toast-in 160ms ease-out;
	}
	.toast > :global(svg) {
		flex-shrink: 0;
		font-size: 18px;
		margin-top: 1px;
	}
	.toast-msg {
		flex: 1;
		min-width: 0;
		word-break: break-word;
	}
	.toast-error {
		border-color: rgba(var(--danger-rgb), 0.5);
	}
	.toast-error > :global(svg:first-child) {
		color: var(--danger);
	}
	.toast-success {
		border-color: rgba(var(--success-rgb, var(--tint-rgb)), 0.45);
	}
	.toast-success > :global(svg:first-child) {
		color: var(--success, var(--fg-accent));
	}
	.toast-info > :global(svg:first-child) {
		color: var(--fg-accent);
	}
	.toast-action {
		position: relative;
		flex: none;
		align-self: center;
		min-height: 32px;
		border: 1px solid rgba(var(--tint-rgb), 0.25);
		background: transparent;
		color: var(--fg-1);
		font: inherit;
		font-weight: 600;
		font-size: 0.85em;
		padding: 4px 12px;
		border-radius: 999px;
		cursor: pointer;
	}
	/* 44 px tall hit area around the 32 px pill. */
	.toast-action::before {
		content: '';
		position: absolute;
		inset: -6px -4px;
	}
	.toast-action:hover {
		background: rgba(var(--tint-rgb), 0.1);
	}
	/* A full 44 px target (pinned toasts stay until this is tapped); the
	   negative margins keep the toast as compact as the old 22 px button. */
	.toast-close {
		flex-shrink: 0;
		align-self: center;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 44px;
		height: 44px;
		margin: -12px -12px -12px -6px;
		padding: 0;
		border: none;
		background: transparent;
		color: var(--fg-3);
		border-radius: 8px;
		cursor: pointer;
		font-size: 16px;
	}
	.toast-close:hover {
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.08);
	}

	@keyframes toast-in {
		from {
			opacity: 0;
			transform: translateY(8px);
		}
		to {
			opacity: 1;
			transform: translateY(0);
		}
	}
</style>
