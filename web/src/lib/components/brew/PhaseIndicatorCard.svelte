<script lang="ts">
	/**
	 * `PhaseIndicatorCard` — the left-column "where in the shot are we" card,
	 * ported from `PhaseIndicatorCard` in `web-dashboard-v2.jsx`. Reuses the
	 * `.crema-phase*` classes from `web-kit.css`.
	 *
	 * A synthetic four-phase model (Pre-infusion → Pressure ramp → Hold →
	 * Decline) drawn as a segmented progress track. It is **display-only** and
	 * derived from the live shot elapsed time plus the pre-infusion target — the
	 * core does not yet expose per-phase boundaries, so the ramp / hold / decline
	 * splits are the design's fixed synthetic durations.
	 *
	 * Only mounted on the resting Brew dashboard (Quick Sheet closed) — when the
	 * sheet is open it would overlap the docked panel.
	 */
	let {
		seconds,
		preinf = 8
	}: {
		/** Live shot elapsed time, seconds. */
		seconds: number;
		/** Pre-infusion duration target, seconds — the first phase's length. */
		preinf?: number;
	} = $props();

	/** A single phase in the synthetic shot model. */
	interface Phase {
		id: 'preinf' | 'ramp' | 'hold' | 'decline';
		label: string;
		tick: string;
		start: number;
		end: number;
	}

	/** Typical shot length used purely for the progress visual. */
	const TOTAL = 32;

	/** The four phases, their boundaries keyed off the pre-infusion target. */
	const phases = $derived<Phase[]>([
		{ id: 'preinf', label: 'Pre-infusion', tick: 'Pre-inf', start: 0, end: preinf },
		{ id: 'ramp', label: 'Pressure ramp', tick: 'Ramp', start: preinf, end: preinf + 4 },
		{ id: 'hold', label: 'Hold', tick: 'Hold', start: preinf + 4, end: preinf + 18 },
		{ id: 'decline', label: 'Decline', tick: 'Decline', start: preinf + 18, end: TOTAL }
	]);

	/** Clamp the elapsed time into the visual window. */
	const t = $derived(Math.max(0, Math.min(seconds, TOTAL)));
	/** The phase currently in progress (the last one once past TOTAL). */
	const active = $derived(
		phases.find((p) => t >= p.start && t < p.end) ?? phases[phases.length - 1]
	);

	/** Track-segment geometry — width %, fill % and past/active flags. */
	const segments = $derived(
		phases.map((ph) => {
			const span = ph.end - ph.start;
			const within = Math.max(0, Math.min(t, ph.end) - ph.start);
			return {
				id: ph.id,
				tick: ph.tick,
				widthPct: (span / TOTAL) * 100,
				fillPct: span > 0 ? Math.min(100, (within / span) * 100) : 0,
				isActive: active.id === ph.id,
				isPast: t >= ph.end
			};
		})
	);
</script>

<div class="crema-target crema-phase">
	<div class="crema-phase-head">
		<div class="t-eyebrow">Phase</div>
		<div class="crema-phase-name">{active.label}</div>
	</div>
	<div class="crema-phase-track">
		{#each segments as seg (seg.id)}
			<div
				class="crema-phase-seg"
				class:is-active={seg.isActive}
				class:is-past={seg.isPast}
				style="width:{seg.widthPct}%"
			>
				<span class="crema-phase-fill" style="width:{seg.fillPct}%"></span>
			</div>
		{/each}
	</div>
	<div class="crema-phase-ticks">
		{#each segments as seg (seg.id)}
			<span class="crema-phase-tick" class:is-active={seg.isActive}>{seg.tick}</span>
		{/each}
	</div>
</div>
