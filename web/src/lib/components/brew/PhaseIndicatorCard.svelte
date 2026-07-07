<script lang="ts">
	import { untrack } from 'svelte';
	import Icon from '$lib/icons/Icon.svelte';
	/**
	 * `PhaseIndicatorCard` — the left-column "where in the shot are we" card.
	 *
	 * One row per profile segment: name, duration (with the segment's
	 * pressure/flow early-exit symbol when it has one), and a progress bar. The
	 * active row fills from when the DE1 entered that frame
	 * (`Event::ShotFrameChanged` → `UiSnapshot.shotFrame`), so an early frame
	 * exit never desyncs the fill from the timer. Falls back to a single
	 * "No profile" row when no profile is active.
	 *
	 * Aligned to the Crema Android (Compose) reference:
	 *  - the overall strip flex-GROWS per segment (`flex: <span> 1 0`) instead of
	 *    a fixed `%` width, so inter-segment gaps are absorbed inside the track
	 *    rather than pushing the last segment past the card edge;
	 *  - the per-phase list flex-fills the card's leftover height and scrolls
	 *    with a scroll-aware edge fade + an auto-centred active row (hidden
	 *    scrollbar);
	 *  - each row is a bordered cell; the active one is copper-accented.
	 *
	 * #4a — a segment whose `exit` watches pressure or flow shows that channel's
	 * symbol by its seconds. Per the product rule, if the DE1 advances to the
	 * next frame before the segment's set time elapses we treat it as an early
	 * exit on that criterion and draw a small bar in the channel colour.
	 *
	 * Presentational — data contract unchanged: `seconds`, `frame`, `segments`.
	 */
	import { SvelteSet } from 'svelte/reactivity';
	import type { ExitMetric, ProfileSegment } from '$lib/profiles';

	let {
		seconds,
		frame,
		segments
	}: {
		/** Live shot elapsed time, seconds. */
		seconds: number;
		/** Zero-based index of the profile frame the DE1 is executing. */
		frame: number;
		/** The active profile's segments — one row each; `undefined` shows a placeholder. */
		segments?: readonly ProfileSegment[];
	} = $props();

	/** A short tick caption for a segment — its name, trimmed for the row. */
	function tickFor(seg: ProfileSegment, i: number): string {
		return seg.name?.trim() || `Frame ${i + 1}`;
	}

	/** Phosphor icon for a pressure/flow early-exit channel. */
	function exitIcon(metric: ExitMetric): string {
		return metric === 'pressure' ? 'gauge' : 'drop';
	}
	/** Channel colour token for a pressure/flow early-exit. */
	function exitColor(metric: ExitMetric): string {
		return metric === 'pressure' ? 'var(--tel-pressure)' : 'var(--tel-flow)';
	}

	// Track the shot elapsed-time at which the current `frame` became active —
	// the DE1 advances frames on its own schedule (exit conditions end a phase
	// early), so the real phase boundaries are NOT the nominal cumulative
	// `seg.time` sums. `earlyExited` collects the ids of frames that ended short
	// of their set duration, which (per the product rule) means they hit their
	// pressure/flow exit criterion.
	let activeStartedAt = $state(0);
	let prevFrame = $state(-1);
	const earlyExited = new SvelteSet<string>();
	/** Slack before a short frame counts as an early exit, seconds. */
	const EARLY_EXIT_EPS = 0.25;

	$effect(() => {
		// Bail BEFORE reading `seconds` (review #38): reading it first
		// subscribed this effect to the ~25 Hz telemetry signal only to
		// return immediately on all but the rare frame-change ticks.
		if (frame === prevFrame) return;
		const t = Math.max(0, untrack(() => Math.max(0, seconds)));
		// A rewind to an earlier frame means a fresh shot — clear the markers.
		if (frame < prevFrame) earlyExited.clear();
		// The frame we're leaving ended at `t`; if that's short of its set
		// duration, the DE1 hit the segment's early-exit criterion.
		const segs = segments;
		if (segs && prevFrame >= 0 && prevFrame < segs.length) {
			const left = segs[prevFrame];
			if (left.exit && t - activeStartedAt < left.time - EARLY_EXIT_EPS) {
				earlyExited.add(left.id);
			}
		}
		activeStartedAt = t;
		prevFrame = frame;
	});

	type Row = {
		id: string;
		name: string;
		duration: number;
		fillPct: number;
		isActive: boolean;
		isPast: boolean;
		exitMetric: ExitMetric | null;
		exited: boolean;
	};

	/**
	 * One row per segment with its progress fill, past/active flags, and the
	 * pressure/flow exit symbol + early-exit marker. Past frames render full;
	 * the active frame fills from when it became active; future frames empty.
	 */
	const rows = $derived.by<Row[]>(() => {
		if (!segments || segments.length === 0) {
			return [
				{
					id: 'none',
					name: 'No profile',
					duration: 0,
					fillPct: 0,
					isActive: true,
					isPast: false,
					exitMetric: null,
					exited: false
				}
			];
		}
		const t = Math.max(0, seconds);
		const activeIdx = Math.max(0, Math.min(frame, segments.length - 1));
		return segments.map((seg, i) => {
			const span = seg.time;
			const isPast = i < activeIdx;
			const isActive = i === activeIdx;
			let fillPct = 0;
			if (isPast) fillPct = 100;
			else if (isActive && span > 0) fillPct = Math.min(100, ((t - activeStartedAt) / span) * 100);
			return {
				id: seg.id,
				name: tickFor(seg, i),
				duration: span,
				fillPct,
				isActive,
				isPast,
				exitMetric: seg.exit?.metric ?? null,
				exited: earlyExited.has(seg.id)
			};
		});
	});

	/** The active segment's label, for the card head. */
	const activeLabel = $derived(rows.find((r) => r.isActive)?.name ?? 'Ready');

	/**
	 * Overall-progress strip: one flex-grow-proportional pill per segment, no
	 * labels. The `grow` weight ∝ duration (floored so a 0s frame keeps a
	 * sliver), so the gaps live inside the track and the strip never overflows.
	 */
	const overall = $derived.by(() => {
		if (!segments || segments.length === 0) {
			return [{ id: 'none', grow: 1, fillPct: 0, isActive: true, isPast: false }];
		}
		const t = Math.max(0, seconds);
		const activeIdx = Math.max(0, Math.min(frame, segments.length - 1));
		return segments.map((seg, i) => {
			const span = seg.time;
			const isPast = i < activeIdx;
			const isActive = i === activeIdx;
			let fillPct = 0;
			if (isPast) fillPct = 100;
			else if (isActive && span > 0) fillPct = Math.min(100, ((t - activeStartedAt) / span) * 100);
			return { id: seg.id, grow: Math.max(0.0001, span), fillPct, isActive, isPast };
		});
	});

	/** Format a duration in seconds for the row's right column. */
	function formatDuration(s: number): string {
		if (s <= 0) return '—';
		return s < 1 ? `${s.toFixed(1)}s` : `${Math.round(s)}s`;
	}

	// ── Scroll-aware edge fades + active-row auto-centre ──────────────────
	// An edge only fades when a row is actually scrolled past it, so the
	// first/last row stays crisp at the extremes (never a half-clipped row).
	let listEl = $state<HTMLDivElement | null>(null);
	let atTop = $state(true);
	let atBottom = $state(true);
	function updateEdges(): void {
		const c = listEl;
		if (!c) return;
		atTop = c.scrollTop <= 1;
		atBottom = c.scrollTop + c.clientHeight >= c.scrollHeight - 1;
	}
	// Recompute edges after the rows (and thus the scroll height) change.
	$effect(() => {
		void rows;
		requestAnimationFrame(updateEdges);
	});
	// Keep the active row centred within the capped list. `void rows;`
	// makes the frame progression a tracked dependency — without it the
	// effect ran once at mount and the scroll-follow was dead (review #38).
	$effect(() => {
		void rows;
		const c = listEl;
		if (!c) return;
		const el = c.querySelector('.crema-phase-row.is-active') as HTMLElement | null;
		if (!el) {
			c.scrollTo({ top: 0, behavior: 'smooth' });
			return;
		}
		const cRect = c.getBoundingClientRect();
		const eRect = el.getBoundingClientRect();
		const target = c.scrollTop + (eRect.top - cRect.top) - (c.clientHeight - eRect.height) / 2;
		c.scrollTo({ top: Math.max(0, target), behavior: 'smooth' });
	});
