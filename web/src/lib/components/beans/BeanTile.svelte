<script lang="ts">
	/**
	 * `BeanTile` — one bag of coffee in the `/beans` library grid.
	 *
	 * Replaces the older {@link BeanCard.svelte}. Visual structure is
	 * adopted one-for-one from the Claude-designed canvas
	 * (`crema-beans-svelte-export/BeanTile.svelte`):
	 *
	 *   ┌──────────────────────────┐
	 *   │ [avatar]   roaster name  │   eyebrow
	 *   │  ★         Bean name     │   display
	 *   │            Country · Reg │   meta
	 *   │            [pills]       │   roast band + flags + tags
	 *   │            [Xd off][rate]│   stats
	 *   │   [════════════════]     │   burn-down
	 *   │   142g  /  250g          │
	 *   └──────────────────────────┘
	 *
	 * Key departures from the old card:
	 *
	 *  - The favourite star is **on the avatar, top-left** (not top-right
	 *    of the card). One overlaid icon, filled when on, hidden when off.
	 *  - The kebab / icon-row at the bottom is gone — actions (set active,
	 *    edit, archive, delete) live in the drawer footer.
	 *  - Days off roast / opened days / frozen days surface as labelled
	 *    chips along the bottom edge; a 5-star Rating component sits to
	 *    the right.
	 *
	 * Click anywhere on the tile fires `onOpen(id)` — the parent decides
	 * whether to open the drawer or jump straight to the editor.
	 */
	import {
		daysOffRoast,
		roastBand,
		roastBand5,
		roastFreshness,
		roasterMarkTone,
		type Bean,
		type Roaster
	} from '$lib/bean';

	let {
		bean,
		roaster,
		isActive,
		onOpen,
		onToggleFavourite
	}: {
		bean: Bean;
		roaster: Roaster | null;
		isActive: boolean;
		onOpen: (id: string) => void;
		onToggleFavourite: (id: string) => void;
	} = $props();

	const mt = $derived(roasterMarkTone(roaster));

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

	const isFrozen = $derived(!!bean.frozenOn && !bean.defrostedOn);
	const isArchived = $derived(bean.archivedAt != null);

	const frozenDays = $derived(
		isFrozen && bean.frozenOn ? daysOffRoast(bean.frozenOn) : null
	);
	const openedDays = $derived(
		!isFrozen && bean.openedOn ? daysOffRoast(bean.openedOn) : null
	);

	const burnPct = $derived.by<number | null>(() => {
		if (bean.bagSizeG <= 0) return null;
		return Math.max(0, Math.min(100, (bean.remainingG / bean.bagSizeG) * 100));
	});

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
	function onFavClick(e: MouseEvent): void {
		e.stopPropagation();
		onToggleFavourite(bean.id);
	}
</script>

<div
	class="bn-tile"
	class:is-active={isActive}
	class:is-archived={isArchived}
	class:is-frozen={isFrozen}
	role="button"
	tabindex="0"
	onclick={onCardClick}
	onkeydown={onCardKey}
	aria-label="Open {bean.name || 'untitled bean'}"
