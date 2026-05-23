<script lang="ts">
	/**
	 * `BeanCard` — one bag of coffee in the `/beans` library grid.
	 *
	 * Mirrors the visual + interaction pattern of
	 * `$lib/components/profiles/ProfileCard.svelte`: a header row with the
	 * favourite star top-right, a body (name + roaster + freshness + tags
	 * + burn-down + per-bean profile recommendation), and an action row at
	 * the bottom — a full-width primary "Set active" button, then a row of
	 * compact icon buttons for Edit, Archive, Delete.
	 *
	 * The card is read-only on the surface (clicks anywhere outside the
	 * action row open the editor). Action icons stop propagation so they
	 * fire their own handler without simultaneously opening the drawer.
	 *
	 * Archived bags render slightly dimmed (`is-archived`) so they read as
	 * out-of-circulation when the user has flipped on the "Archived"
	 * filter.
	 */
	import {
		daysOffRoast,
		roastBand,
		roastFreshness,
		type Bean,
		type Roaster
	} from '$lib/bean';

	let {
		bean,
		roaster,
		isActive,
		shots,
		recommendation,
		onOpen,
		onSetActive,
		onToggleFavourite,
		onToggleArchived,
		onDelete
	}: {
		/** The bean to render. */
		bean: Bean;
		/** The bean's roaster row, if linked. `null` when unset. */
		roaster: Roaster | null;
		/** Whether this is the active bean on Brew. */
		isActive: boolean;
		/** How many shots in History reference this bean. */
		shots: number;
		/**
		 * Optional per-bean profile recommendation — already-formatted
		 * label like `"BBW · avg ★4.5 over 6 shots"`. The card renders the
		 * tip pill when non-null and `shots >= 3`.
		 */
		recommendation: string | null;
		/** Click on the card body — opens the editor / detail drawer. */
		onOpen: (id: string) => void;
		/** "Set active" — make this the active bean on Brew. */
		onSetActive: (id: string) => void;
		/** Toggle the favourite star. */
		onToggleFavourite: (id: string) => void;
		/** Toggle archived state (sets / clears `archivedAt`). */
		onToggleArchived: (id: string) => void;
		/** Hard-delete this bag (parent confirms). */
		onDelete: (id: string) => void;
	} = $props();

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
	const burnPct = $derived.by<number | null>(() => {
		if (bean.bagSizeG <= 0) return null;
		return Math.max(0, Math.min(100, (bean.remainingG / bean.bagSizeG) * 100));
	});

	const isArchived = $derived(bean.archivedAt != null);

	/** Cap visible tag chips so a long list does not blow out the card. */
	const VISIBLE_TAGS = 3;
	const visibleTags = $derived(bean.tags.slice(0, VISIBLE_TAGS));
	const overflowTags = $derived(Math.max(0, bean.tags.length - VISIBLE_TAGS));

	function onCardClick(): void {
		onOpen(bean.id);
	}
	function onCardKey(e: KeyboardEvent): void {
		if (e.key === 'Enter' || e.key === ' ') {
			e.preventDefault();
			onOpen(bean.id);
		}
	}
</script>

<div
	class="bn-card"
	class:is-active={isActive}
	class:is-archived={isArchived}
	role="button"
	tabindex="0"
	onclick={onCardClick}
	onkeydown={onCardKey}
