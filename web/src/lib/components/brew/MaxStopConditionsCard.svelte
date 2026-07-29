<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	/**
	 * `MaxStopConditionsCard` — the consolidated "Stop conditions" card in
	 * the brew dashboard's left column. Replaces the inline `.crema-target-stack`
	 * markup in `BrewDashboard.svelte`.
	 *
	 * One row per active stop guard (Yield / Volume / Time), each with a
	 * channel-coloured Phosphor icon + label, a `live / target unit` readout (the
	 * live value in copper), and a full-width progress bar toward the target.
	 *
	 * Presentational: the parent decides which guards are active and which values
	 * to show, and passes the already-computed rows.
	 *
	 * With `none` set the card collapses to its eyebrow —
	 * "STOP CONDITIONS · ⚠ NONE" — because nothing whatsoever will end the shot.
	 * That is a real state and the most important thing this card can say;
	 * rendering nothing for it (the old behaviour) reads as "fine", which is how
	 * geota/crema#56 stayed invisible from the brew screen. Renders nothing only
	 * when there are no rows AND `none` is false — i.e. the core hasn't reported
	 * yet.
	 */

	export type StopConditionRow = {
		/** Stable key for the `{#each}`. */
		key: string;
		/** Row label — "Yield" | "Volume" | "Time". */
		label: string;
		/** Phosphor icon name (no `ph-` prefix), e.g. `scales`, `drop-half`, `timer`. */
		icon: string;
		/** Live measured value, already in display units. */
		live: string;
		/** Target value, already in display units. */
		target: string;
		/** Unit suffix, e.g. `g`, `ml`, `s`. */
		unit: string;
		/** Progress 0–100 toward the target. */
		pct: number;
		/** Channel colour (CSS value) for the icon + bar fill. */
		color: string;
		/** Why this guard cannot fire (e.g. `'no scale'`), or omitted when it
		 *  can. A noted row is flagged inline with a warning glyph carrying the
		 *  reason as its tooltip. */
		note?: string;
	};

	let { rows, none = false }: { rows: StopConditionRow[]; none?: boolean } = $props();
</script>

{#if rows.length > 0 || none}
	<div class="crema-target crema-stopcond">
		<div class="crema-stopcond-head">
			<span class="t-eyebrow">Stop conditions</span>
			{#if none}
				<span class="t-eyebrow">·</span>
				<span class="t-eyebrow crema-stopcond-none">
					<Icon cls="ph ph-warning" aria-hidden="true" /> None
				</span>
			{/if}
		</div>
		{#each rows as row (row.key)}
			<div class="crema-stopcond-row">
				<div class="crema-stopcond-top">
					<span class="crema-stopcond-label">
						<Icon cls={'ph ph-' + row.icon} style="color:{row.color}" aria-hidden="true" />
						{row.label}
						{#if row.note}
							<!-- The glyph carries the urgent half — this target will not
							     stop the shot — and the reason rides its tooltip rather
							     than spending a line on it. The tooltip sits on a span,
							     not on the Icon: `title` as an ATTRIBUTE does nothing on
							     an SVG element (SVG wants a <title> child), so putting it
							     on the icon itself would silently render no tooltip. -->
							<span class="crema-stopcond-warn" title={row.note} role="img" aria-label={row.note}>
								<Icon cls="ph ph-warning" aria-hidden="true" />
							</span>
						{/if}
					</span>
					<span class="crema-stopcond-val">
						<span class="crema-stopcond-live">{row.live}</span>
						/ {row.target}<em>{row.unit}</em>
					</span>
				</div>
				<div class="crema-stopcond-bar">
					<span style="width:{Math.min(100, row.pct)}%; background:{row.color}"></span>
				</div>
			</div>
		{/each}
	</div>
{/if}

<style>
	.crema-stopcond-warn {
		display: inline-flex;
		align-items: center;
		color: var(--warning);
		margin-inline-start: 0.3rem;
	}

	.crema-stopcond-head {
		display: flex;
		align-items: center;
		gap: 0.35rem;
	}
	.crema-stopcond-none {
		display: inline-flex;
		align-items: center;
		gap: 0.3rem;
		color: var(--warning);
	}

</style>
