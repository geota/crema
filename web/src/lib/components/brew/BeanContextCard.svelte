<script lang="ts">
	/**
	 * `BeanContextCard` — the brew dashboard's "what bag of coffee you're
	 * pulling" card. Reads from the bean library's `activeBean`;
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
		roastFreshness,
		type Roaster
	} from '$lib/bean';
	import RoasterAutocomplete from '$lib/components/beans/RoasterAutocomplete.svelte';
	import { getSettingsStore } from '$lib/settings';
	import type { Roast } from '$lib/profiles';

	let {
		grind
	}: {
		/** The current grinder click setting from the Quick Sheet params. */
		grind: number;
	} = $props();

	/** The shared library store. */
	const library = getBeanStore();
	/** App preferences — supplies the equipment-level default grinder. */
	const settings = getSettingsStore();
	/**
	 * Equipment-level default grinder from Settings → Equipment. Acts as
	 * the fallback when the bean itself doesn't carry a `grinder`
	 * override. Per-shot edits on the History page still take final
	 * precedence (ShotDetail handles that path).
	 */
	const defaultGrinder = $derived(settings.current.grinderModel?.trim() ?? '');
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
	/**
	 * The grinder label shown on the card. Resolution order:
	 *   1. Bean-level override (`bean.grinder`)
	 *   2. Equipment default from Settings (`prefs.grinderModel`)
	 *   3. Empty — user can still set a per-shot value on the History
	 *      page after the shot lands.
	 */
	const grinder = $derived(
		(bean?.grinder.trim() || defaultGrinder) ?? ''
	);
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
	/**
	 * Whether the editor is in create-mode (`+` tapped on an empty card)
	 * vs. edit-mode (an existing bean tapped). Create-mode defers the
	 * library write to Save, so a Cancel after `+` leaves nothing behind.
	 */
	let creating = $state(false);

	// ── Inline-edit local buffers ──────────────────────────────────────
	//
	// The previous version pushed every keystroke straight into the
	// library — typing "Onyx" in the roaster field created four roaster
	// rows (`O`, `On`, `Ony`, `Onyx`). Now field edits are held in local
	// state and only committed on Save; Cancel discards the buffer.
	let eName = $state('');
	let eRoasterName = $state('');
	let eResolvedRoaster = $state<Roaster | null>(null);
	let eGrinder = $state('');
	let eRoastedOn = $state<string | null>(null);
	let eRoastLevel = $state<number | null>(null);

	function loadBufferFromBean(): void {
		if (!bean) return;
		eName = bean.name;
		eRoasterName = roaster?.name ?? '';
		eResolvedRoaster = roaster;
		eGrinder = bean.grinder;
		eRoastedOn = bean.roastedOn;
		eRoastLevel = bean.roastLevel;
	}

	function resetBufferForCreate(): void {
		eName = '';
		eRoasterName = '';
		eResolvedRoaster = null;
		eGrinder = '';
		eRoastedOn = null;
		eRoastLevel = null;
	}

	function startEdit(): void {
		// No active bean → route to the library page instead of opening an
		// editor that has nothing to edit.
		if (!bean) {
			void goto(resolve('/beans'));
			return;
		}
		creating = false;
		loadBufferFromBean();
		editing = true;
	}

	/**
	 * Resolve the roaster id from the current buffer. Picks the typeahead
	 * resolution if the name still matches; otherwise ensures a roaster
	 * row by name (creating exactly one); empty → `null`.
	 */
	function resolveRoasterId(): string | null {
		const trimmed = eRoasterName.trim();
		if (eResolvedRoaster && eResolvedRoaster.name.trim() === trimmed) {
			return eResolvedRoaster.id;
		}
		if (trimmed === '') return null;
		const r = library.ensureRoaster(trimmed);
		return r?.id ?? null;
	}

	function saveEdit(): void {
		const roasterId = resolveRoasterId();
		if (creating) {
			// Mint the bean only now — Cancel leaves the library untouched.
			const fresh = blankBean();
			fresh.name = eName.trim() || 'New bag';
			fresh.favourite = true;
			fresh.roasterId = roasterId;
			fresh.grinder = eGrinder;
			fresh.roastedOn = eRoastedOn;
			fresh.roastLevel = eRoastLevel;
			library.upsertBean(fresh);
			library.setActiveBean(fresh.id);
		} else if (bean) {
			library.updateBean(bean.id, {
				name: eName,
				roasterId,
				grinder: eGrinder,
				roastedOn: eRoastedOn,
				roastLevel: eRoastLevel
			});
		}
		creating = false;
		editing = false;
	}

	function cancelEdit(): void {
		// Buffers are discarded; in create-mode no library row was ever
		// written, so Cancel is a true no-op.
		creating = false;
		editing = false;
	}

	/**
	 * Quick-add a new bag. The brew-page's 10-second path: opens the
	 * editor in create-mode with empty buffers; the bean only lands in
	 * the library on Save.
	 */
	function quickAdd(): void {
		resetBufferForCreate();
		creating = true;
		editing = true;
	}