</script>

<div class="crema-target crema-phase">
	<div class="crema-phase-head">
		<div class="t-eyebrow">Phase</div>
		<div class="crema-phase-name">{activeLabel}</div>
	</div>

	<!-- Overall progress: one flex-grow-proportional pill per segment.
	     Never overflows — gaps are absorbed inside the track. -->
	<div class="crema-phase-overall" aria-hidden="true">
		{#each overall as seg (seg.id)}
			<span
				class="crema-phase-overall-seg"
				class:is-active={seg.isActive}
				class:is-past={seg.isPast}
				style="flex: {seg.grow} 1 0"
			>
				<span class="crema-phase-overall-fill" style="width:{seg.fillPct}%"></span>
			</span>
		{/each}
	</div>

	<!-- Per-phase list: flex-fills the card, scrolls with edge fades + a hidden
	     scrollbar; the active row auto-centres. -->
	<div
		class="crema-phase-list"
		class:at-top={atTop}
		class:at-bottom={atBottom}
		bind:this={listEl}
		onscroll={updateEdges}
	>
		{#each rows as row (row.id)}
			<div class="crema-phase-row" class:is-active={row.isActive} class:is-past={row.isPast}>
				<span class="crema-phase-row-name">{row.name}</span>
				<span class="crema-phase-row-time">
					{#if row.exitMetric}
						{#if row.exited}
							<span
								class="crema-phase-exitbar"
								style="background:{exitColor(row.exitMetric)}"
								title="Exited early on {row.exitMetric}"
							></span>
						{/if}
						<Icon
							cls={'ph ph-' + exitIcon(row.exitMetric)}
							class={row.exited ? 'is-fired' : undefined}
							style="color:{exitColor(row.exitMetric)}"
							aria-hidden="true"
						 />
					{/if}
					{formatDuration(row.duration)}
				</span>
				<span class="crema-phase-row-bar">
					<span class="crema-phase-row-fill" style="width:{row.fillPct}%"></span>
				</span>
			</div>
		{/each}
	</div>
</div>
