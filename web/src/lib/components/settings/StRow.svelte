<script lang="ts">
	/**
	 * `StRow` — one settings row: a title / sub text block on the left, a
	 * control (and optional hint) on the right. The control + hint are passed as
	 * snippets so callers compose any of the `St*` controls. Ported from the
	 * design's `StRow` (`settings-page.jsx`).
	 *
	 * Pass `notImplemented` to mark the row as a documented placeholder —
	 * shows a "Soon" pill next to the title and dims the
	 * control. The row stays discoverable (so the user can see what's
	 * coming) but honest about what doesn't work yet.
	 */
	import type { Snippet } from 'svelte';

	let {
		title,
		sub,
		control,
		hint,
		notImplemented = false,
		needsConnection = false
	}: {
		title: string;
		sub?: string;
		control: Snippet;
		hint?: Snippet;
		/** When true, show a "Soon" pill and dim the control. */
		notImplemented?: boolean;
		/** When true, show a "Connect a DE1" pill — the row's controls need a live device. */
		needsConnection?: boolean;
	} = $props();
</script>

<div class="st-row" class:st-row-not-impl={notImplemented}>
	<div class="st-row-text">
		<div class="st-row-title">
			<span>{title}</span>
			{#if notImplemented}
				<span
					class="st-row-pill"
					title="The control persists a value, but no part of the app reads it yet."
					>Soon</span
				>
			{:else if needsConnection}
				<span
					class="st-row-pill st-row-pill-conn"
					title="This setting needs a connected DE1."
					>Connect DE1</span
				>
			{/if}
		</div>
		{#if sub}<div class="st-row-sub">{sub}</div>{/if}
	</div>
	<div class="st-row-control">
		{@render control()}
		{#if hint}<div class="st-row-hint">{@render hint()}</div>{/if}
	</div>
</div>

<style>
	.st-row-title {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		flex-wrap: wrap;
	}
	.st-row-pill {
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.6);
		background: rgba(var(--tint-rgb), 0.08);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 999px;
		padding: 2px 8px;
		white-space: nowrap;
	}
	.st-row-not-impl .st-row-control {
		opacity: 0.5;
	}
	.st-row-pill-conn {
		color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.08);
		border-color: rgba(var(--copper-rgb), 0.22);
	}
</style>
