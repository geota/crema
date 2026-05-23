<script lang="ts">
	/**
	 * `BeanContextCard` — the brew dashboard's "what bag of coffee you're
	 * pulling" card. Reads from the bean library's `activeBean` (per docs/28);
	 * tap to open the inline editor that targets the active library record.
	 * A `+` corner button quick-adds a fresh bag and makes it active in one
	 * tap (the impatient-Monday case: bought a bag on the way to work, want
	 * to log it in 10 seconds before pulling the first shot).
	 *
	 * No-active-bean state shows a CTA prompting the user to pick a bag from
	 * `/beans` (or tap `+` to quick-add). Re-uses the `.crema-bean*` classes
	 * from `web-kit.css`.
	 */
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import {
		blankBean,
		daysOffRoast,
		getBeanStore,
		ROAST_PILL_LEVEL,
		roastBand,
		roastFreshness
	} from '$lib/bean';
	import type { Roast } from '$lib/profiles';

	let {
		grind
	}: {
		/** The current grinder click setting from the Quick Sheet params. */
		grind: number;
	} = $props();

	/** The shared library store. */
	const library = getBeanStore();
	/** The currently-selected bean, or `null`. */
	const bean = $derived(library.activeBean);
	/** The roaster row for the active bean, or `null`. */
	const roaster = $derived(
		bean?.roasterId ? library.getRoaster(bean.roasterId) : null
	);

	/** Display line — roaster · name, or a neutral fallback. */
	const beanName = $derived.by(() => {
		if (!bean) return 'No bean selected';
		const r = roaster?.name.trim() ?? '';
		const t = bean.name.trim();
		if (r && t) return `${r} · ${t}`;
		return r || t || 'Untitled bean';
	});
	const roastLabel = $derived.by(() => {
		if (!bean) return '—';
		const band = roastBand(bean.roastLevel);
		return band ? band[0].toUpperCase() + band.slice(1) : '—';
	});
	const daysOff = $derived(bean ? daysOffRoast(bean.roastedOn) : null);
	const freshness = $derived(
		bean ? roastFreshness(roastBand(bean.roastLevel), daysOff) : null
	);
	const freshColor = $derived(
		freshness === 'best'
			? 'var(--success)'
			: freshness === 'ok'
				? 'var(--warning)'
				: freshness === 'bad'
					? 'var(--danger)'
					: 'rgba(var(--tint-rgb), 0.4)'
	);
	const roastDate = $derived.by(() => {
		if (!bean?.roastedOn) return '—';
		const d = new Date(`${bean.roastedOn}T00:00:00`);
		if (Number.isNaN(d.getTime())) return '—';
		return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
	});
	const grinder = $derived(bean?.grinder.trim() ?? '');
	/** Burn-down: "2 shots left" warning when bag is near empty. */
	const reorderHint = $derived.by<string | null>(() => {
		if (!bean || bean.bagSizeG <= 0 || bean.remainingG <= 0) return null;
		// Threshold: 2 doses' worth (the brew-page default dose isn't reachable
		// here without the param state, so use 18g as the proxy — same default
		// the history ratio uses).
		const proxyDose = 18;
		if (bean.remainingG <= proxyDose * 2) {
			const shotsLeft = Math.max(1, Math.floor(bean.remainingG / proxyDose));
			return `${shotsLeft} shot${shotsLeft === 1 ? '' : 's'} left — reorder?`;
		}
		return null;
	});
	const roastOptions: Roast[] = ['light', 'medium', 'dark'];

	function parseRoastLevel(raw: string): number | null {
		const trimmed = raw.trim();
		if (trimmed === '') return null;
		const n = Number(trimmed);
		if (!Number.isFinite(n)) return null;
		return Math.max(1, Math.min(10, Math.round(n)));
	}

	let editing = $state(false);

	function startEdit(): void {
		// No active bean → route to the library page instead of opening an
		// editor that has nothing to edit.
		if (!bean) {
			void goto(resolve('/beans'));
			return;
		}
		editing = true;
	}
	function closeEdit(): void {
		editing = false;
	}

	/** Quick-add a new bag and activate it. The brew-page's 10-second path. */
	function quickAdd(): void {
		const fresh = blankBean();
		fresh.name = 'New bag';
		fresh.favourite = true;
		library.upsertBean(fresh);
		library.setActiveBean(fresh.id);
		editing = true;
	}

	// ── Inline editor field setters (route through the library) ────────
	function setName(value: string): void {
		if (!bean) return;
		library.updateBean(bean.id, { name: value });
	}
	function setRoasterName(value: string): void {
		if (!bean) return;
		// Resolve / create the roaster row by name.
		const r = library.ensureRoaster(value);
		library.updateBean(bean.id, { roasterId: r?.id ?? null });
	}
	function setGrinder(value: string): void {
		if (!bean) return;
		library.updateBean(bean.id, { grinder: value });
	}
	function setRoastedOn(value: string | null): void {
		if (!bean) return;
		library.updateBean(bean.id, { roastedOn: value });
	}
	function setRoastLevel(value: number | null): void {
		if (!bean) return;
		library.updateBean(bean.id, { roastLevel: value });
	}
</script>

