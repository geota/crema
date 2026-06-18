<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	import ArchiveIcon from 'phosphor-svelte/lib/ArchiveIcon';
	import ArrowLeftIcon from 'phosphor-svelte/lib/ArrowLeftIcon';
	import CheckCircleIcon from 'phosphor-svelte/lib/CheckCircleIcon';
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import DropHalfIcon from 'phosphor-svelte/lib/DropHalfIcon';
	import InfoIcon from 'phosphor-svelte/lib/InfoIcon';
	import StarIcon from 'phosphor-svelte/lib/StarIcon';
	/**
	 * `BeanEditPage` — full-page bean editor. Replaces the old drawer-style
	 * `BeanEditor` for both create and edit flows. Layout follows the
	 * design's `BeanEditPage.svelte`:
	 *
	 *   ┌─ topbar: ← Beans · eyebrow · title · Cancel / Save ─────┐
	 *   ├─ left rail: photo drop placeholder + section TOC      ├─ right: form blocks
	 *
	 * Each form block is a `StGroup`-style heading + label/control rows.
	 * Roast level uses the design's `RoastSlider` (1-10 with band labels
	 * underneath). Everything else uses Crema primitives:
	 *
	 * - `QuickStepper`     for grams (bag size, remaining).
	 * - native `<select>` for the mix / origin processing dropdowns.
	 * - `StToggle`    for the boolean flags (decaf, favourite, frozen).
	 * - `TagInput`    (the one used by /profiles) for tags.
	 * - `RoasterAutocomplete` for roaster (existing component, kept).
	 *
	 * Both create and edit flows go through this one component. Create-
	 * mode (`isNew={true}`) holds a local draft and writes to the store
	 * on Save; edit-mode commits each patch immediately.
	 */
	import { tick, untrack } from 'svelte';
	import { getProfileStore } from '$lib/profiles';
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import {
		getBeanStore,
		getBeanImageStore,
		beanImageRefFor,
		daysOffRoast,
		roastBand5,
		roasterMarkTone,
		blankBean,
		type Bean,
		type Roaster, activateBean } from '$lib/bean';
	import BeanImage from './BeanImage.svelte';
	import BeanDeleteSplit from './BeanDeleteSplit.svelte';
	import QuickStepper from '$lib/components/brew/QuickStepper.svelte';
	import StToggle from '$lib/components/settings/StToggle.svelte';
	import RoasterAutocomplete from './RoasterAutocomplete.svelte';
	import TagInput from '$lib/components/profiles/TagInput.svelte';
	import RoastSlider from './RoastSlider.svelte';
	import { getSettingsStore } from '$lib/settings';
	import { confirmDialog } from '$lib/components/shared/confirm-dialog.svelte';

	let {
		bean,
		isNew = false,
		tagSuggestions = []
	}: {
		bean: Bean;
		isNew?: boolean;
		tagSuggestions?: string[];
	} = $props();

	const library = getBeanStore();
	/**
	 * Equipment-level grinder default from Settings → Machine. Surfaced
	 * as a small "default" chip below the bean's Grinder field when no
	 * per-bag override is set, so the user understands the value the
	 * brew card will fall back to.
	 */
	const settings = getSettingsStore();
	const defaultGrinder = $derived(settings.current.grinderModel?.trim() ?? '');

	// ── Draft state (new mode only) ────────────────────────────────────
	const live = $derived(!isNew);
	let draftRecord = $state<Bean>(
		untrack(() => ({ ...bean, origin: { ...bean.origin }, tags: [...bean.tags] }))
	);
	const current: Bean = $derived(live ? bean : draftRecord);

	// ── Linked profile (bean → profile auto-load) ────────────────────────
	const profileStore = getProfileStore();
	// The built-in library loads lazily (wasm) — kick it so the select has
	// options even when this editor is the first profile consumer.
	void profileStore.ensureLoaded();
	/** All loadable profiles for the Linked-profile select. */
	const profileOptions = $derived(profileStore.all.map((p) => ({ id: p.id, name: p.name })));
	/** The select's value — '' for none. */
	const linkedProfileValue = $derived(current.linkedProfileId ?? '');
	/** Dangling link (profile deleted / device-local import) — keep it
	 *  selectable so the user sees it and can clear it. */
	const linkedMissing = $derived(
		current.linkedProfileId != null && !profileStore.all.some((p) => p.id === current.linkedProfileId)
	);

	// Roaster + autocomplete state.
	const initialRoaster = untrack(() =>
		bean.roasterId ? library.getRoaster(bean.roasterId) : null
	);
	let resolvedRoaster = $state<Roaster | null>(initialRoaster);
	let roasterName = $state(initialRoaster?.name ?? '');
	$effect(() => {
		if (!live) return;
		const r = current.roasterId ? library.getRoaster(current.roasterId) : null;
		if ((r?.id ?? null) !== (resolvedRoaster?.id ?? null)) {
			resolvedRoaster = r;
			roasterName = r?.name ?? '';
		}
	});

	const mt = $derived(roasterMarkTone(resolvedRoaster));

	// ── Bag photo (IndexedDB blob via `Bean.imageRef`) ─────────────────
	// Display is handled by <BeanImage>; this component owns the
	// upload + remove side. `hasPhoto` mirrors `current.imageRef` so
	// template copy and the Remove button gate on it without
	// duplicating the optional-chain.
	const imageStore = getBeanImageStore();
	const hasPhoto = $derived(current.imageRef != null);
	let photoUploading = $state(false);
	let photoError = $state<string | null>(null);

	async function onPhotoPicked(event: Event): Promise<void> {
		const input = event.currentTarget as HTMLInputElement;
		const file = input.files?.[0];
		input.value = '';
		if (!file) return;
		photoError = null;
		photoUploading = true;
		try {
			const ref = beanImageRefFor(current.id);
			await imageStore.put(ref, file);
			patch({ imageRef: ref });
		} catch (e) {
			photoError =
				e instanceof Error ? e.message : 'Could not save the photo.';
		} finally {
			photoUploading = false;
		}
	}

	async function removePhoto(): Promise<void> {
		const ref = current.imageRef;
		if (!ref) return;
		photoError = null;
		try {
			await imageStore.delete(ref);
		} catch {
			// Non-fatal — clearing the ref is the source of truth.
		}
		patch({ imageRef: null });
	}

	// ── Derived display values ─────────────────────────────────────────
	const days = $derived(daysOffRoast(current.roastedOn));
	const isActive = $derived(library.activeBeanId === current.id);

	// ── Patch + commit ────────────────────────────────────────────────
	function patch(p: Partial<Bean>): void {
		// Bumping bag size re-seeds `remaining` — but ONLY for a brand-new bean
		// (the draft path) or one that's already empty. On the live path with
		// burn-down in progress, reseeding would silently discard the
		// remaining-grams the user has been tracking (GEN2): editing the bag size
		// of a half-finished bag must not reset it to full.
		if (Object.prototype.hasOwnProperty.call(p, 'bagSize') && p.bagSize != null) {
			if (isNew || current.remaining === 0) {
				(p as Partial<Bean>).remaining = p.bagSize;
			}
		}
		if (live) {
			library.updateBean(bean.id, p);
		} else {
			draftRecord = { ...draftRecord, ...p };
		}
	}

	function setOrigin(field: keyof Bean['origin'], value: string): void {
		const next = value.trim() ? value : null;
		patch({ origin: { ...current.origin, [field]: next } });
	}

	function setTags(next: string[]): void {
		patch({ tags: next });
	}

	function setRoasterNameInput(v: string): void {
		roasterName = v;
		if (live && !v.trim()) {
			patch({ roasterId: null });
			resolvedRoaster = null;
		}
	}

	function onRoasterResolve(r: Roaster | null): void {
		resolvedRoaster = r;
		if (live) patch({ roasterId: r?.id ?? null });
	}

	function resolveRoasterOrCreate(name: string): Roaster | null {
		const trimmed = name.trim();
		if (!trimmed) return null;
		const existing = library.findRoasterByName(trimmed);
		if (existing) return existing;
		return library.ensureRoaster(trimmed);
	}

	// ── Validation ────────────────────────────────────────────────────
	let attempted = $state(false);
	let nameInputEl = $state<HTMLInputElement | null>(null);
	let roasterRowEl = $state<HTMLDivElement | null>(null);
	let roastedDateInputEl = $state<HTMLInputElement | null>(null);

	const isNameMissing = $derived(!current.name.trim());
	const isRoasterMissing = $derived(!roasterName.trim());
	const isRoastedOnMissing = $derived(!current.roastedOn);
	// A bag can't be opened before it was roasted. ISO yyyy-MM-dd strings compare
	// lexicographically = chronologically, so a plain `<` is enough.
	const isOpenedBeforeRoasted = $derived(
		!!current.roastedOn && !!current.openedOn && current.openedOn < current.roastedOn
	);

	/**
	 * URL validity gate — accepts an empty value (the field is optional),
	 * a bare URL with http:/https:, or rejects everything else (javascript:,
	 * data:, file:, mailto: …). The bag editor lets users paste in a roastery
	 * link for "Buy again"; only http(s) is safe to render as a clickable
	 * anchor downstream.
	 */
	function isValidHttpUrl(v: string | null | undefined): boolean {
		const s = (v ?? '').trim();
		if (s === '') return true;
		try {
			const u = new URL(s);
			return u.protocol === 'http:' || u.protocol === 'https:';
		} catch {
			return false;
		}
	}

	const isBuyUrlInvalid = $derived(!isValidHttpUrl(current.url));
	let urlTouched = $state(false);
	const urlInputId = 'be-url-input';
	const urlErrorId = 'be-url-error';

	async function save(activate: boolean): Promise<void> {
		attempted = true;
		urlTouched = true;
		await tick();
		// URL validity is enforced for both new and live edits — an invalid
		// `https://…` survives in storage until the next save anyway, so
		// the easiest place to catch it is at the same gate as the required
		// fields. Scroll the URL field into view + bounce out.
		if (isBuyUrlInvalid) {
			const el = document.getElementById(urlInputId) as HTMLInputElement | null;
			el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
			el?.focus();
			return;
		}
		// Opened-before-roasted is invalid for both new bags and live edits.
		if (isOpenedBeforeRoasted) {
			const el = document.getElementById('be-opened-input') as HTMLInputElement | null;
			el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
			el?.focus();
			return;
		}
		if (isNew) {
			if (isNameMissing) {
				nameInputEl?.scrollIntoView({ behavior: 'smooth', block: 'center' });
				nameInputEl?.focus();
				return;
			}
			if (isRoasterMissing) {
				roasterRowEl?.scrollIntoView({ behavior: 'smooth', block: 'center' });
				roasterRowEl?.querySelector('input')?.focus();
				return;
			}
			if (isRoastedOnMissing) {
				roastedDateInputEl?.scrollIntoView({ behavior: 'smooth', block: 'center' });
				roastedDateInputEl?.focus();
				return;
			}
			const roaster = resolveRoasterOrCreate(roasterName);
			const remaining =
				draftRecord.remaining > 0 ? draftRecord.remaining : draftRecord.bagSize;
			const persisted: Bean = {
				...draftRecord,
				roasterId: roaster?.id ?? null,
				name: draftRecord.name.trim(),
				remaining: remaining,
				updatedAt: Date.now()
			};
			library.upsertBean(persisted);
			if (activate) activateBean(persisted.id);
			goto(resolve('/beans'));
		} else {
			// Live mode — already saved every patch. Just commit any pending
			// roaster name change and bounce back.
			if (roasterName.trim() && resolvedRoaster?.name.trim().toLowerCase() !==
				roasterName.trim().toLowerCase()) {
				const r = resolveRoasterOrCreate(roasterName);
				if (r) {
					resolvedRoaster = r;
					patch({ roasterId: r.id });
				}
			}
			if (activate && !isActive) activateBean(current.id);
			goto(resolve('/beans'));
		}
	}

	function back(): void {
		goto(resolve('/beans'));
	}

	async function discard(): Promise<void> {
		if (!(await confirmDialog({ message: 'Discard changes?', confirmLabel: 'Discard' }))) return;
		if (isNew) {
			back();
		} else {
			// In live mode we can't undo; just bounce back.
			back();
		}
	}

	// ── Helpers for the date hints ─────────────────────────────────────
	function dateHint(days: number | null): string {
		if (days == null) return '';
		if (days === 0) return 'today';
		if (days === 1) return '1d ago';
		return `${days}d ago`;
	}

	// ── TOC items ──────────────────────────────────────────────────────
	// Tags moved inline under the Roaster field in the Identity block, so
	// the standalone Tags entry is gone and the buy-again numbering shifts
	// down. "Bag & grinder" → "Bag & Grind" matches the new block heading
	// and the QuickStepper label used in quick controls.
	const TOC = [
		{ id: 'identity', label: 'Identity' },
		{ id: 'roast', label: 'Roast & mix' },
		{ id: 'dates', label: 'Dates' },
		{ id: 'bag', label: 'Bag & Grind' },
		{ id: 'origin', label: 'Origin' },
		{ id: 'tasting', label: 'Tasting' },
		{ id: 'buy', label: 'Buy again' },
		{ id: 'notes', label: 'Notes' }
	] as const;

	function scrollToSection(id: string): void {
		const el = document.getElementById(`be-${id}`);
		if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
	}

	// Bag-size presets, ordered ascending — covers the most common retail
	// sizes from the tiny 113g sampler through to the 1kg home-bulk bag.
	// 907g (2lb) is omitted to keep the row from wrapping; the 5lb (2268g)
	// wholesale brick is excluded since it's seldom a home unit.
	const BAG_PRESETS = [113, 227, 250, 340, 454, 1000] as const;

	function setRoastLevel(n: number): void {
		patch({ roastLevel: Math.max(1, Math.min(10, Math.round(n))) });
	}

	function setRating(n: number): void {
		patch({ rating: current.rating === n ? 0 : n });
	}

	// Grinder setting — string in storage, number in UI for the stepper.
	const grindNum = $derived.by<number>(() => {
		const n = Number(current.grinderSetting);
		return Number.isFinite(n) ? n : 0;
	});
	function setGrind(n: number): void {
		const rounded = Math.round(n * 100) / 100;
		patch({ grinderSetting: rounded.toString() });
	}
