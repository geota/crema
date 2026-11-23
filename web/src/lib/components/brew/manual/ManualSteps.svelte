<script lang="ts">
	/**
	 * `ManualSteps` — the recipe's steps on Brew's Manual page (brew modes
	 * §3): the current one highlighted ("Now"), and "Tap" on the steps that
	 * wait for a tap rather than advancing on their own.
	 */
	import type { Snippet } from 'svelte';
	import type { BrewStep } from '#lib/core/crema-core.js';
	import { StepAdvance } from '#lib/core/crema-core.js';
	import { kindLabel, stepNote, stepSpec } from '#lib/brew/guided-run.svelte.js';

	let {
		steps,
		current = null,
		footer
	}: {
		steps: readonly BrewStep[];
		/** The current step's index; `null` = none (the brew finished). */
		current?: number | null;
		/** Under the list: the scale line and the setup toggles. */
		footer?: Snippet;
	} = $props();

	/** Keep the current step in view as the brew moves on. */
	function followCurrent(list: HTMLOListElement) {
		const i = current;
		const el = i == null ? null : list.children[i];
		if (el instanceof HTMLElement && typeof el.scrollIntoView === 'function') {
			el.scrollIntoView({ block: 'nearest' });
		}
	}
</script>

<section class="ms" aria-label="Recipe steps">
	<ol class="ms-list" {@attach followCurrent}>
		{#each steps as step, i (i)}
			{@const now = i === current}
			<li class={['ms-step', { 'is-now': now }]} aria-current={now ? 'step' : undefined}>
				<span class="ms-n">{i + 1}</span>
				<span class="ms-body">
					<b>{kindLabel(step)}</b>
					<span class="ms-spec">{stepSpec(step)}</span>
					{#if stepNote(step)}<span class="ms-note">{stepNote(step)}</span>{/if}
				</span>
				{#if now}
					<span class="ms-badge">Now</span>
				{:else if step.advance === StepAdvance.Manual}
					<span class="ms-badge is-quiet">Tap</span>
				{/if}
			</li>
		{/each}
	</ol>
	{#if footer}<div class="ms-foot">{@render footer()}</div>{/if}
</section>

<style>
	.ms {
		display: flex;
		flex-direction: column;
		min-height: 0;
		flex: 1 1 auto;
		background: var(--bg-surface);
		border-radius: 16px;
		box-shadow: inset 0 1px 0 rgba(var(--tint-rgb), 0.04);
		overflow: hidden;
	}
	.ms-list {
		list-style: none;
		margin: 0;
		padding: 8px 0;
		overflow-y: auto;
		min-height: 0;
		flex: 1 1 auto;
	}
	.ms-step {
		display: flex;
		align-items: baseline;
		gap: 10px;
		padding: 7px 16px;
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
	}
	.ms-step.is-now {
		background: rgba(var(--copper-rgb), 0.14);
		box-shadow: inset 3px 0 0 var(--copper-500);
	}
	.ms-n {
		flex: 0 0 18px;
		font-family: var(--font-mono);
		font-size: 11px;
		color: var(--fg-muted);
	}
	.is-now .ms-n {
		color: var(--fg-accent);
	}
	.ms-body {
		flex: 1;
		min-width: 0;
	}
	.ms-body b {
		font-weight: 600;
	}
	.ms-spec {
		color: var(--fg-3);
		margin-left: 4px;
	}
	.ms-note {
		display: block;
		font-size: 12px;
		color: var(--fg-muted);
		margin-top: 2px;
	}
	.ms-badge {
		flex: none;
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 600;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		color: var(--fg-accent);
	}
	.ms-badge.is-quiet {
		color: var(--fg-muted);
	}
	.ms-foot {
		border-top: 1px solid rgba(var(--tint-rgb), 0.08);
		padding: 10px 16px;
	}
</style>
