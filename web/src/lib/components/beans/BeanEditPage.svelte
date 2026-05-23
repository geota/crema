<script lang="ts">
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
	 * - `QStepper`     for grams (bag size, remaining).
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
	import { goto } from '$app/navigation';
	import {
		getBeanStore,
		daysOffRoast,
		roastBand5,
		roasterMarkTone,
		blankBean,
		type Bean,
		type Roaster
	} from '$lib/bean';
	import QStepper from '$lib/components/brew/QStepper.svelte';
	import StToggle from '$lib/components/settings/StToggle.svelte';
	import RoasterAutocomplete from './RoasterAutocomplete.svelte';
	import TagInput from '$lib/components/profiles/TagInput.svelte';
	import RoastSlider from './RoastSlider.svelte';

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

	// ── Draft state (new mode only) ────────────────────────────────────
	const live = $derived(!isNew);
	let draftRecord = $state<Bean>(
		untrack(() => ({ ...bean, origin: { ...bean.origin }, tags: [...bean.tags] }))
	);
	const current: Bean = $derived(live ? bean : draftRecord);

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

	// ── Derived display values ─────────────────────────────────────────
	const days = $derived(daysOffRoast(current.roastedOn));
	const openedDays = $derived(daysOffRoast(current.openedOn));
	const isActive = $derived(library.activeBeanId === current.id);

	// ── Patch + commit ────────────────────────────────────────────────
	function patch(p: Partial<Bean>): void {
		// Special-case: bumping bag size silently re-seeds remaining.
		if (Object.prototype.hasOwnProperty.call(p, 'bagSizeG') && p.bagSizeG != null) {
			(p as Partial<Bean>).remainingG = p.bagSizeG;
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

	async function save(activate: boolean): Promise<void> {
		attempted = true;
		await tick();
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
				draftRecord.remainingG > 0 ? draftRecord.remainingG : draftRecord.bagSizeG;
			const persisted: Bean = {
				...draftRecord,
				roasterId: roaster?.id ?? null,
				name: draftRecord.name.trim(),
				remainingG: remaining,
				updatedAt: Date.now()
			};
			library.upsertBean(persisted);
			if (activate) library.setActiveBean(persisted.id);
			goto(`/beans`);
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
			if (activate && !isActive) library.setActiveBean(current.id);
			goto(`/beans`);
		}
	}

	function back(): void {
		goto('/beans');
	}

	function discard(): void {
		if (!confirm('Discard changes?')) return;
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
	const TOC = [
		{ id: 'identity', label: 'Identity' },
		{ id: 'roast', label: 'Roast & mix' },
		{ id: 'dates', label: 'Dates' },
		{ id: 'bag', label: 'Bag & grinder' },
		{ id: 'origin', label: 'Origin' },
		{ id: 'tasting', label: 'Tasting' },
		{ id: 'tags', label: 'Tags' },
		{ id: 'buy', label: 'Buy again' }
	] as const;

	function scrollToSection(id: string): void {
		const el = document.getElementById(`be-${id}`);
		if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
	}

	const BAG_PRESETS = [150, 200, 227, 250, 340, 454] as const;

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
			<i class="ph ph-arrow-left" aria-hidden="true"></i> Beans
		</button>
		<div class="be-bar-mid">
			<div class="t-eyebrow">{isNew ? 'New bean' : 'Edit bean'}</div>
			<div class="be-bar-name">{current.name || 'Untitled bag'}</div>
		</div>
		<div class="be-bar-actions">
			<button class="be-btn be-btn-ghost" onclick={discard}>Cancel</button>
			<button class="be-btn be-btn-primary" onclick={() => save(true)}>
				<i class="ph ph-coffee" aria-hidden="true"></i>
				{isNew ? 'Save & make active' : 'Save & make active'}
			</button>
		</div>
	</header>

	<div class="be-body">
		<!-- Left rail -->
		<aside class="be-rail">
			<div class="be-photo">
				<div class="be-photo-drop" style="--tone: {mt.tone}">
					<div class="be-photo-mark">{mt.mark}</div>
				</div>
				<div class="be-photo-cap">{resolvedRoaster?.name ?? 'No roaster'}</div>
				<div class="be-photo-sub">
					Photo OCR for roast date is on the roadmap — for now use the
					Roasted-on field below.
				</div>
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
				<i class="ph-duotone ph-info" aria-hidden="true"></i>
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

					<div class="be-frow">
						<div class="be-frow-l">
							<div class="be-frow-label">Flags</div>
							<div class="be-frow-sub">
								Pin keeps the bag on the brew strip; decaf is a filter facet.
							</div>
						</div>
						<div class="be-frow-r">
							<div class="be-flagrow">
								<div class="be-flag" class:is-on={current.favourite}>
									<i class="ph-fill ph-star" aria-hidden="true"></i>
									<span>Pinned</span>
									<StToggle
										on={current.favourite}
										onChange={(v) => patch({ favourite: v })}
										label="Pinned"
									/>
								</div>
								<div class="be-flag" class:is-on={current.decaf}>
									<i class="ph ph-drop-half" aria-hidden="true"></i>
									<span>Decaf</span>
									<StToggle
										on={current.decaf}
										onChange={(v) => patch({ decaf: v })}
										label="Decaf"
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
						<div class="be-frow-r">
							<div class="be-seg">
								<button
									type="button"
									class="be-seg-opt"
									class:is-on={current.mix === 'single'}
									onclick={() => patch({ mix: 'single' })}
								>
									<i class="ph ph-coffee-bean" aria-hidden="true"></i>
									<span>Single origin</span>
								</button>
								<button
									type="button"
									class="be-seg-opt"
									class:is-on={current.mix === 'blend'}
									onclick={() => patch({ mix: 'blend' })}
								>
									<i class="ph ph-shuffle" aria-hidden="true"></i>
									<span>Blend</span>
								</button>
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
							</div>
							<div class="be-frow-r">
								<div class="be-fld-date">
									<input
										bind:this={roastedDateInputEl}
										class="be-input"
										class:is-invalid={attempted && isRoastedOnMissing}
										type="date"
										value={current.roastedOn ?? ''}
										onchange={(e) =>
											patch({
												roastedOn:
													(e.currentTarget as HTMLInputElement).value || null
											})}
									/>
									<span class="be-fld-date-hint">{dateHint(days)}</span>
								</div>
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
								<div class="be-fld-date">
									<input
										class="be-input"
										type="date"
										value={current.openedOn ?? ''}
										onchange={(e) =>
											patch({
												openedOn:
													(e.currentTarget as HTMLInputElement).value || null
											})}
									/>
									<span class="be-fld-date-hint">{dateHint(openedDays)}</span>
								</div>
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
								on={!!current.frozenOn && !current.defrostedOn}
								onChange={(v) => {
									const today = new Date().toISOString().slice(0, 10);
									if (v) {
										patch({ frozenOn: today, defrostedOn: null });
									} else if (current.frozenOn) {
										patch({ defrostedOn: today });
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
										class="be-input"
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
										class="be-input"
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

			<!-- Bag & grinder -->
			<section class="be-block" id="be-bag">
				<header class="be-block-head">
					<span class="be-block-n">04</span>
					<div>
						<h3 class="be-block-title">Bag &amp; grinder</h3>
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
								<QStepper
									label=""
									value={current.bagSizeG}
									unit="g"
									min={0}
									max={5000}
									step={50}
									onChange={(n) => patch({ bagSizeG: n })}
								/>
								<div class="be-presets">
									{#each BAG_PRESETS as g (g)}
										<button
											type="button"
											class="be-preset"
											class:is-on={current.bagSizeG === g}
											onclick={() => patch({ bagSizeG: g })}
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
								<div class="be-frow-sub">
									Defaults to bag size — adjust if the bag is partial.
								</div>
							</div>
							<div class="be-frow-r">
								<QStepper
									label=""
									value={current.remainingG}
									unit="g"
									min={0}
									max={5000}
									step={1}
									onChange={(n) => patch({ remainingG: n })}
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
							</div>
						</div>
						<div class="be-frow be-frow-stack">
							<div class="be-frow-l">
								<div class="be-frow-label">Setting</div>
							</div>
							<div class="be-frow-r">
								<QStepper
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
											<i class={i <= current.rating ? 'ph-fill ph-star' : 'ph ph-star'}
											></i>
										</button>
									{/each}
								</div>
							</div>
						</div>
					</div>
					<div class="be-frow be-frow-stack">
						<div class="be-frow-l">
							<div class="be-frow-label">Notes</div>
							<div class="be-frow-sub">
								Tasting notes, brewing advice — whatever you want to remember.
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

			<!-- Tags -->
			<section class="be-block" id="be-tags">
				<header class="be-block-head">
					<span class="be-block-n">07</span>
					<div>
						<h3 class="be-block-title">Tags</h3>
						<div class="be-block-sub">
							Free-form labels — used to filter the library grid.
						</div>
					</div>
				</header>
				<div class="be-block-body">
					<TagInput tags={current.tags} suggestions={tagSuggestions} onChange={setTags} />
				</div>
			</section>

			<!-- Buy again -->
			<section class="be-block" id="be-buy">
				<header class="be-block-head">
					<span class="be-block-n">08</span>
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
								<div class="be-frow-label">URL</div>
							</div>
							<div class="be-frow-r">
								<input
									class="be-input"
									value={current.url ?? ''}
									placeholder="https://…"
									oninput={(e) =>
										patch({
											url: (e.currentTarget as HTMLInputElement).value || null
										})}
								/>
							</div>
						</div>
					</div>
				</div>
			</section>

			<div class="be-foot">
				{#if !isNew}
					<button
						type="button"
						class="be-foot-danger"
						onclick={() => {
							if (
								confirm(
									`Delete "${current.name || 'this bag'}"? This cannot be undone.`
								)
							) {
								library.deleteBean(current.id);
								goto('/beans');
							}
						}}
					>
						<i class="ph ph-trash"></i> Delete bean
					</button>
				{/if}
				<button class="be-btn be-btn-ghost" onclick={discard}>Cancel</button>
				<button class="be-btn be-btn-primary be-btn-lg" onclick={() => save(true)}>
					<i class="ph ph-coffee"></i>
					{isNew ? 'Save & make active' : 'Save'}
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
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
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
	.be-rail-help i {
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
	.be-fld-date {
		display: inline-flex;
		align-items: center;
		gap: 8px;
	}
	.be-fld-date input {
		width: 180px;
	}
	.be-fld-date-hint {
		font-family: var(--font-mono);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
	}

	/* Segmented (Mix) */
	.be-seg {
		display: inline-flex;
		gap: 4px;
		background: rgba(var(--tint-rgb), 0.04);
		padding: 4px;
		border-radius: var(--radius-pill);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
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
	.be-seg-opt:hover {
		color: var(--fg-1);
	}
	.be-seg-opt.is-on {
		background: var(--copper-500);
		color: var(--fg-on-accent);
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
	.be-flag i {
		font-size: 13px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.be-flag.is-on {
		color: var(--copper-300);
		border-color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.08);
	}
	.be-flag.is-on i {
		color: var(--copper-400);
	}

	/* Bag presets */
	.be-presets {
		display: flex;
		flex-wrap: wrap;
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
	.be-rating-btn .ph {
		color: rgba(var(--tint-rgb), 0.18);
	}
	.be-rating-btn .ph-fill {
		color: var(--copper-400);
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
	.be-foot-danger {
		background: transparent;
		border: 1px solid rgba(var(--danger-rgb), 0.35);
		color: var(--danger);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 7px 14px;
		border-radius: var(--radius-pill);
		cursor: pointer;
		margin-right: auto;
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.be-foot-danger:hover {
		background: rgba(var(--danger-rgb), 0.08);
	}
</style>
