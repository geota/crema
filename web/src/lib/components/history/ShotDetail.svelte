<script lang="ts">
	/**
	 * `ShotDetail` — the History page's right pane, ported from `ShotDetail` in
	 * `history-page.jsx`. Header, the stored-curve chart, a metric strip and an
	 * editable tasting-notes / star-rating block.
	 *
	 * The curve chart is the **real** stored telemetry, redrawn by
	 * {@link StaticShotChart}. Tasting notes and the star rating are editable
	 * and persisted to the `lib/history` store. Load-on-Brew / Save-as-profile /
	 * Share are stubs (`// TODO`).
	 */
	import type { ShotRecord } from '$lib/history';
	import { ratioLabel } from '$lib/history';
	import StaticShotChart from './StaticShotChart.svelte';

	let {
		shot,
		onNotesChange,
		onRatingChange
	}: {
		/** The selected shot record. */
		shot: ShotRecord;
		/** Persist edited tasting notes. */
		onNotesChange: (notes: string) => void;
		/** Persist an edited star rating. */
		onRatingChange: (rating: number) => void;
	} = $props();

	/** Whether the notes block is in edit mode. */
	let editing = $state(false);
	/** The draft notes text while editing. */
	let draft = $state('');

	/** Date + time caption, e.g. `May 18 · 14:36`. */
	const stamp = $derived.by(() => {
		const d = new Date(shot.completedAt);
		const date = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
		const time = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
		return `${date} · ${time}`;
	});

	/** Final (or peak) yield, grams. */
	const yieldG = $derived(shot.finalWeightG ?? shot.peakWeightG);

	/** Open the notes editor, seeding the draft from the current notes. */
	function startEdit(): void {
		draft = shot.notes;
		editing = true;
	}
	/** Save the draft notes and leave edit mode. */
	function saveNotes(): void {
		onNotesChange(draft);
		editing = false;
	}

	/** Set the star rating (clicking the same star again clears it). */
	function setStar(n: number): void {
		onRatingChange(shot.rating === n ? 0 : n);
	}

	// TODO: Load-on-Brew / Save-as-profile / Share-to-Visualizer have no core
	// backing yet — the core exposes no profile-upload path and there is no
	// Visualizer integration. Stubbed.
	function loadOnBrew(): void {
		// TODO: wire to DE1 profile upload when the core exposes it.
		alert('Loading a shot on Brew is coming in a later step.');
	}
	function saveAsProfile(): void {
		// TODO: derive a CremaProfile from the stored curve and save it.
		alert('Save-as-profile is coming in a later step.');
	}
	function share(): void {
		// TODO: wire Visualizer upload when the integration lands.
		alert('Visualizer sharing is coming in a later step.');
	}
</script>

