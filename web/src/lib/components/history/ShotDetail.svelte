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
	import type { StoredShot } from '$lib/history';
	import { ratioLabel, shotFilename } from '$lib/history';
	import { getCaptureStore, captureJsonl } from '$lib/capture';
	import { daysOffRoast, roastBand } from '$lib/bean';
	import { getSettingsStore, convertWeight, convertTemp, convertPressure } from '$lib/settings';
	import StaticShotChart from './StaticShotChart.svelte';

	let {
		shot,
		onNotesChange,
		onRatingChange
	}: {
		/** The selected stored shot. */
		shot: StoredShot;
		/** Persist edited tasting notes. */
		onNotesChange: (notes: string) => void;
		/** Persist an edited star rating. */
		onRatingChange: (rating: number) => void;
	} = $props();

	/** Whether the notes block is in edit mode. */
	let editing = $state(false);
	/** The draft notes text while editing. */
	let draft = $state('');
	/**
	 * Whether a raw-BLE capture is stored for the selected shot — gates the
	 * Download button. The capture store is IndexedDB-async; the effect below
	 * checks existence whenever the selected shot changes and writes the
	 * result here.
	 */
	let captureAvailable = $state(false);
	$effect(() => {
		// Capture the id at effect start so a stale promise can't overwrite a
		// later shot's flag (rapid history-row clicks).
		const id = shot.id;
		captureAvailable = false;
		void getCaptureStore()
			.has(id)
			.then((has) => {
				if (id === shot.id) captureAvailable = has;
			})
			.catch(() => {
				if (id === shot.id) captureAvailable = false;
			});
	});

	/** Date + time caption, e.g. `May 18 · 14:36`. */
	const stamp = $derived.by(() => {
		const d = new Date(shot.completedAt);
		const date = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
		const time = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
		return `${date} · ${time}`;
	});

	/** Final (or peak) yield, grams. */
	const yieldG = $derived(shot.finalWeight ?? shot.peakWeight);

	// Unit-aware metric readouts — driven by the Settings unit prefs (D1).
	const settings = getSettingsStore();
	/** Yield in the chosen weight unit. */
	const yieldM = $derived(convertWeight(yieldG, settings.current.weightUnit));
	/** Peak weight in the chosen weight unit. */
	const peakWeightM = $derived(convertWeight(shot.peakWeight, settings.current.weightUnit));
	/** Peak pressure in the chosen pressure unit. */
	const peakPressureM = $derived(convertPressure(shot.peakPressure, settings.current.pressureUnit));
	/** Peak temperature in the chosen temperature unit. */
	const peakTempM = $derived(convertTemp(shot.peakTemp, settings.current.tempUnit));

	/**
	 * The bean caption — the snapshotted bean type (with roaster and roast
	 * band when known) plus its days-off-roast, derived from the shot's own
	 * `completedAt` so it is stable. `null` for a shot recorded with no bean
	 * (including pre-existing records). Tolerant of the pre-`roaster`/`type`
	 * snapshot shape, where `roaster`/`type` may be absent.
	 */
	const beanLine = $derived.by(() => {
		const bean = shot.bean;
		if (!bean) return null;
		const label = bean.type?.trim() || bean.roaster?.trim() || 'Bean';
		const parts: string[] = [];
		if (bean.roaster?.trim() && bean.type?.trim()) {
			parts.push(`${bean.roaster.trim()} · ${bean.type.trim()}`);
		} else {
			parts.push(label);
		}
		const band = roastBand(bean.roastLevel);
		if (band) parts.push(band[0].toUpperCase() + band.slice(1));
		const days = daysOffRoast(bean.roastedOn, shot.completedAt);
		if (days != null) parts.push(`${days}d off roast`);
		return parts.join(' · ');
	});

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

	/**
	 * Download this shot's raw BLE capture as a JSON Lines (`.jsonl`) file —
	 * one `{t, dir, src, hex}` notification per line, the same format the
	 * Advanced → Replay tool consumes. Wired to {@link captureJsonl} on the
	 * IndexedDB-backed `$lib/capture` store. Shots recorded before the capture
	 * recorder existed (or pulled with no BLE link) have no capture; the
	 * button is disabled in that case.
	 */
	async function download(): Promise<void> {
		const entries = await getCaptureStore().get(shot.id);
		if (!entries || entries.length === 0) return;
		const blob = new Blob([captureJsonl(entries)], { type: 'application/x-ndjson' });
		const url = URL.createObjectURL(blob);
		const a = document.createElement('a');
		a.href = url;
		a.download = shotFilename(shot);
		a.click();
		URL.revokeObjectURL(url);
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
			<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">{stamp}</div>
			<div class="hi-detail-title">{shot.profileName ?? 'Untitled shot'}</div>
			<div class="hi-detail-sub">
				{(shot.duration / 1000).toFixed(0)} s
				{#if yieldG != null}· {yieldM.value} {yieldM.unit} · {ratioLabel(shot)}{/if}
			</div>
			{#if beanLine}
				<div class="hi-detail-sub hi-detail-bean">{beanLine}</div>
			{/if}
		</div>
		<div class="hi-detail-actions">
			<button
				class="st-btn st-btn-secondary"
				onclick={download}
				disabled={!captureAvailable}
				title={captureAvailable
					? 'Download a replayable JSONL capture of this shot'
					: 'No replayable capture for this shot — only shots pulled since the capture recorder shipped have one'}
			>
				<i class="ph ph-download-simple" aria-hidden="true"></i> Download
			</button>
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
			<div class="hi-metric-v">{(shot.duration / 1000).toFixed(0)}<em>s</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Peak bar</div>
			<div class="hi-metric-v">{peakPressureM.value}<em>{peakPressureM.unit || 'bar'}</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Peak temp</div>
			<div class="hi-metric-v">{peakTempM.value}<em>{peakTempM.unit || '°C'}</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Peak wt</div>
			<div class="hi-metric-v">{peakWeightM.value}<em>{peakWeightM.unit || 'g'}</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Yield</div>
			<div class="hi-metric-v">{yieldM.value}<em>{yieldM.unit || 'g'}</em></div>
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
		<span class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Rating</span>
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
			<span class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Tasting notes</span>
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
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.05);
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
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.05);
	}
	.hi-detail-title {
		font-family: var(--font-serif);
		font-size: 24px;
		letter-spacing: -0.01em;
		color: var(--fg-1);
		margin-top: 2px;
	}
	.hi-detail-sub {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
		margin-top: 4px;
	}
	.hi-detail-bean {
		margin-top: 2px;
		color: var(--copper-300);
	}
	.hi-detail-actions {
		display: flex;
		gap: 8px;
		flex-shrink: 0;
		flex-wrap: wrap;
		justify-content: flex-end;
	}

	/* .st-btn / .st-btn-secondary come from the global settings kit
	   (styles/settings-page.css) — no scoped re-declaration needed. */

	.hi-chart {
		background: var(--bg-page);
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
		color: rgba(var(--tint-rgb), 0.65);
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
		background: var(--bg-page);
		border-radius: var(--radius-sm);
	}
	.hi-metric-l {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 700;
		color: rgba(var(--tint-rgb), 0.42);
	}
	.hi-metric-v {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 15px;
		color: var(--fg-1);
	}
	.hi-metric-v em {
		font-style: normal;
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.5);
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
		color: rgba(var(--tint-rgb), 0.3);
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
		background: var(--bg-page);
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
		color: rgba(var(--tint-rgb), 0.55);
		font-family: var(--font-sans);
		font-size: 11px;
		cursor: pointer;
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.hi-notes-edit:hover {
		color: var(--fg-1);
	}
	.hi-notes-body {
		font-family: var(--font-sans);
		font-size: 13px;
		line-height: 1.55;
		color: var(--fg-1);
	}
	.hi-notes-body.is-empty {
		color: rgba(var(--tint-rgb), 0.4);
		font-style: italic;
	}
	.hi-notes-input {
		width: 100%;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		line-height: 1.55;
		padding: 8px 10px;
		resize: vertical;
		outline: 0;
	}
	.hi-notes-input:focus {
		border-color: rgba(var(--tint-rgb), 0.25);
	}

	@media (max-width: 1100px) {
		.hi-metrics {
			grid-template-columns: repeat(4, minmax(0, 1fr));
		}
	}
</style>