>
	<!-- Avatar block (left) -->
	<div class="bn-tile-avatar" style="--tone: {mt.tone}">
		<div class="bn-tile-mono">{mt.mark}</div>
		{#if isActive}
			<div class="bn-tile-active-dot" title="Active on Brew"></div>
		{/if}
		<button
			type="button"
			class="bn-tile-fav"
			class:is-on={bean.favourite}
			onclick={onFavClick}
			aria-label={bean.favourite ? 'Unpin from brew picker' : 'Pin to brew picker'}
			title={bean.favourite ? 'Pinned' : 'Pin to brew picker'}
		>
			<i class={bean.favourite ? 'ph-fill ph-star' : 'ph ph-star'} aria-hidden="true"></i>
		</button>
		{#if isFrozen}
			<div class="bn-tile-frozen" title="Frozen storage">
				<i class="ph-fill ph-snowflake" aria-hidden="true"></i>
			</div>
		{/if}
	</div>

	<!-- Body -->
	<div class="bn-tile-body">
		<div class="bn-tile-roaster">{roaster?.name ?? 'No roaster'}</div>
		<div class="bn-tile-name">{bean.name || 'Untitled bag'}</div>

		<div class="bn-tile-meta">
			<span class="bn-tile-origin">
				{#if bean.origin.country}
					{bean.origin.country}{bean.origin.region ? ' · ' + bean.origin.region : ''}
				{:else}
					&mdash;
				{/if}
			</span>
		</div>

		<div class="bn-tile-tags">
			{#if bean.roastLevel != null}
				<span class="bn-tile-roastpill">{roastBand5(bean.roastLevel)}</span>
			{/if}
			{#if bean.mix === 'blend'}<span class="bn-tile-pill">Blend</span>{/if}
			{#if bean.decaf}<span class="bn-tile-pill">Decaf</span>{/if}
			{#each visibleTags as t (t)}
				<span class="bn-tile-pill bn-tile-pill-tag">{t}</span>
			{/each}
			{#if overflowTags > 0}
				<span class="bn-tile-pill bn-tile-pill-tag">+{overflowTags}</span>
			{/if}
		</div>

		<div class="bn-tile-stats">
			<div class="bn-tile-stat">
				<span class="bn-tile-stat-dot" style:background={freshColor}></span>
				<div class="bn-tile-stat-text">
					<span class="bn-tile-stat-val">{days != null ? days + 'd' : '—'}</span>
					<span class="bn-tile-stat-lab">off roast</span>
				</div>
			</div>
			{#if frozenDays != null}
				<div class="bn-tile-stat">
					<i class="ph ph-snowflake" aria-hidden="true"></i>
					<div class="bn-tile-stat-text">
						<span class="bn-tile-stat-val">{frozenDays}d</span>
						<span class="bn-tile-stat-lab">frozen</span>
					</div>
				</div>
			{:else if openedDays != null}
				<div class="bn-tile-stat">
					<i class="ph ph-package" aria-hidden="true"></i>
					<div class="bn-tile-stat-text">
						<span class="bn-tile-stat-val">{openedDays}d</span>
						<span class="bn-tile-stat-lab">open</span>
					</div>
				</div>
			{/if}
			<div class="bn-tile-stat bn-tile-stat-rating" aria-label="{bean.rating} of 5">
				{#each [1, 2, 3, 4, 5] as i (i)}
					<i
						class={i <= bean.rating ? 'ph-fill ph-star' : 'ph ph-star'}
						aria-hidden="true"
					></i>
				{/each}
			</div>
		</div>

		<!-- Burn-down bar -->
		{#if burnPct != null}
			<div
				class="bn-tile-burn"
				title="{bean.remainingG.toFixed(0)} g of {bean.bagSizeG.toFixed(0)} g"
			>
				<div class="bn-tile-burn-track">
					<div class="bn-tile-burn-fill" style:width="{burnPct}%"></div>
				</div>
				<div class="bn-tile-burn-text">
					<span class="bn-tile-burn-rem"
						>{bean.remainingG.toFixed(0)}<em>g</em></span
					>
					<span class="bn-tile-burn-total"
						>/ {bean.bagSizeG.toFixed(0)}<em>g</em></span
					>
				</div>
			</div>
		{/if}
	</div>
</div>

<style>
	.bn-tile {
		position: relative;
		display: grid;
		grid-template-columns: 88px 1fr;
		gap: 16px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-lg, 14px);
		padding: 14px;
		text-align: left;
		color: var(--fg-1);
		font: inherit;
		cursor: pointer;
		transition:
			background var(--dur-1) var(--ease),
			border-color var(--dur-1) var(--ease),
			transform var(--dur-1) var(--ease);
		min-height: 168px;
		outline: 0;
	}
	.bn-tile:hover {
		background: rgba(var(--tint-rgb), 0.06);
		border-color: rgba(var(--tint-rgb), 0.16);
	}
	.bn-tile:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
	.bn-tile.is-active {
		border-color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.07);
	}
	.bn-tile.is-archived {
		opacity: 0.55;
	}
	.bn-tile.is-frozen {
		background: linear-gradient(
			180deg,
			rgba(125, 160, 205, 0.04),
			rgba(var(--tint-rgb), 0.04) 60%
		);
	}

	/* Avatar (left column) */
	.bn-tile-avatar {
		position: relative;
		width: 88px;
		height: 88px;
		border-radius: var(--radius-md, 12px);
		background:
			linear-gradient(135deg, color-mix(in srgb, var(--tone) 88%, transparent), var(--tone)),
			var(--tone);
		display: flex;
		align-items: center;
		justify-content: center;
		flex-shrink: 0;
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.1);
	}
	.bn-tile-mono {
		font-family: var(--font-serif);
		font-weight: 500;
		font-size: 32px;
		color: rgba(255, 255, 255, 0.92);
		letter-spacing: -0.02em;
		text-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
	}
	.bn-tile-active-dot {
		position: absolute;
		bottom: 6px;
		right: 6px;
		width: 10px;
		height: 10px;
		border-radius: 50%;
		background: var(--copper-400);
		border: 2px solid var(--bg-page);
	}
	.bn-tile-fav {
		position: absolute;
		top: 4px;
		left: 4px;
		width: 22px;
		height: 22px;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		border: 0;
		background: rgba(0, 0, 0, 0.18);
		color: rgba(255, 255, 255, 0.55);
		border-radius: 50%;
		cursor: pointer;
		font-size: 13px;
		padding: 0;
		transition:
			background var(--dur-1) var(--ease),
			color var(--dur-1) var(--ease),
			transform var(--dur-1) var(--ease);
	}
	.bn-tile-fav:hover {
		background: rgba(0, 0, 0, 0.32);
		color: #fff;
		transform: scale(1.08);
	}
	.bn-tile-fav.is-on {
		background: rgba(255, 255, 255, 0.95);
		color: var(--copper-500);
	}
	.bn-tile-fav.is-on:hover {
		background: #fff;
	}
	.bn-tile-frozen {
		position: absolute;
		top: 4px;
		right: 4px;
		width: 22px;
		height: 22px;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		background: rgba(125, 160, 205, 0.9);
		color: #fff;
		border-radius: 50%;
		font-size: 12px;
	}

	/* Body (right column) */
	.bn-tile-body {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
	}
	.bn-tile-roaster {
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(var(--tint-rgb), 0.55);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.bn-tile-name {
		font-family: var(--font-serif);
		font-size: 17px;
		font-weight: 500;
		line-height: 1.2;
		color: var(--fg-1);
		letter-spacing: -0.01em;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.bn-tile-meta {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		margin-top: 2px;
	}
	.bn-tile-origin {
		display: inline-block;
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	/* Pills row — roast band + Blend/Decaf + tags */
	.bn-tile-tags {
		display: flex;
		flex-wrap: wrap;
		gap: 4px;
		margin-top: 4px;
	}
	.bn-tile-roastpill,
	.bn-tile-pill {
		display: inline-flex;
		align-items: center;
		gap: 3px;
		font-family: var(--font-sans);
		font-size: 9.5px;
		font-weight: 600;
		padding: 2px 7px;
		border-radius: var(--radius-pill);
		letter-spacing: 0.02em;
		white-space: nowrap;
	}
	.bn-tile-roastpill {
		background: rgba(var(--copper-rgb), 0.14);
		color: var(--copper-300);
		border: 1px solid rgba(var(--copper-rgb), 0.28);
	}
	.bn-tile-pill {
		background: rgba(var(--tint-rgb), 0.06);
		color: rgba(var(--tint-rgb), 0.62);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
	}
	.bn-tile-pill-tag {
		background: rgba(var(--copper-rgb), 0.08);
		color: var(--copper-400);
		border-color: rgba(var(--copper-rgb), 0.22);
		text-transform: none;
		font-weight: 500;
	}

	/* Stats row — off-roast + open/frozen + rating */
	.bn-tile-stats {
		display: flex;
		flex-wrap: wrap;
		gap: 12px;
		align-items: center;
		margin-top: auto;
		padding-top: 6px;
	}
	.bn-tile-stat {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-family: var(--font-sans);
	}
	.bn-tile-stat i {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.45);
	}
	.bn-tile-stat-dot {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		flex-shrink: 0;
	}
	.bn-tile-stat-text {
		display: inline-flex;
		flex-direction: column;
		line-height: 1.1;
	}
	.bn-tile-stat-val {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 11px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.bn-tile-stat-lab {
		font-size: 9px;
		color: rgba(var(--tint-rgb), 0.5);
		text-transform: uppercase;
		letter-spacing: var(--track-allcaps);
	}
	.bn-tile-stat-rating {
		margin-left: auto;
		gap: 1px;
		font-size: 10px;
		color: var(--copper-400);
	}
	.bn-tile-stat-rating .ph,
	.bn-tile-stat-rating .ph-fill {
		font-size: 10px;
	}
	.bn-tile-stat-rating .ph {
		color: rgba(var(--tint-rgb), 0.2);
	}

	/* Burn-down bar */
	.bn-tile-burn {
		display: flex;
		flex-direction: column;
		gap: 3px;
		margin-top: 6px;
	}
	.bn-tile-burn-track {
		height: 4px;
		background: rgba(var(--tint-rgb), 0.06);
		border-radius: 999px;
		overflow: hidden;
	}
	.bn-tile-burn-fill {
		height: 100%;
		background: linear-gradient(90deg, var(--copper-500), var(--copper-400));
		border-radius: 999px;
		transition: width var(--dur-2) var(--ease);
	}
	.bn-tile-burn-text {
		display: inline-flex;
		gap: 4px;
		align-items: baseline;
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 10px;
		color: var(--fg-1);
	}
	.bn-tile-burn-rem em,
	.bn-tile-burn-total em {
		font-style: normal;
		color: rgba(var(--tint-rgb), 0.5);
		font-size: 9px;
		margin-left: 1px;
	}
	.bn-tile-burn-total {
		color: rgba(var(--tint-rgb), 0.45);
	}
</style>
