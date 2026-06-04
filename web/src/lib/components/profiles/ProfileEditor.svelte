<script lang="ts">
	import ArrowCounterClockwiseIcon from 'phosphor-svelte/lib/ArrowCounterClockwiseIcon';
	import ArrowLeftIcon from 'phosphor-svelte/lib/ArrowLeftIcon';
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import CopyIcon from 'phosphor-svelte/lib/CopyIcon';
	import PlusIcon from 'phosphor-svelte/lib/PlusIcon';
	/**
	 * `ProfileEditor` — the Edit / Create profile page body, ported from
	 * `ProfileEditPage` in `profile-edit-page.jsx`.
	 *
	 * Two columns: the left form (serif name, Bean, Notes, roast chips, the tag
	 * chip-input, the pin toggle, the Targets steppers, the Behaviour toggles)
	 * and the right pane (`ProfileCurveEditor` over the segment list).
	 *
	 * ## Working model
	 *
	 * The editor owns a *draft* `CremaProfile` in local `$state`. All edits —
	 * the form fields, a curve-dot drag, a segment-row input — mutate the draft.
	 * Save persists it via the `lib/profiles` store.
	 *
	 * ## Built-ins are read-only
	 *
	 * A built-in profile cannot be saved over. When the editor opens on a
	 * built-in (or in `duplicate` mode), the draft is a fresh **custom** copy
	 * (`duplicateProfile`); saving creates a new library entry. The top bar
	 * shows this with a "Duplicate" heading.
	 */
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { page } from '$app/state';
	import {
		getProfileStore,
		blankProfile,
		duplicateProfile,
		uid,
		newProfileId,
		ratioLabel,
		totalTime,
		type CremaProfile,
		type ProfileSegment,
		type Roast,
		type BeverageType
	} from '$lib/profiles';
	import ProfileCurveEditor from './ProfileCurveEditor.svelte';
	import SegmentRow from './SegmentRow.svelte';
	import TagInput from './TagInput.svelte';
	import PeNumber from './PeNumber.svelte';
	import { getSettingsStore } from '$lib/settings';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { confirmDialog } from '$lib/components/shared/confirm-dialog.svelte';
	import { ProfileBounds } from '$lib/profiles/bounds';

	const settings = getSettingsStore();

	let {
		mode,
		sourceId
	}: {
		/** `new` for `/profiles/new`; `edit` for `/profiles/[id]/edit`. */
		mode: 'new' | 'edit';
		/** The profile id to edit — only set in `edit` mode. */
		sourceId?: string;
	} = $props();

	const store = getProfileStore();
	void store.ensureLoaded();

	/**
	 * Whether the `?duplicate=1` flag is set — read reactively from the route
	 * URL rather than imperatively off `window.location`.
	 */
	const duplicateFlag = $derived(page.url.searchParams.get('duplicate') === '1');

	/**
	 * Where Back / Save should return to. Defaults to the profile library
	 * (`/profiles`); the Brew page's "Edit" affordance passes `?from=brew`
	 * so back/save return to `/brew` instead of dumping the user in the
	 * library. Future surfaces (Settings → Machine, History detail) can
	 * pass other tokens — extend the switch below as they land.
	 */
	const returnPath = $derived.by<string>(() => {
		const from = page.url.searchParams.get('from');
		switch (from) {
			case 'brew':
				return resolve('/');
			default:
				return resolve('/profiles');
		}
	});

	/**
	 * Whether the editor was opened from the Brew page (Edit button on the
	 * loaded profile). When true, Save makes the saved profile the active
	 * one + syncs to the DE1 — the user is editing what they're brewing
	 * with, so the edits should take effect immediately. When false (came
	 * from the Profiles library), Save just persists; the active profile
	 * is unchanged.
	 */
	const cameFromBrew = $derived(page.url.searchParams.get('from') === 'brew');

	/**
	 * The CremaApp orchestrator — used on save to seed the freshly-forked
	 * custom profile as the active one + sync to the DE1 when the user
	 * edited the loaded profile (which forks because built-ins are
	 * read-only). `null` while the core is loading.
	 */
	const appCtx = getCremaAppContext();
	const app = $derived(appCtx().app);

	/**
	 * The **loaded baseline** — the profile the editor opens on, before any
	 * edits. Derived (not synchronised through `$effect`) so it tracks the
	 * route inputs cleanly:
	 *
	 *  - `new` mode: a fresh blank custom profile.
	 *  - `edit` mode, library not yet loaded: `undefined` (the grid shows a
	 *    loading state).
	 *  - `edit` mode, loaded: the source profile, deep-copied; a built-in or a
	 *    `?duplicate=1` arrival is forked into a fresh custom copy.
	 *  - `edit` mode, unknown id: a blank custom profile (treated as a create).
	 *
	 * Keyed off `store.loaded`, `sourceId` and `duplicateFlag` — change any and
	 * the baseline (and, when not yet edited, the `draft` below) recomputes.
	 */
	const baseline = $derived.by<CremaProfile | undefined>(() => {
		if (mode === 'new') return blankProfile();
		if (!store.loaded) return undefined;
		const src = sourceId ? store.get(sourceId) : undefined;
		if (!src) return blankProfile();
		const duplicating = src.source === 'builtin' || duplicateFlag;
		return duplicating
			? duplicateProfile(src)
			: { ...src, segments: src.segments.map((s) => ({ ...s })) };
	});

	/**
	 * Whether the source id in `edit` mode resolved to a profile already in the
	 * library. False for `/profiles/new` and for an unknown id — both of which
	 * are saving a brand-new entry.
	 */
	const sourceResolved = $derived(
		mode === 'edit' && store.loaded && sourceId != null && store.get(sourceId) != null
	);

	/**
	 * Whether the editor is saving a *new* library entry rather than updating
	 * one. True for `/profiles/new`, and true when editing a built-in or when
	 * the `?duplicate=1` flag is set — built-ins are read-only, so editing one
	 * forks a custom copy. An unknown id also reads as a create.
	 *
	 * `duplicateDraft()` may flip this true on an in-editor duplicate; that
	 * local override wins until the route inputs change.
	 */
	let isCreateOverride = $state<boolean | null>(null);
	const isCreate = $derived(
		isCreateOverride ?? (!sourceResolved || baseline?.source !== 'custom')
	);

	/**
	 * The editable draft profile. It starts as a `$derived` mirror of the
	 * loaded {@link baseline}; the first edit reassigns it to a plain value
	 * (Svelte 5.25+ permits reassigning a `$derived`), after which the draft is
	 * the editor's own state and no longer tracks the baseline. `discard()`
	 * reassigns it back to a fresh copy of the baseline.
	 */
	let draft = $derived.by<CremaProfile>(() => baseline ?? blankProfile());
	/**
	 * The explicitly-selected segment id, or `null` to fall back to the
	 * draft's second (or first) segment — see {@link activeSegId}.
	 */
	let selectedSegId = $state<string | null>(null);
	/** The id of the selected segment, highlighted in the curve. */
	const activeSegId = $derived(
		selectedSegId ?? draft.segments[1]?.id ?? draft.segments[0]?.id ?? null
	);
	/** Whether the draft differs from its loaded baseline (dirty flag). */
	let dirty = $state(false);
	/** Set once the loaded baseline is resolved, so the grid does not flash. */
	const ready = $derived(baseline != null);

	/** The heading reflects what saving will do. */
	const heading = $derived(
		mode === 'new' ? 'New profile' : isCreate ? 'Duplicate profile' : 'Edit profile'
	);

	/** Tag suggestions — every tag used elsewhere in the library. */
	const tagSuggestions = $derived.by(() => {
		const set = new Set<string>();
		for (const p of store.all) for (const t of p.tags) set.add(t);
		return [...set];
	});

	/** The computed brew ratio for the read-out card. */
	const ratio = $derived(ratioLabel(draft));
	/** Total shot time across the segments. */
	const total = $derived(totalTime(draft.segments));

	/** Patch the draft and mark it dirty. */
	function patch(p: Partial<CremaProfile>): void {
		draft = { ...draft, ...p };
		dirty = true;
	}

	/** Apply a `{ time, target }` patch from the curve editor to one segment. */
	function editSegment(id: string, p: Partial<ProfileSegment>): void {
		draft = {
			...draft,
			segments: draft.segments.map((s) => (s.id === id ? { ...s, ...p } : s))
		};
		dirty = true;
	}

	/** Append a new segment after the last one. */
	function addSegment(): void {
		const last = draft.segments[draft.segments.length - 1];
		const seg: ProfileSegment = {
			id: uid('seg'),
			name: 'New segment',
			mode: last?.mode ?? 'pressure',
			target: last?.target ?? 6,
			ramp: 'smooth',
			time: 6,
			exit: null,
			temp: last?.temp ?? draft.brewTemp,
			tempSensor: last?.tempSensor ?? 'coffee',
			volumeLimitMl: 0,
			limiter: null
		};
		draft = { ...draft, segments: [...draft.segments, seg] };
		selectedSegId = seg.id;
		dirty = true;
	}

	/** Delete a segment (the profile keeps at least one). */
	function deleteSegment(id: string): void {
		if (draft.segments.length <= 1) return;
		draft = { ...draft, segments: draft.segments.filter((s) => s.id !== id) };
		if (activeSegId === id) selectedSegId = draft.segments[0]?.id ?? null;
		dirty = true;
	}

	/**
	 * Move a segment to a new position, sliding the rest along — drives both
	 * drag-to-reorder and the editable row number. `toIndex` is clamped to the
	 * valid range, so setting a segment to position N simply lands it at N.
	 */
	function moveSegment(id: string, toIndex: number): void {
		const segs = [...draft.segments];
		const from = segs.findIndex((s) => s.id === id);
		if (from < 0) return;
		const to = Math.max(0, Math.min(segs.length - 1, Math.trunc(toIndex)));
		if (to === from) return;
		const [moved] = segs.splice(from, 1);
		segs.splice(to, 0, moved);
		draft = { ...draft, segments: segs };
		dirty = true;
	}

	/** Reset the segments to a fresh default list. */
	function resetSegments(): void {
		const fresh = blankProfile().segments;
		draft = { ...draft, segments: fresh };
		selectedSegId = fresh[1]?.id ?? fresh[0].id;
		dirty = true;
	}

	/**
	 * Save the draft to the library and return to wherever the user came
	 * from (Brew or Profiles per `returnPath`).
	 *
	 * Persistence is unconditional — a custom profile is updated in place,
	 * a built-in / `?duplicate=1` arrival forks into a fresh custom
	 * profile (built-ins are read-only). What differs across surfaces:
	 *
	 *  - **From Brew (`cameFromBrew === true`):** the user opened Edit on
	 *    the *loaded* profile. After save, the saved profile becomes the
	 *    active one + a sync to the DE1 fires — so the edits take effect
	 *    immediately on the user's next shot. Fork or in-place both flip
	 *    active (the in-place case is a no-op if it was already active).
	 *  - **From Profiles library (`cameFromBrew === false`):** the user
	 *    is just curating the library. Save persists; the active profile
	 *    is unchanged. They can hit Load on Brew separately if they want
	 *    it on the DE1.
	 */
	function save(): void {
		const toSave: CremaProfile = isCreate
			? {
					...draft,
					// Profile ids are UUID v7 strings — `blankProfile` /
					// `duplicateProfile` already mint one via
					// `newProfileId()` (the wasm bridge over the Rust
					// `de1_domain::new_profile_id`), so this `||` is just
					// belt-and-braces against an empty draft id.
					id: draft.id || newProfileId(),
					source: 'custom'
				}
			: draft;
		store.save(toSave);
		if (cameFromBrew) {
			// User was on the brew page editing the loaded profile —
			// promote the (possibly forked) save as the active profile
			// + sync to the DE1 so the next shot uses the edited bytes.
			store.setActive(toSave.id);
			if (app) {
				// Mirrors `loadOnBrew` in routes/profiles — same path so
				// the fingerprint cache stays consistent.
				void app.syncActiveProfile(toSave, {});
			}
		}
		dirty = false;
		void goto(returnPath);
	}

	/** Discard edits — revert the draft to its loaded baseline. */
	async function discard(): Promise<void> {
		if (dirty && !(await confirmDialog({ message: 'Discard all changes to this profile?', confirmLabel: 'Discard' }))) return;
		const base = baseline ?? blankProfile();
		draft = { ...base, segments: base.segments.map((s) => ({ ...s })) };
		isCreateOverride = null;
		selectedSegId = null;
		dirty = false;
	}

	/** Duplicate the current draft into a brand-new profile draft. */
	function duplicateDraft(): void {
		draft = duplicateProfile(draft);
		isCreateOverride = true;
		dirty = true;
	}

	/** Back to wherever the user came from (Brew or Profiles per `returnPath`). */
	async function back(): Promise<void> {
		if (dirty && !(await confirmDialog({ message: 'Leave the editor? Unsaved changes will be lost.', confirmLabel: 'Leave' }))) return;
		void goto(returnPath);
	}

	const roastOptions: Roast[] = ['light', 'medium', 'dark'];
	/**
	 * Beverage-type choices in editor-friendly order. Matches the v2
	 * contract enum (Espresso/Calibrate/Cleaning/Manual/Pourover); the
	 * UI shows them lower-cased to align with the wire spelling.
	 */
	const beverageTypeOptions: BeverageType[] = [
		'espresso',
		'pourover',
		'manual',
		'cleaning',
		'calibrate'
	];
