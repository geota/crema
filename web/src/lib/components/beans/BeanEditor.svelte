<script lang="ts">
	/**
	 * `BeanEditor` — the right-rail drawer / fullscreen sheet on `/beans` that
	 * edits one library bean. Mirrors the design's "Bean detail / edit view"
	 * in docs/28 §UX. Form fields are organised into collapsible sections
	 * (Identity, Dates, Bag, Origin, Tasting, Buy-again) so the median user
	 * only sees the rows they need.
	 *
	 * Two modes:
	 *
	 * - **Edit existing (`isNew={false}`, default):** every field persists
	 *   immediately via the library store; the drawer is a view-of-truth
	 *   onto the live record, Close = dismiss.
	 * - **Create new (`isNew={true}`):** the editor holds a local *draft* and
	 *   shows a Save / Cancel footer. Nothing is written to the store until
	 *   Save fires. This way a half-typed roaster name doesn't leak into the
	 *   directory, and abandoning the form is a clean no-op.
	 *
	 * Polished 2026-05-23 (bean-editor-polish): toggles for boolean rows, a
	 * QStepper + preset chip pattern for roast level / bag size / grind, a
	 * proper Save button when creating, and a roaster autocomplete that
	 * creates *at save time only* (the old `oninput`-driven flow polluted
	 * the directory with every keystroke).
	 */
	import { untrack } from 'svelte';
	import {
		getBeanStore,
		daysOffRoast,
		roastBand,
		roastFreshness,
		type Bean,
		type Roaster
	} from '$lib/bean';
	import type { Roast } from '$lib/profiles';
	import { getHistoryStore } from '$lib/history';
	import QStepper from '$lib/components/brew/QStepper.svelte';
	import StToggle from '$lib/components/settings/StToggle.svelte';
	import RoasterAutocomplete from './RoasterAutocomplete.svelte';
	import TagInput from '$lib/components/profiles/TagInput.svelte';

	let {
		bean,
		isActive,
		isNew = false,
		tagSuggestions = [],
		onClose,
		onMakeActive,
		onSaved
	}: {
		bean: Bean;
		isActive: boolean;
		/**
		 * True when the editor is being used to create a fresh bean. Switches
		 * the editor into draft-mode (no live persistence) and reveals the
		 * Save / Cancel footer. Defaults to false — existing beans keep the
		 * pre-polish live-patch behaviour.
		 */
		isNew?: boolean;
		/**
		 * Union of tags used across the rest of the bean library. Feeds the
		 * tag-chip input's autocomplete `<datalist>` so users land on the
		 * same vocabulary across bags. Empty list = no suggestions (fine,
		 * the input still accepts free-form entries).
		 */
		tagSuggestions?: string[];
		onClose: () => void;
		onMakeActive: () => void;
		/**
		 * Fires after a successful Save in `isNew` mode with the new bean's
		 * id. Parents typically swap from "creating" state to "editing" so a
		 * subsequent open lands on the persisted record.
		 */
		onSaved?: (id: string) => void;
	} = $props();

	const library = getBeanStore();
	const history = getHistoryStore();

	// ── Draft state (new-bean mode only) ───────────────────────────────────
	//
	// In edit-existing mode the working record `current` mirrors the live
	// `bean` prop straight through — every patch hits the store, the prop
	// updates, and we re-render. In new-bean mode we hold a local mutable
	// `draftRecord` and read from that instead; nothing is written to the
	// store until Save fires.
	const live = $derived(!isNew);
	// `untrack` because the seed is the one-shot initial value of the prop:
	// the editor is unmounted + remounted whenever the parent swaps beans,
	// so the draft never wants to re-mirror a later `bean` reference.
	let draftRecord = $state<Bean>(
		untrack(() => ({ ...bean, origin: { ...bean.origin } }))
	);
	/** The active record the form reads from — live `bean` prop, or local draft. */
	const current: Bean = $derived(live ? bean : draftRecord);

	// ── Roaster state ──────────────────────────────────────────────────────
	//
	// `resolvedRoaster` is the Roaster row the user has actually picked
	// (either an existing one or one we created on a previous save). It
	// can lag the typed name during typing — that's intentional; we only
	// create a fresh roaster at Save time, never on `oninput`.
	const initialRoaster = untrack(() =>
		bean.roasterId ? library.getRoaster(bean.roasterId) : null
	);
	let resolvedRoaster = $state<Roaster | null>(initialRoaster);
	let roasterName = $state<string>(initialRoaster?.name ?? '');
	// Re-sync when the underlying bean's roasterId changes (live mode).
	$effect(() => {
		if (!live) return;
		const r = current.roasterId ? library.getRoaster(current.roasterId) : null;
		if ((r?.id ?? null) !== (resolvedRoaster?.id ?? null)) {
			resolvedRoaster = r;
			roasterName = r?.name ?? '';
		}
	});

	const days = $derived(daysOffRoast(current.roastedOn));
	const band = $derived(roastBand(current.roastLevel));
	const freshness = $derived(roastFreshness(band, days));
	const freshColor = $derived(
		freshness === 'best'
			? 'var(--success)'
			: freshness === 'ok'
				? 'var(--warning)'
				: freshness === 'bad'
					? 'var(--danger)'
					: 'rgba(var(--tint-rgb), 0.4)'
	);

	const shotsWithThis = $derived(
		history.all.filter((s) => s.bean?.beanId === current.id)
	);

	const bestShot = $derived.by(() => {
		let best: (typeof shotsWithThis)[number] | null = null;
		for (const s of shotsWithThis) {
			if (s.rating <= 0) continue;
			if (!best || s.rating > best.rating) best = s;
		}
		return best;
	});

	// Section open / closed state.
	let showOrigin = $state(false);
	let showBuyAgain = $state(false);

	const roastOptions: Roast[] = ['light', 'medium', 'dark'];

	// ── Save-button gating (new-bean mode only) ───────────────────────────
	//
	// Required: a name, a roaster (typed name or an explicit pick), and a
	// roast date. Anything else is optional — the user can dial in tasting
	// notes etc. later.
	const canSave = $derived.by<boolean>(() => {
		if (!isNew) return true; // edit mode persists eagerly, no save gate
		if (!current.name.trim()) return false;
		if (!roasterName.trim()) return false;
		if (!current.roastedOn) return false;
		return true;
	});

	/**
	 * Patch one or more fields. In live mode this writes through to the
	 * store immediately; in draft mode it mutates `draftRecord` in place
	 * and the caller is responsible for hitting Save.
	 */
	function patch(p: Partial<Bean>): void {
		if (live) {
			library.updateBean(bean.id, p);
		} else {
			// `draftRecord` is `$state` so a fresh object reassignment triggers reactivity.
			draftRecord = { ...draftRecord, ...p };
		}
	}

	// ── Roast level — numeric stepper canonical, chips snap to band ───────
	//
	// Stepper edits raw 1..10 (Visualizer scale). The three chips below
	// snap to canonical band centres: Light → 3, Medium → 5, Dark → 8.
	// (The legacy `ROAST_PILL_LEVEL` map uses 1/5/10, but those bracket the
	// extreme of each band; 3/5/8 read truer for "typical of band".)
	const ROAST_CHIP_LEVEL: Readonly<Record<Roast, number>> = {
		light: 3,
		medium: 5,
		dark: 8
	};

	function setRoastLevel(n: number): void {
		patch({ roastLevel: Math.max(1, Math.min(10, Math.round(n))) });
	}

	// ── Grind — keep store type as string (shot history compatibility) ────
	//
	// `Bean.grinderSetting: string` is wire-compatible with Visualizer +
	// Beanconqueror, and the same field rides on every shot's metadata as
	// a free-form string. Coerce numerically only in the UI: parse on read,
	// serialise on write, default to 0 when empty.
	const grindNum = $derived.by<number>(() => {
		const n = Number(current.grinderSetting);
		return Number.isFinite(n) ? n : 0;
	});
	function setGrind(n: number): void {
		// Two decimals max — grinder dials don't go finer than that, and
		// keeping the string short keeps the History view tidy.
		const rounded = Math.round(n * 100) / 100;
		patch({ grinderSetting: rounded.toString() });
	}

	// ── Roaster name → roaster row glue ───────────────────────────────────
	function setRoasterName(value: string): void {
		roasterName = value;
		// Live mode: clear the bean's roasterId if the user clears the input,
		// so the UI immediately reflects the empty state. We do NOT call
		// `ensureRoaster` here (that's the keystroke-creation bug).
		if (live && !value.trim()) {
			patch({ roasterId: null });
			resolvedRoaster = null;
		}
		// If the typed name no longer matches the resolved row, the resolver
		// in `RoasterAutocomplete` already cleared `resolvedRoaster` via
		// `onResolve(null)`.
	}

	function onRoasterResolve(r: Roaster | null): void {
		resolvedRoaster = r;
		if (live) patch({ roasterId: r?.id ?? null });
	}

	/**
	 * Look up or create the roaster matching the typed name. Called at Save
	 * time (and immediately for live-mode roaster edits when the user has
	 * typed a fresh name and tabbed away).
	 *
	 * Returns the roaster row (or `null` if the name is blank).
	 */
	function resolveRoasterOrCreate(name: string): Roaster | null {
		const trimmed = name.trim();
		if (!trimmed) return null;
		const existing = library.findRoasterByName(trimmed);
		if (existing) return existing;
		// Create exactly once, at save time. This is the fix for the
		// keystroke-creation bug.
		return library.ensureRoaster(trimmed);
	}

	function ratingTap(n: number): void {
		patch({ rating: current.rating === n ? 0 : n });
	}

	// ── Bag-size chips: canonical sizes ───────────────────────────────────
	const BAG_PRESETS = [250, 340, 454];

	/**
	 * Save handler — only meaningful in new-bean mode. Persists the draft
	 * (creating its roaster on the fly if needed), then closes the drawer.
	 */
	function commitDraftAndClose(): void {
		if (!isNew) {
			onClose();
			return;
		}
		if (!canSave) return;
		const roaster = resolveRoasterOrCreate(roasterName);
		const persisted: Bean = {
			...draftRecord,
			roasterId: roaster?.id ?? null,
			name: draftRecord.name.trim(),
			updatedAt: Date.now()
		};
		library.upsertBean(persisted);
		onSaved?.(persisted.id);
		onClose();
	}

	function cancelDraft(): void {
		// Nothing was written; just close.
		onClose();
	}

