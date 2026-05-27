<script lang="ts">
	/**
	 * `CompareOverlay` — full-screen modal that overlays multiple recorded
	 * shots on a single chart so the user can compare them side-by-side. One
	 * channel at a time (pressure, flow, temp, weight); a colour-coded legend
	 * names each shot; a metric table underneath shows the headline numbers
	 * for every selected shot.
	 *
	 * Up to 5 shots — beyond that the lines stop being distinguishable. The
	 * caller (the History page) caps selection at 5 in the same spirit.
	 *
	 * Chart is hand-rolled SVG — the data per shot is small (a few hundred
	 * samples), each shot has its own time series (different durations), and
	 * we want fine control over per-shot colour. uPlot's shared-x model would
	 * require resampling every shot to a common grid; SVG paths sidestep that.
	 */
	import type { StoredShot } from '$lib/history';
	import { ratioLabel, peaksOf, flatSamplesOf } from '$lib/history';
	import type { TelemetrySample } from '$lib/state';
	import {
		getSettingsStore,
		convertWeight,
		convertTemp,
		convertPressure,
		unitLabel
	} from '$lib/settings';

	let {
		shots,
		onClose
	}: {
		/** The shots to compare — between 2 and 5 entries. */
		shots: readonly StoredShot[];
		/** Dismiss callback — fires on close button, scrim click, and Escape. */
		onClose: () => void;
	} = $props();

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);

	/**
	 * Per-shot colours. Five distinct hues so 2–5 shots stay legible against
	 * both the light and dark themes. Index by selection order; copper
	 * (Crema's brand colour) is the first so a 2-shot compare reads like a
	 * "current vs. reference" pairing.
	 */
	const SHOT_COLORS = [
		'var(--copper-500)',
		'#4A6FA5', // teal-blue
		'#8b6bb1', // soft purple
		'#D89030', // amber
		'#5fa07a' // sage
	];
	const colorFor = (idx: number): string => SHOT_COLORS[idx % SHOT_COLORS.length];

	/**
	 * The 8 selectable channels — the 4 primaries (pressure / flow / coffee
	 * temp / weight) plus the 4 secondaries paired with them on the brew
	 * dashboard (resistance / dispensed water / mix temp / weight flow).
	 * The compare chart shows ONE channel across N shots at a time; the
	 * grouped selector lets the user move between the pair on each card.
	 */
	type Channel =
		| 'pressure'
		| 'resistance'
		| 'flow'
		| 'water'
		| 'temp'
		| 'mixTemp'
		| 'weight'
		| 'weightFlow';

	/** The channel currently overlaid. Defaults to pressure — the most diagnostic. */
	let channel = $state<Channel>('pressure');

	/**
	 * Whether ANY shot in the overlay has scale-based resistance samples.
	 * The chart's per-sample fallback (`resistanceWeight ?? resistance`)
	 * prefers the scale-derived value when present, so even one paired
	 * shot in the set means the axis is reading in g-flow units. When
	 * comparing a paired + unpaired shot the magnitudes won't line up
	 * perfectly anyway — that's a property of the data, not a unit bug.
	 */
	/** Flat samples per shot, cached via `flatSamplesOf`. */
	const flatByShot = $derived(new Map(shots.map((s) => [s.id, flatSamplesOf(s)])));
	function seriesFor(shot: StoredShot): TelemetrySample[] {
		return flatByShot.get(shot.id) ?? [];
	}

	const anyResistanceFromWeight = $derived.by(() =>
		shots.some((shot) => seriesFor(shot).some((s) => s.resistanceWeight != null))
	);

	/** Y-axis unit label, by channel + user pref. */
	const yUnit = $derived.by(() => {
		if (channel === 'pressure') return unitLabel('pressure', prefs);
		if (channel === 'resistance') return anyResistanceFromWeight ? 'bar·s²/g²' : 'bar·s²/ml²';
		if (channel === 'flow') return 'ml/s';
		if (channel === 'water') return unitLabel('volume', prefs);
		if (channel === 'temp' || channel === 'mixTemp') return unitLabel('temp', prefs);
		if (channel === 'weightFlow') return 'g/s';
		return unitLabel('weight', prefs);
	});

	/**
	 * Convert a canonical channel sample to the user's display unit. Flow
	 * stays in ml/s (no sensible imperial flow unit); pressure / temp /
	 * weight / water pick up the user's pref. Resistance and weight-flow
	 * are unit-fixed.
	 */
	function toDisplay(value: number | null, ch: Channel): number | null {
		if (value == null || !Number.isFinite(value)) return null;
		if (ch === 'pressure')
			return prefs.pressureUnit === 'psi' ? value * 14.5038 : value;
		if (ch === 'temp' || ch === 'mixTemp')
			return prefs.tempUnit === 'F' ? value * 1.8 + 32 : value;
		if (ch === 'weight')
			return prefs.weightUnit === 'oz' ? value / 28.3495 : value;
		if (ch === 'water') return prefs.volumeUnit === 'floz' ? value / 29.5735 : value;
		return value; // flow, resistance, weightFlow — unit-fixed
	}

	/** Read the channel value off a telemetry sample, pre-converted to display unit. */
	function valueAt(s: TelemetrySample, ch: Channel): number | null {
		if (ch === 'pressure') return toDisplay(s.pressure, ch);
		// Resistance auto-switch: prefer scale-derived per-sample.
		if (ch === 'resistance') return toDisplay(s.resistanceWeight ?? s.resistance ?? null, ch);
		if (ch === 'flow') return toDisplay(s.flow, ch);
		if (ch === 'water') return toDisplay(s.dispensedVolume ?? null, ch);
		if (ch === 'temp') return toDisplay(s.temp, ch);
		if (ch === 'mixTemp') return toDisplay(s.mixTemp, ch);
		if (ch === 'weightFlow') return toDisplay(s.weightFlow ?? null, ch);
		return toDisplay(s.weight, ch);
	}

	/** Max time across the selected shots, seconds, with a tiny ceiling pad. */
	const maxTimeSec = $derived(
		Math.max(
			...shots.map((s) => {
				const series = seriesFor(s);
				return series.length === 0 ? 0 : series[series.length - 1].elapsed / 1000;
			}),
			15
		)
	);

	/** Max channel value across all shots, in display units, with a 10 % headroom pad. */
	const maxYDisplay = $derived.by(() => {
		let m = 0;
		for (const s of shots) {
			for (const sample of seriesFor(s)) {
				const v = valueAt(sample, channel);
				if (v != null && v > m) m = v;
			}
		}
		// Sensible minimum so a "flow ~0" shot still renders nice ticks.
		// Each channel has a different natural range — pick a floor that
		// keeps the y-axis from collapsing onto a single tick mark when the
		// shot's actual max is near-zero.
		const floor = (() => {
			switch (channel) {
				case 'pressure':
					return prefs.pressureUnit === 'psi' ? 150 : 10;
				case 'resistance':
					return 5; // bar·s²/ml² (flow) or bar·s²/g² (weight) — typical extraction peak
				case 'flow':
					return 6;
				case 'water':
					return prefs.volumeUnit === 'floz' ? 2 : 60;
				case 'temp':
				case 'mixTemp':
					return prefs.tempUnit === 'F' ? 210 : 100;
				case 'weightFlow':
					return 3; // g/s — typical peak espresso flow
				case 'weight':
				default:
					return prefs.weightUnit === 'oz' ? 2 : 60;
			}
		})();
		return Math.max(m * 1.1, floor);
	});

	// ── Chart sizing — purely viewport-relative; the SVG `viewBox` scales it. ─
	const CHART_W = 1000;
	const CHART_H = 360;
	const PAD_L = 44;
	const PAD_R = 24;
	const PAD_T = 16;
	const PAD_B = 28;
	const PLOT_W = CHART_W - PAD_L - PAD_R;
	const PLOT_H = CHART_H - PAD_T - PAD_B;

	/** Map a seconds value to its plot x. */
	const xAt = (t: number): number => PAD_L + (t / maxTimeSec) * PLOT_W;
	/** Map a display value to its plot y (inverted — high values are up). */
	const yAt = (v: number): number => PAD_T + PLOT_H - (v / maxYDisplay) * PLOT_H;

	/** Build an SVG path `d` for one shot's selected channel. */
	function pathFor(shot: StoredShot, ch: Channel): string {
		const segs: string[] = [];
		let drawing = false;
		for (const s of seriesFor(shot)) {
			const v = valueAt(s, ch);
			if (v == null) {
				drawing = false;
				continue;
			}
			const x = xAt(s.elapsed / 1000);
			const y = yAt(v);
			segs.push(`${drawing ? 'L' : 'M'} ${x.toFixed(1)} ${y.toFixed(1)}`);
			drawing = true;
		}
		return segs.join(' ');
	}

	/** Round-number x-axis splits — same cadence as the static chart. */
	const xSplits = $derived.by(() => {
		const incr = maxTimeSec <= 30 ? 5 : maxTimeSec <= 90 ? 10 : maxTimeSec <= 180 ? 20 : 30;
		const out: number[] = [];
		for (let t = incr; t < maxTimeSec; t += incr) out.push(t);
		return out;
	});

	/** Round-number y-axis splits — five ticks evenly spaced. */
	const ySplits = $derived.by(() => {
		const n = 4; // 5 lines including the top
		const out: number[] = [];
		for (let i = 1; i <= n; i++) {
			out.push((maxYDisplay * i) / n);
		}
		return out;
	});

	/** A friendly date label for a shot — `Mon, 18:42`. */
	function shotDate(shot: StoredShot): string {
		return new Date(shot.completedAt).toLocaleString(undefined, {
			weekday: 'short',
			hour: '2-digit',
			minute: '2-digit'
		});
	}

	/** Yield (final or peak) in the user's weight unit. */
	function shotYield(shot: StoredShot): string {
		const p = peaksOf(shot);
		const y = p.finalWeight ?? p.peakWeight;
		const m = convertWeight(y, prefs.weightUnit);
		return m.unit ? `${m.value} ${m.unit}` : m.value;
	}

	function close(): void {
		onClose();
	}

	function onKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape') close();
	}

	const channelLabel: Record<Channel, string> = {
		pressure: 'PRESSURE',
		resistance: 'RESISTANCE',
		flow: 'FLOW',
		water: 'WATER',
		temp: 'COFFEE',
		mixTemp: 'WATER',
		weight: 'WEIGHT',
		weightFlow: 'FLOW'
	};

	/**
	 * The selector layout — 4 groups (pressure family, flow family, temp
	 * family, weight family), each holding the primary + secondary
	 * channel that share the brew dashboard's icon. Identical to the
	 * Quick Sheet "Chart" toggle grouping.
	 */
	const CHANNEL_GROUPS: ReadonlyArray<{
		readonly icon: string;
		readonly options: ReadonlyArray<Channel>;
	}> = [
		{ icon: 'gauge', options: ['pressure', 'resistance'] },
		{ icon: 'drop', options: ['flow', 'water'] },
		{ icon: 'thermometer', options: ['temp', 'mixTemp'] },
		{ icon: 'scales', options: ['weight', 'weightFlow'] }
	];