<div class="hi-detail">
	<div class="hi-detail-head">
		<div>
			<div class="t-eyebrow" style="color:rgba(244,237,224,0.55)">{stamp}</div>
			<div class="hi-detail-title">{shot.profileName ?? 'Untitled shot'}</div>
			<div class="hi-detail-sub">
				{(shot.durationMs / 1000).toFixed(0)} s
				{#if yieldG != null}· {yieldG.toFixed(1)} g · {ratioLabel(shot)}{/if}
			</div>
		</div>
		<div class="hi-detail-actions">
			<button class="st-btn st-btn-secondary" onclick={loadOnBrew}>
				<i class="ph ph-coffee" aria-hidden="true"></i> Load on Brew
			</button>
			<button class="st-btn st-btn-secondary" onclick={saveAsProfile}>
				<i class="ph ph-bookmark-simple" aria-hidden="true"></i> Save as profile
			</button>
			<button class="st-btn st-btn-secondary" onclick={share}>
				<i class="ph ph-share" aria-hidden="true"></i> Share
			</button>
		</div>
	</div>

	<!-- Chart -->
	<div class="hi-chart">
		<StaticShotChart series={shot.series} height={380} />
		<div class="hi-chart-legend">
			<span><i class="hi-leg" style="background:var(--tel-pressure)"></i>Pressure</span>
			<span><i class="hi-leg" style="background:var(--tel-flow)"></i>Flow</span>
			<span><i class="hi-leg" style="background:var(--tel-temp)"></i>Temp</span>
			<span><i class="hi-leg" style="background:var(--tel-weight)"></i>Weight</span>
		</div>
	</div>

	<!-- Metric strip -->
	<div class="hi-metrics">
		<div class="hi-metric">
			<div class="hi-metric-l">Time</div>
			<div class="hi-metric-v">{(shot.durationMs / 1000).toFixed(0)}<em>s</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Peak bar</div>
			<div class="hi-metric-v">{shot.peakPressure.toFixed(1)}<em>bar</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Peak temp</div>
			<div class="hi-metric-v">{shot.peakTemp.toFixed(1)}<em>°C</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Peak wt</div>
			<div class="hi-metric-v">
				{shot.peakWeightG != null ? shot.peakWeightG.toFixed(1) : '–'}<em>g</em>
			</div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Yield</div>
			<div class="hi-metric-v">{yieldG != null ? yieldG.toFixed(1) : '–'}<em>g</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Ratio</div>
			<div class="hi-metric-v">{ratioLabel(shot)}</div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Samples</div>
			<div class="hi-metric-v">{shot.series.length}</div>
		</div>
	</div>

	<!-- Rating -->
	<div class="hi-rating">
		<span class="t-eyebrow" style="color:rgba(244,237,224,0.55)">Rating</span>
		<div class="hi-stars">
			{#each [1, 2, 3, 4, 5] as n (n)}
				<button
					class="hi-star"
					class:is-on={n <= shot.rating}
					onclick={() => setStar(n)}
					aria-label="{n} star{n === 1 ? '' : 's'}"
				>
					<i class={n <= shot.rating ? 'ph-fill ph-star' : 'ph ph-star'} aria-hidden="true"
					></i>
				</button>
			{/each}
		</div>
	</div>

	<!-- Notes -->
	<div class="hi-notes">
		<div class="hi-notes-head">
			<span class="t-eyebrow" style="color:rgba(244,237,224,0.55)">Tasting notes</span>
			{#if editing}
				<button class="hi-notes-edit" onclick={saveNotes}>
					<i class="ph ph-check" aria-hidden="true"></i> Save
				</button>
			{:else}
				<button class="hi-notes-edit" onclick={startEdit}>
					<i class="ph ph-pencil-simple" aria-hidden="true"></i> Edit
				</button>
			{/if}
		</div>
		{#if editing}
			<textarea
				class="hi-notes-input"
				bind:value={draft}
				placeholder="How did this shot taste?"
				rows="3"
			></textarea>
		{:else}
			<div class="hi-notes-body" class:is-empty={!shot.notes}>
				{shot.notes || 'No notes for this shot yet.'}
			</div>
		{/if}
	</div>
</div>

<style>
	.hi-detail {
		background: var(--espresso-900);
		border: 1px solid rgba(244, 237, 224, 0.05);
		border-radius: var(--radius-lg, 14px);
		padding: 20px 24px 24px;
		display: flex;
		flex-direction: column;
		gap: 18px;
		align-self: start;
	}
	.hi-detail-head {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		gap: 16px;
		padding-bottom: 16px;
		border-bottom: 1px solid rgba(244, 237, 224, 0.05);
	}
	.hi-detail-title {
		font-family: var(--font-serif);
		font-size: 24px;
		letter-spacing: -0.01em;
		color: var(--ink-50);
		margin-top: 2px;
	}
	.hi-detail-sub {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(244, 237, 224, 0.55);
		margin-top: 4px;
	}
	.hi-detail-actions {
		display: flex;
		gap: 8px;
		flex-shrink: 0;
		flex-wrap: wrap;
		justify-content: flex-end;
	}

	/* st-btn ported from the design's settings kit. */
	.st-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 7px 12px;
		border-radius: var(--radius-sm);
		font-family: var(--font-sans);
		font-size: 12px;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
		border: 1px solid transparent;
	}
	.st-btn i {
		font-size: 13px;
	}
	.st-btn-secondary {
		background: rgba(244, 237, 224, 0.04);
		border-color: rgba(244, 237, 224, 0.1);
		color: var(--ink-50);
	}
	.st-btn-secondary:hover {
		background: rgba(244, 237, 224, 0.08);
	}

	.hi-chart {
		background: var(--espresso-950);
		border-radius: var(--radius-md);
		padding: 14px 14px 10px;
		position: relative;
	}
	.hi-chart-legend {
		display: flex;
		gap: 14px;
		padding-top: 8px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(244, 237, 224, 0.65);
	}
	.hi-chart-legend > span {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.hi-leg {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		display: inline-block;
	}

	.hi-metrics {
		display: grid;
		grid-template-columns: repeat(7, minmax(0, 1fr));
		gap: 6px;
	}
	.hi-metric {
		display: flex;
		flex-direction: column;
		gap: 4px;
		padding: 10px 12px;
		background: var(--espresso-950);
		border-radius: var(--radius-sm);
	}
	.hi-metric-l {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 700;
		color: rgba(244, 237, 224, 0.42);
	}
	.hi-metric-v {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 15px;
		color: var(--ink-50);
	}
	.hi-metric-v em {
		font-style: normal;
		font-size: 10px;
		color: rgba(244, 237, 224, 0.5);
		margin-left: 2px;
	}

	.hi-rating {
		display: flex;
		align-items: center;
		gap: 14px;
	}
	.hi-stars {
		display: flex;
		gap: 2px;
	}
	.hi-star {
		background: transparent;
		border: 0;
		padding: 2px;
		cursor: pointer;
		color: rgba(244, 237, 224, 0.3);
		font-size: 18px;
		line-height: 1;
		transition: color var(--dur-1) var(--ease);
	}
	.hi-star:hover {
		color: var(--copper-300);
	}
	.hi-star.is-on {
		color: var(--copper-400);
	}

	.hi-notes {
		background: var(--espresso-950);
		border-radius: var(--radius-md);
		padding: 14px 16px;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.hi-notes-head {
		display: flex;
		justify-content: space-between;
		align-items: center;
	}
	.hi-notes-edit {
		background: transparent;
		border: 0;
		color: rgba(244, 237, 224, 0.55);
		font-family: var(--font-sans);
		font-size: 11px;
		cursor: pointer;
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.hi-notes-edit:hover {
		color: var(--ink-50);
	}
	.hi-notes-body {
		font-family: var(--font-sans);
		font-size: 13px;
		line-height: 1.55;
		color: var(--ink-50);
	}
	.hi-notes-body.is-empty {
		color: rgba(244, 237, 224, 0.4);
		font-style: italic;
	}
	.hi-notes-input {
		width: 100%;
		background: rgba(244, 237, 224, 0.04);
		border: 1px solid rgba(244, 237, 224, 0.12);
		border-radius: var(--radius-sm);
		color: var(--ink-50);
		font-family: var(--font-sans);
		font-size: 13px;
		line-height: 1.55;
		padding: 8px 10px;
		resize: vertical;
		outline: 0;
	}
	.hi-notes-input:focus {
		border-color: rgba(244, 237, 224, 0.25);
	}

	@media (max-width: 1100px) {
		.hi-metrics {
			grid-template-columns: repeat(4, minmax(0, 1fr));
		}
	}
</style>
