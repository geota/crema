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
	import { ratioLabel, shotFilename, exportStoredShotAsV2Json } from '$lib/history';
	import { getCaptureStore, captureJsonl } from '$lib/capture';
	import { daysOffRoast, roastBand, type Bean, type Roaster } from '$lib/bean';
	import { getSettingsStore, convertWeight, convertTemp, convertPressure } from '$lib/settings';
	import { getProfileStore } from '$lib/profiles';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { downloadBlob } from '$lib/utils/download';
	import { getBeanStore } from '$lib/bean';
	import TagInput from '$lib/components/profiles/TagInput.svelte';
	import StaticShotChart from './StaticShotChart.svelte';
	import BeanPicker from './BeanPicker.svelte';

	let {
		shot,
		onNotesChange,
		onRatingChange,
		onGrinderModelChange,
		onTagsChange,
		onBeanChange
	}: {
		/** The selected stored shot. */
		shot: StoredShot;
		/** Persist edited tasting notes. */
		onNotesChange: (notes: string) => void;
		/** Persist an edited star rating. */
		onRatingChange: (rating: number) => void;
		/**
		 * Persist an edited equipment-level grinder model. `null` clears
		 * the override (cascade re-engages → settings default), a string
		 * sets a shot-specific override.
		 */
		onGrinderModelChange: (grinderModel: string | null) => void;
		/** Persist edited shot-level tags. */
		onTagsChange: (tags: string[]) => void;
		/**
		 * Retroactively (re)bind the shot to a live bean — caller wraps
		 * `HistoryStore.setBeanFromLive` so the shared `snapshotFromBean`
		 * helper handles the live → snapshot mapping. Passing `null`
		 * clears the binding (shot becomes bean-less).
		 */
		onBeanChange: (bean: Bean | null, roaster: Roaster | null) => void;
	} = $props();

	/** Whether the notes block is in edit mode. */
	let editing = $state(false);
	/** The draft notes text while editing. */
	let draft = $state('');
	/** Whether the bean-picker modal is open. */
	let pickerOpen = $state(false);

	const library = getBeanStore();

	/**
	 * Tag suggestions for the inline TagInput — every other shot's
	 * tag, plus every live bean's tag. Mirrors how `/profiles` and
	 * `/beans` populate the same input (library-wide suggestions
	 * minus the current entity's own tags, dedup'd).
	 */
	const tagSuggestions = $derived.by(() => {
		const set = new Set<string>();
		// `library.beans` is the in-memory live list — reading it for a
		// suggestion list is safe (no persistence side effect, no
		// snapshot rewrite).
		for (const b of library.beans) for (const t of b.tags ?? []) set.add(t);
		return [...set].sort();
	});

	/** The currently-bound live bean (if any), for the roaster name etc. */
	const boundLiveBean = $derived(
		shot.bean?.beanId ? library.getBean(shot.bean.beanId) : null
	);
	const boundRoasterName = $derived.by(() => {
		// Snapshot roaster wins (history is content-snapshot-authoritative);
		// fall back to the live row's roaster only when the snapshot string
		// is empty (typical for shots imported with the legacy "roaster · type"
		// label split, which sometimes left roaster blank).
		const fromSnap = shot.bean?.roaster?.trim();
		if (fromSnap) return fromSnap;
		const liveRoasterId = boundLiveBean?.roasterId ?? null;
		if (!liveRoasterId) return null;
		return library.getRoaster(liveRoasterId)?.name.trim() ?? null;
	});

	/** Date + time caption, e.g. `May 18 · 14:36`. */
	const stamp = $derived.by(() => {
		const d = new Date(shot.completedAt);
		const date = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
		const time = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
		return `${date} · ${time}`;
	});

	/** Final (or peak) yield, grams. */
	const yieldOut = $derived(shot.finalWeight ?? shot.peakWeight);

	// Unit-aware metric readouts — driven by the Settings unit prefs (D1).
	const settings = getSettingsStore();
	/** Yield in the chosen weight unit. */
	const yieldM = $derived(convertWeight(yieldOut, settings.current.weightUnit));
	/** Peak weight in the chosen weight unit. */
	const peakWeightM = $derived(convertWeight(shot.peakWeight, settings.current.weightUnit));
	/** Peak pressure in the chosen pressure unit. */
	const peakPressureM = $derived(convertPressure(shot.peakPressure, settings.current.pressureUnit));
	/** Peak temperature in the chosen temperature unit. */
	const peakTempM = $derived(convertTemp(shot.peakTemp, settings.current.tempUnit));

	// ── Grinder model override (#81) ─────────────────────────────────────
	// The shot's own override wins; empty input clears it and the
	// upload-time cascade falls back to the settings default. The
	// placeholder shows the current settings default so the user can see
	// what the upload *would* use today without committing to an override.
	const settingsDefaultGrinder = $derived(settings.current.grinderModel?.trim() ?? '');
	const grinderPlaceholder = $derived(
		settingsDefaultGrinder ? settingsDefaultGrinder : 'No default set'
	);
	function commitGrinderModel(raw: string): void {
		const trimmed = raw.trim();
		const next = trimmed.length > 0 ? trimmed : null;
		// Only persist on real changes so a click-and-blur doesn't churn.
		if ((shot.grinderModel ?? null) === next) return;
		onGrinderModelChange(next);
	}

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
	 * Download this shot in the community v2 `.shot.json` format —
	 * portable across reaprime / Visualizer / de1app, pre-decoded
	 * telemetry, user-readable. The default action on the Download
	 * split-button.
	 */
	function downloadCommunity(): void {
		const json = exportStoredShotAsV2Json(shot);
		const blob = new Blob([json], { type: 'application/json' });
		downloadBlob(shotFilename(shot), blob);
	}

	/**
	 * Download this shot's raw BLE capture (`.jsonl`) — every wire
	 * byte preserved, bit-exact playback via Crema's Replay tool.
	 * Crema-only; right for bug reports + development. Falls back
	 * to community v2 with a console warn if the shot has no
	 * IndexedDB capture (older shots from before the recorder
	 * shipped, or imported shots — neither has the wire bytes).
	 */
	async function downloadReplayCapture(): Promise<void> {
		const entries = await getCaptureStore().get(shot.id);
		if (entries && entries.length > 0) {
			const stamp = shotFilename(shot).replace(/\.shot\.json$/, '');
			const blob = new Blob([captureJsonl(entries)], {
				type: 'application/x-ndjson'
			});
			downloadBlob(`${stamp}.jsonl`, blob);
			return;
		}
		console.warn(
			`No raw capture available for ${shot.id}; falling back to v2 JSON export.`
		);
		downloadCommunity();
	}

	let downloadMenuOpen = $state(false);
	function closeDownloadMenuOnDocClick(): void {
		downloadMenuOpen = false;
	}

	const ctx = getCremaAppContext();

	/**
	 * Reload the profile this shot was pulled against. Looks up the profile
	 * by name in the local store (`StoredShot.profileName` is the only
	 * identifying field we persist on shots) and routes through
	 * `app.uploadProfile`, same as the /profiles → Load on Brew flow. If
	 * the profile has since been deleted or renamed, surface a friendly
	 * message rather than a no-op.
	 */
	function loadOnBrew(): void {
		const app = ctx().app;
		if (!app) {
			alert('App is still loading — try again in a moment.');
			return;
		}
		if (!shot.profileName) {
			alert("This shot wasn't tagged with a profile name; nothing to load.");
			return;
		}
		const store = getProfileStore();
		const profile = store.all.find((p) => p.name === shot.profileName);
		if (!profile) {
			alert(
				`Profile "${shot.profileName}" was renamed or deleted since this shot. Open Profiles to load a current profile manually.`
			);
			return;
		}
		store.setActive(profile.id);
		// `syncActiveProfile` keeps the fingerprint cache in lockstep
		// with the upload — without it, the next Coffee tap on Brew
		// would treat the DE1's loaded bytes as still being whatever
		// was there before, and trigger an unnecessary re-upload.
		void app.syncActiveProfile(profile, {});
	}

	// Save-as-profile needs a curve-to-profile deriver (not the same as the
	// v2 profile importer); deferred.
	function saveAsProfile(): void {
		alert('Save-as-profile is coming in a later step.');
	}
	// Visualizer sharing is wired via Settings → Sharing — shots upload
	// automatically when sync is on, and a manual "Sync now" force-pushes
	// the queue. This button just nudges the user there.
	function share(): void {
		alert('Open Settings → Sharing to sign in to Visualizer and sync shots. Synced shots are visible at visualizer.coffee/shots.');
	}

	// ── Bean rebind (retroactive) ────────────────────────────────────────
	function openPicker(): void {
		pickerOpen = true;
	}
	function closePicker(): void {
		pickerOpen = false;
	}
	function pickBean(bean: Bean, roaster: Roaster | null): void {
		onBeanChange(bean, roaster);
		pickerOpen = false;
	}
	function clearBean(): void {
		onBeanChange(null, null);
		pickerOpen = false;
	}