</script>

{#if editing && (bean || creating)}
	<div class="crema-target crema-bean">
		<div class="crema-bean-head">
			<div class="t-eyebrow">{creating ? 'Add bean' : 'Edit bean'}</div>
		</div>
		<div class="bean-edit">
			<label class="bean-field">
				<span class="t-eyebrow">Roaster</span>
				<RoasterAutocomplete
					value={eRoasterName}
					resolved={eResolvedRoaster}
					roasters={library.roasters}
					placeholder="Roastery, e.g. Onyx Coffee Lab"
					onChange={(v) => {
						eRoasterName = v;
						// Typing free-form invalidates any prior resolved pick.
						if (!eResolvedRoaster || eResolvedRoaster.name !== v) {
							eResolvedRoaster = null;
						}
					}}
					onResolve={(r) => {
						eResolvedRoaster = r;
						if (r) eRoasterName = r.name;
					}}
				/>
			</label>
			<label class="bean-field">
				<span class="t-eyebrow">Beans</span>
				<input
					class="bean-input"
					bind:value={eName}
					placeholder="Coffee, e.g. Colombian Geisha"
				/>
			</label>
			<label class="bean-field">
				<span class="t-eyebrow">Grinder</span>
				<input
					class="bean-input"
					bind:value={eGrinder}
					placeholder="Grinder, e.g. Niche Zero"
				/>
				{#if defaultGrinder && eGrinder.trim() === ''}
					<button
						type="button"
						class="bean-default-chip"
						title="Use the equipment-level default from Settings → Machine"
						onclick={() => (eGrinder = defaultGrinder)}
					>
						<span class="bean-default-chip-tag">default</span>
						<span>{defaultGrinder}</span>
					</button>
				{/if}
			</label>
			<label class="bean-field">
				<span class="t-eyebrow">Roasted on</span>
				<input
					class="bean-input"
					type="date"
					value={eRoastedOn ?? ''}
					oninput={(e) => (eRoastedOn = e.currentTarget.value || null)}
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
					value={eRoastLevel ?? ''}
					placeholder="—"
					oninput={(e) => (eRoastLevel = parseRoastLevel(e.currentTarget.value))}
				/>
				<div class="bean-roast">
					{#each roastOptions as r (r)}
						<button
							class="bean-roast-opt"
							class:is-active={roastBand(eRoastLevel) === r}
							onclick={() => (eRoastLevel = ROAST_PILL_LEVEL[r])}
						>
							{r}
						</button>
					{/each}
				</div>
			</div>
			<div class="bean-edit-actions">
				<button
					class="bean-edit-btn bean-edit-cancel"
					onclick={cancelEdit}
					aria-label="Cancel"
					title="Cancel"
				>
					<i class="ph ph-x" aria-hidden="true"></i>
				</button>
				<button
					class="bean-edit-btn bean-edit-save"
					onclick={saveEdit}
					aria-label="Save"
					title="Save"
				>
					<i class="ph ph-check" aria-hidden="true"></i>
				</button>
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

	/* Save / Cancel pair — icon-only round buttons beneath the form
	   fields. The X (cancel) is neutral; the ✓ (save) wears the copper
	   accent to read as the affirmative action. */
	.bean-edit-actions {
		display: flex;
		justify-content: flex-end;
		gap: 8px;
		margin-top: 4px;
	}
	.bean-edit-btn {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 28px;
		height: 28px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 999px;
		color: var(--fg-1);
		font-size: 14px;
		padding: 0;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.bean-edit-btn:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.bean-edit-cancel {
		color: rgba(var(--tint-rgb), 0.7);
	}
	.bean-edit-save {
		background: rgba(193, 124, 79, 0.16);
		border-color: var(--copper-400);
		color: var(--copper-300);
	}
	.bean-edit-save:hover {
		background: rgba(193, 124, 79, 0.24);
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
	/* "Default" chip — surfaces the equipment-level grinder default when
	   the per-bag override is empty. Visually distinct from typed text:
	   pill-shaped, copper-tinted "default" tag + the value. Tapping
	   copies the default into the input so the user can either accept
	   or edit it. Disappears the moment the user types an override. */
	.bean-default-chip {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		align-self: flex-start;
		margin-top: 4px;
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
	.bean-default-chip:hover {
		background: rgba(var(--tint-rgb), 0.08);
		border-color: rgba(var(--copper-rgb), 0.32);
		color: var(--copper-300);
	}
	.bean-default-chip-tag {
		font-size: 9px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.12);
		border-radius: 999px;
		padding: 2px 6px;
	}
</style>