</script>

<div
	class="be-scrim"
	onclick={isNew ? cancelDraft : onClose}
	onkeydown={(e) => e.key === 'Escape' && (isNew ? cancelDraft() : onClose())}
	role="button"
	tabindex="-1"
	aria-label="Close bean editor"
></div>

<div class="be-drawer" role="dialog" aria-modal="true" aria-labelledby="be-title">
	<header class="be-head">
		<div>
			<div class="t-eyebrow">{resolvedRoaster?.name ?? (isNew ? 'New bag' : 'Bean')}</div>
			<h2 class="be-title" id="be-title">
				{isNew ? 'Add a new bag' : current.name || 'Untitled bag'}
			</h2>
		</div>
		<div class="be-head-actions">
			{#if !isNew}
				{#if !isActive}
					<button class="be-btn" onclick={onMakeActive}>
						<i class="ph ph-target" aria-hidden="true"></i> Make active
					</button>
				{:else}
					<span class="be-active-pill">
						<span class="be-dot"></span> Active on Brew
					</span>
				{/if}
			{/if}
			<button
				class="be-btn be-btn-icon"
				onclick={() => (isNew ? cancelDraft() : onClose())}
				aria-label="Close"
			>
				<i class="ph ph-x" aria-hidden="true"></i>
			</button>
		</div>
	</header>

	<div class="be-body">
		<!-- Identity -->
		<section class="be-section">
			<h3 class="be-section-title">Identity</h3>
			<label class="be-row">
				<span class="be-label">Name</span>
				<input
					class="be-input"
					value={current.name}
					placeholder="e.g. Geisha Esmeralda Lot 3"
					oninput={(e) => patch({ name: e.currentTarget.value })}
				/>
			</label>
			<div class="be-row">
				<span class="be-label">Roaster</span>
				<RoasterAutocomplete
					value={roasterName}
					resolved={resolvedRoaster}
					roasters={library.roasters}
					placeholder="e.g. Onyx Coffee Lab"
					onChange={setRoasterName}
					onResolve={(r) => {
						onRoasterResolve(r);
						if (r) roasterName = r.name;
					}}
					onCommitTyped={live
						? (typed) => {
								// Live mode: the user typed a fresh name and blurred
								// without picking. Commit it now so the bag is linked
								// to the roastery. In draft mode the parent's Save
								// handler does this once at save-time instead.
								const r = resolveRoasterOrCreate(typed);
								if (r) {
									resolvedRoaster = r;
									patch({ roasterId: r.id });
								}
							}
						: undefined}
				/>
			</div>
			<div class="be-row">
				<span class="be-label">Roast level</span>
				<QStepper
					value={current.roastLevel ?? 5}
					min={1}
					max={10}
					step={1}
					onChange={(v) => setRoastLevel(v)}
					fmt={(v) => v.toFixed(0)}
				/>
				<div class="be-chip-spacer"></div>
				<div class="qchipr">
					{#each roastOptions as r (r)}
						<button
							type="button"
							class="qchip"
							class:is-active={band === r}
							onclick={() => setRoastLevel(ROAST_CHIP_LEVEL[r])}
							style="text-transform: capitalize"
						>
							{r}
						</button>
					{/each}
				</div>
			</div>
			<div class="be-toggle-row">
				<div class="be-toggle-text">
					<div class="be-toggle-title">Decaf</div>
				</div>
				<StToggle
					on={current.decaf}
					onChange={(v) => patch({ decaf: v })}
					label="Decaf"
				/>
			</div>
			<div class="be-toggle-row">
				<div class="be-toggle-text">
					<div class="be-toggle-title">Pin to brew picker</div>
					<div class="be-toggle-sub">Show this bag in the favourites strip</div>
				</div>
				<StToggle
					on={current.favourite}
					onChange={(v) => patch({ favourite: v })}
					label="Favourite"
				/>
			</div>
		</section>

		<!-- Dates -->
		<section class="be-section">
			<h3 class="be-section-title">Dates</h3>
			<label class="be-row">
				<span class="be-label">Roasted on{isNew ? ' *' : ''}</span>
				<div class="be-date-row">
					<input
						class="be-input be-input-date"
						type="date"
						value={current.roastedOn ?? ''}
						oninput={(e) => patch({ roastedOn: e.currentTarget.value || null })}
					/>
					{#if days != null}
						<span class="be-fresh" style:color={freshColor}>
							<span class="be-dot" style:background={freshColor}></span>
							{days}d off roast · {freshness ?? 'pending'}
						</span>
					{/if}
				</div>
			</label>
			<label class="be-row">
				<span class="be-label">Opened on</span>
				<input
					class="be-input be-input-date"
					type="date"
					value={current.openedOn ?? ''}
					oninput={(e) => patch({ openedOn: e.currentTarget.value || null })}
				/>
			</label>
			<label class="be-row">
				<span class="be-label">Frozen on</span>
				<input
					class="be-input be-input-date"
					type="date"
					value={current.frozenOn ?? ''}
					oninput={(e) => patch({ frozenOn: e.currentTarget.value || null })}
				/>
			</label>
			{#if current.frozenOn}
				<label class="be-row">
					<span class="be-label">Defrosted on</span>
					<input
						class="be-input be-input-date"
						type="date"
						value={current.defrostedOn ?? ''}
						oninput={(e) =>
							patch({ defrostedOn: e.currentTarget.value || null })}
					/>
				</label>
			{/if}
		</section>

		<!-- Bag -->
		<section class="be-section">
			<h3 class="be-section-title">Bag</h3>
			<div class="be-row">
				<span class="be-label">Bag size</span>
				<QStepper
					value={current.bagSizeG}
					unit="g"
					min={100}
					max={2000}
					step={10}
					onChange={(v) => patch({ bagSizeG: v })}
					fmt={(v) => v.toFixed(0)}
				/>
				<div class="be-chip-spacer"></div>
				<div class="qchipr">
					{#each BAG_PRESETS as g (g)}
						<button
							type="button"
							class="qchip"
							class:is-active={current.bagSizeG === g}
							onclick={() => patch({ bagSizeG: g })}
						>
							{g}<span class="qchip-unit">g</span>
						</button>
					{/each}
				</div>
			</div>
			<div class="be-row">
				<span class="be-label">Remaining</span>
				<QStepper
					value={current.remainingG}
					unit="g"
					min={0}
					max={Math.max(current.bagSizeG, 2000)}
					step={1}
					onChange={(v) => patch({ remainingG: v })}
					fmt={(v) => v.toFixed(0)}
				/>
				<!--
					PM call (2026-05-23, bean-editor-polish):
					We keep "Refill bag" since it's a common path after a
					weighing reset, but drop the old "Mark empty" button —
					the auto-debit on each shot handles emptying the bag,
					and a user who needs to zero it can edit the value
					inline. See task brief §5.
				-->
				<div class="be-row-inline">
					<button
						class="be-mini"
						type="button"
						onclick={() => patch({ remainingG: current.bagSizeG })}
					>
						<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i>
						Refill to bag size
					</button>
				</div>
			</div>
			<div class="be-row-grid">
				<label class="be-row">
					<span class="be-label">Mix</span>
					<select
						class="be-input"
						value={current.mix ?? ''}
						onchange={(e) => {
							const v = e.currentTarget.value;
							patch({ mix: v === 'single' || v === 'blend' ? v : null });
						}}
					>
						<option value="">—</option>
						<option value="single">Single origin</option>
						<option value="blend">Blend</option>
					</select>
				</label>
				<label class="be-row">
					<span class="be-label">Grinder</span>
					<input
						class="be-input"
						value={current.grinder}
						placeholder="e.g. Niche Zero"
						oninput={(e) => patch({ grinder: e.currentTarget.value })}
					/>
				</label>
			</div>
			<div class="be-row">
				<span class="be-label">Grind</span>
				<QStepper
					value={grindNum}
					min={0}
					max={20}
					step={0.1}
					onChange={(v) => setGrind(v)}
					fmt={(v) => v.toFixed(1)}
				/>
			</div>
		</section>

		<!-- Origin (collapsible) -->
		<section class="be-section">
			<button class="be-collapse" onclick={() => (showOrigin = !showOrigin)}>
				<i class={'ph ph-caret-' + (showOrigin ? 'down' : 'right')} aria-hidden="true"></i>
				<span>Origin</span>
			</button>
			{#if showOrigin}
				<div class="be-row-grid">
					<label class="be-row">
						<span class="be-label">Country</span>
						<input
							class="be-input"
							value={current.origin.country ?? ''}
							oninput={(e) =>
								patch({
									origin: { ...current.origin, country: e.currentTarget.value || null }
								})}
						/>
					</label>
					<label class="be-row">
						<span class="be-label">Region</span>
						<input
							class="be-input"
							value={current.origin.region ?? ''}
							oninput={(e) =>
								patch({
									origin: { ...current.origin, region: e.currentTarget.value || null }
								})}
						/>
					</label>
				</div>
				<div class="be-row-grid">
					<label class="be-row">
						<span class="be-label">Variety</span>
						<input
							class="be-input"
							value={current.origin.variety ?? ''}
							oninput={(e) =>
								patch({
									origin: { ...current.origin, variety: e.currentTarget.value || null }
								})}
						/>
					</label>
					<label class="be-row">
						<span class="be-label">Processing</span>
						<input
							class="be-input"
							value={current.origin.processing ?? ''}
							placeholder="Washed, Natural, Anaerobic…"
							oninput={(e) =>
								patch({
									origin: {
										...current.origin,
										processing: e.currentTarget.value || null
									}
								})}
						/>
					</label>
				</div>
				<div class="be-row-grid">
					<label class="be-row">
						<span class="be-label">Farm</span>
						<input
							class="be-input"
							value={current.origin.farm ?? ''}
							oninput={(e) =>
								patch({
									origin: { ...current.origin, farm: e.currentTarget.value || null }
								})}
						/>
					</label>
					<label class="be-row">
						<span class="be-label">Elevation</span>
						<input
							class="be-input"
							value={current.origin.elevation ?? ''}
							placeholder="1900-2100 masl"
							oninput={(e) =>
								patch({
									origin: {
										...current.origin,
										elevation: e.currentTarget.value || null
									}
								})}
						/>
					</label>
				</div>
			{/if}
		</section>

		<!-- Tasting -->
		<section class="be-section">
			<h3 class="be-section-title">Tasting</h3>
			<div class="be-row-grid">
				<label class="be-row">
					<span class="be-label">Quality score</span>
					<input
						class="be-input"
						value={current.qualityScore}
						placeholder="88 · A- · score"
						oninput={(e) => patch({ qualityScore: e.currentTarget.value })}
					/>
				</label>
				<div class="be-row">
					<span class="be-label">Rating</span>
					<div class="be-stars">
						{#each [1, 2, 3, 4, 5] as n (n)}
							<button
								class="be-star"
								class:is-active={current.rating >= n}
								onclick={() => ratingTap(n)}
								aria-label="{n} star"
							>
								<i
									class={current.rating >= n ? 'ph-fill ph-star' : 'ph ph-star'}
									aria-hidden="true"
								></i>
							</button>
						{/each}
					</div>
				</div>
			</div>
			<label class="be-row">
				<span class="be-label">Tasting notes</span>
				<textarea
					class="be-textarea"
					rows="3"
					value={current.tastingNotes}
					placeholder="Stone fruit, jasmine, honey…"
					oninput={(e) => patch({ tastingNotes: e.currentTarget.value })}
				></textarea>
			</label>
			<label class="be-row">
				<span class="be-label">Notes</span>
				<textarea
					class="be-textarea"
					rows="2"
					value={current.notes}
					placeholder="Personal notes — drift on dial-in, store at…"
					oninput={(e) => patch({ notes: e.currentTarget.value })}
				></textarea>
			</label>
		</section>

		<!-- Tags -->
		<section class="be-section">
			<h3 class="be-section-title">Tags</h3>
			<div class="be-row">
				<TagInput
					tags={current.tags}
					suggestions={tagSuggestions}
					onChange={(t) => patch({ tags: t })}
				/>
			</div>
		</section>

		<!-- Buy again (collapsible) -->
		<section class="be-section">
			<button class="be-collapse" onclick={() => (showBuyAgain = !showBuyAgain)}>
				<i class={'ph ph-caret-' + (showBuyAgain ? 'down' : 'right')} aria-hidden="true"></i>
				<span>Buy again</span>
			</button>
			{#if showBuyAgain}
				<label class="be-row">
					<span class="be-label">URL</span>
					<input
						class="be-input"
						type="url"
						value={current.url ?? ''}
						placeholder="https://…"
						oninput={(e) => patch({ url: e.currentTarget.value || null })}
					/>
				</label>
				<label class="be-row">
					<span class="be-label">Place of purchase</span>
					<input
						class="be-input"
						value={current.placeOfPurchase ?? ''}
						oninput={(e) =>
							patch({ placeOfPurchase: e.currentTarget.value || null })}
					/>
				</label>
			{/if}
		</section>

		<!-- Shots with this bean (edit mode only — a new bean has no shots yet) -->
		{#if !isNew && shotsWithThis.length > 0}
			<section class="be-section">
				<h3 class="be-section-title">
					Shots with this bag <span class="be-count">{shotsWithThis.length}</span>
				</h3>
				{#if bestShot}
					<div class="be-bestshot">
						<i class="ph ph-trophy" aria-hidden="true"></i>
						Best so far: {bestShot.rating} star · {bestShot.profileName ?? 'No profile'}
					</div>
				{/if}
				<ul class="be-shotlist">
					{#each shotsWithThis.slice(0, 8) as shot (shot.id)}
						<li class="be-shotitem">
							<span class="be-shotdate">
								{new Date(shot.completedAt).toLocaleString('en-US', {
									month: 'short',
									day: 'numeric',
									hour: 'numeric',
									minute: '2-digit'
								})}
							</span>
							<span class="be-shotprofile">{shot.profileName ?? '—'}</span>
							{#if shot.rating > 0}
								<span class="be-shotrating">{shot.rating}★</span>
							{/if}
						</li>
					{/each}
				</ul>
			</section>
		{/if}
	</div>

	{#if isNew}
		<footer class="be-footer">
			<button class="be-btn" type="button" onclick={cancelDraft}>Cancel</button>
			<button
				class="be-btn be-btn-primary"
				type="button"
				disabled={!canSave}
				onclick={commitDraftAndClose}
				title={canSave
					? undefined
					: 'A name, roaster, and roast date are required to save.'}
			>
				<i class="ph ph-check" aria-hidden="true"></i> Save bag
			</button>
		</footer>
	{/if}
</div>

<style>
	.be-scrim {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.4);
		z-index: 60;
	}
	.be-drawer {
		position: fixed;
		top: 0;
		right: 0;
		bottom: 0;
		width: min(520px, 100vw);
		background: var(--bg-page);
		border-left: 1px solid rgba(var(--tint-rgb), 0.1);
		z-index: 61;
		display: flex;
		flex-direction: column;
		overflow: hidden;
		box-shadow: -16px 0 40px rgba(0, 0, 0, 0.5);
	}
	.be-head {
		display: flex;
		justify-content: space-between;
		align-items: center;
		padding: 20px 24px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.be-title {
		font-family: var(--font-sans);
		font-size: 18px;
		font-weight: 600;
		margin: 4px 0 0;
		color: var(--fg-1);
	}
	.be-head-actions {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.be-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-pill);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 6px 12px;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.be-btn:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.be-btn:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}
	.be-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		border-color: var(--copper-500);
		font-weight: 600;
		padding: 8px 16px;
		font-size: 13px;
	}
	.be-btn-primary:hover:not(:disabled) {
		background: var(--copper-600);
	}
	.be-btn-icon {
		padding: 6px;
		border-radius: var(--radius-sm);
	}
	.be-active-pill {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: rgba(193, 124, 79, 0.12);
		border: 1px solid var(--copper-400);
		color: var(--copper-300);
		font-family: var(--font-sans);
		font-size: 11px;
		padding: 4px 10px;
		border-radius: var(--radius-pill);
	}
	.be-dot {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: var(--copper-400);
	}
	.be-body {
		flex: 1 1 auto;
		overflow-y: auto;
		padding: 16px 24px 40px;
		display: flex;
		flex-direction: column;
		gap: 24px;
	}
	.be-section {
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.be-section-title {
		font-family: var(--font-sans);
		font-size: 11px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
		font-weight: 600;
		margin: 0 0 4px;
	}
	.be-count {
		font-family: var(--font-mono);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.4);
		margin-left: 6px;
	}
	.be-row {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.be-row-grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 10px;
	}
	.be-row-inline {
		display: flex;
		gap: 12px;
		flex-wrap: wrap;
		align-items: center;
		margin-top: 4px;
	}
	.be-label {
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
		font-weight: 600;
	}
	.be-chip-spacer {
		height: 8px;
	}
	.be-input,
	.be-textarea {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 8px 10px;
		outline: 0;
		color-scheme: dark;
		width: 100%;
		box-sizing: border-box;
	}
	.be-textarea {
		font-family: var(--font-sans);
		resize: vertical;
	}
	.be-input:focus,
	.be-textarea:focus {
		border-color: var(--copper-400);
	}
	.be-input-date {
		max-width: 200px;
	}
	.be-date-row {
		display: flex;
		gap: 12px;
		align-items: center;
	}
	.be-fresh {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
	}
	.be-toggle-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 8px 0;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.05);
	}
	.be-toggle-row:last-child {
		border-bottom: 0;
	}
	.be-toggle-text {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.be-toggle-title {
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
		font-weight: 500;
	}
	.be-toggle-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.be-mini {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: transparent;
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: rgba(var(--tint-rgb), 0.7);
		font-family: var(--font-sans);
		font-size: 11px;
		padding: 6px 10px;
		cursor: pointer;
	}
	.be-mini:hover {
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.06);
	}
	.be-collapse {
		display: inline-flex;
		gap: 6px;
		background: transparent;
		border: 0;
		font-family: var(--font-sans);
		font-size: 11px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
		font-weight: 600;
		padding: 0;
		cursor: pointer;
		align-items: center;
	}
	.be-collapse:hover {
		color: var(--fg-1);
	}
	.be-stars {
		display: inline-flex;
		gap: 2px;
	}
	.be-star {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.3);
		cursor: pointer;
		font-size: 16px;
		padding: 4px;
	}
	.be-star.is-active {
		color: var(--copper-400);
	}
	.be-bestshot {
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--copper-300);
		background: rgba(193, 124, 79, 0.08);
		padding: 8px 10px;
		border-radius: var(--radius-sm);
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.be-shotlist {
		list-style: none;
		padding: 0;
		margin: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.be-shotitem {
		display: grid;
		grid-template-columns: 130px 1fr auto;
		gap: 12px;
		padding: 6px 0;
		font-family: var(--font-sans);
		font-size: 12px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.05);
	}
	.be-shotdate {
		color: rgba(var(--tint-rgb), 0.55);
		font-variant-numeric: tabular-nums;
	}
	.be-shotprofile {
		color: var(--fg-1);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.be-shotrating {
		color: var(--copper-400);
	}
	.be-footer {
		display: flex;
		justify-content: flex-end;
		gap: 10px;
		padding: 14px 24px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.08);
		background: var(--bg-page);
	}
</style>
