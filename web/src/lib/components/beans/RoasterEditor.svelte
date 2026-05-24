<script lang="ts">
	/**
	 * `RoasterEditor` — standalone page editor for a {@link Roaster} row.
	 *
	 * Lighter than {@link BeanEditPage} (5 fields, no rail / TOC / blocks),
	 * but uses the same form primitives so the two editors read as one
	 * design family:
	 *
	 *   ┌─ topbar: ← Beans · eyebrow · title · Cancel / Save ─────┐
	 *   ├─ centered single-column form (max-width 720)            │
	 *   │   • Name (required)                                    │
	 *   │   • Website (URL — http(s) only)                       │
	 *   │   • Logo URL + 32×32 preview                            │
	 *   │   • City + Country (two-column row)                     │
	 *   │   • Notes (textarea)                                    │
	 *   └─────────────────────────────────────────────────────────┘
	 *
	 * Used by `/beans/roasters/new` (`isNew={true}`) and
	 * `/beans/roasters/[id]/edit` (`isNew={false}`). Save always saves;
	 * required-field gating highlights the Name row on first submit when
	 * empty (mirroring {@link BeanEditPage}). The Save button is always
	 * clickable.
	 *
	 * Edit-mode commits each patch immediately through the live store
	 * (same pattern as the bean editor), so abandoning the page mid-edit
	 * persists whatever you typed. Create-mode holds a draft locally and
	 * writes once on Save — abandoning leaves no trace.
	 *
	 * URL validation reuses {@link BeanEditPage}'s rule: a bare http(s)
	 * URL passes; everything else (javascript:, data:, mailto:, file:)
	 * fails. The `Logo URL` field uses the same gate so a broken image
	 * URL is caught at save rather than rendering a sad-cloud thumbnail.
	 */
	import { tick, untrack } from 'svelte';
	import { goto } from '$app/navigation';
	import { getBeanStore, roasterMarkTone, type Roaster } from '$lib/bean';

	let {
		roaster,
		isNew = false
	}: {
		roaster: Roaster;
		isNew?: boolean;
	} = $props();

	const library = getBeanStore();

	// ── Draft state (new mode only) ────────────────────────────────────
	const live = $derived(!isNew);
	let draftRecord = $state<Roaster>(untrack(() => ({ ...roaster })));
	const current: Roaster = $derived(live ? roaster : draftRecord);

	// Bean count for the page subtitle — derived live in edit mode so the
	// readout follows the store.
	const beanCount = $derived(library.beans.filter((b) => b.roasterId === current.id).length);

	const mt = $derived(roasterMarkTone(current));

	// ── Patch + commit ────────────────────────────────────────────────
	function patch(p: Partial<Roaster>): void {
		if (live) {
			library.updateRoaster(roaster.id, p);
		} else {
			draftRecord = { ...draftRecord, ...p };
		}
	}

	// ── Validation ────────────────────────────────────────────────────
	let attempted = $state(false);
	let nameInputEl = $state<HTMLInputElement | null>(null);

	const isNameMissing = $derived(!current.name.trim());

	/** Same http(s) gate as BeanEditPage — accepts empty, http://, https://. */
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

	const isWebsiteInvalid = $derived(!isValidHttpUrl(current.website));
	const isLogoInvalid = $derived(!isValidHttpUrl(current.imageUrl));
	let websiteTouched = $state(false);
	let logoTouched = $state(false);

	// Logo preview — falls back to the deterministic mark on load error so
	// a typo'd URL doesn't render a broken-image glyph.
	let logoLoadFailed = $state(false);
	$effect(() => {
		// Reset the failed flag whenever the URL changes so a corrected
		// URL gets another chance to load.
		void current.imageUrl;
		logoLoadFailed = false;
	});

	async function save(): Promise<void> {
		attempted = true;
		websiteTouched = true;
		logoTouched = true;
		await tick();

		// Required-field gate: Name must be filled.
		if (isNameMissing) {
			nameInputEl?.scrollIntoView({ behavior: 'smooth', block: 'center' });
			nameInputEl?.focus();
			return;
		}
		// URL gates run last so the rest of the form is at least named first.
		if (isWebsiteInvalid || isLogoInvalid) {
			const id = isWebsiteInvalid ? 'rd-website-input' : 'rd-logo-input';
			const el = document.getElementById(id) as HTMLInputElement | null;
			el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
			el?.focus();
			return;
		}

		if (isNew) {
			const persisted: Roaster = {
				...draftRecord,
				name: draftRecord.name.trim(),
				updatedAt: Date.now()
			};
			library.upsertRoaster(persisted);
		}
		// Live mode already saved each patch — just bounce back. The
		// `?tab=roasters` query keeps the Roasters tab pinned on return so
		// the user lands where they came from rather than getting bumped
		// to the Bags tab.
		goto('/beans?tab=roasters');
	}

	function back(): void {
		goto('/beans?tab=roasters');
	}

	function discard(): void {
		if (isNew) {
			// Nothing to lose unless the draft is non-empty.
			const dirty =
				draftRecord.name.trim() ||
				draftRecord.website ||
				draftRecord.imageUrl ||
				draftRecord.city ||
				draftRecord.country ||
				draftRecord.notes.trim();
			if (dirty && !confirm('Discard new roaster?')) return;
		}
		back();
	}

	function onDelete(): void {
		if (isNew) return;
		const label = current.name || 'this roaster';
		if (
			!confirm(
				`Delete "${label}"? Their ${beanCount} bag(s) will keep but lose the roaster link.`
			)
		)
			return;
		library.deleteRoaster(current.id);
		goto('/beans?tab=roasters');
	}
