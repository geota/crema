<script lang="ts">
	import WarningIcon from 'phosphor-svelte/lib/WarningIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
	/**
	 * `BrewStatusCard` — a condition that lasts on Brew: a DE1 firmware fault,
	 * a shot-start blocker ("Can't start shot"), maintenance due, an empty bag.
	 * Brew renders one at a time, at rest only: at the foot of the wide left
	 * column (in the Last shot / Dial-in slot) and under the phone's machine
	 * tiles. See `.scratch/brew-messages/PRD.md` §1.
	 *
	 * Anatomy: an icon in a tinted circle, the bold title, a mono detail line,
	 * an optional link line (the whole text block is then one button, the
	 * existing tap-through), an optional labelled action, and a ✕ where the
	 * condition can be dismissed. Machine faults pass no `onDismiss`: the
	 * firmware clears the substate itself and the card goes with it.
	 *
	 * `'error'` uses the danger palette and announces as `role="alert"`;
	 * `'warning'` (advisory: maintenance, empty bag) uses the warning palette
	 * and `role="status"`.
	 */

	let {
		text,
		title,
		variant = 'error',
		onClick,
		linkLabel,
		action,
		onDismiss
	}: {
		/** The mono detail line ("Filter due", the firmware fault text). */
		text: string;
		/** The bold title ("Machine error", "Can't start shot", …). */
		title: string;
		variant?: 'error' | 'warning';
		/** Tap-through: the text block becomes one button that runs this. */
		onClick?: () => void;
		/** Link line under the detail ("Water & maintenance →"); needs `onClick`. */
		linkLabel?: string;
		/** Optional labelled button ("Switch bag"). */
		action?: { label: string; onClick: () => void };
		/** Renders the ✕; omit for conditions the user can't dismiss. */
		onDismiss?: () => void;
	} = $props();
</script>

{#snippet body()}
	<span class="bsc-title">{title}</span>
	<span class="bsc-text">{text}</span>
	{#if onClick && linkLabel}<span class="bsc-link">{linkLabel}</span>{/if}
{/snippet}

<div
	class={['bsc', `is-${variant}`]}
	role={variant === 'error' ? 'alert' : 'status'}
>
	<span class="bsc-icon" aria-hidden="true">
		<WarningIcon weight="duotone" />
	</span>
	{#if onClick}
		<button type="button" class="bsc-body bsc-body-btn" onclick={onClick}>
			{@render body()}
		</button>
	{:else}
		<span class="bsc-body">{@render body()}</span>
	{/if}
	{#if action}
		<button type="button" class="bsc-action" onclick={action.onClick}>
			{action.label}
		</button>
	{/if}
	{#if onDismiss}
		<button type="button" class="bsc-dismiss" onclick={onDismiss} aria-label="Dismiss {title}">
			<XIcon aria-hidden="true" />
		</button>
	{/if}
</div>

<style>
	.bsc {
		--bsc-rgb: var(--danger-rgb);
		--bsc-fg: var(--danger);
		display: flex;
		align-items: flex-start;
		gap: 12px;
		padding: 12px 8px 12px 14px;
		border-radius: 12px;
		background: rgba(var(--bsc-rgb), 0.12);
		border: 1px solid rgba(var(--bsc-rgb), 0.5);
		color: var(--fg-1);
	}
	.bsc.is-warning {
		--bsc-rgb: var(--warning-rgb);
		--bsc-fg: var(--warning);
	}
	.bsc-icon {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 32px;
		height: 32px;
		flex: 0 0 auto;
		border-radius: 50%;
		background: rgba(var(--bsc-rgb), 0.16);
		color: var(--bsc-fg);
		font-size: 17px;
	}
	.bsc-body {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 3px;
		/* Centre a one-line title + detail on the 32 px icon. */
		padding-top: 1px;
	}
	.bsc-body-btn {
		appearance: none;
		margin: 0;
		padding: 1px 0 0;
		border: 0;
		background: transparent;
		font: inherit;
		color: inherit;
		text-align: left;
		cursor: pointer;
		border-radius: 6px;
	}
	.bsc-body-btn:hover .bsc-title {
		text-decoration: underline;
	}
	.bsc-title {
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		line-height: 1.3;
		color: var(--fg-1);
	}
	.bsc-text {
		font-family: var(--font-mono);
		font-size: 12px;
		line-height: 1.35;
		color: rgba(var(--tint-rgb), 0.7);
		font-variant-numeric: tabular-nums;
		overflow-wrap: anywhere;
	}
	.bsc-link {
		margin-top: 3px;
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 600;
		color: var(--bsc-fg);
	}
	.bsc-action {
		position: relative;
		flex: none;
		align-self: center;
		min-height: 32px;
		padding: 0 12px;
		border: 1px solid rgba(var(--bsc-rgb), 0.5);
		border-radius: 8px;
		background: rgba(var(--bsc-rgb), 0.16);
		color: var(--bsc-fg);
		font: inherit;
		font-size: 12px;
		font-weight: 600;
		cursor: pointer;
	}
	.bsc-action:hover {
		background: rgba(var(--bsc-rgb), 0.28);
	}
	/* 44 px tall hit area around the 32 px button. */
	.bsc-action::before {
		content: '';
		position: absolute;
		inset: -6px -4px;
	}
	.bsc-dismiss {
		position: relative;
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 32px;
		height: 32px;
		margin: 0;
		padding: 0;
		border: 0;
		border-radius: 8px;
		background: transparent;
		color: var(--fg-3);
		font-size: 15px;
		cursor: pointer;
	}
	.bsc-dismiss:hover {
		color: var(--fg-1);
		background: rgba(var(--bsc-rgb), 0.18);
	}
	/* 44 px hit area around the 32 px glyph button. */
	.bsc-dismiss::before {
		content: '';
		position: absolute;
		inset: -6px;
	}
</style>
