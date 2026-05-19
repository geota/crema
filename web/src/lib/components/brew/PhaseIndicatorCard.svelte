<script lang="ts">
	/**
	 * `PhaseIndicatorCard` — the left-column "where in the shot are we" card,
	 * ported from `PhaseIndicatorCard` in `web-dashboard-v2.jsx`. Reuses the
	 * `.crema-phase*` classes from `web-kit.css`.
	 *
	 * The track is the **active profile's frames**: one segment per profile
	 * segment, its width proportional to the segment's duration. The DE1
	 * reports which frame it is executing via `Event::ShotFrameChanged`, folded
	 * into `UiSnapshot.shotFrame`; that index drives the active segment, and the
	 * live shot elapsed time fills the active segment's progress bar.
	 *
	 * Falls back to a neutral single-segment track when no profile is active.
	 *
	 * Only mounted on the resting Brew dashboard (Quick Sheet closed) — when the
	 * sheet is open it would overlap the docked panel.
	 */
	import { totalTime, type ProfileSegment } from '$lib/profiles';

	let {
		seconds,
		frame,
		segments
	}: {
		/** Live shot elapsed time, seconds. */
		seconds: number;
		/** Zero-based index of the profile frame the DE1 is executing. */
		frame: number;
		/**
		 * The active profile's segments — one track segment each. `undefined`
		 * when no profile is active, in which case a neutral placeholder shows.
		 */
		segments?: readonly ProfileSegment[];
	} = $props();

	/** The shot's total time across all segments, seconds — the track's span. */
	const total = $derived(segments && segments.length > 0 ? totalTime(segments) : 1);

	/** A short tick caption for a segment — its name, trimmed for the strip. */
	function tickFor(seg: ProfileSegment, i: number): string {
		return seg.name?.trim() || `Frame ${i + 1}`;
	}

	/**
	 * Track-segment geometry — start time, width %, fill % and past/active
	 * flags. The active segment is the one the DE1 reports via `frame`; its
	 * fill follows the elapsed time within the segment's own time window.
	 */
	const tracks = $derived.by(() => {
		if (!segments || segments.length === 0) {
			return [
				{ id: 'none', tick: 'No profile', widthPct: 100, fillPct: 0, isActive: true, isPast: false }
			];
		}
		const t = Math.max(0, seconds);
		// Clamp the reported frame into the segment range.
		const activeIdx = Math.max(0, Math.min(frame, segments.length - 1));
		let start = 0;
		return segments.map((seg, i) => {
			const span = seg.time;
			const within = Math.max(0, Math.min(t, start + span) - start);
			const out = {
				id: seg.id,
				tick: tickFor(seg, i),
				widthPct: total > 0 ? (span / total) * 100 : 0,
				fillPct: span > 0 ? Math.min(100, (within / span) * 100) : 0,
				isActive: i === activeIdx,
				isPast: i < activeIdx
			};
			start += span;
			return out;
		});
	});

	/** The active segment's label, for the card head. */
	const activeLabel = $derived(tracks.find((s) => s.isActive)?.tick ?? 'Ready');
</script>

<div class="crema-target crema-phase">
	<div class="crema-phase-head">
		<div class="t-eyebrow">Phase</div>
		<div class="crema-phase-name">{activeLabel}</div>
	</div>
	<div class="crema-phase-track">
		{#each tracks as seg (seg.id)}
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
		{#each tracks as seg (seg.id)}
			<span class="crema-phase-tick" class:is-active={seg.isActive}>{seg.tick}</span>
		{/each}
	</div>
</div>
