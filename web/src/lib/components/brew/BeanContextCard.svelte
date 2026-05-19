<script lang="ts">
	/**
	 * `BeanContextCard` — the left-column "what bag of coffee you're pulling"
	 * card, ported from `BeanContextCard` in `web-dashboard-v2.jsx`. Reuses the
	 * `.crema-bean*` classes from `web-kit.css`.
	 *
	 * Wired to the **current bean** (`$lib/bean`), not the profile: a bag of
	 * coffee and an extraction recipe have different lifecycles, so bean identity
	 * is not profile-scoped. The card shows the roaster + bean, the roast band,
	 * the roast date and a derived days-off-roast; tapping it opens an inline
	 * editor for the roaster, beans, roast date and 1..10 roast level.
	 *
	 * Only mounted on the resting Brew dashboard (Quick Sheet closed).
	 */
	import {
		daysOffRoast,
		getBeanStore,
		roastBand,
		roastFreshness,
		ROAST_PILL_LEVEL
	} from '$lib/bean';
	import type { Roast } from '$lib/profiles';

	let {
		grind
	}: {
		/** The current grinder click setting from the Quick Sheet params. */
		grind: number;
	} = $props();

	/** The shared current-bean store. */
	const beanStore = getBeanStore();
	/** The current bean — reactive. */
	const bean = $derived(beanStore.current);

	/**
	 * The bean line — roaster + type. Shows both joined with `·` when both are
	 * set, whichever is set when only one is, or a neutral fallback for none.
	 */
	const beanName = $derived.by(() => {
		const roaster = bean.roaster.trim();
		const type = bean.type.trim();
		if (roaster && type) return `${roaster} · ${type}`;
		return roaster || type || 'No bean logged';
	});
	/** The roast band word (`Light`/`Medium`/`Dark`), or a dash when unknown. */
	const roastLabel = $derived.by(() => {
		const band = roastBand(bean.roastLevel);
		return band ? band[0].toUpperCase() + band.slice(1) : '—';
	});
	/** Whole days off roast, or `null` when no roast date is logged. */
	const daysOff = $derived(daysOffRoast(bean.roastedOn));
	/**
	 * The rest verdict — how `daysOff` sits against the ideal window for this
	 * roast band (`roastFreshness`). `null` when the roast level or date is
	 * unknown, in which case the status dot stays neutral rather than green.
	 */
	const freshness = $derived(roastFreshness(roastBand(bean.roastLevel), daysOff));
	/** The status colour driving the rest dot — green / amber / red / neutral. */
	const freshColor = $derived(
		freshness === 'best'
			? 'var(--success)'
			: freshness === 'ok'
				? 'var(--warning)'
				: freshness === 'bad'
					? 'var(--danger)'
					: 'rgba(244, 237, 224, 0.4)'
	);
	/** The roast date as a short `May 11`, or a dash when not logged. */
	const roastDate = $derived.by(() => {
		if (!bean.roastedOn) return '—';
		const d = new Date(`${bean.roastedOn}T00:00:00`);
		if (Number.isNaN(d.getTime())) return '—';
		return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
	});

	/** The grinder this bag is dialled in on, from the bean model. */
	const grinder = $derived(bean.grinder.trim());

	/** The roast-pill quick-sets — each maps the bean to a 1..10 level. */
	const roastOptions: Roast[] = ['light', 'medium', 'dark'];

	/**
	 * Coerce a roast-number input to an integer clamped to 1..10, or `null`
	 * for an empty / unparseable field (the bean's "not logged" state).
	 */
	function parseRoastLevel(raw: string): number | null {
		const trimmed = raw.trim();
		if (trimmed === '') return null;
		const n = Number(trimmed);
		if (!Number.isFinite(n)) return null;
		return Math.max(1, Math.min(10, Math.round(n)));
	}

	/** Whether the inline editor is open. */
	let editing = $state(false);

	/** Open the inline editor. */
	function startEdit(): void {
		editing = true;
	}
	/** Close the inline editor (every field already persists on change). */
	function closeEdit(): void {
		editing = false;
	}
