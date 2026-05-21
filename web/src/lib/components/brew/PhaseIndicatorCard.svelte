<script lang="ts">
	/**
	 * `PhaseIndicatorCard` — the left-column "where in the shot are we" card.
	 *
	 * Renders one row per profile segment, stacked vertically; each row shows
	 * the segment name (horizontal), its duration, and a slim progress bar.
	 * This scales to many-phase profiles (8+) gracefully where the older
	 * horizontal-pill layout got cramped — each row carries its label inline
	 * instead of stuffing it under a too-narrow segment.
	 *
	 * The DE1 reports which frame it is executing via `Event::ShotFrameChanged`,
	 * folded into `UiSnapshot.shotFrame`; that index drives the active row,
	 * and the live shot elapsed time fills the active row's bar.
	 *
	 * Falls back to a single "No profile" row when no profile is active.
	 *
	 * Only mounted on the resting Brew dashboard (Quick Sheet closed).
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
		 * The active profile's segments — one row each. `undefined` when no
		 * profile is active, in which case a neutral placeholder row shows.
		 */
		segments?: readonly ProfileSegment[];
	} = $props();

	/** A short tick caption for a segment — its name, trimmed for the row. */
	function tickFor(seg: ProfileSegment, i: number): string {
		return seg.name?.trim() || `Frame ${i + 1}`;
	}

	/**
	 * One row per segment with its progress fill and past/active flags. The
	 * active segment is the one the DE1 reports via `frame`; its fill follows
	 * the elapsed time within the segment's own time window.
	 */
	const rows = $derived.by(() => {
		if (!segments || segments.length === 0) {
			return [
				{
					id: 'none',
					name: 'No profile',
					duration: 0,
					fillPct: 0,
					isActive: true,
					isPast: false
				}
			];
		}
		const t = Math.max(0, seconds);
		const activeIdx = Math.max(0, Math.min(frame, segments.length - 1));
		let start = 0;
		return segments.map((seg, i) => {
			const span = seg.time;
			const within = Math.max(0, Math.min(t, start + span) - start);
			const row = {
				id: seg.id,
				name: tickFor(seg, i),
				duration: span,
				fillPct: span > 0 ? Math.min(100, (within / span) * 100) : 0,
				isActive: i === activeIdx,
				isPast: i < activeIdx
			};
			start += span;
			return row;
		});
	});

	/** The active segment's label, for the card head. */
	const activeLabel = $derived(rows.find((r) => r.isActive)?.name ?? 'Ready');

	/**
	 * Overall-progress strip above the per-phase list: one width-proportional
	 * pill per segment, no labels. Reuses the same fill / active / past
	 * state as the per-row bars; widths are proportional to segment time so
	 * a glance gives a "where in the shot am I" sense even when the list
	 * below is tall.
	 */
	const totalSec = $derived(
		segments && segments.length > 0 ? totalTime(segments) : 1
	);
	const overall = $derived.by(() => {
		if (!segments || segments.length === 0) {
			return [{ id: 'none', widthPct: 100, fillPct: 0, isActive: true, isPast: false }];
		}
		const t = Math.max(0, seconds);
		const activeIdx = Math.max(0, Math.min(frame, segments.length - 1));
		let start = 0;
		return segments.map((seg, i) => {
			const span = seg.time;
			const within = Math.max(0, Math.min(t, start + span) - start);
			const seg2 = {
				id: seg.id,
				widthPct: totalSec > 0 ? (span / totalSec) * 100 : 0,
				fillPct: span > 0 ? Math.min(100, (within / span) * 100) : 0,
				isActive: i === activeIdx,
				isPast: i < activeIdx
			};
			start += span;
			return seg2;
		});
	});

	/** Format a duration in seconds for the row's right column. */
	function formatDuration(s: number): string {
		if (s <= 0) return '—';
		return s < 1 ? `${s.toFixed(1)}s` : `${Math.round(s)}s`;
	}
</script>

<div class="crema-target crema-phase">
	<div class="crema-phase-head">
		<div class="t-eyebrow">Phase</div>
		<div class="crema-phase-name">{activeLabel}</div>
	</div>
	<!-- Overall progress: one width-proportional pill per segment, no labels.
	     Gives the at-a-glance "where am I in the shot" without crowding the
	     vertical list below. -->
	<div class="crema-phase-overall" aria-hidden="true">
		{#each overall as seg (seg.id)}
			<span
				class="crema-phase-overall-seg"
				class:is-active={seg.isActive}
				class:is-past={seg.isPast}
				style="flex: 0 0 {seg.widthPct}%"
			>
				<span class="crema-phase-overall-fill" style="width:{seg.fillPct}%"></span>
			</span>
		{/each}
	</div>
	<div class="crema-phase-list">
		{#each rows as row (row.id)}
			<div class="crema-phase-row" class:is-active={row.isActive} class:is-past={row.isPast}>
				<span class="crema-phase-row-name">{row.name}</span>
				<span class="crema-phase-row-time">{formatDuration(row.duration)}</span>
				<span class="crema-phase-row-bar">
					<span class="crema-phase-row-fill" style="width:{row.fillPct}%"></span>
				</span>
			</div>
		{/each}
	</div>
</div>
