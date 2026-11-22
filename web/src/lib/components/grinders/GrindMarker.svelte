<script lang="ts">
	/**
	 * `GrindMarker` — the unsaved-grind marker (#488, boards
	 * WideGrindUnsavedRest / QC, PhoneGrindUnsavedRest / QC): a copper dot and
	 * "Bag 4.2" (the bag's saved grind for the active grinder; "Bag —" with
	 * none) while Brew's grind differs from it. Hover explains it; on touch the
	 * explanation is its accessible name and a tap toggles it.
	 */
	let {
		text,
		title,
		interactive = true
	}: {
		text: string;
		title: string;
		/** False inside another tap target (the header block): a plain label with a tooltip. */
		interactive?: boolean;
	} = $props();
	let shown = $state(false);
</script>

{#if !interactive}
	<span class="gmark" {title}><span class="gmark-dot" aria-hidden="true"></span>{text}<span class="sr-only">: {title}</span></span>
{:else}
<span class="gmark-wrap">
	<button
		type="button"
		class="gmark"
		{title}
		aria-label="{text}: {title}"
		aria-expanded={shown}
		onclick={(e) => {
			e.stopPropagation();
			shown = !shown;
		}}
		onblur={() => (shown = false)}
	>
		<span class="gmark-dot" aria-hidden="true"></span>{text}
	</button>
	{#if shown}
		<span class="gmark-tip" role="status">{title}</span>
	{/if}
</span>
{/if}

<style>
	.gmark-wrap {
		position: relative;
		display: inline-flex;
	}
	.gmark {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		border: 0;
		background: transparent;
		padding: 0;
		font-family: var(--font-mono);
		font-size: 12px;
		font-weight: 500;
		color: var(--fg-accent);
		cursor: help;
		white-space: nowrap;
	}
	.sr-only {
		position: absolute;
		width: 1px;
		height: 1px;
		overflow: hidden;
		clip: rect(0 0 0 0);
		white-space: nowrap;
	}
	.gmark-dot {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		background: var(--copper-500, #c7763b);
		flex: none;
	}
	.gmark-tip {
		position: absolute;
		top: calc(100% + 6px);
		left: 0;
		z-index: 60;
		width: max-content;
		max-width: 280px;
		padding: 10px 12px;
		border-radius: 10px;
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.16);
		box-shadow: var(--shadow-lg);
		font-family: var(--font-sans);
		font-size: 12px;
		line-height: 1.5;
		color: var(--fg-2);
		white-space: normal;
	}
</style>
