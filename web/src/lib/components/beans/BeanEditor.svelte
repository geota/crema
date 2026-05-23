<script lang="ts">
	/**
	 * `BeanEditor` — the right-rail drawer / fullscreen sheet on `/beans` that
	 * edits one library bean. Mirrors the design's "Bean detail / edit view"
	 * in docs/28 §UX. Form fields are organised into collapsible sections
	 * (Identity, Dates, Bag, Origin, Tasting, Buy-again) so the median user
	 * only sees the rows they need.
	 *
	 * Every field persists immediately via the library store; the drawer is
	 * a view-of-truth onto the live record, not a draft. Close = dismiss.
	 */
	import {
		getBeanStore,
		daysOffRoast,
		ROAST_PILL_LEVEL,
		roastBand,
		roastFreshness,
		type Bean
	} from '$lib/bean';
	import type { Roast } from '$lib/profiles';
	import { getHistoryStore } from '$lib/history';

	let {
		bean,
		isActive,
		onClose,
		onMakeActive
	}: {
		bean: Bean;
		isActive: boolean;
		onClose: () => void;
		onMakeActive: () => void;
	} = $props();

	const library = getBeanStore();
	const history = getHistoryStore();

	const roaster = $derived(
		bean.roasterId ? library.getRoaster(bean.roasterId) : null
	);

	const days = $derived(daysOffRoast(bean.roastedOn));
	const band = $derived(roastBand(bean.roastLevel));
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
		history.all.filter((s) => s.bean?.beanId === bean.id)
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

	function parseLevel(raw: string): number | null {
		const trimmed = raw.trim();
		if (trimmed === '') return null;
		const n = Number(trimmed);
		if (!Number.isFinite(n)) return null;
		return Math.max(1, Math.min(10, Math.round(n)));
	}

	function parseNumber(raw: string): number {
		const n = Number(raw);
		return Number.isFinite(n) && n >= 0 ? n : 0;
	}

	// Field setters route through the library store so the update timestamp +
	// persist happen consistently.
	function patch(p: Partial<Bean>): void {
		library.updateBean(bean.id, p);
	}

	function setRoasterName(value: string): void {
		const trimmed = value.trim();
		if (!trimmed) {
			patch({ roasterId: null });
			return;
		}
		const r = library.ensureRoaster(trimmed);
		patch({ roasterId: r?.id ?? null });
	}

	function ratingTap(n: number): void {
		patch({ rating: bean.rating === n ? 0 : n });
	}
</script>

<div
	class="be-scrim"
	onclick={onClose}
	onkeydown={(e) => e.key === 'Escape' && onClose()}
	role="button"
	tabindex="-1"
	aria-label="Close bean editor"
></div>

