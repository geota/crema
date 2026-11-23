<script lang="ts">
	/**
	 * `FollowedGrind` — the Grind cell while a connected grinder sets Brew's
	 * grind (spec `.scratch/grinder-ble/SPEC.md` §5.2): the grinder's value with
	 * "from grinder" (and the speed for a variable-speed grinder) where the
	 * stepper was, the live value greyed while the collar moves, and "Pause
	 * following" and Devices where the chips were. It takes the stepper's and
	 * the chip row's places with their classes, so the cell keeps its height.
	 */
	import type { FollowCell } from '#lib/grinder/follow.svelte.js';

	let { follow, unit = '' }: { follow: FollowCell; unit?: string } = $props();

	const shown = $derived(follow.value ?? '—');
</script>

<div class="qcs">
	<div class="qcs-row">
		<div
			class="qcs-val has-sub qfg-val"
			class:is-moving={follow.moving}
			role="status"
			aria-label="Grind {shown}{follow.value !== null && unit ? ` ${unit}` : ''}, {follow.source ?? 'from grinder'}{follow.moving ? ', the collar is moving' : ''}"
			title="The grinder's collar sets this"
		>
			<span class="qcs-num">{shown}</span>
			<span class="qcs-unit">{follow.value === null ? '' : unit}</span>
			<span class="qcs-sub">{follow.source}</span>
		</div>
	</div>
</div>
<div style="height:8px"></div>
<div class="qchipr" role="group" aria-label="Following the grinder">
	<button type="button" class="qchip" onclick={follow.onPause}>Pause following</button>
	<button type="button" class="qchip" onclick={follow.onDevices}>Devices</button>
</div>

<style>
	.qfg-val {
		cursor: default;
	}
	.qfg-val .qcs-sub {
		font-family: var(--font-sans);
		font-size: 10px;
		color: var(--fg-3);
	}
	/* The collar is moving: the live value, greyed, until it holds. */
	.qfg-val.is-moving .qcs-num {
		color: var(--fg-3);
	}
	.qchipr .qchip {
		font-family: var(--font-sans);
	}
</style>