</script>

<svelte:window onclick={closeDownloadMenuOnDocClick} />

<div class="hi-detail">
	<div class="hi-detail-head">
		<div>
			<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">{stamp}</div>
			<div class="hi-detail-title">{shot.profileName ?? 'Untitled shot'}</div>
			<div class="hi-detail-sub">
				{(shot.duration / 1000).toFixed(0)} s
				{#if yieldOut != null}· {yieldM.value} {yieldM.unit} · {ratioLabel(shot)}{/if}
			</div>
			{#if beanLine}
				<div class="hi-detail-sub hi-detail-bean">{beanLine}</div>
			{/if}
		</div>
		<div class="hi-detail-actions">
			<!-- svelte-ignore a11y_click_events_have_key_events -->
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<div class="hi-split" onclick={(e) => e.stopPropagation()}>
				<button
					class="st-btn st-btn-secondary hi-split-main"
					onclick={downloadCommunity}
					title="Download as community v2 .shot.json — re-importable to Crema, reaprime, Visualizer or de1app"
				>
					<i class="ph ph-download-simple" aria-hidden="true"></i> Download
				</button>
				<button
					class="st-btn st-btn-secondary hi-split-caret-btn"
					onclick={(e) => {
						e.stopPropagation();
						downloadMenuOpen = !downloadMenuOpen;
					}}
					aria-haspopup="menu"
					aria-expanded={downloadMenuOpen}
					aria-label="Choose download format"
				>
					<i class="ph ph-caret-down" aria-hidden="true"></i>
				</button>
				{#if downloadMenuOpen}
					<div class="hi-split-menu" role="menu">
						<div class="hi-split-menu-head">Download as</div>
						<button
							class="hi-split-menu-item"
							role="menuitem"
							onclick={() => {
								downloadMenuOpen = false;
								downloadCommunity();
							}}
						>
							<i class="ph-duotone ph-file-text" aria-hidden="true"></i>
							<div class="hi-split-menu-text">
								<div class="hi-split-menu-title">Community v2 (.shot.json)</div>
								<div class="hi-split-menu-sub">
									Portable across reaprime / Visualizer / de1app.
									Pre-decoded telemetry, user-readable.
								</div>
							</div>
						</button>
						<button
							class="hi-split-menu-item"
							role="menuitem"
							onclick={() => {
								downloadMenuOpen = false;
								void downloadReplayCapture();
							}}
						>
							<i class="ph-duotone ph-file-code" aria-hidden="true"></i>
							<div class="hi-split-menu-text">
								<div class="hi-split-menu-title">Replayable capture (.jsonl)</div>
								<div class="hi-split-menu-sub">
									Raw BLE bytes — bit-exact playback via Replay. Crema-only;
									right for bug reports.
								</div>
							</div>
						</button>
					</div>
				{/if}
			</div>
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
		<!--
			Legend grouped into 4 channel families — mirrors the brew
			dashboard's Quick Controls "Chart" toggle layout. Each group
			carries the channel's icon and labels both the primary trace
			(solid) and the paired secondary trace (also solid, in the
			lighter sibling color). The static chart plots every series
			whose data is non-null, so the legend names them all.
		-->
		<div class="hi-chart-legend">
			<span class="hi-leg-group">
				<i class="ph ph-gauge hi-leg-icon" aria-hidden="true"></i>
				<span class="hi-leg-item"><i class="hi-leg" style="background:var(--tel-pressure)"></i>Pressure</span>
				<span class="hi-leg-item"><i class="hi-leg" style="background:var(--tel-pressure-2)"></i>Resistance</span>
			</span>
			<span class="hi-leg-rule" aria-hidden="true"></span>
			<span class="hi-leg-group">
				<i class="ph ph-drop hi-leg-icon" aria-hidden="true"></i>
				<span class="hi-leg-item"><i class="hi-leg" style="background:var(--tel-flow)"></i>Flow</span>
				<span class="hi-leg-item"><i class="hi-leg" style="background:var(--tel-flow-2)"></i>Water</span>
			</span>
			<span class="hi-leg-rule" aria-hidden="true"></span>
			<span class="hi-leg-group">
				<i class="ph ph-thermometer hi-leg-icon" aria-hidden="true"></i>
				<span class="hi-leg-item"><i class="hi-leg" style="background:var(--tel-temp)"></i>Coffee</span>
				<span class="hi-leg-item"><i class="hi-leg" style="background:var(--tel-temp-2)"></i>Water</span>
			</span>
			<span class="hi-leg-rule" aria-hidden="true"></span>
			<span class="hi-leg-group">
				<i class="ph ph-scales hi-leg-icon" aria-hidden="true"></i>
				<span class="hi-leg-item"><i class="hi-leg" style="background:var(--tel-weight)"></i>Weight</span>
				<span class="hi-leg-item"><i class="hi-leg" style="background:var(--tel-weight-2)"></i>Flow</span>
			</span>
		</div>
	</div>

	<!-- Metric strip -->
	<div class="hi-metrics">
		<div class="hi-metric">
			<div class="hi-metric-l">Time</div>
			<div class="hi-metric-v">{(shot.duration / 1000).toFixed(0)}<em>s</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Peak pressure</div>
			<div class="hi-metric-v">{peakPressureM.value}<em>{peakPressureM.unit}</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Peak temp</div>
			<div class="hi-metric-v">{peakTempM.value}<em>{peakTempM.unit}</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Peak wt</div>
			<div class="hi-metric-v">{peakWeightM.value}<em>{peakWeightM.unit}</em></div>
		</div>
		<div class="hi-metric">
			<div class="hi-metric-l">Yield</div>
			<div class="hi-metric-v">{yieldM.value}<em>{yieldM.unit}</em></div>
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

	<!-- Bean — the bound bag snapshot + roaster, with a button to rebind
	     retroactively. Shows the snapshot (so a later rename of the live
	     bag can't rewrite history). "Change bean" opens the BeanPicker;
	     "Assign bean" appears when no bean is bound. -->
	<div class="hi-bean">
		<div class="hi-bean-head">
			<span class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Bean</span>
			<button class="hi-bean-action" onclick={openPicker}>
				<i class="ph ph-pencil-simple" aria-hidden="true"></i>
				{shot.bean ? 'Change bean' : 'Assign bean'}
			</button>
		</div>
		{#if shot.bean}
			<div class="hi-bean-body">
				<div class="hi-bean-line">
					{#if boundRoasterName}
						<span class="hi-bean-roaster">{boundRoasterName}</span>
						<span class="hi-bean-sep">·</span>
					{/if}
					<span class="hi-bean-name">{shot.bean.type || 'Untitled bean'}</span>
				</div>
				<div class="hi-bean-meta">
					{#if shot.bean.roastedOn}
						<span>Roasted {shot.bean.roastedOn}</span>
					{/if}
					{#if roastBand(shot.bean.roastLevel)}
						{#if shot.bean.roastedOn}<span class="hi-bean-sep">·</span>{/if}
						<span class="hi-bean-roast"
							>{roastBand(shot.bean.roastLevel)?.[0].toUpperCase()}{roastBand(
								shot.bean.roastLevel
							)?.slice(1)} roast</span
						>
					{/if}
				</div>
			</div>
		{:else}
			<div class="hi-bean-empty">No bean assigned to this shot.</div>
		{/if}
	</div>

	<!-- Shot-level tags — free-form, edited via the canonical TagInput.
	     Wires straight to `HistoryStore.setTags`; the upload-time
	     `resolveTagList` reads `shot.tags` and rides as Visualizer's
	     `tag_list` on the next PATCH. -->
	<div class="hi-tags">
		<span class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Tags</span>
		<TagInput
			tags={shot.tags ?? []}
			suggestions={tagSuggestions}
			onChange={onTagsChange}
		/>
	</div>

	<!-- Grinder model — equipment-level override of the settings default
	     (`prefs.grinderModel`). Empty saves as `null` so the upload-time
	     cascade re-engages. The placeholder shows the current default so
	     the user knows what the upload *would* use today. -->
	<div class="hi-grinder">
		<span class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Grinder model</span>
		<input
			class="hi-grinder-input"
			type="text"
			placeholder={grinderPlaceholder}
			value={shot.grinderModel ?? ''}
			onchange={(e) => commitGrinderModel((e.currentTarget as HTMLInputElement).value)}
			onblur={(e) => commitGrinderModel((e.currentTarget as HTMLInputElement).value)}
		/>
		<span class="hi-grinder-hint">
			{shot.grinderModel
				? 'Per-shot override.'
				: settingsDefaultGrinder
					? `Using settings default (“${settingsDefaultGrinder}”). Type to override.`
					: 'Override the settings default. Free text.'}
		</span>
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

{#if pickerOpen}
	<BeanPicker
		currentBeanId={shot.bean?.beanId ?? null}
		onPick={pickBean}
		onClear={clearBean}
		onClose={closePicker}
	/>
{/if}

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

	/* Download split-button — primary + caret pair the user can click
	   for a quick default or a format menu. Mirrors `/beans`'s
	   `bn-split-*`; renamed to hi-split for scope-safety. */
	.hi-split {
		position: relative;
		display: inline-flex;
	}
	.hi-split-main {
		border-radius: var(--radius-pill) 0 0 var(--radius-pill);
	}
	.hi-split-caret-btn {
		border-radius: 0 var(--radius-pill) var(--radius-pill) 0;
		padding-left: 8px;
		padding-right: 10px;
		margin-left: -1px;
	}
	.hi-split-menu {
		position: absolute;
		top: calc(100% + 6px);
		right: 0;
		min-width: 320px;
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-md, 10px);
		padding: 6px;
		z-index: 60;
		box-shadow: 0 8px 28px rgba(0, 0, 0, 0.32);
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.hi-split-menu-head {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
		padding: 6px 10px 4px;
	}
	.hi-split-menu-item {
		display: flex;
		gap: 12px;
		align-items: flex-start;
		background: transparent;
		border: 0;
		color: var(--fg-1);
		text-align: left;
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 10px 12px;
		border-radius: var(--radius-sm);
		cursor: pointer;
	}
	.hi-split-menu-item:hover {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.hi-split-menu-item i {
		font-size: 18px;
		color: var(--copper-400);
		flex-shrink: 0;
		margin-top: 1px;
	}
	.hi-split-menu-text {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.hi-split-menu-title {
		font-weight: 600;
	}
	.hi-split-menu-sub {
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.6);
		line-height: 1.4;
	}

	.hi-chart {
		background: var(--bg-page);
		border-radius: var(--radius-md);
		padding: 14px 14px 10px;
		position: relative;
	}
	.hi-chart-legend {
		display: flex;
		flex-wrap: wrap;
		gap: 8px 14px;
		padding-top: 8px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.65);
	}
	.hi-leg-group {
		display: inline-flex;
		align-items: center;
		gap: 10px;
	}
	.hi-leg-icon {
		font-size: 14px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.hi-leg-item {
		display: inline-flex;
		align-items: center;
		gap: 5px;
	}
	.hi-leg-rule {
		display: inline-block;
		width: 1px;
		height: 14px;
		background: rgba(var(--tint-rgb), 0.18);
		align-self: center;
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

	/* Bean block — snapshot summary plus the Change/Assign button. */
	.hi-bean {
		background: var(--bg-page);
		border-radius: var(--radius-md);
		padding: 12px 14px;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.hi-bean-head {
		display: flex;
		justify-content: space-between;
		align-items: center;
	}
	.hi-bean-action {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		background: transparent;
		border: 0;
		color: var(--copper-400);
		font-family: var(--font-sans);
		font-size: 11px;
		cursor: pointer;
		padding: 2px 0;
	}
	.hi-bean-action:hover {
		color: var(--copper-300);
		text-decoration: underline;
	}
	.hi-bean-body {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.hi-bean-line {
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
	}
	.hi-bean-roaster {
		color: rgba(var(--tint-rgb), 0.7);
	}
	.hi-bean-name {
		color: var(--fg-1);
		font-weight: 500;
	}
	.hi-bean-sep {
		color: rgba(var(--tint-rgb), 0.3);
		margin: 0 4px;
	}
	.hi-bean-meta {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.hi-bean-roast {
		text-transform: capitalize;
	}
	.hi-bean-empty {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.45);
		font-style: italic;
	}

	/* Shot-level tags block — wraps the canonical TagInput. */
	.hi-tags {
		display: flex;
		flex-direction: column;
		gap: 8px;
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
	/* Grinder-model override — same surface as the rating row, with
	   the text input sized so a "Eureka Mignon Specialita"-class string
	   fits comfortably. The hint is the cascade explainer. */
	.hi-grinder {
		display: flex;
		align-items: center;
		gap: 14px;
		flex-wrap: wrap;
	}
	.hi-grinder-input {
		flex: 1 1 220px;
		min-width: 220px;
		max-width: 360px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 6px 10px;
		outline: 0;
	}
	.hi-grinder-input:focus {
		border-color: rgba(var(--tint-rgb), 0.4);
	}
	.hi-grinder-input::placeholder {
		color: rgba(var(--tint-rgb), 0.35);
		font-style: italic;
	}
	.hi-grinder-hint {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.45);
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
