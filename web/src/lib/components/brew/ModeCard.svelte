<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	/**
	 * `ModeCard` — the Phase card's stand-in while a service mode runs.
	 *
	 * Steam / hot water / flush run no profile, so `PhaseIndicatorCard` has
	 * nothing to say while one is active: it sits on "Ready" above a "No profile"
	 * row for the whole session. Handing the slot to the mode costs no vertical
	 * space, which is the point — this replaces a header banner
	 * (`ModeHeadStatus`) that pushed the whole main grid down. On the Android
	 * tablet the same banner cost the chart nearly half its height on a 600dp
	 * display; the web layout is roomier but the trade was the same one, paying
	 * layout to repeat what the screen already showed (geota/crema#57).
	 *
	 * Built from the `MaxStopConditionsCard` row idiom — channel-coloured glyph,
	 * label, `live / target unit` readout, full-width bar — because that card
	 * sits directly above this one in the left column and the two should read as
	 * a pair. It deliberately does NOT repeat the mode name and clock (the
	 * extraction timer carries those), nor the live temperature (the temperature
	 * channel promotes it during a mode): progress against the firmware timeout
	 * is the one thing that had nowhere else to go.
	 */

	let {
		label,
		icon,
		color,
		elapsedSec,
		targetSec,
		pct,
		targetValue,
		targetUnit
	}: {
		/** Mode noun — "Steam" | "Hot water" | "Flush", matching the foot chips. */
		label: string;
		/** Phosphor icon name without the `ph-` prefix. */
		icon: string;
		/** Mode colour (CSS value) for the glyph and the bar fill. */
		color: string;
		/** Live elapsed seconds in the mode. */
		elapsedSec: number;
		/** The firmware timeout this mode runs against, seconds. */
		targetSec: number;
		/** Progress 0–100 toward [targetSec]. */
		pct: number;
		/** The mode's configured target, already in display units. */
		targetValue: string;
		/** Unit suffix for [targetValue], e.g. `°C`, `ml`. */
		targetUnit: string;
	} = $props();
</script>

<div class="crema-target crema-stopcond">
	<div class="crema-stopcond-head"><span class="t-eyebrow">Mode</span></div>
	<div class="crema-stopcond-row">
		<div class="crema-stopcond-top">
			<span class="crema-stopcond-label">
				<Icon cls={'ph ph-' + icon} style="color:{color}" aria-hidden="true" />
				{label}
			</span>
			<span class="crema-stopcond-val">
				<span class="crema-stopcond-live">{elapsedSec.toFixed(1)}</span>
				/ {targetSec.toFixed(0)}<em>s</em>
			</span>
		</div>
		<div class="crema-stopcond-bar">
			<span style="width:{Math.min(100, pct)}%; background:{color}"></span>
		</div>
	</div>
	<!-- The mode's configured target, unlabelled and right-aligned under the
	     live value it is the ceiling for. "Target" spelled out earned nothing:
	     the number sits directly beneath `elapsed / total` in a card headed by
	     the mode, which is all the context it needs. -->
	<div class="crema-stopcond-top mode-target">
		<span class="crema-stopcond-val">{targetValue}<em>{targetUnit}</em></span>
	</div>
</div>

<style>
	/* `.crema-stopcond-top` is `space-between` for its label/value pair; with
	   the label gone the lone value would sit left. */
	.mode-target {
		justify-content: flex-end;
	}
</style>