</script>

<svelte:window onkeydown={onKeydown} />

<!--
	The scrim is a "click-outside-to-close" surface, not a control — it sits
	above the panel and intercepts background clicks. A dedicated close
	`<button>` in the header is the real keyboard target. Escape is wired on
	`<svelte:window>`. Suppressing the a11y warning for the scrim click is
	intentional: making it a `<button>` would trap focus and announce the
	scrim itself as an interactive element, which is misleading.
-->
<div
	class="cmp-scrim"
	role="dialog"
	aria-modal="true"
	aria-label="Compare {shots.length} shots"
	tabindex="-1"
	onclick={close}
	onkeydown={(e) => {
		if (e.key === 'Escape') close();
	}}
>
	<!-- The panel stops propagation so an in-panel click doesn't read as a
	     scrim click and close the dialog. -->
	<div
		class="cmp-panel"
		role="presentation"
		onclick={(e) => e.stopPropagation()}
	>
		<div class="cmp-head">
			<div>
				<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Compare</div>
				<div class="cmp-title">
					{shots.length} shots overlaid
				</div>
			</div>
			<button class="cmp-close" type="button" onclick={close} aria-label="Close">
				<i class="ph ph-x" aria-hidden="true"></i>
			</button>
		</div>

		<!--
			Channel selector — 4 groups of 2, each with the channel-family
			icon + the primary/secondary chips. Mirrors the brew
			dashboard's Quick Controls "Chart" toggle layout.
		-->
		<div class="cmp-ch">
			{#each CHANNEL_GROUPS as group, gi (gi)}
				{#if gi > 0}
					<span class="cmp-ch-rule" aria-hidden="true"></span>
				{/if}
				<span class="cmp-ch-group">
					<i class={`ph ph-${group.icon} cmp-ch-icon`} aria-hidden="true"></i>
					{#each group.options as ch (ch)}
						<button
							type="button"
							class="cmp-ch-chip"
							class:is-active={channel === ch}
							onclick={() => (channel = ch)}
						>
							{channelLabel[ch]}
						</button>
					{/each}
				</span>
			{/each}
			<span class="cmp-ch-unit">· {yUnit}</span>
		</div>

		<!-- Chart -->
		<div class="cmp-chart-wrap">
			<svg
				class="cmp-chart"
				viewBox="0 0 {CHART_W} {CHART_H}"
				preserveAspectRatio="none"
				aria-label="Overlaid {channel} curves"
			>
				<!-- Plot frame -->
				<rect
					x={PAD_L}
					y={PAD_T}
					width={PLOT_W}
					height={PLOT_H}
					fill="transparent"
					stroke="var(--chart-grid)"
				/>

				<!-- Horizontal y grid -->
				{#each ySplits as v (v)}
					<line
						x1={PAD_L}
						x2={PAD_L + PLOT_W}
						y1={yAt(v)}
						y2={yAt(v)}
						stroke="var(--chart-grid)"
						stroke-width={1}
					/>
					<text
						x={PAD_L - 6}
						y={yAt(v) + 3}
						text-anchor="end"
						class="cmp-axis-label">{Math.round(v)}</text
					>
				{/each}

				<!-- Vertical x grid -->
				{#each xSplits as t (t)}
					<line
						x1={xAt(t)}
						x2={xAt(t)}
						y1={PAD_T}
						y2={PAD_T + PLOT_H}
						stroke="var(--chart-grid)"
						stroke-width={1}
						stroke-dasharray="2 4"
					/>
					<text
						x={xAt(t)}
						y={PAD_T + PLOT_H + 16}
						text-anchor="middle"
						class="cmp-axis-label">{t}s</text
					>
				{/each}

				<!-- One path per shot -->
				{#each shots as shot, idx (shot.id)}
					<path
						d={pathFor(shot, channel)}
						fill="none"
						stroke={colorFor(idx)}
						stroke-width={2.2}
						stroke-linejoin="round"
						stroke-linecap="round"
					/>
				{/each}
			</svg>
		</div>

		<!-- Legend + metric table -->
		<div class="cmp-table-wrap">
			<table class="cmp-table">
				<thead>
					<tr>
						<th class="cmp-th-shot">Shot</th>
						<th>Time</th>
						<th>Peak pressure</th>
						<th>Peak temp</th>
						<th>Final</th>
						<th>Ratio</th>
						<th>Rating</th>
					</tr>
				</thead>
				<tbody>
					{#each shots as shot, idx (shot.id)}
						{@const rowPeaks = peaksOf(shot)}
						{@const pkP = convertPressure(rowPeaks.peakPressure, prefs.pressureUnit)}
						{@const pkT = convertTemp(rowPeaks.peakTemp, prefs.tempUnit)}
						{@const rowRating = shot.metadata.rating ?? 0}
						<tr>
							<td class="cmp-td-shot">
								<i class="cmp-dot" style="--c:{colorFor(idx)}"></i>
								<div>
									<div class="cmp-td-shot-name">
										{shot.profileName ?? 'Untitled shot'}
									</div>
									<div class="cmp-td-shot-meta">{shotDate(shot)}</div>
								</div>
							</td>
							<td>{(shot.record.duration / 1000).toFixed(0)} s</td>
							<td>{pkP.value} {pkP.unit}</td>
							<td>{pkT.value} {pkT.unit}</td>
							<td>{shotYield(shot)}</td>
							<td>{ratioLabel(shot)}</td>
							<td>
								<span class="cmp-stars" class:is-unrated={rowRating <= 0}>
									{'★'.repeat(Math.max(0, Math.min(5, Math.round(rowRating))))}
								</span>
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>
	</div>
</div>

<style>
	.cmp-scrim {
		position: fixed;
		inset: 0;
		z-index: 60;
		background: rgba(var(--scrim-rgb), 0.65);
		backdrop-filter: blur(6px);
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 24px;
		animation: cmp-fade 160ms var(--ease, ease-out);
	}
	@keyframes cmp-fade {
		from {
			opacity: 0;
		}
		to {
			opacity: 1;
		}
	}
	.cmp-panel {
		background: var(--bg-page);
		border: 1px solid var(--hairline-strong);
		border-radius: var(--radius-md, 12px);
		box-shadow: var(--shadow-lg);
		width: min(1200px, calc(100vw - 48px));
		max-height: calc(100vh - 48px);
		display: flex;
		flex-direction: column;
		overflow: hidden;
		animation: cmp-rise 200ms var(--ease, ease-out);
	}
	@keyframes cmp-rise {
		from {
			transform: translateY(8px) scale(0.99);
			opacity: 0;
		}
		to {
			transform: none;
			opacity: 1;
		}
	}
	.cmp-head {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		padding: 18px 22px 6px;
	}
	.cmp-title {
		font-family: var(--font-serif, var(--font-sans));
		font-size: 22px;
		color: var(--fg-1);
		margin-top: 2px;
	}
	.cmp-close {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		font-size: 20px;
		padding: 6px 8px;
		cursor: pointer;
		border-radius: var(--radius-sm, 6px);
		transition: background var(--dur-1, 140ms) var(--ease, ease);
	}
	.cmp-close:hover {
		background: rgba(var(--tint-rgb), 0.06);
		color: var(--fg-1);
	}
	.cmp-ch {
		display: flex;
		gap: 6px;
		padding: 8px 22px 12px;
		align-items: center;
		flex-wrap: wrap;
	}
	.cmp-ch-group {
		display: inline-flex;
		align-items: center;
		gap: 5px;
	}
	.cmp-ch-icon {
		font-size: 13px;
		color: rgba(var(--tint-rgb), 0.55);
		margin-right: 2px;
	}
	.cmp-ch-rule {
		display: inline-block;
		width: 1px;
		height: 18px;
		background: rgba(var(--tint-rgb), 0.18);
		margin: 0 6px;
	}
	.cmp-ch-chip {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		color: rgba(var(--tint-rgb), 0.75);
		border-radius: 999px;
		padding: 5px 12px;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
		letter-spacing: var(--track-allcaps, 0.06em);
		cursor: pointer;
		transition:
			background var(--dur-1, 140ms) var(--ease, ease),
			color var(--dur-1, 140ms) var(--ease, ease),
			border-color var(--dur-1, 140ms) var(--ease, ease);
	}
	.cmp-ch-chip:hover {
		background: rgba(var(--tint-rgb), 0.07);
		color: var(--fg-1);
	}
	.cmp-ch-chip.is-active {
		background: rgba(var(--copper-rgb), 0.16);
		border-color: rgba(var(--copper-rgb), 0.4);
		color: var(--copper-400);
	}
	.cmp-ch-unit {
		font-family: var(--font-mono);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
		margin-left: 6px;
	}
	.cmp-chart-wrap {
		padding: 0 22px;
		flex: 0 0 auto;
	}
	.cmp-chart {
		width: 100%;
		height: 360px;
		display: block;
	}
	.cmp-axis-label {
		font-family: var(--font-mono);
		font-size: 10px;
		fill: var(--chart-axis-label);
	}
	.cmp-table-wrap {
		flex: 1 1 auto;
		overflow: auto;
		padding: 14px 22px 22px;
	}
	.cmp-table {
		width: 100%;
		border-collapse: collapse;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-1);
	}
	.cmp-table thead th {
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 500;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
		text-align: left;
		padding: 8px 10px;
		border-bottom: 1px solid var(--hairline);
	}
	.cmp-table tbody td {
		padding: 10px;
		border-bottom: 1px solid var(--hairline);
		vertical-align: middle;
		font-variant-numeric: tabular-nums;
	}
	.cmp-table tbody tr:last-child td {
		border-bottom: 0;
	}
	.cmp-th-shot {
		min-width: 200px;
	}
	.cmp-td-shot {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.cmp-dot {
		display: inline-block;
		width: 10px;
		height: 10px;
		border-radius: 50%;
		background: var(--c);
		flex: 0 0 auto;
	}
	.cmp-td-shot-name {
		font-weight: 500;
		color: var(--fg-1);
	}
	.cmp-td-shot-meta {
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.5);
		margin-top: 1px;
	}
	.cmp-stars {
		color: var(--copper-400);
		letter-spacing: -1px;
	}
	.cmp-stars.is-unrated {
		color: rgba(var(--tint-rgb), 0.25);
	}
</style>
