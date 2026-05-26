<script lang="ts">
	/**
	 * `BeanTile` — one bag of coffee in the `/beans` library grid.
	 *
	 * Replaces the older `BeanCard.svelte`. Visual structure is adopted
	 * from the Claude-designed canvas
	 * (`crema-beans-svelte-export/BeanTile.svelte`):
	 *
	 *   ┌──────────────────────────────────┐
	 *   │ [avatar]  roaster name        ★  │   eyebrow + favourite
	 *   │           Bean name              │   display
	 *   │           Country · Region       │   meta
	 *   │           [pills]                │   roast band + flags + tags
	 *   │           [Xd off] [opened]      │   stats + rating
	 *   │   [════════════════]             │   burn-down
	 *   │   142g / 250g                    │
	 *   │   [Set active]  [⎘] [✎] [🗑]    │   action row
	 *   └──────────────────────────────────┘
	 *
	 * Card chrome (grid template, top-right pin star, action-row layout)
	 * lives in {@link LibraryCardShell} — this component owns only the
	 * per-tile contents of each slot (avatar mark, header text, stats /
	 * burn-down, and the action-row buttons).
	 */
	import { untrack } from 'svelte';
	import {
		daysOffRoast,
		getBeanImageStore,
		roastBand,
		roastBand5,
		roastFreshness,
		roasterMarkTone,
		type Bean,
		type Roaster
	} from '$lib/bean';
	import LibraryCardShell from '$lib/components/shared/LibraryCardShell.svelte';

	let {
		bean,
		roaster,
		isActive,
		onOpen,
		onToggleFavourite,
		onSetActive,
		onDuplicate,
		onEdit,
		onDelete
	}: {
		bean: Bean;
		roaster: Roaster | null;
		isActive: boolean;
		onOpen: (id: string) => void;
		onToggleFavourite: (id: string) => void;
		onSetActive: (id: string) => void;
		onDuplicate: (id: string) => void;
		onEdit: (id: string) => void;
		onDelete: (id: string) => void;
	} = $props();

	const mt = $derived(roasterMarkTone(roaster));

	/**
	 * Object-URL for the bag photo when one is stored in IndexedDB
	 * (imported from a Beanconqueror export). Loaded asynchronously
	 * via the bean image store; revoked on unmount or when the bean
	 * changes to a different `imageRef`. `null` when no photo.
	 */
	let imageUrl = $state<string | null>(null);
	$effect(() => {
		const ref = bean.imageRef;
		if (!ref) {
			imageUrl = null;
			return;
		}
		let cancelled = false;
		let createdUrl: string | null = null;
		void (async () => {
			const blob = await getBeanImageStore().get(ref);
			if (cancelled) return;
			if (blob) {
				createdUrl = URL.createObjectURL(blob);
				imageUrl = createdUrl;
			} else {
				imageUrl = null;
			}
		})();
		return () => {
			cancelled = true;
			untrack(() => {
				if (createdUrl) URL.revokeObjectURL(createdUrl);
			});
		};
	});

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
	function onFavClick(): void {
		onToggleFavourite(bean.id);
	}
	function onSetActiveClick(e: MouseEvent): void {
		e.stopPropagation();
		if (isActive) return;
		onSetActive(bean.id);
	}
	function onDuplicateClick(e: MouseEvent): void {
		e.stopPropagation();
		onDuplicate(bean.id);
	}
	function onEditClick(e: MouseEvent): void {
		e.stopPropagation();
		onEdit(bean.id);
	}
	function onDeleteClick(e: MouseEvent): void {
		e.stopPropagation();
		onDelete(bean.id);
	}
</script>

<LibraryCardShell
	cardKind="bean"
	favourite={bean.favourite}
	onFavouriteClick={onFavClick}
	{onCardClick}
	ariaLabel={`Open ${bean.name || 'untitled bean'}`}
	{isActive}
	{isArchived}
	{isFrozen}