</script>

<div class="be-page">
	<!-- Topbar -->
	<header class="be-bar">
		<button class="be-back" onclick={back}>
			<ArrowLeftIcon aria-hidden="true" /> Beans
		</button>
		<div class="be-bar-mid">
			<div class="t-eyebrow">{isNew ? 'New bean' : 'Edit bean'}</div>
			<div class="be-bar-name">{current.name || 'Untitled bag'}</div>
		</div>
		<div class="be-bar-actions">
			<button class="be-btn be-btn-ghost" onclick={discard}>Cancel</button>
			<button class="be-btn be-btn-primary" onclick={() => save(false)}>
				<CheckIcon aria-hidden="true" />
				Save
			</button>
		</div>
	</header>

	<div class="be-body">
		<!-- Left rail -->
		<aside class="be-rail">
			<div class="be-photo">
				<label class="be-photo-drop" style="--tone: {mt.tone}">
					<BeanImage ref={current.imageRef} className="be-photo-img">
						{#snippet fallback()}
							<div class="be-photo-mark">{mt.mark}</div>
						{/snippet}
					</BeanImage>
					<input
						type="file"
						accept="image/*"
						class="be-photo-input"
						onchange={onPhotoPicked}
						disabled={photoUploading}
						aria-label={hasPhoto ? 'Replace bag photo' : 'Add bag photo'}
					/>
					<span class="be-photo-overlay">
						<i
							class="ph {hasPhoto ? 'ph-pencil-simple' : 'ph-camera-plus'}"
							aria-hidden="true"
						></i>
					</span>
				</label>
				<div class="be-photo-cap">{resolvedRoaster?.name ?? 'No roaster'}</div>
				<div class="be-photo-actions">
					{#if hasPhoto}
						<button class="be-photo-link" type="button" onclick={removePhoto}>
							Remove photo
						</button>
					{/if}
				</div>
				{#if photoError}
					<div class="be-photo-error">{photoError}</div>
				{:else}
					<div class="be-photo-sub">
						{#if hasPhoto}
							Click the photo to replace it.
						{:else}
							Add a JPG/PNG of the bag (any photo from your phone or
							pulled in from a Beanconqueror import).
						{/if}
					</div>
				{/if}
			</div>
			<nav class="be-toc" aria-label="Sections">
				{#each TOC as s, i (s.id)}
					<button class="be-toc-item" onclick={() => scrollToSection(s.id)}>
						<span class="be-toc-n">{String(i + 1).padStart(2, '0')}</span>
						<span>{s.label}</span>
					</button>
				{/each}
			</nav>
			<div class="be-rail-help">
				<InfoIcon weight="duotone" aria-hidden="true" />
				<span>
					Required fields are marked <span class="be-req">*</span>. Everything
					else is optional and editable later.
				</span>
			</div>
		</aside>

		<!-- Right: form -->
		<main class="be-main">
			<!-- Identity -->
			<section class="be-block" id="be-identity">
				<header class="be-block-head">
					<span class="be-block-n">01</span>
					<div>
						<h3 class="be-block-title">Identity</h3>
						<div class="be-block-sub">
							The name and roaster this bag will be filed under.
						</div>
					</div>
				</header>
				<div class="be-block-body">
					<div class="be-frow">
						<div class="be-frow-l">
							<div class="be-frow-label">Name <span class="be-req">*</span></div>
						</div>
						<div class="be-frow-r">
							<input
								bind:this={nameInputEl}
								class="be-input"
								class:is-invalid={attempted && isNameMissing}
								value={current.name}
								placeholder="e.g. Geisha Esmeralda Lot 3"
								oninput={(e) =>
									patch({ name: (e.currentTarget as HTMLInputElement).value })}
							/>
							{#if attempted && isNameMissing}
								<div class="be-required">Required</div>
							{/if}
						</div>
					</div>

					<div class="be-frow" bind:this={roasterRowEl}>
						<div class="be-frow-l">
							<div class="be-frow-label">Roaster <span class="be-req">*</span></div>
						</div>
						<div class="be-frow-r">
							<RoasterAutocomplete
								value={roasterName}
								resolved={resolvedRoaster}
								roasters={library.roasters}
								placeholder="Search or create roaster"
								invalid={attempted && isRoasterMissing}
								onChange={setRoasterNameInput}
								onResolve={(r) => {
									onRoasterResolve(r);
									if (r) roasterName = r.name;
								}}
								onCommitTyped={live
									? (typed) => {
											const r = resolveRoasterOrCreate(typed);
											if (r) {
												resolvedRoaster = r;
												patch({ roasterId: r.id });
											}
										}
									: undefined}
							/>
							{#if attempted && isRoasterMissing}
								<div class="be-required">Required</div>
							{/if}
						</div>
					</div>

					<!--
					    Tags moved into the Identity block (below Roaster) per the
					    design alignment pass — the standalone Tags section is
					    gone. Same TagInput component, same suggestion list, just
					    a different home.
					-->
					<div class="be-frow">
						<div class="be-frow-l">
							<div class="be-frow-label">Tags</div>
							<div class="be-frow-sub">
								Free-form labels — used to filter the library grid.
							</div>
						</div>
						<div class="be-frow-r">
							<TagInput tags={current.tags} suggestions={tagSuggestions} onChange={setTags} />
						</div>
					</div>

					<!-- Linked profile — auto-loads on activation (bean → profile). -->
					<div class="be-frow be-frow-stack">
						<div class="be-frow-l">
							<div class="be-frow-label">Linked profile</div>
							<div class="be-frow-sub">Auto-loads when this bean is selected on Brew.</div>
						</div>
						<div class="be-frow-r">
							<select
								class="be-input"
								value={linkedProfileValue}
								onchange={(e) => {
									const v = (e.currentTarget as HTMLSelectElement).value;
									patch({ linkedProfileId: v === '' ? null : v });
								}}
							>
								<option value="">None</option>
								{#each profileOptions as p (p.id)}
									<option value={p.id}>{p.name}</option>
								{/each}
								{#if linkedMissing}
									<option value={current.linkedProfileId}>(missing profile)</option>
								{/if}
							</select>
						</div>
					</div>

					<div class="be-frow">
						<div class="be-frow-l">
							<div class="be-frow-label">Flags</div>
							<div class="be-frow-sub">
								Active selects this bag on Brew; pin keeps it on the brew
								strip; decaf is a filter facet; archived hides it from the
								active grid.
							</div>
						</div>
						<div class="be-frow-r">
							<div class="be-flagrow">
								<!-- Active toggle drives the library's activeBeanId pointer.
								     Effectively replaces the "& make active" half of the
								     prior Save button. -->
								<div class="be-flag" class:is-on={isActive}>
									<CheckCircleIcon weight="fill" aria-hidden="true" />
									<span>Active</span>
									<StToggle
										on={isActive}
										onChange={(v) => {
											if (v) activateBean(current.id);
											else if (isActive) library.setActiveBean(null);
										}}
										label="Active"
									/>
								</div>
								<div class="be-flag" class:is-on={current.favourite}>
									<StarIcon weight="fill" aria-hidden="true" />
									<span>Pinned</span>
									<StToggle
										on={current.favourite}
										onChange={(v) => patch({ favourite: v })}
										label="Pinned"
									/>
								</div>
								<div class="be-flag" class:is-on={current.decaf}>
									<DropHalfIcon aria-hidden="true" />
									<span>Decaf</span>
									<StToggle
										on={current.decaf}
										onChange={(v) => patch({ decaf: v })}
										label="Decaf"
									/>
								</div>
								<div class="be-flag" class:is-on={current.archivedAt != null}>
									<ArchiveIcon aria-hidden="true" />
									<span>Archived</span>
									<StToggle
										on={current.archivedAt != null}
										onChange={(v) => patch({ archivedAt: v ? Date.now() : null })}
										label="Archived"
									/>
								</div>
							</div>
						</div>
					</div>
				</div>
			</section>

			<!-- Roast & mix -->
			<section class="be-block" id="be-roast">
				<header class="be-block-head">
					<span class="be-block-n">02</span>
					<div>
						<h3 class="be-block-title">Roast &amp; mix</h3>
						<div class="be-block-sub">
							Roast level drives the freshness window. Mix changes how the
							origin block reads.
						</div>
					</div>
				</header>
				<div class="be-block-body">
					<div class="be-frow be-frow-stack">
						<div class="be-frow-l">
							<div class="be-frow-label">Roast level</div>
							<div class="be-frow-sub">1 = light, 10 = dark</div>
						</div>
						<div class="be-frow-r">
							<RoastSlider
								value={current.roastLevel ?? 5}
								editMode
								onChange={setRoastLevel}
							/>
						</div>
					</div>
					<div class="be-frow">
						<div class="be-frow-l">
							<div class="be-frow-label">Mix</div>
							<div class="be-frow-sub">Single origin or blend.</div>
						</div>
						<div class="be-frow-r be-frow-r-end">
							<!--
							    The mix toggle is a small segmented pill — same scale as
							    the settings `StSegment` so the editor reads as one
							    design family. Right-aligned within the value column so
							    the eye doesn't have to track from the left rule to a
							    floating pill on the right; it lives flush with the
							    column edge.
							-->
							<div class="be-seg be-seg-sm">
								<button
									type="button"
									class="be-seg-opt"
									class:is-on={current.mix === 'single'}
									onclick={() => patch({ mix: 'single' })}
								>Single</button>
								<button
									type="button"
									class="be-seg-opt"
									class:is-on={current.mix === 'blend'}
									onclick={() => patch({ mix: 'blend' })}
								>Blend</button>
							</div>
						</div>
					</div>
					<div class="be-frow">
						<div class="be-frow-l">
							<div class="be-frow-label">Roast type</div>
							<div class="be-frow-sub">What it's roasted for. Click an active option to clear.</div>
						</div>
						<div class="be-frow-r be-frow-r-end">
							<div class="be-seg be-seg-sm">
								<button
									type="button"
									class="be-seg-opt"
									class:is-on={current.roastType === 'espresso'}
									onclick={() =>
										patch({
											roastType: current.roastType === 'espresso' ? null : 'espresso'
										})}
								>Espresso</button>
								<button
									type="button"
									class="be-seg-opt"
									class:is-on={current.roastType === 'filter'}
									onclick={() =>
										patch({
											roastType: current.roastType === 'filter' ? null : 'filter'
										})}
								>Filter</button>
								<button
									type="button"
									class="be-seg-opt"
									class:is-on={current.roastType === 'omni'}
									onclick={() =>
										patch({
											roastType: current.roastType === 'omni' ? null : 'omni'
										})}
								>Omni</button>
							</div>
						</div>
					</div>
				</div>
			</section>

			<!-- Dates -->
			<section class="be-block" id="be-dates">
				<header class="be-block-head">
					<span class="be-block-n">03</span>
					<div>
						<h3 class="be-block-title">Dates</h3>
						<div class="be-block-sub">
							Roast date drives the freshness signal everywhere else.
						</div>
					</div>
				</header>
				<div class="be-block-body">
					<div class="be-grid2">
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Roasted on <span class="be-req">*</span></div>
								<div class="be-frow-sub">{dateHint(days)}</div>
							</div>
							<div class="be-frow-r">
								<input
									bind:this={roastedDateInputEl}
									class="be-input be-input-date"
									class:is-invalid={attempted && isRoastedOnMissing}
									type="date"
									value={current.roastedOn ?? ''}
									onchange={(e) =>
										patch({
											roastedOn:
												(e.currentTarget as HTMLInputElement).value || null
										})}
								/>
								{#if attempted && isRoastedOnMissing}
									<div class="be-required">Required</div>
								{/if}
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Opened on</div>
								<div class="be-frow-sub">When you cracked the bag.</div>
							</div>
							<div class="be-frow-r">
								<input
									id="be-opened-input"
									class="be-input be-input-date"
									class:is-invalid={isOpenedBeforeRoasted}
									type="date"
									min={current.roastedOn ?? undefined}
									value={current.openedOn ?? ''}
									onchange={(e) =>
										patch({
											openedOn:
												(e.currentTarget as HTMLInputElement).value || null
										})}
								/>
								{#if isOpenedBeforeRoasted}
									<div class="be-required">Can't be before the roast date</div>
								{/if}
							</div>
						</div>
					</div>
					<div class="be-frow">
						<div class="be-frow-l">
							<div class="be-frow-label">Frozen storage</div>
							<div class="be-frow-sub">Pauses the off-roast clock.</div>
						</div>
						<div class="be-frow-r">
							<StToggle
								on={!!current.frozenOn}
								onChange={(v) => {
									if (v) {
										// Flip ON — surface the date pickers with empty
										// values so the user fills them in. (The frozenOn
										// being non-null is what reveals the picker row,
										// so we seed with `null` and let the user pick.)
										//
										// To make the `{#if current.frozenOn}` reveal
										// trigger we seed `frozenOn` with today's date —
										// the user can edit it. defrostedOn stays null.
										const today = new Date().toISOString().slice(0, 10);
										patch({ frozenOn: today, defrostedOn: null });
									} else {
										// Flip OFF — hide the pickers AND clear the
										// stored dates so toggling back on starts from
										// a clean slate rather than re-showing the
										// previous values.
										patch({ frozenOn: null, defrostedOn: null });
									}
								}}
								label="Frozen"
							/>
						</div>
					</div>
					{#if current.frozenOn}
						<div class="be-grid2">
							<div class="be-frow be-frow-stack">
								<div class="be-frow-l">
									<div class="be-frow-label">Frozen on</div>
								</div>
								<div class="be-frow-r">
									<input
										class="be-input be-input-date"
										type="date"
										value={current.frozenOn ?? ''}
										onchange={(e) =>
											patch({
												frozenOn:
													(e.currentTarget as HTMLInputElement).value || null
											})}
									/>
								</div>
							</div>
							<div class="be-frow be-frow-stack">
								<div class="be-frow-l">
									<div class="be-frow-label">Defrosted on</div>
								</div>
								<div class="be-frow-r">
									<input
										class="be-input be-input-date"
										type="date"
										value={current.defrostedOn ?? ''}
										onchange={(e) =>
											patch({
												defrostedOn:
													(e.currentTarget as HTMLInputElement).value || null
											})}
									/>
								</div>
							</div>
						</div>
					{/if}
				</div>
			</section>

			<!-- Bag & Grind (renamed from "Bag & grinder" — matches the
			     QuickStepper "Grind" label in the brew quick-controls so the
			     mental model lines up). -->
			<section class="be-block" id="be-bag">
				<header class="be-block-head">
					<span class="be-block-n">04</span>
					<div>
						<h3 class="be-block-title">Bag &amp; Grind</h3>
						<div class="be-block-sub">
							Bag size auto-debits per shot. Grinder is bean-scoped — surfaced
							at brew time when you swap to this bean.
						</div>
					</div>
				</header>
				<div class="be-block-body">
					<div class="be-grid2">
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Bag size</div>
							</div>
							<div class="be-frow-r">
								<QuickStepper
									label=""
									value={current.bagSize}
									unit="g"
									min={0}
									max={5000}
									step={50}
									onChange={(n) => patch({ bagSize: n })}
								/>
								<div class="be-presets">
									{#each BAG_PRESETS as g (g)}
										<button
											type="button"
											class="be-preset"
											class:is-on={current.bagSize === g}
											onclick={() => patch({ bagSize: g })}
										>
											{g}<em>g</em>
										</button>
									{/each}
								</div>
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Remaining</div>
							</div>
							<div class="be-frow-r">
								<QuickStepper
									label=""
									value={current.remaining}
									unit="g"
									min={0}
									max={5000}
									step={1}
									onChange={(n) => patch({ remaining: n })}
								/>
							</div>
						</div>
					</div>
					<div class="be-grid2">
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Grinder</div>
							</div>
							<div class="be-frow-r">
								<input
									class="be-input"
									value={current.grinder}
									placeholder="e.g. Niche Zero"
									oninput={(e) =>
										patch({
											grinder: (e.currentTarget as HTMLInputElement).value
										})}
								/>
								{#if defaultGrinder && current.grinder.trim() === ''}
									<button
										type="button"
										class="be-default-chip"
										title="Use the equipment-level default from Settings → Machine"
										onclick={() => patch({ grinder: defaultGrinder })}
									>
										<span class="be-default-chip-tag">default</span>
										<span>{defaultGrinder}</span>
									</button>
								{/if}
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Grind</div>
							</div>
							<div class="be-frow-r">
								<QuickStepper
									label=""
									value={grindNum}
									unit=""
									min={0}
									max={100}
									step={0.1}
									onChange={setGrind}
								/>
							</div>
						</div>
					</div>
				</div>
			</section>

			<!-- Origin -->
			<section class="be-block" id="be-origin">
				<header class="be-block-head">
					<span class="be-block-n">05</span>
					<div>
						<h3 class="be-block-title">Origin</h3>
						<div class="be-block-sub">
							Free text. For blends, separate components with a slash (Ethiopia
							/ Colombia).
						</div>
					</div>
				</header>
				<div class="be-block-body">
					<div class="be-grid2">
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Country</div>
							</div>
							<div class="be-frow-r">
								<input
									class="be-input"
									value={current.origin.country ?? ''}
									placeholder="Ethiopia"
									oninput={(e) =>
										setOrigin('country', (e.currentTarget as HTMLInputElement).value)}
								/>
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Region</div>
							</div>
							<div class="be-frow-r">
								<input
									class="be-input"
									value={current.origin.region ?? ''}
									placeholder="Yirgacheffe"
									oninput={(e) =>
										setOrigin('region', (e.currentTarget as HTMLInputElement).value)}
								/>
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Farm / co-op</div>
							</div>
							<div class="be-frow-r">
								<input
									class="be-input"
									value={current.origin.farm ?? ''}
									placeholder="Halo Hartume"
									oninput={(e) =>
										setOrigin('farm', (e.currentTarget as HTMLInputElement).value)}
								/>
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Variety</div>
							</div>
							<div class="be-frow-r">
								<input
									class="be-input"
									value={current.origin.variety ?? ''}
									placeholder="Heirloom"
									oninput={(e) =>
										setOrigin('variety', (e.currentTarget as HTMLInputElement).value)}
								/>
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Elevation</div>
							</div>
							<div class="be-frow-r">
								<input
									class="be-input"
									value={current.origin.elevation ?? ''}
									placeholder="1900–2100 masl"
									oninput={(e) =>
										setOrigin('elevation', (e.currentTarget as HTMLInputElement).value)}
								/>
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Processing</div>
							</div>
							<div class="be-frow-r">
								<input
									class="be-input"
									value={current.origin.processing ?? ''}
									placeholder="Natural · Washed · Anaerobic…"
									oninput={(e) =>
										setOrigin(
											'processing',
											(e.currentTarget as HTMLInputElement).value
										)}
								/>
							</div>
						</div>
					</div>
					<div class="be-frow be-frow-stack">
						<div class="be-frow-l">
							<div class="be-frow-label">Harvest time</div>
						</div>
						<div class="be-frow-r">
							<input
								class="be-input"
								value={current.origin.harvestTime ?? ''}
								placeholder="Nov 2025"
								oninput={(e) =>
									setOrigin('harvestTime', (e.currentTarget as HTMLInputElement).value)}
							/>
						</div>
					</div>
				</div>
			</section>

			<!-- Tasting -->
			<section class="be-block" id="be-tasting">
				<header class="be-block-head">
					<span class="be-block-n">06</span>
					<div>
						<h3 class="be-block-title">Tasting</h3>
						<div class="be-block-sub">
							One free-text note; chase it with stars.
						</div>
					</div>
				</header>
				<div class="be-block-body">
					<div class="be-grid2">
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Score</div>
								<div class="be-frow-sub">
									Free text — number, letter grade, whatever you log.
								</div>
							</div>
							<div class="be-frow-r">
								<input
									class="be-input"
									value={current.qualityScore}
									placeholder="88"
									oninput={(e) =>
										patch({
											qualityScore: (e.currentTarget as HTMLInputElement).value
										})}
								/>
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Rating</div>
							</div>
							<div class="be-frow-r">
								<div class="be-rating">
									{#each [1, 2, 3, 4, 5] as i (i)}
										<button
											type="button"
											class="be-rating-btn"
											onclick={() => setRating(i)}
											aria-label="{i} of 5"
										>
											<Icon
												cls={i <= current.rating ? 'ph-fill ph-star' : 'ph ph-star'}
												color={i <= current.rating ? 'var(--copper-400)' : 'rgba(var(--tint-rgb), 0.18)'}
											/>
										</button>
									{/each}
								</div>
							</div>
						</div>
					</div>
					<div class="be-frow be-frow-stack">
						<div class="be-frow-l">
							<div class="be-frow-label">Tasting notes</div>
							<div class="be-frow-sub">
								Flavour, aroma, brewing advice — whatever the cup tells you.
							</div>
						</div>
						<div class="be-frow-r">
							<textarea
								class="be-input be-textarea"
								rows="4"
								value={current.tastingNotes}
								placeholder="Peach, jasmine, syrupy. Loves Blooming at 93°C, 5–14d off roast."
								oninput={(e) =>
									patch({
										tastingNotes: (e.currentTarget as HTMLTextAreaElement).value
									})}
							></textarea>
						</div>
					</div>
				</div>
			</section>

			<!-- Buy again (was 08 before the standalone Tags block was folded
			     up into Identity; numbering compacted as a result). -->
			<section class="be-block" id="be-buy">
				<header class="be-block-head">
					<span class="be-block-n">07</span>
					<div>
						<h3 class="be-block-title">Buy again</h3>
						<div class="be-block-sub">
							Used by the bag burn-down nudge when you're running low.
						</div>
					</div>
				</header>
				<div class="be-block-body">
					<div class="be-grid2">
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Place of purchase</div>
							</div>
							<div class="be-frow-r">
								<input
									class="be-input"
									value={current.placeOfPurchase ?? ''}
									placeholder="Roastery website · Cafe · Subscription"
									oninput={(e) =>
										patch({
											placeOfPurchase:
												(e.currentTarget as HTMLInputElement).value || null
										})}
								/>
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Cost</div>
							</div>
							<div class="be-frow-r">
								<input
									class="be-input"
									type="number"
									inputmode="decimal"
									min="0"
									step="0.01"
									value={current.cost ?? ''}
									placeholder="What you paid"
									oninput={(e) => {
										const raw = (e.currentTarget as HTMLInputElement).value;
										const n = raw === '' ? null : Number(raw);
										patch({
											cost: n != null && Number.isFinite(n) && n >= 0 ? n : null
										});
									}}
								/>
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">URL</div>
							</div>
							<div class="be-frow-r">
								<input
									id={urlInputId}
									class="be-input"
									class:is-invalid={(urlTouched || attempted) && isBuyUrlInvalid}
									type="url"
									inputmode="url"
									autocomplete="url"
									aria-invalid={(urlTouched || attempted) && isBuyUrlInvalid}
									aria-describedby={(urlTouched || attempted) && isBuyUrlInvalid
										? urlErrorId
										: undefined}
									value={current.url ?? ''}
									placeholder="https://…"
									oninput={(e) =>
										patch({
											url: (e.currentTarget as HTMLInputElement).value || null
										})}
									onblur={() => (urlTouched = true)}
								/>
								{#if (urlTouched || attempted) && isBuyUrlInvalid}
									<div id={urlErrorId} class="be-required">
										Must start with https:// or http://
									</div>
								{/if}
							</div>
						</div>
					</div>
				</div>
			</section>

			<!-- Notes — anything else worth keeping that isn't a tasting
			     impression or a re-buy detail. Kept as its own section so
			     it doesn't fight Tasting notes for the same row. -->
			<section class="be-block" id="be-notes">
				<header class="be-block-head">
					<span class="be-block-n">08</span>
					<div>
						<h3 class="be-block-title">Notes</h3>
						<div class="be-block-sub">
							Anything else worth keeping — not surfaced on the brew page.
						</div>
					</div>
				</header>
				<div class="be-block-body">
					<div class="be-frow be-frow-stack">
						<div class="be-frow-r">
							<textarea
								class="be-input be-textarea"
								rows="4"
								value={current.notes}
								placeholder="Bought at the cafe down the road. They were closing out."
								oninput={(e) =>
									patch({
										notes: (e.currentTarget as HTMLTextAreaElement).value
									})}
							></textarea>
						</div>
					</div>
				</div>
			</section>

			<div class="be-foot">
				{#if !isNew}
					<div class="be-foot-del">
						<BeanDeleteSplit
							beanId={current.id}
							beanName={current.name || 'this bag'}
							label="Delete bean"
							size="md"
							onDeleted={() => goto(resolve('/beans'))}
						/>
					</div>
				{/if}
				<button class="be-btn be-btn-ghost" onclick={discard}>Cancel</button>
				<button class="be-btn be-btn-primary be-btn-lg" onclick={() => save(false)}>
					<CheckIcon aria-hidden="true" />
					Save
				</button>
			</div>
		</main>
	</div>
</div>

<style>
	.be-page {
		min-height: 100vh;
		background: var(--bg-page);
		color: var(--fg-1);
		display: flex;
		flex-direction: column;
	}

	/* Top bar */
	.be-bar {
		display: flex;
		align-items: center;
		gap: 16px;
		padding: 14px var(--page-pad-x, 24px);
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.08);
		position: sticky;
		top: 0;
		background: var(--bg-page);
		z-index: 10;
	}
	.be-back {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.7);
		font-family: var(--font-sans);
		font-size: 13px;
		cursor: pointer;
		padding: 6px 8px;
		border-radius: var(--radius-sm);
	}
	.be-back:hover {
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.06);
	}
	.be-bar-mid {
		flex: 1 1 auto;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.be-bar-name {
		font-family: var(--font-serif);
		font-size: 16px;
		font-weight: 500;
		color: var(--fg-1);
		letter-spacing: -0.01em;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.be-bar-actions {
		display: inline-flex;
		gap: 8px;
		align-items: center;
	}

	.be-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 8px 16px;
		border-radius: var(--radius-pill);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		border: 1px solid transparent;
		transition: all var(--dur-1) var(--ease);
	}
	.be-btn-ghost {
		background: rgba(var(--tint-rgb), 0.04);
		border-color: rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
	}
	.be-btn-ghost:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.be-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.be-btn-primary:hover {
		background: var(--copper-600);
	}
	.be-btn-lg {
		padding: 10px 22px;
		font-size: 14px;
	}

	/* Body — two-column layout */
	.be-body {
		display: grid;
		grid-template-columns: 240px 1fr;
		gap: 28px;
		padding: 28px var(--page-pad-x, 24px) 60px;
		max-width: 1180px;
		width: 100%;
		margin: 0 auto;
	}
	@media (max-width: 880px) {
		.be-body {
			grid-template-columns: 1fr;
			gap: 16px;
			padding: 18px var(--page-pad-x, 16px) 40px;
		}
	}

	/* Rail */
	.be-rail {
		display: flex;
		flex-direction: column;
		gap: 18px;
		position: sticky;
		top: 72px;
		align-self: flex-start;
	}
	@media (max-width: 880px) {
		.be-rail {
			position: static;
		}
	}
	.be-photo {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.be-photo-drop {
		aspect-ratio: 1 / 1;
		width: 100%;
		border-radius: var(--radius-md);
		background:
			linear-gradient(135deg, color-mix(in srgb, var(--tone) 88%, transparent), var(--tone)),
			var(--tone);
		display: flex;
		align-items: center;
		justify-content: center;
		overflow: hidden;
		position: relative;
		cursor: pointer;
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
		transition: filter var(--dur-1) var(--ease);
	}
	.be-photo-drop:hover {
		filter: brightness(0.92);
	}
	:global(.be-photo-img) {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.be-photo-input {
		position: absolute;
		inset: 0;
		opacity: 0;
		width: 100%;
		height: 100%;
		cursor: pointer;
	}
	.be-photo-overlay {
		position: absolute;
		right: 8px;
		bottom: 8px;
		width: 28px;
		height: 28px;
		border-radius: 999px;
		background: rgba(0, 0, 0, 0.55);
		color: #fff;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 14px;
		pointer-events: none;
	}
	.be-photo-mark {
		font-family: var(--font-serif);
		font-weight: 500;
		font-size: 56px;
		color: rgba(255, 255, 255, 0.94);
		letter-spacing: -0.02em;
	}
	.be-photo-cap {
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.6);
		margin-top: 8px;
	}
	.be-photo-actions {
		display: flex;
		gap: 8px;
		margin-top: 2px;
	}
	.be-photo-link {
		background: transparent;
		border: 0;
		padding: 0;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		text-decoration: underline;
		text-underline-offset: 2px;
		cursor: pointer;
	}
	.be-photo-link:hover {
		color: var(--fg-1);
	}
	.be-photo-error {
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--danger);
		line-height: 1.5;
	}
	.be-photo-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
		line-height: 1.5;
	}
	.be-toc {
		display: flex;
		flex-direction: column;
		gap: 1px;
	}
	.be-toc-item {
		display: inline-flex;
		gap: 10px;
		align-items: center;
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		text-align: left;
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 6px 8px;
		border-radius: var(--radius-sm);
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.be-toc-item:hover {
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.04);
	}
	.be-toc-n {
		font-family: var(--font-mono);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.4);
	}
	.be-rail-help {
		display: flex;
		gap: 8px;
		align-items: flex-start;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-sm);
		padding: 10px;
		line-height: 1.5;
	}
	.be-rail-help :global(svg) {
		color: var(--copper-400);
		font-size: 14px;
	}
	.be-req {
		color: var(--copper-400);
		font-weight: 700;
	}

	/* Main form */
	.be-main {
		display: flex;
		flex-direction: column;
		gap: 24px;
		min-width: 0;
	}
	.be-block {
		background: rgba(var(--tint-rgb), 0.025);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-md);
		overflow: hidden;
	}
	.be-block-head {
		display: flex;
		gap: 12px;
		align-items: flex-start;
		padding: 16px 18px 4px;
	}
	.be-block-n {
		font-family: var(--font-mono);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.4);
		margin-top: 4px;
	}
	.be-block-title {
		font-family: var(--font-serif);
		font-size: 19px;
		font-weight: 500;
		color: var(--fg-1);
		letter-spacing: -0.01em;
		margin: 0;
	}
	.be-block-sub {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
		margin-top: 2px;
		line-height: 1.45;
	}
	.be-block-body {
		display: flex;
		flex-direction: column;
		gap: 14px;
		padding: 14px 18px 18px;
	}
	.be-grid2 {
		display: grid;
		grid-template-columns: repeat(2, 1fr);
		gap: 12px;
		/* Don't stretch cells to row height. When one cell has extra
		   content beneath the primary control (e.g. Bag size's presets
		   row, or a default-grinder chip), the sibling cell stays at its
		   natural height so its label + control stay glued at the top of
		   the row — keeping all steppers aligned across the grid. */
		align-items: start;
	}
	@media (max-width: 680px) {
		.be-grid2 {
			grid-template-columns: 1fr;
		}
	}

	.be-frow {
		display: grid;
		grid-template-columns: 1fr 1.8fr;
		gap: 16px;
		align-items: flex-start;
	}
	.be-frow-stack {
		grid-template-columns: 1fr;
		gap: 6px;
	}
	@media (max-width: 680px) {
		.be-frow:not(.be-frow-stack) {
			grid-template-columns: 1fr;
			gap: 6px;
		}
	}
	.be-frow-l {
		display: flex;
		flex-direction: column;
		gap: 2px;
		padding-top: 4px;
	}
	.be-frow-stack .be-frow-l {
		padding-top: 0;
	}
	.be-frow-label {
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: var(--fg-1);
	}
	.be-frow-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		line-height: 1.4;
	}
	.be-frow-r {
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.be-input {
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
		/* Match the QuickStepper's `.qcs-row` height (4px padding + 36px
		   button + 4px padding = 44px) so an `<input>` and a stepper
		   sitting in adjacent grid cells line up vertically with no
		   per-row tweaks. */
		min-height: 44px;
	}
	.be-input:focus {
		border-color: var(--copper-400);
	}
	.be-input.is-invalid {
		border-color: var(--danger);
	}
	.be-textarea {
		resize: vertical;
		min-height: 80px;
		line-height: 1.5;
	}
	.be-required {
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--danger);
	}
	/* Right-align the value column. Used on the Mix row so the small
	   segmented control sits flush right rather than floating left of a
	   long blank gutter. */
	.be-frow-r-end {
		align-items: flex-end;
	}

	/* "Default" chip — same pattern as the brew bean card. Appears
	   below the Grinder input when the per-bag override is empty and a
	   Settings → Machine default exists. Tappable to copy the default
	   into the field, then disappears. */
	.be-default-chip {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		align-self: flex-start;
		padding: 3px 8px 3px 4px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: 999px;
		color: rgba(var(--tint-rgb), 0.7);
		font-family: var(--font-sans);
		font-size: 11px;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.be-default-chip:hover {
		background: rgba(var(--tint-rgb), 0.08);
		border-color: rgba(var(--copper-rgb), 0.32);
		color: var(--copper-300);
	}
	.be-default-chip-tag {
		font-size: 9px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.12);
		border-radius: 999px;
		padding: 2px 6px;
	}

	/* Segmented (Mix). `.be-seg-sm` matches the `.st-segment` from the
	   settings panel — same 3px padded shell, 5px/12px button hit area,
	   11px label — so the editor and settings read as one design
	   family. */
	.be-seg {
		display: inline-flex;
		gap: 4px;
		background: rgba(var(--tint-rgb), 0.04);
		padding: 4px;
		border-radius: var(--radius-pill);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.be-seg-sm {
		padding: 3px;
	}
	.be-seg-opt {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 6px 14px;
		border: 0;
		background: transparent;
		color: rgba(var(--tint-rgb), 0.6);
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 500;
		cursor: pointer;
		border-radius: var(--radius-pill);
		transition: all var(--dur-1) var(--ease);
	}
	.be-seg-sm .be-seg-opt {
		padding: 5px 12px;
		font-size: 11px;
	}
	.be-seg-opt:hover {
		color: var(--fg-1);
	}
	.be-seg-opt.is-on {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	/* Date input — used for the Roasted on / Opened on rows. Inherits
	   `width: 100%` from `.be-input` so two date pickers in a `.be-grid2`
	   split the card width equally (when wrapped to a single column they
	   each fill the column). No hardcoded pixel cap — locale-driven
	   content length is handled by the browser's date control. */
	.be-input-date {
		width: 100%;
	}

	/* Flag row */
	.be-flagrow {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.be-flag {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 7px 12px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-pill);
		color: rgba(var(--tint-rgb), 0.7);
		font-family: var(--font-sans);
		font-size: 12px;
	}
	.be-flag :global(svg) {
		font-size: 13px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.be-flag.is-on {
		color: var(--copper-300);
		border-color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.08);
	}
	.be-flag.is-on :global(svg) {
		color: var(--copper-400);
	}

	/* Bag presets — centered so the 6 chips visually anchor under the
	   stepper rather than left-aligning against an empty right gutter. */
	.be-presets {
		display: flex;
		flex-wrap: wrap;
		justify-content: center;
		gap: 4px;
	}
	.be-preset {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 10px;
		padding: 3px 8px;
		border-radius: var(--radius-pill);
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.be-preset em {
		font-style: normal;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.be-preset:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.be-preset.is-on {
		background: rgba(var(--copper-rgb), 0.16);
		border-color: var(--copper-400);
		color: var(--copper-300);
	}
	.be-preset.is-on em {
		color: var(--copper-400);
	}

	/* Rating */
	.be-rating {
		display: inline-flex;
		gap: 2px;
	}
	.be-rating-btn {
		background: transparent;
		border: 0;
		padding: 2px;
		color: var(--copper-400);
		cursor: pointer;
		font-size: 22px;
		display: inline-flex;
		align-items: center;
	}
	.be-rating-btn :global(svg) {
		color: rgba(var(--tint-rgb), 0.18);
	}
	.be-rating-btn:hover {
		transform: scale(1.05);
	}

	/* Foot */
	.be-foot {
		display: flex;
		gap: 8px;
		justify-content: flex-end;
		align-items: center;
		padding-top: 8px;
		flex-wrap: wrap;
	}
	/* Pushes the delete control to the left edge; Cancel/Save stay right. */
	.be-foot-del {
		margin-right: auto;
	}
</style>