</script>

<div class="rd-page">
	<!-- Topbar -->
	<header class="rd-bar">
		<button class="rd-back" onclick={back}>
			<i class="ph ph-arrow-left" aria-hidden="true"></i> Beans
		</button>
		<div class="rd-bar-mid">
			<div class="t-eyebrow">{isNew ? 'New roaster' : 'Edit roaster'}</div>
			<div class="rd-bar-name">{current.name || 'Untitled roaster'}</div>
		</div>
		<div class="rd-bar-actions">
			<button class="rd-btn rd-btn-ghost" onclick={discard}>Cancel</button>
			<button class="rd-btn rd-btn-primary" onclick={save}>
				<i class="ph ph-check" aria-hidden="true"></i>
				Save
			</button>
		</div>
	</header>

	<main class="rd-main">
		<!-- Hero — the same gradient avatar as the bean editor's photo slot,
		     but using the live logo URL when present (falls back to the
		     deterministic mark on null / load error). -->
		<section class="rd-hero">
			<div class="rd-hero-mark" style="--tone: {mt.tone}">
				{#if current.imageUrl && !logoLoadFailed}
					<img
						src={current.imageUrl}
						alt=""
						onerror={() => (logoLoadFailed = true)}
					/>
				{:else}
					<span class="rd-hero-glyph">{mt.mark}</span>
				{/if}
			</div>
			<div class="rd-hero-text">
				<div class="t-eyebrow">Roastery</div>
				<div class="rd-hero-name">{current.name || 'Untitled roaster'}</div>
				{#if !isNew}
					<div class="rd-hero-sub">
						{beanCount} bag{beanCount === 1 ? '' : 's'} in the library
					</div>
				{/if}
			</div>
		</section>

		<!-- Form -->
		<section class="rd-form">
			<!-- Name -->
			<div class="rd-frow">
				<div class="rd-frow-l">
					<div class="rd-frow-label">Name <span class="rd-req">*</span></div>
				</div>
				<div class="rd-frow-r">
					<input
						bind:this={nameInputEl}
						class="rd-input"
						class:is-invalid={attempted && isNameMissing}
						value={current.name}
						placeholder="e.g. Counter Culture Coffee"
						autocomplete="off"
						oninput={(e) => patch({ name: (e.currentTarget as HTMLInputElement).value })}
					/>
					{#if attempted && isNameMissing}
						<div class="rd-required">Required</div>
					{/if}
				</div>
			</div>

			<!-- Website -->
			<div class="rd-frow">
				<div class="rd-frow-l">
					<div class="rd-frow-label">Website</div>
					<div class="rd-frow-sub">Where to buy again — opens in a new tab.</div>
				</div>
				<div class="rd-frow-r">
					<input
						id="rd-website-input"
						class="rd-input"
						class:is-invalid={(websiteTouched || attempted) && isWebsiteInvalid}
						type="url"
						inputmode="url"
						autocomplete="url"
						aria-invalid={(websiteTouched || attempted) && isWebsiteInvalid}
						value={current.website ?? ''}
						placeholder="https://counterculturecoffee.com"
						oninput={(e) =>
							patch({
								website: (e.currentTarget as HTMLInputElement).value || null
							})}
						onblur={() => (websiteTouched = true)}
					/>
					{#if (websiteTouched || attempted) && isWebsiteInvalid}
						<div class="rd-required">Must start with https:// or http://</div>
					{/if}
				</div>
			</div>

			<!-- Logo URL + preview -->
			<div class="rd-frow">
				<div class="rd-frow-l">
					<div class="rd-frow-label">Logo URL</div>
					<div class="rd-frow-sub">
						32×32 thumbnail on the roaster card. Falls back to the mark on load
						error.
					</div>
				</div>
				<div class="rd-frow-r">
					<div class="rd-logo-row">
						<input
							id="rd-logo-input"
							class="rd-input rd-logo-input"
							class:is-invalid={(logoTouched || attempted) && isLogoInvalid}
							type="url"
							inputmode="url"
							autocomplete="url"
							aria-invalid={(logoTouched || attempted) && isLogoInvalid}
							value={current.imageUrl ?? ''}
							placeholder="https://…/logo.png"
							oninput={(e) =>
								patch({
									imageUrl: (e.currentTarget as HTMLInputElement).value || null
								})}
							onblur={() => (logoTouched = true)}
						/>
						<div class="rd-logo-preview" style="--tone: {mt.tone}">
							{#if current.imageUrl && !logoLoadFailed}
								<img
									src={current.imageUrl}
									alt="logo preview"
									onerror={() => (logoLoadFailed = true)}
								/>
							{:else}
								<span class="rd-logo-mark">{mt.mark}</span>
							{/if}
						</div>
					</div>
					{#if (logoTouched || attempted) && isLogoInvalid}
						<div class="rd-required">Must start with https:// or http://</div>
					{/if}
				</div>
			</div>

			<!-- City + Country -->
			<div class="rd-frow">
				<div class="rd-frow-l">
					<div class="rd-frow-label">Location</div>
					<div class="rd-frow-sub">City stays local; country also drives the Roasters tab region filter.</div>
				</div>
				<div class="rd-frow-r">
					<div class="rd-grid2">
						<input
							class="rd-input"
							value={current.city ?? ''}
							placeholder="Portland"
							autocomplete="address-level2"
							oninput={(e) =>
								patch({
									city: (e.currentTarget as HTMLInputElement).value || null
								})}
						/>
						<input
							class="rd-input"
							value={current.country ?? ''}
							placeholder="USA"
							autocomplete="country-name"
							oninput={(e) =>
								patch({
									country: (e.currentTarget as HTMLInputElement).value || null
								})}
						/>
					</div>
				</div>
			</div>

			<!-- Notes -->
			<div class="rd-frow">
				<div class="rd-frow-l">
					<div class="rd-frow-label">Notes</div>
					<div class="rd-frow-sub">
						Free-form, private to you — never pushed to Visualizer.
					</div>
				</div>
				<div class="rd-frow-r">
					<textarea
						class="rd-input rd-textarea"
						rows="4"
						value={current.notes}
						placeholder="Favourite single origins, subscription cadence, contact…"
						oninput={(e) =>
							patch({
								notes: (e.currentTarget as HTMLTextAreaElement).value
							})}
					></textarea>
				</div>
			</div>
		</section>

		<div class="rd-foot">
			{#if !isNew}
				<button type="button" class="rd-foot-danger" onclick={onDelete}>
					<i class="ph ph-trash"></i> Delete roaster
				</button>
			{/if}
			<button class="rd-btn rd-btn-ghost" onclick={discard}>Cancel</button>
			<button class="rd-btn rd-btn-primary rd-btn-lg" onclick={save}>
				<i class="ph ph-check"></i>
				Save
			</button>
		</div>
	</main>
</div>

<style>
	.rd-page {
		min-height: 100vh;
		background: var(--bg-page);
		color: var(--fg-1);
		display: flex;
		flex-direction: column;
	}

	/* Topbar — mirrors BeanEditPage. */
	.rd-bar {
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
	.rd-back {
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
	.rd-back:hover {
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.06);
	}
	.rd-bar-mid {
		flex: 1 1 auto;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.rd-bar-name {
		font-family: var(--font-serif);
		font-size: 16px;
		font-weight: 500;
		color: var(--fg-1);
		letter-spacing: -0.01em;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.rd-bar-actions {
		display: inline-flex;
		gap: 8px;
		align-items: center;
	}

	.rd-btn {
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
	.rd-btn-ghost {
		background: rgba(var(--tint-rgb), 0.04);
		border-color: rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
	}
	.rd-btn-ghost:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.rd-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.rd-btn-primary:hover {
		background: var(--copper-600);
	}
	.rd-btn-lg {
		padding: 10px 22px;
		font-size: 14px;
	}

	/* Main column — centred, narrower than BeanEditPage since the form is
	   ~5 fields rather than ~25. */
	.rd-main {
		max-width: 720px;
		width: 100%;
		margin: 0 auto;
		padding: 28px var(--page-pad-x, 24px) 60px;
		display: flex;
		flex-direction: column;
		gap: 24px;
	}

	/* Hero — small avatar + heading. The avatar mirrors the bean editor's
	   `.be-photo-drop` gradient pattern so the two pages share a visual
	   language. */
	.rd-hero {
		display: grid;
		grid-template-columns: 96px 1fr;
		gap: 18px;
		align-items: center;
	}
	.rd-hero-mark {
		width: 96px;
		height: 96px;
		border-radius: var(--radius-md);
		background:
			linear-gradient(135deg, color-mix(in srgb, var(--tone) 88%, transparent), var(--tone)),
			var(--tone);
		display: flex;
		align-items: center;
		justify-content: center;
		overflow: hidden;
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
	}
	.rd-hero-mark img {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.rd-hero-glyph {
		font-family: var(--font-serif);
		font-weight: 500;
		font-size: 44px;
		color: rgba(255, 255, 255, 0.94);
		letter-spacing: -0.02em;
	}
	.rd-hero-text {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
	}
	.rd-hero-name {
		font-family: var(--font-serif);
		font-size: 22px;
		font-weight: 500;
		color: var(--fg-1);
		letter-spacing: -0.01em;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.rd-hero-sub {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
	}

	/* Form rows — mirror BeanEditPage's `.be-frow` so the two pages have
	   matching label / control geometry. */
	.rd-form {
		display: flex;
		flex-direction: column;
		gap: 18px;
		padding: 20px;
		background: rgba(var(--tint-rgb), 0.03);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-md);
	}
	.rd-frow {
		display: grid;
		grid-template-columns: minmax(160px, 200px) 1fr;
		gap: 16px;
		align-items: start;
	}
	@media (max-width: 640px) {
		.rd-frow {
			grid-template-columns: 1fr;
			gap: 6px;
		}
	}
	.rd-frow-l {
		display: flex;
		flex-direction: column;
		gap: 2px;
		padding-top: 8px;
	}
	.rd-frow-label {
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.rd-frow-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
		line-height: 1.4;
	}
	.rd-req {
		color: var(--danger);
	}
	.rd-frow-r {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
	}
	.rd-required {
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--danger);
	}

	.rd-input {
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		padding: 9px 12px;
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
		width: 100%;
		min-width: 0;
		transition: border-color var(--dur-1) var(--ease);
	}
	.rd-input:focus {
		outline: 0;
		border-color: var(--copper-400);
	}
	.rd-input.is-invalid {
		border-color: var(--danger);
	}
	.rd-textarea {
		font-family: var(--font-sans);
		min-height: 96px;
		resize: vertical;
	}

	/* Two-column row for City + Country. Wraps on narrow viewports. */
	.rd-grid2 {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 10px;
	}
	@media (max-width: 480px) {
		.rd-grid2 {
			grid-template-columns: 1fr;
		}
	}

	/* Logo URL row — input + preview thumbnail. The thumbnail sits flush
	   right at 32×32 so the URL input keeps the rest of the row width. */
	.rd-logo-row {
		display: flex;
		gap: 10px;
		align-items: center;
	}
	.rd-logo-input {
		flex: 1 1 auto;
		min-width: 0;
	}
	.rd-logo-preview {
		flex: 0 0 32px;
		width: 32px;
		height: 32px;
		border-radius: var(--radius-sm);
		background:
			linear-gradient(135deg, color-mix(in srgb, var(--tone) 88%, transparent), var(--tone)),
			var(--tone);
		display: inline-flex;
		align-items: center;
		justify-content: center;
		overflow: hidden;
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
	}
	.rd-logo-preview img {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.rd-logo-mark {
		font-family: var(--font-serif);
		font-weight: 500;
		font-size: 14px;
		color: rgba(255, 255, 255, 0.92);
		letter-spacing: -0.02em;
	}

	/* Foot — actions + (in edit mode) a delete button anchored left. */
	.rd-foot {
		display: flex;
		gap: 8px;
		align-items: center;
		padding-top: 8px;
	}
	.rd-foot-danger {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: transparent;
		border: 0;
		color: var(--danger);
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 500;
		cursor: pointer;
		padding: 8px 10px;
		border-radius: var(--radius-sm);
		margin-right: auto;
	}
	.rd-foot-danger:hover {
		background: rgba(var(--danger-rgb), 0.08);
	}
</style>
