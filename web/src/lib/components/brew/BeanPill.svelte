<script lang="ts">
	/**
	 * `BeanPill` — the bean-half of the Quick Sheet's unified favourites strip.
	 *
	 * Companion to `ProfilePreview compact` (the profile half): renders one
	 * favourited bag as a pill chip the same height + general silhouette as a
	 * profile chip, so the row reads as one coherent strip even though the
	 * two halves render different content.
	 *
	 * Contents (left → right):
	 *   - 30 × 30 roaster-mark avatar (the same `roasterMarkTone` helper the
	 *     library cards use, so the colour is stable across surfaces)
	 *   - bean name (the chip's main label)
	 *   - roast-band sub-label in mono — matches the profile chip's ratio
	 *     mono caption visually
	 *
	 * Active-state styling (copper border + glow) lives on the parent
	 * `.qfstrip-item` rule shared with the profile pills — same selector,
	 * same treatment, so "this one is selected" reads identically across
	 * profile and bean chips.
	 */
	import { roasterMarkTone, type Bean, type Roaster } from '$lib/bean';

	let {
		bean,
		roaster,
		active = false
	}: {
		/** The bean to render. */
		bean: Bean;
		/** The bean's roaster row (drives the mark + tone), or `null`. */
		roaster: Roaster | null;
		/** Whether this bean is the active picker selection. */
		active?: boolean;
	} = $props();

	/** Mark + tone for the avatar; deterministic from the roaster name. */
	const mt = $derived(roasterMarkTone(roaster));
</script>

<span class="bp-avatar" class:is-active={active} style="--tone: {mt.tone}">
	<span class="bp-mark">{mt.mark}</span>
</span>
<span class="bp-name">{bean.name || 'Untitled bean'}</span>
<span class="bp-roaster">{roaster?.name ?? 'No roaster'}</span>

<style>
	/*
	 * Avatar sits at the chip's leading edge — sized 30 px square to match
	 * the height of the `ProfilePreview` compact thumbnail in the profile
	 * pill. The tone gradient mirrors `BeanTile`'s avatar so the same
	 * roaster carries the same colour everywhere it appears.
	 */
	.bp-avatar {
		flex: 0 0 auto;
		width: 30px;
		height: 30px;
		border-radius: 8px;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		background:
			linear-gradient(135deg, color-mix(in srgb, var(--tone) 88%, transparent), var(--tone)),
			var(--tone);
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.1);
		transition: box-shadow var(--dur-1) var(--ease);
	}
	.bp-avatar.is-active {
		box-shadow:
			inset 0 1px 0 rgba(255, 255, 255, 0.1),
			0 0 0 1.5px var(--copper-400);
	}
	.bp-mark {
		font-family: var(--font-serif);
		font-weight: 500;
		font-size: 13px;
		color: rgba(255, 255, 255, 0.92);
		letter-spacing: -0.02em;
		text-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
	}
	/* Bean name — same weight / size class as `.qfstrip-name`. */
	.bp-name {
		font-weight: 500;
		max-width: 140px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	/* Roaster name sub-label — matches the position of the profile pill's
	   ratio caption visually so the row reads with one rhythm. Plain sans
	   (not mono) because roaster names aren't tabular data. */
	.bp-roaster {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
		max-width: 120px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
