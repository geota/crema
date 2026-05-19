<script lang="ts">
	/**
	 * `BeanContextCard` — the left-column "what bag of coffee you're pulling"
	 * card, ported from `BeanContextCard` in `web-dashboard-v2.jsx`. Reuses the
	 * `.crema-bean*` classes from `web-kit.css`.
	 *
	 * Wired to the **current bean** (`$lib/bean`), not the profile: a bag of
	 * coffee and an extraction recipe have different lifecycles, so bean identity
	 * is not profile-scoped. The card shows the bean name, roast level, the
	 * roast date and a derived days-off-roast; tapping it opens an inline editor
	 * for the name, roast date and roast level.
	 *
	 * Only mounted on the resting Brew dashboard (Quick Sheet closed).
	 */
	import { daysOffRoast, getBeanStore } from '$lib/bean';
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

	/** The bean line — the current bean's name, or a neutral fallback. */
	const beanName = $derived(bean.name.trim() || 'No bean logged');
	/** The roast level, title-cased, or a dash when unknown. */
	const roastLabel = $derived(
		bean.roastLevel
			? bean.roastLevel[0].toUpperCase() + bean.roastLevel.slice(1)
			: '—'
	);
	/** Whole days off roast, or `null` when no roast date is logged. */
	const daysOff = $derived(daysOffRoast(bean.roastedOn));
	/** The roast date as a short `May 11`, or a dash when not logged. */
	const roastDate = $derived.by(() => {
		if (!bean.roastedOn) return '—';
		const d = new Date(`${bean.roastedOn}T00:00:00`);
		if (Number.isNaN(d.getTime())) return '—';
		return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
	});

	// Grinder model is not carried by the bean model — a representative
	// placeholder, kept static until the model grows a grinder field.
	const grinder = 'Niche Zero';

	/** The roast-level choices for the inline editor. */
	const roastOptions: Roast[] = ['light', 'medium', 'dark'];

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
				<span class="t-eyebrow">Name</span>
				<input
					class="bean-input"
					value={bean.name}
					placeholder="Bean name or origin"
					oninput={(e) => beanStore.setName(e.currentTarget.value)}
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
				<span class="t-eyebrow">Roast level</span>
				<div class="bean-roast">
					{#each roastOptions as r (r)}
						<button
							class="bean-roast-opt"
							class:is-active={bean.roastLevel === r}
							onclick={() =>
								beanStore.setRoastLevel(bean.roastLevel === r ? null : r)}
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
				<div class="crema-bean-rest">
					<span class="crema-bean-rest-dot"></span>
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
				<span class="crema-bean-val">{grind.toFixed(1)} <em>{grinder}</em></span>
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
	}
	.bean-input:focus {
		border-color: rgba(244, 237, 224, 0.25);
	}
	.bean-roast {
		display: flex;
		gap: 6px;
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