</script>

<svelte:head>
	<title>Crema — {heading}</title>
</svelte:head>

<div class="pe-page">
	<!-- Top bar -->
	<div class="pe-topbar">
		<div class="pe-topbar-l">
			<button class="pe-back" onclick={back}>
				<ArrowLeftIcon aria-hidden="true" /> Profiles
			</button>
			<div class="pe-trail">
				<span class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.45)">{heading}</span>
			</div>
		</div>
		<div class="pe-topbar-r">
			<button class="pe-btn-text" disabled={!dirty} onclick={discard}>
				Discard changes
			</button>
			<button class="pp-btn pp-btn-secondary" onclick={duplicateDraft}>
				<CopyIcon aria-hidden="true" /> Duplicate
			</button>
			<button class="pp-btn pp-btn-primary" onclick={save}>
				<CheckIcon aria-hidden="true" /> Save profile
			</button>
		</div>
	</div>

	{#if !ready}
		<div class="pe-loading">Loading profile…</div>
	{:else}
		<div class="pe-body">
			<!-- Left column — metadata + params -->
			<div class="pe-left">
				<input
					class="pe-name"
					value={draft.name}
					placeholder="Untitled profile"
					oninput={(e) => patch({ name: e.currentTarget.value })}
				/>

				<div class="pe-section">
					<div class="pe-field">
						<label class="t-eyebrow" for="pe-author">Author</label>
						<input
							id="pe-author"
							class="pe-input"
							type="text"
							value={draft.author}
							placeholder="Who designed this profile?"
							oninput={(e) => patch({ author: e.currentTarget.value })}
						/>
					</div>
					<div class="pe-field">
						<label class="t-eyebrow" for="pe-notes">Notes</label>
						<textarea
							id="pe-notes"
							class="pe-input pe-textarea"
							rows={3}
							value={draft.notes}
							placeholder="What's this profile for?"
							oninput={(e) => patch({ notes: e.currentTarget.value })}
						></textarea>
					</div>
					<div class="pe-field">
						<span class="t-eyebrow">Roast</span>
						<div class="pe-tags">
							{#each roastOptions as r (r)}
								<button
									class="pe-tag"
									class:is-active={draft.roast === r}
									onclick={() => patch({ roast: draft.roast === r ? null : r })}
									>{r}</button
								>
							{/each}
						</div>
					</div>
					<div class="pe-field">
						<!-- Beverage type — espresso is the default and the only
						     one most users will pick; the others are utility
						     profile categories (calibration, cleaning, manual
						     control, pour-over) that the v2 contract codifies. -->
						<span class="t-eyebrow">Beverage type</span>
						<div class="pe-tags">
							{#each beverageTypeOptions as bt (bt)}
								<button
									class="pe-tag"
									class:is-active={draft.beverageType === bt}
									onclick={() => patch({ beverageType: bt })}>{bt}</button
								>
							{/each}
						</div>
					</div>
					<div class="pe-field">
						<span class="t-eyebrow">Tags</span>
						<TagInput
							tags={draft.tags}
							suggestions={tagSuggestions}
							onChange={(tags) => patch({ tags })}
						/>
					</div>
					<button
						class="pe-pin"
						type="button"
						onclick={() => patch({ pinned: !draft.pinned })}
					>
						<span class="qmini-tog" class:on={draft.pinned}></span>
						<span>Pin to favorites strip in Quick Controls</span>
					</button>
				</div>

				<div class="pe-section">
					<div class="pe-section-title">Targets</div>
					<div class="pe-grid">
						<PeNumber
							label="Dose"
							value={draft.dose}
							step={0.1}
							dimension="weight"
							min={5}
							max={30}
							onChange={(v) => patch({ dose: v })}
						/>
						<PeNumber
							label="Yield"
							value={draft.yieldOut}
							step={0.5}
							dimension="weight"
							min={0}
							max={80}
							onChange={(v) => patch({ yieldOut: v })}
							dot
							dotOn={draft.yieldOut > 0}
							onDot={() =>
								patch({
									yieldOut: draft.yieldOut > 0 ? 0 : (draft.dose || 18) * 2
								})}
						/>
						<PeNumber
							label="Brew temp"
							value={draft.brewTemp}
							step={0.5}
							dimension="temp"
							min={80}
							max={100}
							onChange={(v) => patch({ brewTemp: v })}
						/>
						<div class="pe-readout">
							<div class="pe-readout-head">
								<div class="t-eyebrow">Ratio</div>
								<span class="pe-readout-sub">computed</span>
							</div>
							<div class="pe-readout-val">{ratio}</div>
						</div>
					</div>
				</div>

				<div class="pe-section">
					<div class="pe-section-title">Limits</div>
					<div class="pe-grid">
						<PeNumber
							label="Max total volume"
							value={draft.maxTotalVolumeMl}
							step={5}
							dimension="volume"
							min={ProfileBounds.MIN_TOTAL_VOLUME_ML}
							max={ProfileBounds.MAX_TOTAL_VOLUME_ML}
							digits={0}
							onChange={(v) => patch({ maxTotalVolumeMl: Math.round(v) })}
							dot
							dotOn={draft.maxTotalVolumeMl > 0}
							onDot={() =>
								patch({ maxTotalVolumeMl: draft.maxTotalVolumeMl > 0 ? 0 : 50 })}
						/>
						<PeNumber
							label="Preinfuse steps"
							value={draft.preinfuseStepCount}
							step={1}
							unit=""
							min={0}
							max={draft.segments.length}
							digits={0}
							onChange={(v) => patch({ preinfuseStepCount: Math.round(v) })}
							dot
							dotOn={draft.preinfuseStepCount > 0}
							onDot={() =>
								patch({
									preinfuseStepCount: draft.preinfuseStepCount > 0 ? 0 : 1
								})}
						/>
						<!-- Tank temperature — advanced v2-only field. 0 means
						     "no override" (the firmware keeps its current
						     setpoint). Setting a non-zero value writes the tank
						     setpoint at profile-load time. Most users leave it
						     at 0. -->
						<PeNumber
							label="Tank temp"
							value={draft.tankTemperatureC}
							step={1}
							dimension="temp"
							min={0}
							max={ProfileBounds.MAX_TEMPERATURE_C}
							digits={0}
							onChange={(v) => patch({ tankTemperatureC: Math.round(v) })}
							dot
							dotOn={draft.tankTemperatureC > 0}
							onDot={() =>
								patch({
									tankTemperatureC: draft.tankTemperatureC > 0 ? 0 : 92
								})}
						/>
					</div>
				</div>

				<!--
					"Stop on weight" and "Auto-tare on start" are global app
					preferences now (Settings → Brew defaults → Shot behaviour),
					mirrored in the Quick Controls. The recipe target weight
					itself lives on the Yield field above — set its dot to off
					(or stepper to 0) to mark the profile as "no SAW target."
				-->

			</div>

			<!-- Right column — curve editor -->
			<div class="pe-right">
				<div class="pe-section">
					<div class="pe-section-head">
						<div>
							<div class="pe-section-title">Pressure profile</div>
							<div class="pe-section-sub">
								{draft.segments.length} segments · {total}s total · drag the dots or
								edit the rows below
							</div>
						</div>
						<div class="pe-section-actions">
							<button class="pe-btn-ghost" onclick={resetSegments}>
								<ArrowCounterClockwiseIcon aria-hidden="true" /> Reset
							</button>
						</div>
					</div>
					<ProfileCurveEditor
						segments={draft.segments}
						{activeSegId}
						onSelect={(id) => (selectedSegId = id)}
						onEdit={editSegment}
					/>
				</div>

				<div class="pe-section">
					<div class="pe-section-head">
						<div class="pe-section-title">Segments</div>
						<button class="pe-btn-ghost" onclick={addSegment}>
							<PlusIcon aria-hidden="true" /> Add segment
						</button>
					</div>
					<!-- Every per-segment field — including the exit condition and
					     the limiter — lives on a single (wide) `SegmentRow`. The
					     row outgrows a narrow viewport, so the list scrolls
					     horizontally rather than crushing its columns. -->
					<div class="pe-segs">
						{#each draft.segments as seg, i (seg.id)}
							<SegmentRow
								{seg}
								index={i}
								count={draft.segments.length}
								active={activeSegId === seg.id}
								onSelect={() => (selectedSegId = seg.id)}
								onEdit={(p) => editSegment(seg.id, p)}
								onDelete={() => deleteSegment(seg.id)}
								onReorder={moveSegment}
							/>
						{/each}
					</div>
				</div>
			</div>
		</div>
	{/if}
</div>

<style>
	.pe-page {
		background: var(--bg-page);
		color: var(--fg-1);
		display: flex;
		flex-direction: column;
		min-height: 100vh;
	}

	/* Top bar */
	.pe-topbar {
		display: flex;
		align-items: center;
		justify-content: space-between;
		/* Shared page-header rhythm — see --page-pad-* in app.css — so the
		   header sits where every other route's does (no jump on navigation). */
		padding: var(--page-pad-top) var(--page-pad-x) 18px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.05);
		flex: 0 0 auto;
		gap: 16px;
	}
	.pe-topbar-l {
		display: flex;
		align-items: center;
		gap: 16px;
	}
	.pe-back {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-pill);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 7px 14px;
		cursor: pointer;
	}
	.pe-back:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.pe-back :global(svg) {
		font-size: 13px;
	}
	.pe-topbar-r {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.pe-btn-text {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.55);
		font-family: var(--font-sans);
		font-size: 12px;
		cursor: pointer;
		padding: 8px 10px;
	}
	.pe-btn-text:hover:not(:disabled) {
		color: var(--fg-1);
	}
	.pe-btn-text:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}

	/* Shared pill buttons (mirrors the library page's .pp-btn) */
	.pp-btn {
		display: inline-flex;
		align-items: center;
		gap: 7px;
		padding: 9px 16px;
		border-radius: var(--radius-pill);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
		border: 1px solid transparent;
		white-space: nowrap;
	}
	.pp-btn :global(svg) {
		font-size: 14px;
	}
	.pp-btn-secondary {
		background: rgba(var(--tint-rgb), 0.04);
		border-color: rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
	}
	.pp-btn-secondary:hover {
		background: rgba(var(--tint-rgb), 0.07);
	}
	.pp-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.pp-btn-primary:hover {
		background: var(--copper-600);
	}

	.pe-loading {
		padding: 60px 32px;
		color: rgba(var(--tint-rgb), 0.5);
		font-family: var(--font-sans);
		font-size: 14px;
	}

	/* Body */
	.pe-body {
		display: grid;
		grid-template-columns: 440px 1fr;
		flex: 1 1 auto;
	}
	/* Tablet landscape and narrower — the two editor panes stack vertically. */
	@media (max-width: 980px) {
		.pe-body {
			grid-template-columns: minmax(0, 1fr);
		}
		.pe-left {
			border-right: 0 !important;
			border-bottom: 1px solid rgba(var(--tint-rgb), 0.05);
		}
	}

	/* Tablet portrait — the fixed-width readout pair becomes fluid so it fills
	   the now full-width pane instead of leaving a gap. */
	@media (max-width: 820px) {
		.pe-grid {
			grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
		}
	}
	.pe-left {
		padding: 28px var(--page-pad-x);
		border-right: 1px solid rgba(var(--tint-rgb), 0.05);
		display: flex;
		flex-direction: column;
		gap: 28px;
	}
	.pe-right {
		padding: 28px var(--page-pad-x);
		display: flex;
		flex-direction: column;
		gap: 22px;
		min-width: 0;
	}

	.pe-name {
		background: transparent;
		border: 0;
		outline: 0;
		border-bottom: 1px solid transparent;
		font-family: var(--font-serif);
		font-size: 26px;
		letter-spacing: -0.015em;
		line-height: 1.2;
		color: var(--fg-1);
		padding: 4px 0 8px;
		width: 100%;
		min-width: 0;
		transition: border-color var(--dur-1) var(--ease);
	}
	.pe-name:hover {
		border-bottom-color: rgba(var(--tint-rgb), 0.1);
	}
	.pe-name:focus {
		border-bottom-color: var(--copper-500);
	}
	.pe-name::placeholder {
		color: rgba(var(--tint-rgb), 0.3);
	}

	/* Section */
	.pe-section {
		display: flex;
		flex-direction: column;
		gap: 14px;
	}
	.pe-section-head {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		gap: 16px;
	}
	.pe-section-title {
		font-family: var(--font-serif);
		font-size: 18px;
		letter-spacing: -0.01em;
		color: var(--fg-1);
	}
	.pe-section-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
		margin-top: 2px;
	}
	.pe-section-actions {
		display: flex;
		gap: 6px;
	}

	/* Field */
	.pe-field {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.pe-field :global(.t-eyebrow) {
		color: rgba(var(--tint-rgb), 0.5);
	}
	.pe-input {
		background: rgba(var(--tint-rgb), 0.03);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-sm);
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
		padding: 9px 12px;
		outline: 0;
		transition: border-color var(--dur-1) var(--ease);
	}
	.pe-input:focus {
		border-color: rgba(var(--tint-rgb), 0.25);
	}
	.pe-textarea {
		resize: vertical;
		min-height: 60px;
		line-height: 1.5;
	}

	.pe-tags {
		display: flex;
		gap: 6px;
		/* Allow chip rows with more than ~3 options (beverage type:
		   espresso/pourover/manual/cleaning/calibrate) to wrap rather
		   than overflow the metadata column. */
		flex-wrap: wrap;
	}
	.pe-tag {
		/* Size to content; the wrap rule above handles overflow. (Earlier
		   `flex: 1 1 0` forced equal-width chips, which clipped 5-option
		   rows in narrow columns.) */
		flex: 0 0 auto;
		padding: 6px 9px;
		background: rgba(var(--tint-rgb), 0.03);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-sm);
		color: rgba(var(--tint-rgb), 0.7);
		font-family: var(--font-sans);
		font-size: 10.5px;
		text-transform: uppercase;
		letter-spacing: var(--track-allcaps);
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.pe-tag:hover {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.pe-tag.is-active {
		background: var(--copper-500);
		border-color: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}

	.pe-pin {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
		cursor: pointer;
		padding: 4px 0;
		background: transparent;
		border: 0;
		text-align: left;
		width: 100%;
	}
	.pe-pin .qmini-tog {
		margin-top: 1px;
		flex: 0 0 30px;
	}

	/* Number grid — fixed 175px columns (not `1fr`). `1fr` is really
	   `minmax(auto, 1fr)`, so a long nowrap label like "Max total volume"
	   widens its track and shifts the gutter; fixed columns keep every box
	   the same width and the column gutter at the same X in every section.
	   175 + 10 + 175 = 360 = the `.pe-left` content width. */
	.pe-grid {
		display: grid;
		grid-template-columns: 175px 175px;
		gap: 10px;
	}
	/* The Ratio read-out matches a `.pe-num` box: same padding and gap, an
	   eyebrow row on top, a value the height of a stepper's −/+ bar below.
	   "computed" rides the top-right of the eyebrow row so the box is two
	   rows tall, like the steppers — not three. */
	.pe-readout {
		display: flex;
		flex-direction: column;
		gap: 6px;
		/* Same fixed height as `.pe-num`, so every box in the Targets and
		   Limits grids is identical regardless of its grid row. */
		height: 76px;
		box-sizing: border-box;
		padding: 12px;
		background: rgba(var(--tint-rgb), 0.03);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-sm);
	}
	.pe-readout-head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 8px;
	}
	.pe-readout :global(.t-eyebrow) {
		color: rgba(var(--tint-rgb), 0.5);
		font-size: 10px;
		letter-spacing: 0.04em;
		white-space: nowrap;
	}
	.pe-readout-val {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 22px;
		color: var(--copper-400);
		font-weight: 500;
		min-height: 28px;
		display: flex;
		align-items: center;
	}
	.pe-readout-sub {
		font-family: var(--font-sans);
		font-size: 9px;
		color: rgba(var(--tint-rgb), 0.4);
		text-transform: uppercase;
		letter-spacing: 0.04em;
		white-space: nowrap;
	}

	/* Segments list — the wide single-row segments scroll horizontally so a
	   wide row never breaks the page layout. */
	.pe-segs {
		display: flex;
		flex-direction: column;
		gap: 6px;
		overflow-x: auto;
	}

	/* Ghost button */
	.pe-btn-ghost {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: transparent;
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-pill);
		padding: 6px 12px;
		color: rgba(var(--tint-rgb), 0.7);
		font-family: var(--font-sans);
		font-size: 11px;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.pe-btn-ghost:hover {
		color: var(--fg-1);
		border-color: rgba(var(--tint-rgb), 0.18);
	}
	.pe-btn-ghost :global(svg) {
		font-size: 13px;
	}
</style>