>
	<div class="bn-card-head">
		{#if isActive}
			<div class="bn-card-active">Active</div>
		{:else}
			<span class="bn-card-head-spacer"></span>
		{/if}
		<button
			class="bn-card-pin"
			class:bn-card-pin-off={!bean.favourite}
			title={bean.favourite ? 'Pinned to brew picker' : 'Pin to brew picker'}
			onclick={(e) => {
				e.stopPropagation();
				onToggleFavourite(bean.id);
			}}
			aria-label={bean.favourite ? 'Unpin' : 'Pin'}
		>
			<i class={bean.favourite ? 'ph-fill ph-star' : 'ph ph-star'} aria-hidden="true"></i>
		</button>
	</div>

	<div class="bn-card-body">
		<div class="bn-card-name">{bean.name || 'Untitled bag'}</div>
		<div class="bn-card-roaster">
			{roaster?.name ?? 'No roaster'}
			{#if days != null}
				<span class="bn-card-dot">·</span>
				<span class="bn-card-fresh" style:color={freshColor}>
					<span class="bn-card-fresh-dot" style:background={freshColor}></span>
					{days}d off roast
				</span>
			{/if}
		</div>

		{#if bean.tags.length > 0 || bean.decaf || (bean.frozenOn && !bean.defrostedOn)}
			<div class="bn-card-tags">
				{#if bean.decaf}
					<span class="bn-card-flagchip">Decaf</span>
				{/if}
				{#if bean.frozenOn && !bean.defrostedOn}
					<span class="bn-card-flagchip bn-card-flagchip-cold">
						<i class="ph ph-snowflake" aria-hidden="true"></i>Frozen
					</span>
				{/if}
				{#each visibleTags as t (t)}
					<span class="bn-card-tag">{t}</span>
				{/each}
				{#if overflowTags > 0}
					<span class="bn-card-tag bn-card-tag-more">+{overflowTags} more</span>
				{/if}
			</div>
		{/if}

		<div class="bn-card-metrics">
			<div class="bn-card-metric">
				<div class="bn-card-metric-label">Rating</div>
				<div class="bn-card-metric-val bn-card-stars">
					{#if bean.rating > 0}
						{'★'.repeat(bean.rating)}<span class="bn-card-stars-off"
							>{'★'.repeat(5 - bean.rating)}</span
						>
					{:else}
						<span class="bn-card-stars-off">unrated</span>
					{/if}
				</div>
			</div>
			<div class="bn-card-metric">
				<div class="bn-card-metric-label">Shots</div>
				<div class="bn-card-metric-val">{shots}</div>
			</div>
			<div class="bn-card-metric">
				<div class="bn-card-metric-label">Mix</div>
				<div class="bn-card-metric-val">
					{bean.mix === 'single' ? 'Single' : bean.mix === 'blend' ? 'Blend' : '—'}
				</div>
			</div>
			<div class="bn-card-metric">
				<div class="bn-card-metric-label">Roast</div>
				<div class="bn-card-metric-val">
					{band ? band.charAt(0).toUpperCase() + band.slice(1) : '—'}
				</div>
			</div>
		</div>

		{#if burnPct != null}
			<div
				class="bn-card-burn"
				title="{bean.remainingG.toFixed(0)} g of {bean.bagSizeG.toFixed(0)} g"
			>
				<div class="bn-card-burn-bar" style:width="{burnPct}%"></div>
				<span class="bn-card-burn-text">
					{bean.remainingG.toFixed(0)} / {bean.bagSizeG.toFixed(0)} g
				</span>
			</div>
		{/if}

		{#if recommendation && shots >= 3}
			<div class="bn-card-tip">
				<i class="ph ph-lightbulb" aria-hidden="true"></i>
				Best with <strong>{recommendation}</strong>
			</div>
		{/if}

		<div class="bn-card-actions">
			<button
				class="bn-card-primary"
				class:is-on={isActive}
				onclick={(e) => {
					e.stopPropagation();
					if (!isActive) onSetActive(bean.id);
				}}
				disabled={isArchived}
				title={isArchived
					? 'Restore from archive to set active'
					: isActive
						? 'Already the active bean'
						: 'Set as the active bean on Brew'}
			>
				<i
					class={isActive ? 'ph-fill ph-check-circle' : 'ph ph-target'}
					aria-hidden="true"
				></i>
				<span>{isActive ? 'Active on Brew' : 'Set active'}</span>
			</button>
			<button
				class="bn-card-iconbtn"
				title="Edit"
				onclick={(e) => {
					e.stopPropagation();
					onOpen(bean.id);
				}}
				aria-label="Edit"
			>
				<i class="ph ph-pencil-simple" aria-hidden="true"></i>
			</button>
			<button
				class="bn-card-iconbtn"
				title={isArchived ? 'Restore from archive' : 'Archive this bag'}
				onclick={(e) => {
					e.stopPropagation();
					onToggleArchived(bean.id);
				}}
				aria-label={isArchived ? 'Restore' : 'Archive'}
			>
				<i
					class={isArchived ? 'ph ph-archive-box' : 'ph ph-archive'}
					aria-hidden="true"
				></i>
			</button>
			<button
				class="bn-card-iconbtn bn-card-iconbtn-danger"
				title="Delete this bag"
				onclick={(e) => {
					e.stopPropagation();
					onDelete(bean.id);
				}}
				aria-label="Delete"
			>
				<i class="ph ph-trash" aria-hidden="true"></i>
			</button>
		</div>
	</div>
</div>

<style>
	.bn-card {
		position: relative;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-lg, 14px);
		padding: 14px 16px 14px;
		display: flex;
		flex-direction: column;
		gap: 10px;
		text-align: left;
		color: var(--fg-1);
		font: inherit;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
		min-height: 300px;
	}
	.bn-card:hover {
		background: rgba(var(--tint-rgb), 0.06);
		border-color: rgba(var(--tint-rgb), 0.16);
	}
	.bn-card:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
	.bn-card.is-active {
		border-color: var(--copper-400);
		background: rgba(193, 124, 79, 0.05);
	}
	.bn-card.is-archived {
		opacity: 0.6;
	}

	.bn-card-head {
		display: flex;
		justify-content: space-between;
		align-items: center;
		min-height: 22px;
	}
	.bn-card-active {
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: var(--fg-on-accent);
		background: var(--copper-500);
		border-radius: 999px;
		padding: 2px 8px;
	}
	.bn-card-head-spacer {
		display: inline-block;
	}
	.bn-card-pin {
		background: transparent;
		border: 0;
		padding: 4px;
		color: var(--copper-400);
		cursor: pointer;
		font-size: 17px;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		border-radius: var(--radius-sm);
		transition:
			color var(--dur-1) var(--ease),
			background var(--dur-1) var(--ease);
	}
	.bn-card-pin:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.bn-card-pin-off {
		color: rgba(var(--tint-rgb), 0.4);
	}
	.bn-card-pin-off:hover {
		color: var(--copper-400);
	}

	.bn-card-body {
		display: flex;
		flex-direction: column;
		gap: 8px;
		flex: 1 1 auto;
	}
	.bn-card-name {
		font-family: var(--font-sans);
		font-size: 16px;
		font-weight: 600;
		color: var(--fg-1);
		line-height: 1.25;
	}
	.bn-card-roaster {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.6);
		display: inline-flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 5px;
	}
	.bn-card-dot {
		color: rgba(var(--tint-rgb), 0.3);
	}
	.bn-card-fresh {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-size: 11px;
		font-weight: 500;
	}
	.bn-card-fresh-dot {
		width: 6px;
		height: 6px;
		border-radius: 50%;
	}

	.bn-card-tags {
		display: flex;
		flex-wrap: wrap;
		gap: 4px;
		align-items: center;
	}
	.bn-card-tag {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 500;
		padding: 2px 8px;
		border-radius: var(--radius-pill);
		background: rgba(193, 116, 75, 0.1);
		border: 1px solid rgba(193, 116, 75, 0.25);
		color: var(--copper-400);
		white-space: nowrap;
	}
	.bn-card-tag-more {
		background: rgba(var(--tint-rgb), 0.04);
		border-color: rgba(var(--tint-rgb), 0.12);
		color: rgba(var(--tint-rgb), 0.55);
	}
	.bn-card-flagchip {
		display: inline-flex;
		align-items: center;
		gap: 3px;
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 600;
		letter-spacing: 0.04em;
		padding: 2px 8px;
		border-radius: var(--radius-pill);
		background: rgba(var(--tint-rgb), 0.06);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		color: rgba(var(--tint-rgb), 0.65);
		text-transform: uppercase;
	}
	.bn-card-flagchip i {
		font-size: 10px;
	}
	.bn-card-flagchip-cold {
		background: rgba(120, 180, 220, 0.08);
		border-color: rgba(120, 180, 220, 0.3);
		color: rgb(140, 195, 230);
	}

	.bn-card-metrics {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: 6px;
		padding: 8px 0 4px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.05);
	}
	.bn-card-metric {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.bn-card-metric-label {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(var(--tint-rgb), 0.4);
	}
	.bn-card-metric-val {
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
		font-weight: 500;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.bn-card-stars {
		color: var(--copper-400);
		letter-spacing: 1px;
		font-size: 12px;
	}
	.bn-card-stars-off {
		color: rgba(var(--tint-rgb), 0.25);
		font-size: 11px;
	}

	.bn-card-burn {
		position: relative;
		height: 16px;
		background: rgba(var(--tint-rgb), 0.05);
		border-radius: 8px;
		overflow: hidden;
	}
	.bn-card-burn-bar {
		position: absolute;
		inset: 0 auto 0 0;
		background: linear-gradient(90deg, var(--copper-500), var(--copper-400));
	}
	.bn-card-burn-text {
		position: relative;
		display: block;
		text-align: center;
		font-family: var(--font-mono);
		font-size: 9px;
		line-height: 16px;
		color: var(--fg-1);
		mix-blend-mode: difference;
	}

	.bn-card-tip {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--copper-300);
		background: rgba(193, 124, 79, 0.08);
		border-radius: var(--radius-sm);
		padding: 6px 8px;
	}
	.bn-card-tip i {
		font-size: 12px;
	}

	.bn-card-actions {
		display: flex;
		gap: 4px;
		margin-top: auto;
		padding-top: 4px;
		align-items: stretch;
	}
	.bn-card-primary {
		flex: 1 1 auto;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 6px;
		background: var(--copper-500);
		color: var(--fg-on-accent);
		border: 0;
		border-radius: var(--radius-sm);
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 600;
		padding: 8px 12px;
		cursor: pointer;
		transition: background var(--dur-1) var(--ease);
	}
	.bn-card-primary i {
		font-size: 13px;
	}
	.bn-card-primary:hover:not(:disabled) {
		background: var(--copper-600);
	}
	.bn-card-primary:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}
	.bn-card-primary.is-on {
		background: rgba(193, 124, 79, 0.16);
		color: var(--copper-300);
		border: 1px solid var(--copper-400);
		cursor: default;
	}
	.bn-card-primary.is-on:hover {
		background: rgba(193, 124, 79, 0.16);
	}

	.bn-card-iconbtn {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		color: rgba(var(--tint-rgb), 0.6);
		font-size: 14px;
		padding: 6px 8px;
		border-radius: var(--radius-sm);
		cursor: pointer;
		text-decoration: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		transition: all var(--dur-1) var(--ease);
	}
	.bn-card-iconbtn:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}
	.bn-card-iconbtn-danger:hover {
		color: var(--danger);
		background: rgba(var(--danger-rgb), 0.12);
	}
</style>