{#if editing && bean}
	<div class="crema-target crema-bean">
		<div class="crema-bean-head">
			<div class="t-eyebrow">Edit bean</div>
			<button class="bean-edit-done" onclick={closeEdit}>
				<i class="ph ph-check" aria-hidden="true"></i> Done
			</button>
		</div>
		<div class="bean-edit">
			<label class="bean-field">
				<span class="t-eyebrow">Roaster</span>
				<input
					class="bean-input"
					value={roaster?.name ?? ''}
					placeholder="Roastery, e.g. Onyx Coffee Lab"
					oninput={(e) => setRoasterName(e.currentTarget.value)}
				/>
			</label>
			<label class="bean-field">
				<span class="t-eyebrow">Beans</span>
				<input
					class="bean-input"
					value={bean.name}
					placeholder="Coffee, e.g. Colombian Geisha"
					oninput={(e) => setName(e.currentTarget.value)}
				/>
			</label>
			<label class="bean-field">
				<span class="t-eyebrow">Grinder</span>
				<input
					class="bean-input"
					value={bean.grinder}
					placeholder="Grinder, e.g. Niche Zero"
					oninput={(e) => setGrinder(e.currentTarget.value)}
				/>
			</label>
			<label class="bean-field">
				<span class="t-eyebrow">Roasted on</span>
				<input
					class="bean-input"
					type="date"
					value={bean.roastedOn ?? ''}
					oninput={(e) => setRoastedOn(e.currentTarget.value || null)}
				/>
			</label>
			<div class="bean-field">
				<span class="t-eyebrow">Roast level (1–10)</span>
				<input
					class="bean-input"
					type="number"
					min="1"
					max="10"
					step="1"
					value={bean.roastLevel ?? ''}
					placeholder="—"
					oninput={(e) => setRoastLevel(parseRoastLevel(e.currentTarget.value))}
				/>
				<div class="bean-roast">
					{#each roastOptions as r (r)}
						<button
							class="bean-roast-opt"
							class:is-active={roastBand(bean.roastLevel) === r}
							onclick={() => setRoastLevel(ROAST_PILL_LEVEL[r])}
						>
							{r}
						</button>
					{/each}
				</div>
			</div>
			<a class="bean-fullopen" href={resolve('/beans')}>Open in library →</a>
		</div>
	</div>
{:else}
	<div
		class="crema-target crema-bean bean-card-btn"
		role="button"
		tabindex="0"
		onclick={startEdit}
		onkeydown={(e) => {
			if (e.key === 'Enter' || e.key === ' ') {
				e.preventDefault();
				startEdit();
			}
		}}
		aria-label={bean ? 'Edit current bean' : 'Pick a bean from the library'}
	>
		<div class="crema-bean-head">
			<div class="t-eyebrow">Bean</div>
			{#if bean && daysOff != null}
				<div class="crema-bean-rest" style:color={freshColor}>
					<span class="crema-bean-rest-dot" style:background={freshColor}></span>
					{daysOff}d off roast
				</div>
			{:else if !bean}
				<button
					class="bean-quick-add"
					aria-label="Quick-add a new bean"
					onclick={(e) => {
						e.stopPropagation();
						quickAdd();
					}}
				>
					<i class="ph ph-plus" aria-hidden="true"></i>
				</button>
			{/if}
		</div>
		<div class="crema-bean-name">{beanName}</div>
		<div class="crema-bean-origin">
			{#if bean}
				{roastLabel} roast
				{#if reorderHint}
					· <span class="bean-reorder">{reorderHint}</span>
				{/if}
			{:else}
				Tap to choose a bag from the library
			{/if}
		</div>
		<div class="crema-bean-grid">
			<div class="crema-bean-cell">
				<span class="t-eyebrow">Roasted</span>
				<span class="crema-bean-val">{roastDate}</span>
			</div>
			<div class="crema-bean-cell">
				<span class="t-eyebrow">Grind</span>
				<span class="crema-bean-val"
					>{grind.toFixed(1)}{#if grinder}&nbsp;<em>{grinder}</em>{/if}</span
				>
			</div>
		</div>
	</div>
{/if}

<style>
	.bean-card-btn {
		display: block;
		width: 100%;
		text-align: left;
		border: 0;
		font: inherit;
		cursor: pointer;
	}

	.bean-edit-done {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		font-family: var(--font-sans);
		font-size: 10px;
		cursor: pointer;
	}
	.bean-edit-done:hover {
		color: var(--fg-1);
	}

	.bean-edit {
		display: flex;
		flex-direction: column;
		gap: 10px;
		margin-top: 10px;
	}
	.bean-field {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.bean-input {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 7px 9px;
		outline: 0;
		color-scheme: dark;
	}
	.bean-input:focus {
		border-color: rgba(var(--tint-rgb), 0.25);
	}
	.bean-roast {
		display: flex;
		gap: 6px;
		margin-top: 2px;
	}
	.bean-roast-opt {
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
		transition: all var(--dur-1) var(--ease);
	}
	.bean-roast-opt:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.bean-roast-opt.is-active {
		background: rgba(193, 124, 79, 0.16);
		border-color: var(--copper-400);
		color: var(--copper-300);
	}
	.bean-fullopen {
		display: inline-block;
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--copper-400);
		text-decoration: none;
		margin-top: 6px;
		padding: 4px 0;
	}
	.bean-fullopen:hover {
		text-decoration: underline;
	}
	.bean-quick-add {
		background: rgba(var(--tint-rgb), 0.06);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 999px;
		color: var(--copper-400);
		cursor: pointer;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 22px;
		height: 22px;
		padding: 0;
		font-size: 12px;
	}
	.bean-quick-add:hover {
		background: rgba(193, 124, 79, 0.16);
	}
	.bean-reorder {
		color: var(--warning);
	}
</style>
