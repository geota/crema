<script lang="ts">
	/**
	 * `ChartModal` — a full-viewport overlay that shows an enlarged shot chart
	 * (issue #11). Generic: the caller passes the chart as a `children` snippet,
	 * so the live `LiveChart` and the static `StaticShotChart` both reuse it. The
	 * live chart keeps updating while open — the snippet renders a second chart
	 * bound to the same reactive series, so nothing is re-mounted mid-shot.
	 *
	 * Patterned on {@link ConfirmDialog}: fixed scrim, `role="dialog"` +
	 * `aria-modal`, Escape / scrim-click / close-button to dismiss.
	 */
	import type { Snippet } from 'svelte';
	import Icon from '$lib/icons/Icon.svelte';

	let { onclose, children }: { onclose: () => void; children: Snippet } = $props();

	function onKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			onclose();
		}
	}
</script>

<svelte:document onkeydown={onKeydown} />

<div
	class="chm-scrim"
	role="presentation"
	onclick={onclose}
	onkeydown={(e) => {
		if (e.key === 'Enter' || e.key === ' ') onclose();
	}}
>
	<div
		class="chm-panel"
		role="dialog"
		aria-modal="true"
		aria-label="Enlarged shot chart"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.stopPropagation()}
	>
		<button type="button" class="chm-close" onclick={onclose} aria-label="Close enlarged chart">
			<Icon cls="ph ph-x" aria-hidden="true" />
		</button>
		<div class="chm-body">
			{@render children()}
		</div>
	</div>
</div>

<style>
	.chm-scrim {
		position: fixed;
		inset: 0;
		z-index: 1040; /* above content; below ConfirmDialog's 1050 */
		display: flex;
		align-items: center;
		justify-content: center;
		padding: var(--space-4, 16px);
		background: rgba(0, 0, 0, 0.62);
	}
	.chm-panel {
		position: relative;
		width: min(96vw, 1400px);
		height: min(82vh, 900px);
		display: flex;
		flex-direction: column;
		background: var(--bg-surface, #1a1a1a);
		border: 1px solid var(--hairline, rgba(128, 128, 128, 0.25));
		border-radius: var(--radius-lg, 16px);
		box-shadow: var(--shadow-md, 0 12px 40px rgba(0, 0, 0, 0.4));
		padding: var(--space-6, 24px) var(--space-4, 16px) var(--space-4, 16px);
	}
	.chm-close {
		position: absolute;
		top: var(--space-2, 8px);
		right: var(--space-2, 8px);
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 32px;
		height: 32px;
		border: none;
		border-radius: 8px;
		background: transparent;
		color: var(--text-muted, rgba(128, 128, 128, 0.9));
		cursor: pointer;
		font-size: 18px;
	}
	.chm-close:hover {
		background: rgba(128, 128, 128, 0.14);
	}
	/* The chart (live or static) fills the panel body. */
	.chm-body {
		flex: 1 1 auto;
		min-height: 0;
		display: flex;
		flex-direction: column;
	}
	.chm-body :global(> *) {
		flex: 1 1 auto;
		min-height: 0;
	}
</style>
