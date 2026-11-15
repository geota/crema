<script lang="ts">
	/**
	 * `SparkChart` — a History row's tiny chart, drawn from the row's stored
	 * spark (`summary.spark`, #310 P4w) rather than its samples, so a list
	 * loads no curve and makes no core call per row.
	 *
	 * A machine shot draws pressure, flow and, with a scale, weight, each on
	 * its own scale as `MiniShotChart` did (10 bar, 8 ml/s, the shot's own
	 * peak weight). A guided brew draws its weight curve with hairline ticks
	 * at its stage boundaries, as `MiniBrewChart` did. The core quantised
	 * each channel to 32 points on those scales; here they're only placed.
	 */
	import { decodeSpark, SPARK_POINTS } from '#lib/history/spark.js';

	let {
		spark,
		width = 96,
		height = 32
	}: {
		/** The row's spark, base64; nothing is drawn without one. */
		spark: string | null | undefined;
		width?: number;
		height?: number;
	} = $props();

	const PAD = 1;
	const decoded = $derived(decodeSpark(spark));

	const xAt = (i: number): number => PAD + ((i + 0.5) / SPARK_POINTS) * (width - 2 * PAD);
	const yAt = (norm: number): number => PAD + (1 - Math.min(1, Math.max(0, norm))) * (height - 2 * PAD);

	/** An SVG path over `points`, lifting the pen at each gap. */
	function pathFor(points: readonly (number | null)[]): string {
		const segs: string[] = [];
		let drawing = false;
		points.forEach((v, i) => {
			if (v == null) {
				drawing = false;
				return;
			}
			segs.push(`${drawing ? 'L' : 'M'} ${xAt(i).toFixed(1)} ${yAt(v).toFixed(1)}`);
			drawing = true;
		});
		return segs.join(' ');
	}

	const pressurePath = $derived(decoded?.machine ? pathFor(decoded.pressure) : '');
	const flowPath = $derived(decoded?.machine ? pathFor(decoded.flow) : '');
	const weightPath = $derived(decoded?.hasWeight ? pathFor(decoded.weight) : '');
	/** Stage boundaries, past the left edge. */
	const tickXs = $derived(
		decoded?.brew ? decoded.ticks.filter((t) => t > 0).map((t) => PAD + t * (width - 2 * PAD)) : []
	);
</script>

{#if decoded}
	<svg
		class="spark-chart"
		{width}
		{height}
		viewBox="0 0 {width} {height}"
		preserveAspectRatio="none"
		aria-hidden="true"
		data-spark={decoded.brew ? 'brew' : 'machine'}
	>
		{#each tickXs as x, i (i)}
			<line class="spark-stage" x1={x} y1={PAD} x2={x} y2={height - PAD} />
		{/each}
		{#if weightPath}
			<path
				class={['spark-line', decoded.brew ? 'spark-line-brew' : 'spark-line-weight']}
				d={weightPath}
				fill="none"
				stroke="var(--tel-weight)"
			/>
		{/if}
		{#if decoded.machine}
			<path class="spark-line spark-line-flow" d={flowPath} fill="none" stroke="var(--tel-flow)" />
			<path class="spark-line spark-line-pressure" d={pressurePath} fill="none" stroke="var(--tel-pressure)" />
		{/if}
	</svg>
{/if}

<style>
	.spark-chart {
		display: block;
		overflow: visible;
	}
	.spark-line {
		stroke-linecap: round;
		stroke-linejoin: round;
		vector-effect: non-scaling-stroke;
	}
	.spark-line-pressure,
	.spark-line-brew {
		stroke-width: 1.5;
	}
	.spark-line-flow {
		stroke-width: 1.1;
		opacity: 0.85;
	}
	.spark-line-weight {
		stroke-width: 1.1;
		opacity: 0.7;
	}
	.spark-stage {
		stroke: rgba(var(--tint-rgb), 0.18);
		stroke-width: 1;
		vector-effect: non-scaling-stroke;
	}
</style>
