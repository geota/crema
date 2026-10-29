/**
 * Text alternatives for the telemetry charts (cohort review 07 #15; spec
 * docs/12 item E). The charts are canvases, so the container is exposed as
 * `role="img"` with one of these summaries as its name. The numbers also
 * appear as text next to each chart; the summary says what the picture
 * shows without making a screen-reader user hunt for them.
 */
import type { TelemetrySample } from '#lib/state/index.js';

const fmt = (n: number, digits = 1): string => (Number.isFinite(n) ? n.toFixed(digits) : '—');

/** Live Brew chart: the latest reading, or that no shot is plotted yet. */
export function liveChartSummary(series: readonly TelemetrySample[]): string {
	const last = series.at(-1);
	if (!last) return 'Shot telemetry chart, no shot plotted yet';
	const parts = [`pressure ${fmt(last.pressure)} bar`, `flow ${fmt(last.flow)} ml/s`];
	return `Shot telemetry chart at ${Math.round(last.elapsed / 1000)} s: ${parts.join(', ')}`;
}

/** Stored shot chart: duration and the peak pressure and flow. */
export function storedChartSummary(series: readonly TelemetrySample[]): string {
	if (series.length === 0) return 'Shot telemetry chart, no samples recorded';
	let peakP = -Infinity;
	let peakF = -Infinity;
	for (const s of series) {
		if (s.pressure > peakP) peakP = s.pressure;
		if (s.flow > peakF) peakF = s.flow;
	}
	const secs = Math.round((series.at(-1)?.elapsed ?? 0) / 1000);
	return `Shot telemetry chart, ${secs} s: peak pressure ${fmt(peakP)} bar, peak flow ${fmt(peakF)} ml/s`;
}