>
	{#snippet avatar()}
		<div class="bn-tile-avatar" style="--tone: {mt.tone}">
			{#if imageUrl}
				<img class="bn-tile-photo" src={imageUrl} alt="" />
			{:else}
				<div class="bn-tile-mono">{mt.mark}</div>
			{/if}
			{#if isActive}
				<div class="bn-tile-active-dot" title="Active on Brew"></div>
			{/if}
			{#if isFrozen}
				<div class="bn-tile-frozen" title="Frozen storage">
					<i class="ph-fill ph-snowflake" aria-hidden="true"></i>
				</div>
			{/if}
		</div>
	{/snippet}

	{#snippet head()}
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
	{/snippet}

	{#snippet metrics()}
		<div class="bn-tile-stats">
			<div class="bn-tile-stat">
				<span class="bn-tile-stat-dot" style:background={freshColor}></span>
				<div class="bn-tile-stat-text">
					<span class="bn-tile-stat-val">{days != null ? days + 'd' : '—'}</span>
					<span class="bn-tile-stat-lab">off roast</span>
				</div>
			</div>
			<div class="bn-tile-stat bn-tile-stat-lifecycle">
				{#if frozenDays != null}
					<i class="ph ph-snowflake" aria-hidden="true"></i>
					<div class="bn-tile-stat-text">
						<span class="bn-tile-stat-val">{frozenDays}d</span>
						<span class="bn-tile-stat-lab">frozen</span>
					</div>
				{:else if openedDays != null}
					<i class="ph ph-package" aria-hidden="true"></i>
					<div class="bn-tile-stat-text">
						<span class="bn-tile-stat-val">{openedDays}d</span>
						<span class="bn-tile-stat-lab">open</span>
					</div>
				{:else}
					<!-- Placeholder reserves the slot so off-roast + rating don't
					     drift when a bag has neither frozen nor opened set. -->
					<span class="bn-tile-stat-empty" aria-hidden="true">—</span>
				{/if}
			</div>
			<div class="bn-tile-stat bn-tile-stat-rating" aria-label="{bean.rating} of 5">
				{#each [1, 2, 3, 4, 5] as i (i)}
					<i
						class={i <= bean.rating ? 'ph-fill ph-star' : 'ph ph-star'}
						aria-hidden="true"
					></i>
				{/each}
			</div>
		</div>

		<div
			class="bn-tile-burn"
			class:bn-tile-burn-empty={burnPct == null}
			title={burnPct != null
				? `${bean.remainingG.toFixed(0)} g of ${bean.bagSizeG.toFixed(0)} g`
				: 'No bag size set'}
		>
			<div class="bn-tile-burn-track">
				<div
					class="bn-tile-burn-fill"
					style:width={burnPct != null ? `${burnPct}%` : '0%'}
				></div>
			</div>
			<div class="bn-tile-burn-text">
				{#if burnPct != null}
					<span class="bn-tile-burn-rem"
						>{bean.remainingG.toFixed(0)}<em>g</em></span
					>
					<span class="bn-tile-burn-total"
						>/ {bean.bagSizeG.toFixed(0)}<em>g</em></span
					>
				{:else}
					<span class="bn-tile-burn-total">No bag size</span>
				{/if}
			</div>
		</div>
	{/snippet}

	{#snippet actions()}
		<button
			type="button"
			class="lcs-action lcs-action-primary"
			class:is-on={isActive}
			onclick={onSetActiveClick}
		>
			<i
				class={isActive ? 'ph-fill ph-check-circle' : 'ph ph-coffee'}
				aria-hidden="true"
			></i>
			<span>{isActive ? 'Active on Brew' : 'Set active'}</span>
		</button>
		<button
			type="button"
			class="lcs-action-icon"
			title="Duplicate"
			aria-label="Duplicate"
			onclick={onDuplicateClick}
		>
			<i class="ph ph-copy" aria-hidden="true"></i>
		</button>
		<button
			type="button"
			class="lcs-action-icon"
			title="Edit"
			aria-label="Edit"
			onclick={onEditClick}
		>
			<i class="ph ph-pencil" aria-hidden="true"></i>
		</button>
		<button
			type="button"
			class="lcs-action-icon lcs-action-icon-danger"
			title="Delete"
			aria-label="Delete"
			onclick={onDeleteClick}
		>
			<i class="ph ph-trash" aria-hidden="true"></i>
		</button>
	{/snippet}
</LibraryCardShell>

<style>
	/* Avatar — sits in the shell's `avatar` slot. */
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
		overflow: hidden;
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.1);
	}
	.bn-tile-photo {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
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

	/* Header text in the shell's `head` slot. */
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

	/* Stats row — fixed 3-column grid so off-roast / lifecycle / rating
	   each own a slot that doesn't shift when neighbours are missing. */
	.bn-tile-stats {
		display: grid;
		grid-template-columns: 1fr 1fr auto;
		gap: 12px;
		align-items: center;
	}
	.bn-tile-stat-lifecycle {
		justify-self: start;
	}
	.bn-tile-stat-empty {
		font-family: var(--font-mono);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.25);
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
		gap: 1px;
		font-size: 10px;
		color: var(--copper-400);
		justify-self: end;
	}
	.bn-tile-stat-rating .ph,
	.bn-tile-stat-rating .ph-fill {
		font-size: 10px;
	}
	.bn-tile-stat-rating .ph {
		color: rgba(var(--tint-rgb), 0.2);
	}

	/* Burn-down bar. Always renders so the slot is reserved even when no
	   bag size is set; the `.bn-tile-burn-empty` modifier dims it. */
	.bn-tile-burn {
		display: flex;
		flex-direction: column;
		gap: 3px;
	}
	.bn-tile-burn-empty {
		opacity: 0.45;
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
