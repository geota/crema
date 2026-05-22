<script lang="ts">
	/**
	 * `ModeActionRow` — the row of three Steam / Hot-water / Flush chips
	 * that sits between `.crema-dash-main` and `.crema-dash-foot` on the
	 * Brew page. Ported from `mode-controls.jsx:ActionRowFoot` in the
	 * crema-chips handoff.
	 *
	 * Each chip's tap (or its `×` cancel) is delegated to the caller via
	 * separate event callbacks — the orchestrator decides which DE1
	 * RequestedState to write.
	 *
	 * Mutual exclusion is implicit: `state` is a single union value, so at
	 * most one chip has the `is-active` class at a time. Matches the DE1
	 * firmware's contract (RequestedState is a single byte).
	 */
	import ModeChip from './ModeChip.svelte';

	type ModeState = 'idle' | 'steaming' | 'dispensing' | 'flushing';

	let {
		state = 'idle',
		ready = true,
		steamSub,
		waterSub,
		flushSub,
		onTapSteam,
		onTapWater,
		onTapFlush,
		onCancel
	}: {
		/** Live DE1 mode state — derived from `snapshot.machineState`. */
		state?: ModeState;
		/** Whether the DE1 will accept the mode-request write right now. */
		ready?: boolean;
		/** Sub-text on the Steam chip when idle (e.g. `'148 °C · 8 s'`). */
		steamSub?: string;
		/** Sub-text on the Hot-water chip when idle. */
		waterSub?: string;
		/** Sub-text on the Flush chip when idle. */
		flushSub?: string;
		/** Tap a chip while idle → start that mode. */
		onTapSteam?: () => void;
		onTapWater?: () => void;
		onTapFlush?: () => void;
		/** Tap an active chip (or its `×`) → return to Idle. */
		onCancel?: () => void;
	} = $props();

	const isSteam = $derived(state === 'steaming');
	const isWater = $derived(state === 'dispensing');
	const isFlush = $derived(state === 'flushing');

	function tap(kind: 'steam' | 'water' | 'flush'): void {
		// If this kind's chip is already active, the tap is a cancel
		// (returns to Idle). Otherwise it starts the mode.
		const active =
			(kind === 'steam' && isSteam) ||
			(kind === 'water' && isWater) ||
			(kind === 'flush' && isFlush);
		if (active) {
			onCancel?.();
			return;
		}
		if (kind === 'steam') onTapSteam?.();
		else if (kind === 'water') onTapWater?.();
		else onTapFlush?.();
	}
</script>

<div class="mc-actionrow">
	<ModeChip
		kind="steam"
		active={isSteam}
		{ready}
		icon="cloud"
		label="Steam"
		sub={isSteam ? 'In progress' : steamSub}
		onTap={() => tap('steam')}
	/>
	<ModeChip
		kind="water"
		active={isWater}
		{ready}
		icon="drop"
		label="Hot water"
		sub={isWater ? 'Dispensing' : waterSub}
		onTap={() => tap('water')}
	/>
	<ModeChip
		kind="flush"
		active={isFlush}
		{ready}
		icon="sparkle"
		label="Flush group"
		sub={isFlush ? 'Flushing' : flushSub}
		onTap={() => tap('flush')}
	/>
	<span class="mc-actionrow-hint">
		{ready ? 'Tap to start, again to cancel' : 'DE1 warming…'}
	</span>
</div>
