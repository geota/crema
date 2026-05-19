<script lang="ts">
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
	import { page } from '$app/state';
	import {
		getProfileStore,
		blankProfile,
		duplicateProfile,
		uid,
		ratioLabel,
		totalTime,
		type CremaProfile,
		type ProfileSegment,
		type Roast
	} from '$lib/profiles';
	import ProfileCurveEditor from './ProfileCurveEditor.svelte';
	import SegmentRow from './SegmentRow.svelte';
	import TagInput from './TagInput.svelte';
	import PeNumber from './PeNumber.svelte';

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
			exitAt: null,
			temperatureC: last?.temperatureC ?? draft.brewTemp
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

	/** Reset the segments to a fresh default list. */
	function resetSegments(): void {
		const fresh = blankProfile().segments;
		draft = { ...draft, segments: fresh };
		selectedSegId = fresh[1]?.id ?? fresh[0].id;
		dirty = true;
	}

	/** Save the draft to the library and return to the grid. */
	function save(): void {
		// A built-in / duplicate is saved as a fresh custom profile; an
		// existing custom profile is updated in place.
		const toSave: CremaProfile = isCreate
			? {
					...draft,
					id: draft.id.startsWith('custom:') ? draft.id : uid('custom'),
					source: 'custom'
				}
			: draft;
		store.save(toSave);
		dirty = false;
		void goto('/profiles');
	}

	/** Discard edits — revert the draft to its loaded baseline. */
	function discard(): void {
		if (dirty && !confirm('Discard all changes to this profile?')) return;
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

	/** Back to the library — warn on unsaved edits. */
	function back(): void {
		if (dirty && !confirm('Leave the editor? Unsaved changes will be lost.')) return;
		void goto('/profiles');
	}

	const roastOptions: Roast[] = ['light', 'medium', 'dark'];
</script>

<svelte:head>
	<title>Crema — {heading}</title>
</svelte:head>

<div class="pe-page">
	<!-- Top bar -->
	<div class="pe-topbar">
		<div class="pe-topbar-l">
			<button class="pe-back" onclick={back}>
				<i class="ph ph-arrow-left" aria-hidden="true"></i> Profiles
			</button>
			<div class="pe-trail">
				<span class="t-eyebrow" style="color:rgba(244,237,224,0.45)">{heading}</span>
			</div>
		</div>
		<div class="pe-topbar-r">
			<button class="pe-btn-text" disabled={!dirty} onclick={discard}>
				Discard changes
			</button>
			<button class="pp-btn pp-btn-secondary" onclick={duplicateDraft}>
				<i class="ph ph-copy" aria-hidden="true"></i> Duplicate
			</button>
			<button class="pp-btn pp-btn-primary" onclick={save}>
				<i class="ph ph-check" aria-hidden="true"></i> Save profile
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
							unit="g"
							min={5}
							max={30}
							onChange={(v) => patch({ dose: v })}
						/>
						<PeNumber
							label="Yield"
							value={draft.yieldG}
							step={0.5}
							unit="g"
							min={10}
							max={80}
							onChange={(v) => patch({ yieldG: v })}
						/>
						<PeNumber
							label="Brew temp"
							value={draft.brewTemp}
							step={0.5}
							unit="°C"
							min={80}
							max={100}
							onChange={(v) => patch({ brewTemp: v })}
						/>
						<div class="pe-readout">
							<div class="t-eyebrow">Ratio</div>
							<div class="pe-readout-val">{ratio}</div>
							<div class="pe-readout-sub">computed</div>
						</div>
					</div>
				</div>

				<div class="pe-section">
					<div class="pe-section-title">Behaviour</div>
					<button
						class="pe-tog"
						type="button"
						onclick={() => patch({ stopOnWeight: !draft.stopOnWeight })}
					>
						<span class="qmini-tog" class:on={draft.stopOnWeight}></span>
						<span>
							<span class="pe-tog-title">Stop on weight</span>
							<span class="pe-tog-sub">
								End the shot when the scale reads {draft.yieldG.toFixed(1)} g.
							</span>
						</span>
					</button>
					<button
						class="pe-tog"
						type="button"
						onclick={() => patch({ autoTare: !draft.autoTare })}
					>
						<span class="qmini-tog" class:on={draft.autoTare}></span>
						<span>
							<span class="pe-tog-title">Auto-tare on start</span>
							<span class="pe-tog-sub">
								Zero the scale automatically when the shot begins.
							</span>
						</span>
					</button>
				</div>
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
								<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i> Reset
							</button>
						</div>
					</div>
					<ProfileCurveEditor
						segments={draft.segments}
						{activeSegId}
						onSelect={(id) => (selectedSegId = id)}
						onEdit={editSegment}
						width={720}
						height={300}
					/>
				</div>

				<div class="pe-section">
					<div class="pe-section-head">
						<div class="pe-section-title">Segments</div>
						<button class="pe-btn-ghost" onclick={addSegment}>
							<i class="ph ph-plus" aria-hidden="true"></i> Add segment
						</button>
					</div>
					<div class="pe-segs">
						{#each draft.segments as seg, i (seg.id)}
							<SegmentRow
								{seg}
								index={i}
								active={activeSegId === seg.id}
								onSelect={() => (selectedSegId = seg.id)}
								onEdit={(p) => editSegment(seg.id, p)}
								onDelete={() => deleteSegment(seg.id)}
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
		background: var(--espresso-950);
		color: var(--ink-50);
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
		border-bottom: 1px solid rgba(244, 237, 224, 0.05);
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
		background: rgba(244, 237, 224, 0.04);
		border: 1px solid rgba(244, 237, 224, 0.08);
		border-radius: var(--radius-pill);
		color: var(--ink-50);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 7px 14px;
		cursor: pointer;
	}
	.pe-back:hover {
		background: rgba(244, 237, 224, 0.08);
	}
	.pe-back i {
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
		color: rgba(244, 237, 224, 0.55);
		font-family: var(--font-sans);
		font-size: 12px;
		cursor: pointer;
		padding: 8px 10px;
	}
	.pe-btn-text:hover:not(:disabled) {
		color: var(--ink-50);
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
	.pp-btn i {
		font-size: 14px;
	}
	.pp-btn-secondary {
		background: rgba(244, 237, 224, 0.04);
		border-color: rgba(244, 237, 224, 0.1);
		color: var(--ink-50);
	}
	.pp-btn-secondary:hover {
		background: rgba(244, 237, 224, 0.07);
	}
	.pp-btn-primary {
		background: var(--copper-500);
		color: #1a120c;
		font-weight: 600;
	}
	.pp-btn-primary:hover {
		background: var(--copper-600);
	}

	.pe-loading {
		padding: 60px 32px;
		color: rgba(244, 237, 224, 0.5);
		font-family: var(--font-sans);
		font-size: 14px;
	}

	/* Body */
	.pe-body {
		display: grid;
		grid-template-columns: 380px 1fr;
		flex: 1 1 auto;
	}
	@media (max-width: 980px) {
		.pe-body {
			grid-template-columns: minmax(0, 1fr);
		}
		.pe-left {
			border-right: 0 !important;
			border-bottom: 1px solid rgba(244, 237, 224, 0.05);
		}
	}
	.pe-left {
		padding: 28px var(--page-pad-x);
		border-right: 1px solid rgba(244, 237, 224, 0.05);
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
		color: var(--ink-50);
		padding: 4px 0 8px;
		width: 100%;
		min-width: 0;
		transition: border-color var(--dur-1) var(--ease);
	}
	.pe-name:hover {
		border-bottom-color: rgba(244, 237, 224, 0.1);
	}
	.pe-name:focus {
		border-bottom-color: var(--copper-500);
	}
	.pe-name::placeholder {
		color: rgba(244, 237, 224, 0.3);
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
		color: var(--ink-50);
	}
	.pe-section-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(244, 237, 224, 0.5);
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
		color: rgba(244, 237, 224, 0.5);
	}
	.pe-input {
		background: rgba(244, 237, 224, 0.03);
		border: 1px solid rgba(244, 237, 224, 0.08);
		border-radius: var(--radius-sm);
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--ink-50);
		padding: 9px 12px;
		outline: 0;
		transition: border-color var(--dur-1) var(--ease);
	}
	.pe-input:focus {
		border-color: rgba(244, 237, 224, 0.25);
	}
	.pe-textarea {
		resize: vertical;
		min-height: 60px;
		line-height: 1.5;
	}

	.pe-tags {
		display: flex;
		gap: 6px;
	}
	.pe-tag {
		flex: 1 1 0;
		padding: 7px 8px;
		background: rgba(244, 237, 224, 0.03);
		border: 1px solid rgba(244, 237, 224, 0.08);
		border-radius: var(--radius-sm);
		color: rgba(244, 237, 224, 0.7);
		font-family: var(--font-sans);
		font-size: 11px;
		text-transform: uppercase;
		letter-spacing: var(--track-allcaps);
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.pe-tag:hover {
		background: rgba(244, 237, 224, 0.06);
	}
	.pe-tag.is-active {
		background: var(--copper-500);
		border-color: var(--copper-500);
		color: #1a120c;
		font-weight: 600;
	}

	.pe-pin,
	.pe-tog {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--ink-50);
		cursor: pointer;
		padding: 4px 0;
		background: transparent;
		border: 0;
		text-align: left;
		width: 100%;
	}
	.pe-pin .qmini-tog,
	.pe-tog .qmini-tog {
		margin-top: 1px;
		flex: 0 0 30px;
	}
	.pe-tog > span:last-child {
		display: flex;
		flex-direction: column;
	}
	.pe-tog-sub {
		font-size: 11px;
		color: rgba(244, 237, 224, 0.5);
		margin-top: 2px;
	}

	/* Number grid */
	.pe-grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 10px;
	}
	.pe-readout {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding: 12px;
		background: rgba(244, 237, 224, 0.03);
		border: 1px solid rgba(244, 237, 224, 0.06);
		border-radius: var(--radius-sm);
		justify-content: space-between;
	}
	.pe-readout :global(.t-eyebrow) {
		color: rgba(244, 237, 224, 0.5);
	}
	.pe-readout-val {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 22px;
		color: var(--copper-400);
		font-weight: 500;
	}
	.pe-readout-sub {
		font-family: var(--font-sans);
		font-size: 10px;
		color: rgba(244, 237, 224, 0.4);
		text-transform: uppercase;
		letter-spacing: var(--track-allcaps);
	}

	/* Segments list */
	.pe-segs {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}

	/* Ghost button */
	.pe-btn-ghost {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: transparent;
		border: 1px solid rgba(244, 237, 224, 0.08);
		border-radius: var(--radius-pill);
		padding: 6px 12px;
		color: rgba(244, 237, 224, 0.7);
		font-family: var(--font-sans);
		font-size: 11px;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.pe-btn-ghost:hover {
		color: var(--ink-50);
		border-color: rgba(244, 237, 224, 0.18);
	}
	.pe-btn-ghost i {
		font-size: 13px;
	}
</style>
