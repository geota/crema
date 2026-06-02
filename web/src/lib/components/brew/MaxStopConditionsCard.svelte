<script lang="ts">
	/**
	 * `MaxStopConditionsCard` — the consolidated "Max · stop conditions" card in
	 * the brew dashboard's left column. Replaces the inline `.crema-target-stack`
	 * markup in `BrewDashboard.svelte`.
	 *
	 * One row per active stop guard (Yield / Volume / Time), each with a
	 * channel-coloured Phosphor icon + label, a `live / target unit` readout (the
	 * live value in copper), and a full-width progress bar toward the target.
	 *
	 * Presentational: the parent decides which guards are active and which values
	 * to show, and passes the already-computed rows. Renders nothing when `rows`
	 * is empty, so the parent's `{#if}` gate is optional.
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
	};

	let { rows }: { rows: StopConditionRow[] } = $props();
</script>

{#if rows.length > 0}
	<div class="crema-target crema-stopcond">
		<div class="crema-stopcond-head"><span class="t-eyebrow">Max · stop conditions</span></div>
		{#each rows as row (row.key)}
			<div class="crema-stopcond-row">
				<div class="crema-stopcond-top">
					<span class="crema-stopcond-label">
						<i class={'ph ph-' + row.icon} style="color:{row.color}" aria-hidden="true"></i>
						{row.label}
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