</script>

{#if editing}
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
					value={bean.roaster}
					placeholder="Roastery, e.g. Onyx Coffee Lab"
					oninput={(e) => beanStore.setRoaster(e.currentTarget.value)}
				/>
			</label>
			<label class="bean-field">
				<span class="t-eyebrow">Beans</span>
				<input
					class="bean-input"
					value={bean.type}
					placeholder="Coffee, e.g. Colombian Geisha"
					oninput={(e) => beanStore.setType(e.currentTarget.value)}
				/>
			</label>
			<label class="bean-field">
				<span class="t-eyebrow">Grinder</span>
				<input
					class="bean-input"
					value={bean.grinder}
					placeholder="Grinder, e.g. Niche Zero"
					oninput={(e) => beanStore.setGrinder(e.currentTarget.value)}
				/>
			</label>
			<label class="bean-field">
				<span class="t-eyebrow">Roasted on</span>
				<input
					class="bean-input"
					type="date"
					value={bean.roastedOn ?? ''}
					oninput={(e) =>
						beanStore.setRoastedOn(e.currentTarget.value || null)}
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
					oninput={(e) =>
						beanStore.setRoastLevel(parseRoastLevel(e.currentTarget.value))}
				/>
				<div class="bean-roast">
					{#each roastOptions as r (r)}
						<button
							class="bean-roast-opt"
							class:is-active={roastBand(bean.roastLevel) === r}
							onclick={() => beanStore.setRoastLevel(ROAST_PILL_LEVEL[r])}
						>
							{r}
						</button>
					{/each}
				</div>
			</div>
		</div>
	</div>
{:else}
	<button
		class="crema-target crema-bean bean-card-btn"
		onclick={startEdit}
		aria-label="Edit current bean"
	>
		<div class="crema-bean-head">
			<div class="t-eyebrow">Bean</div>
			{#if daysOff != null}
				<div class="crema-bean-rest" style:color={freshColor}>
					<span class="crema-bean-rest-dot" style:background={freshColor}></span>
					{daysOff}d off roast
				</div>
			{/if}
		</div>
		<div class="crema-bean-name">{beanName}</div>
		<div class="crema-bean-origin">{roastLabel} roast</div>
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
	</button>
{/if}

<style>
	/* The card itself is a button when not editing — strip the native chrome
	   so it stays visually identical to the design's static card. */
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
		color: rgba(244, 237, 224, 0.6);
		font-family: var(--font-sans);
		font-size: 10px;
		cursor: pointer;
	}
	.bean-edit-done:hover {
		color: var(--ink-50);
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
		background: rgba(244, 237, 224, 0.04);
		border: 1px solid rgba(244, 237, 224, 0.12);
		border-radius: var(--radius-sm);
		color: var(--ink-50);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 7px 9px;
		outline: 0;
		/* `type="date"` renders its text + calendar-picker icon from the
		   browser's color-scheme; without this the native control is dark
		   ink on the brown field. `dark` flips it to light-on-dark. */
		color-scheme: dark;
	}
	.bean-input:focus {
		border-color: rgba(244, 237, 224, 0.25);
	}
	.bean-roast {
		display: flex;
		gap: 6px;
		margin-top: 2px;
	}
	.bean-roast-opt {
		flex: 1;
		background: rgba(244, 237, 224, 0.04);
		border: 1px solid rgba(244, 237, 224, 0.12);
		border-radius: var(--radius-sm);
		color: rgba(244, 237, 224, 0.6);
		font-family: var(--font-sans);
		font-size: 11px;
		text-transform: capitalize;
		padding: 6px 8px;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.bean-roast-opt:hover {
		background: rgba(244, 237, 224, 0.08);
	}
	.bean-roast-opt.is-active {
		background: rgba(193, 124, 79, 0.16);
		border-color: var(--copper-400);
		color: var(--copper-300);
	}
</style>