<div class="be-drawer" role="dialog" aria-modal="true" aria-labelledby="be-title">
	<header class="be-head">
		<div>
			<div class="t-eyebrow">{roaster?.name ?? 'Bean'}</div>
			<h2 class="be-title" id="be-title">{bean.name || 'Untitled bag'}</h2>
		</div>
		<div class="be-head-actions">
			{#if !isActive}
				<button class="be-btn" onclick={onMakeActive}>
					<i class="ph ph-target" aria-hidden="true"></i> Make active
				</button>
			{:else}
				<span class="be-active-pill">
					<span class="be-dot"></span> Active on Brew
				</span>
			{/if}
			<button class="be-btn be-btn-icon" onclick={onClose} aria-label="Close">
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
					value={bean.name}
					placeholder="e.g. Geisha Esmeralda Lot 3"
					oninput={(e) => patch({ name: e.currentTarget.value })}
				/>
			</label>
			<label class="be-row">
				<span class="be-label">Roaster</span>
				<input
					class="be-input"
					value={roaster?.name ?? ''}
					placeholder="e.g. Onyx Coffee Lab"
					oninput={(e) => setRoasterName(e.currentTarget.value)}
				/>
			</label>
			<div class="be-row">
				<span class="be-label">Roast level (1–10)</span>
				<div class="be-roast">
					<input
						class="be-input be-input-narrow"
						type="number"
						min="1"
						max="10"
						step="1"
						value={bean.roastLevel ?? ''}
						placeholder="—"
						oninput={(e) =>
							patch({ roastLevel: parseLevel(e.currentTarget.value) })}
					/>
					{#each roastOptions as r (r)}
						<button
							class="be-pill"
							class:is-active={band === r}
							onclick={() => patch({ roastLevel: ROAST_PILL_LEVEL[r] })}
						>
							{r}
						</button>
					{/each}
				</div>
			</div>
			<div class="be-row be-row-inline">
				<label class="be-check">
					<input
						type="checkbox"
						checked={bean.decaf}
						onchange={(e) => patch({ decaf: e.currentTarget.checked })}
					/>
					<span>Decaf</span>
				</label>
				<label class="be-check">
					<input
						type="checkbox"
						checked={bean.favourite}
						onchange={(e) => patch({ favourite: e.currentTarget.checked })}
					/>
					<span>Pinned to brew picker</span>
				</label>
			</div>
		</section>

		<!-- Dates -->
		<section class="be-section">
			<h3 class="be-section-title">Dates</h3>
			<label class="be-row">
				<span class="be-label">Roasted on</span>
				<div class="be-date-row">
					<input
						class="be-input be-input-date"
						type="date"
						value={bean.roastedOn ?? ''}
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
					value={bean.openedOn ?? ''}
					oninput={(e) => patch({ openedOn: e.currentTarget.value || null })}
				/>
			</label>
			<label class="be-row">
				<span class="be-label">Frozen on</span>
				<input
					class="be-input be-input-date"
					type="date"
					value={bean.frozenOn ?? ''}
					oninput={(e) => patch({ frozenOn: e.currentTarget.value || null })}
				/>
			</label>
			{#if bean.frozenOn}
				<label class="be-row">
					<span class="be-label">Defrosted on</span>
					<input
						class="be-input be-input-date"
						type="date"
						value={bean.defrostedOn ?? ''}
						oninput={(e) =>
							patch({ defrostedOn: e.currentTarget.value || null })}
					/>
				</label>
			{/if}
		</section>

		<!-- Bag -->
		<section class="be-section">
			<h3 class="be-section-title">Bag</h3>
			<div class="be-row-grid">
				<label class="be-row">
					<span class="be-label">Bag size (g)</span>
					<input
						class="be-input"
						type="number"
						min="0"
						step="1"
						value={bean.bagSizeG}
						oninput={(e) => patch({ bagSizeG: parseNumber(e.currentTarget.value) })}
					/>
				</label>
				<label class="be-row">
					<span class="be-label">Remaining (g)</span>
					<input
						class="be-input"
						type="number"
						min="0"
						step="1"
						value={bean.remainingG}
						oninput={(e) =>
							patch({ remainingG: parseNumber(e.currentTarget.value) })}
					/>
				</label>
			</div>
			<div class="be-row-inline">
				<button
					class="be-mini"
					onclick={() => patch({ remainingG: bean.bagSizeG })}
				>
					Refill to bag size
				</button>
				<button class="be-mini" onclick={() => patch({ remainingG: 0 })}>
					Mark empty
				</button>
			</div>
			<div class="be-row-grid">
				<label class="be-row">
					<span class="be-label">Mix</span>
					<select
						class="be-input"
						value={bean.mix ?? ''}
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
						value={bean.grinder}
						placeholder="e.g. Niche Zero"
						oninput={(e) => patch({ grinder: e.currentTarget.value })}
					/>
				</label>
			</div>
			<label class="be-row">
				<span class="be-label">Grinder setting</span>
				<input
					class="be-input"
					value={bean.grinderSetting}
					placeholder="e.g. 1.2"
					oninput={(e) => patch({ grinderSetting: e.currentTarget.value })}
				/>
			</label>
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
							value={bean.origin.country ?? ''}
							oninput={(e) =>
								patch({
									origin: { ...bean.origin, country: e.currentTarget.value || null }
								})}
						/>
					</label>
					<label class="be-row">
						<span class="be-label">Region</span>
						<input
							class="be-input"
							value={bean.origin.region ?? ''}
							oninput={(e) =>
								patch({
									origin: { ...bean.origin, region: e.currentTarget.value || null }
								})}
						/>
					</label>
				</div>
				<div class="be-row-grid">
					<label class="be-row">
						<span class="be-label">Variety</span>
						<input
							class="be-input"
							value={bean.origin.variety ?? ''}
							oninput={(e) =>
								patch({
									origin: { ...bean.origin, variety: e.currentTarget.value || null }
								})}
						/>
					</label>
					<label class="be-row">
						<span class="be-label">Processing</span>
						<input
							class="be-input"
							value={bean.origin.processing ?? ''}
							placeholder="Washed, Natural, Anaerobic…"
							oninput={(e) =>
								patch({
									origin: {
										...bean.origin,
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
							value={bean.origin.farm ?? ''}
							oninput={(e) =>
								patch({
									origin: { ...bean.origin, farm: e.currentTarget.value || null }
								})}
						/>
					</label>
					<label class="be-row">
						<span class="be-label">Elevation</span>
						<input
							class="be-input"
							value={bean.origin.elevation ?? ''}
							placeholder="1900-2100 masl"
							oninput={(e) =>
								patch({
									origin: {
										...bean.origin,
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
						value={bean.qualityScore}
						placeholder="88 · A- · 🔥🔥🔥"
						oninput={(e) => patch({ qualityScore: e.currentTarget.value })}
					/>
				</label>
				<div class="be-row">
					<span class="be-label">Rating</span>
					<div class="be-stars">
						{#each [1, 2, 3, 4, 5] as n (n)}
							<button
								class="be-star"
								class:is-active={bean.rating >= n}
								onclick={() => ratingTap(n)}
								aria-label="{n} star"
							>
								<i
									class={bean.rating >= n ? 'ph-fill ph-star' : 'ph ph-star'}
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
					value={bean.tastingNotes}
					placeholder="Stone fruit, jasmine, honey…"
					oninput={(e) => patch({ tastingNotes: e.currentTarget.value })}
				></textarea>
			</label>
			<label class="be-row">
				<span class="be-label">Notes</span>
				<textarea
					class="be-textarea"
					rows="2"
					value={bean.notes}
					placeholder="Personal notes — drift on dial-in, store at…"
					oninput={(e) => patch({ notes: e.currentTarget.value })}
				></textarea>
			</label>
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
						value={bean.url ?? ''}
						placeholder="https://…"
						oninput={(e) => patch({ url: e.currentTarget.value || null })}
					/>
				</label>
				<label class="be-row">
					<span class="be-label">Place of purchase</span>
					<input
						class="be-input"
						value={bean.placeOfPurchase ?? ''}
						oninput={(e) =>
							patch({ placeOfPurchase: e.currentTarget.value || null })}
					/>
				</label>
			{/if}
		</section>

		<!-- Shots with this bean -->
		{#if shotsWithThis.length > 0}
			<section class="be-section">
				<h3 class="be-section-title">
					Shots with this bag <span class="be-count">{shotsWithThis.length}</span>
				</h3>
				{#if bestShot}
					<div class="be-bestshot">
						<i class="ph ph-trophy" aria-hidden="true"></i>
						Best so far: ★{bestShot.rating} · {bestShot.profileName ?? 'No profile'}
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
								<span class="be-shotrating">★ {shot.rating}</span>
							{/if}
						</li>
					{/each}
				</ul>
			</section>
		{/if}
	</div>
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
	}
	.be-label {
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
		font-weight: 600;
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
	.be-input-narrow {
		max-width: 80px;
	}
	.be-roast {
		display: flex;
		gap: 6px;
		align-items: center;
	}
	.be-pill {
		flex: 1;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: rgba(var(--tint-rgb), 0.6);
		font-family: var(--font-sans);
		font-size: 11px;
		text-transform: capitalize;
		padding: 6px 8px;
		cursor: pointer;
	}
	.be-pill.is-active {
		background: rgba(193, 124, 79, 0.16);
		border-color: var(--copper-400);
		color: var(--copper-300);
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
	.be-check {
		display: inline-flex;
		gap: 6px;
		align-items: center;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-1);
		cursor: pointer;
	}
	.be-check input[type='checkbox'] {
		accent-color: var(--copper-500);
	}
	.be-mini {
		background: transparent;
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: rgba(var(--tint-rgb), 0.7);
		font-family: var(--font-sans);
		font-size: 11px;
		padding: 4px 10px;
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
</style>
