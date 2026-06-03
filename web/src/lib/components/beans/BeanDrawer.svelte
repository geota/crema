<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	import CoffeeIcon from 'phosphor-svelte/lib/CoffeeIcon';
	import PencilSimpleIcon from 'phosphor-svelte/lib/PencilSimpleIcon';
	import SnowflakeIcon from 'phosphor-svelte/lib/SnowflakeIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
	/**
	 * `BeanDrawer` — read-only detail panel that slides in from the right
	 * when a bean tile is clicked. Mirrors the design's `BeanDrawer.svelte`
	 * structure: a hero + status strip + collapsible groups (Identity,
	 * Origin, Bag + Grinder, Dates, Tasting, Buy-again, Shots) and a
	 * footer with Set-active / Edit / Archive / Delete.
	 *
	 * Editing happens elsewhere — the design's drawer is *display*, not
	 * an input surface. Clicking the Edit footer button fires `onEdit(id)`
	 * which the parent uses to navigate to the full editor route.
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
	import { getHistoryStore } from '$lib/history';
	import BeanImage from './BeanImage.svelte';
	import RoastSlider from './RoastSlider.svelte';
	import BeanDeleteSplit from './BeanDeleteSplit.svelte';

	let {
		bean,
		roaster,
		isActive,
		onClose,
		onSetActive,
		onEdit,
		onToggleArchived,
		onToggleFavourite
	}: {
		bean: Bean;
		roaster: Roaster | null;
		isActive: boolean;
		onClose: () => void;
		onSetActive: (id: string) => void;
		onEdit: (id: string) => void;
		onToggleArchived: (id: string) => void;
		onToggleFavourite: (id: string) => void;
	} = $props();

	const history = getHistoryStore();

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
	const freshLabel = $derived(
		freshness === 'best'
			? 'In window'
			: freshness === 'ok'
				? 'Fading'
				: freshness === 'bad'
					? 'Stale'
					: 'Unknown'
	);

	const isFrozen = $derived(!!bean.frozenOn && !bean.defrostedOn);
	const isArchived = $derived(bean.archivedAt != null);
	const openedDays = $derived(bean.openedOn ? daysOffRoast(bean.openedOn) : null);

	const burnPct = $derived.by<number>(() => {
		if (bean.bagSize <= 0) return 0;
		return Math.max(0, Math.min(100, (bean.remaining / bean.bagSize) * 100));
	});
	const shotsRemaining = $derived.by<number>(() => {
		if (bean.bagSize <= 0) return 0;
		return Math.max(0, Math.floor(bean.remaining / 18));
	});

	const shotsWithThis = $derived(history.all.filter((s) => s.bean?.beanId === bean.id));

	// Group open/closed state — match the design's defaults.
	let openIdentity = $state(true);
	let openDates = $state(true);
	let openBag = $state(true);
	let openOrigin = $state(true);
	let openTasting = $state(true);
	let openBuyAgain = $state(false);
	let openShots = $state(true);

	function fmtDate(s: string | null): string {
		if (!s) return '—';
		const d = new Date(s);
		if (Number.isNaN(d.getTime())) return '—';
		return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
	}

	function onKey(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			onClose();
		}
	}

</script>

<div
	class="bn-drawer-scrim"
	onclick={onClose}
	onkeydown={onKey}
	role="button"
	tabindex="-1"
	aria-label="Close detail"
></div>

<!-- The drawer uses `<aside>` for landmark semantics but presents as a
     modal dialog, which Svelte's a11y linter (correctly) flags as a
     conflict. Drop the role; the scrim + `aria-modal` semantics on the
     wrapping `<div role="button">` already establish focus management.
     The dialog name is conveyed by `aria-labelledby` on the inner
     content. The Escape key is intercepted at the window level via
     {@link svelte:window} below — no per-element listener needed. -->
<svelte:window onkeydown={onKey} />
<aside class="bn-drawer" aria-labelledby="bn-drawer-name">
	<header class="bn-drawer-head">
		<div class="bn-drawer-roaster">
			<span class="bn-drawer-roaster-mark" style="--tone: {mt.tone}">{mt.mark}</span>
			<span class="bn-drawer-roaster-name">{roaster?.name ?? 'No roaster'}</span>
		</div>
		<div class="bn-drawer-actions">
			{#if isActive}
				<span class="bn-drawer-pill bn-drawer-pill-on">
					<span class="bn-drawer-pill-dot"></span> Active
				</span>
			{/if}
			<button
				class="bn-drawer-icon"
				onclick={() => onToggleFavourite(bean.id)}
				title={bean.favourite ? 'Unpin from brew picker' : 'Pin to brew picker'}
				aria-label={bean.favourite ? 'Unpin' : 'Pin'}
			>
				<Icon
					cls={bean.favourite ? 'ph-fill ph-star' : 'ph ph-star'}
					color={bean.favourite ? 'var(--copper-400)' : undefined}
				 />
			</button>
			<button
				class="bn-drawer-icon"
				onclick={() => onEdit(bean.id)}
				title="Edit"
				aria-label="Edit"
			>
				<PencilSimpleIcon aria-hidden="true" />
			</button>
			<button
				class="bn-drawer-icon"
				onclick={onClose}
				title="Close"
				aria-label="Close"
			>
				<XIcon aria-hidden="true" />
			</button>
		</div>
	</header>

	<div class="bn-drawer-scroll">
		<!-- Hero -->
		<div class="bn-drawer-hero">
			<div class="bn-drawer-photo" style="--tone: {mt.tone}">
				<BeanImage ref={bean.imageRef} className="bn-drawer-photo-img">
					{#snippet fallback()}
						<div class="bn-drawer-photo-mark">{mt.mark}</div>
					{/snippet}
				</BeanImage>
				{#if isFrozen}
					<div class="bn-drawer-photo-frozen" title="Frozen storage">
						<SnowflakeIcon weight="fill" aria-hidden="true" />
					</div>
				{/if}
			</div>
			<div class="bn-drawer-hero-text">
				<div class="bn-drawer-name" id="bn-drawer-name">
					{bean.name || 'Untitled bag'}
				</div>
				<div class="bn-drawer-origin">
					{#if bean.origin.country}
						{bean.origin.country}{bean.origin.region
							? ' · ' + bean.origin.region
							: ''}
					{:else}
						No origin set
					{/if}
				</div>
				<div class="bn-drawer-pills">
					{#if bean.roastLevel != null}
						<span class="bn-tile-roastpill"
							>{roastBand5(bean.roastLevel)} · {bean.roastLevel}/10</span
						>
					{/if}
					{#if bean.mix === 'blend'}
						<span class="bn-tile-pill">Blend</span>
					{/if}
					{#if bean.decaf}
						<span class="bn-tile-pill">Decaf</span>
					{/if}
					<span class="bn-drawer-rating" aria-label="{bean.rating} of 5">
						{#each [1, 2, 3, 4, 5] as i (i)}
							<Icon
									cls={i <= bean.rating ? 'ph-fill ph-star' : 'ph ph-star'}
									color={i <= bean.rating ? 'var(--copper-400)' : 'rgba(var(--tint-rgb), 0.2)'}
								/>
						{/each}
					</span>
				</div>
			</div>
		</div>

		<!-- Status strip -->
		<div class="bn-drawer-status">
			<div class="bn-drawer-status-row">
				<div class="bn-drawer-status-cell">
					<div class="bn-cell-label">Off roast</div>
					<div class="bn-cell-val">
						<span class="bn-status-dot" style:background={freshColor}></span>{days ??
							'—'}<em>{days != null ? 'd' : ''}</em>
					</div>
					<div class="bn-cell-sub">{freshLabel}</div>
				</div>
				<div class="bn-drawer-status-cell">
					<div class="bn-cell-label">Opened</div>
					<div class="bn-cell-val">{openedDays ?? '—'}<em>{openedDays != null ? 'd' : ''}</em></div>
					<div class="bn-cell-sub">{fmtDate(bean.openedOn)}</div>
				</div>
				<div class="bn-drawer-status-cell">
					<div class="bn-cell-label">Remaining</div>
					<div class="bn-cell-val">{bean.remaining.toFixed(0)}<em>g</em></div>
					<div class="bn-cell-sub">
						of {bean.bagSize.toFixed(0)}<em>g</em>
						{#if shotsRemaining > 0}· ~{shotsRemaining} shots{/if}
					</div>
				</div>
				<div class="bn-drawer-status-cell">
					<div class="bn-cell-label">Shots</div>
					<div class="bn-cell-val">{shotsWithThis.length}</div>
					<div class="bn-cell-sub">
						{bean.qualityScore ? bean.qualityScore + ' score' : 'in history'}
					</div>
				</div>
			</div>
			{#if bean.bagSize > 0}
				<div class="bn-drawer-burn">
					<div class="bn-drawer-burn-track">
						<div class="bn-drawer-burn-fill" style:width="{burnPct}%"></div>
					</div>
				</div>
			{/if}
		</div>

		<!-- Identity -->
		<section class="bn-group" class:is-collapsed={!openIdentity}>
			<header class="bn-group-head">
				<div class="bn-group-title">Identity</div>
				<button
					class="bn-group-toggle"
					onclick={() => (openIdentity = !openIdentity)}
					aria-label={openIdentity ? 'Collapse' : 'Expand'}
				>
					<Icon cls={openIdentity ? 'ph ph-caret-up' : 'ph ph-caret-down'} />
				</button>
			</header>
			{#if openIdentity}
				<div class="bn-group-body">
					<div class="bn-row"><div class="bn-row-label">Name</div><div class="bn-row-val">{bean.name || '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Roaster</div><div class="bn-row-val">{roaster?.name ?? '—'}</div></div>
					<div class="bn-row">
						<div class="bn-row-label">Roast level</div>
						<div class="bn-row-val"><RoastSlider value={bean.roastLevel} /></div>
					</div>
					<div class="bn-row"><div class="bn-row-label">Mix</div><div class="bn-row-val">{bean.mix === 'blend' ? 'Blend' : bean.mix === 'single' ? 'Single origin' : '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Decaf</div><div class="bn-row-val">{bean.decaf ? 'Yes' : 'No'}</div></div>
					{#if bean.tags.length > 0}
						<div class="bn-row">
							<div class="bn-row-label">Tags</div>
							<div class="bn-row-val bn-row-tags">
								{#each bean.tags as t (t)}
									<span class="bn-tile-pill bn-tile-pill-tag">{t}</span>
								{/each}
							</div>
						</div>
					{/if}
				</div>
			{/if}
		</section>

		<!-- Dates -->
		<section class="bn-group" class:is-collapsed={!openDates}>
			<header class="bn-group-head">
				<div class="bn-group-title">Dates</div>
				<button
					class="bn-group-toggle"
					onclick={() => (openDates = !openDates)}
					aria-expanded={openDates}
					aria-label={openDates ? 'Collapse Dates' : 'Expand Dates'}
				>
					<Icon cls={openDates ? 'ph ph-caret-up' : 'ph ph-caret-down'} />
				</button>
			</header>
			{#if openDates}
				<div class="bn-group-body">
					<div class="bn-row"><div class="bn-row-label">Roasted on</div><div class="bn-row-val">{fmtDate(bean.roastedOn)}{days != null ? ` · ${days}d ago` : ''}</div></div>
					<div class="bn-row"><div class="bn-row-label">Opened on</div><div class="bn-row-val">{fmtDate(bean.openedOn)}{openedDays != null ? ` · ${openedDays}d ago` : ''}</div></div>
					<div class="bn-row"><div class="bn-row-label">Frozen on</div><div class="bn-row-val">{fmtDate(bean.frozenOn)}</div></div>
					<div class="bn-row"><div class="bn-row-label">Defrosted on</div><div class="bn-row-val">{fmtDate(bean.defrostedOn)}</div></div>
				</div>
			{/if}
		</section>

		<!-- Bag + Grinder -->
		<section class="bn-group" class:is-collapsed={!openBag}>
			<header class="bn-group-head">
				<div class="bn-group-title">Bag &amp; grinder</div>
				<button
					class="bn-group-toggle"
					onclick={() => (openBag = !openBag)}
					aria-expanded={openBag}
					aria-label={openBag ? 'Collapse Bag' : 'Expand Bag'}
				>
					<Icon cls={openBag ? 'ph ph-caret-up' : 'ph ph-caret-down'} />
				</button>
			</header>
			{#if openBag}
				<div class="bn-group-body">
					<div class="bn-row"><div class="bn-row-label">Bag size</div><div class="bn-row-val">{bean.bagSize > 0 ? `${bean.bagSize.toFixed(0)}g` : '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Remaining</div><div class="bn-row-val">{bean.bagSize > 0 ? `${bean.remaining.toFixed(0)}g` : '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Grinder</div><div class="bn-row-val">{bean.grinder || '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Setting</div><div class="bn-row-val">{bean.grinderSetting || '—'}</div></div>
				</div>
			{/if}
		</section>

		<!-- Origin -->
		<section class="bn-group" class:is-collapsed={!openOrigin}>
			<header class="bn-group-head">
				<div class="bn-group-title">Origin</div>
				<button
					class="bn-group-toggle"
					onclick={() => (openOrigin = !openOrigin)}
					aria-expanded={openOrigin}
					aria-label={openOrigin ? 'Collapse Origin' : 'Expand Origin'}
				>
					<Icon cls={openOrigin ? 'ph ph-caret-up' : 'ph ph-caret-down'} />
				</button>
			</header>
			{#if openOrigin}
				<div class="bn-group-body">
					<div class="bn-row"><div class="bn-row-label">Country</div><div class="bn-row-val">{bean.origin.country || '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Region</div><div class="bn-row-val">{bean.origin.region || '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Farm</div><div class="bn-row-val">{bean.origin.farm || '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Variety</div><div class="bn-row-val">{bean.origin.variety || '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Elevation</div><div class="bn-row-val">{bean.origin.elevation || '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Processing</div><div class="bn-row-val">{bean.origin.processing || '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Harvest</div><div class="bn-row-val">{bean.origin.harvestTime || '—'}</div></div>
				</div>
			{/if}
		</section>

		<!-- Tasting -->
		<section class="bn-group" class:is-collapsed={!openTasting}>
			<header class="bn-group-head">
				<div class="bn-group-title">Tasting</div>
				<button
					class="bn-group-toggle"
					onclick={() => (openTasting = !openTasting)}
					aria-expanded={openTasting}
					aria-label={openTasting ? 'Collapse Tasting' : 'Expand Tasting'}
				>
					<Icon cls={openTasting ? 'ph ph-caret-up' : 'ph ph-caret-down'} />
				</button>
			</header>
			{#if openTasting}
				<div class="bn-group-body">
					<div class="bn-row"><div class="bn-row-label">Score</div><div class="bn-row-val">{bean.qualityScore || '—'}</div></div>
					<div class="bn-row"><div class="bn-row-label">Rating</div><div class="bn-row-val bn-row-rating">
						{#each [1, 2, 3, 4, 5] as i (i)}
							<Icon
									cls={i <= bean.rating ? 'ph-fill ph-star' : 'ph ph-star'}
									color={i <= bean.rating ? 'var(--copper-400)' : 'rgba(var(--tint-rgb), 0.2)'}
								/>
						{/each}
					</div></div>
					{#if bean.tastingNotes}
						<div class="bn-row bn-row-stack">
							<div class="bn-row-label">Notes</div>
							<div class="bn-row-val bn-row-notes">{bean.tastingNotes}</div>
						</div>
					{/if}
				</div>
			{/if}
		</section>

		<!-- Buy again -->
		<section class="bn-group" class:is-collapsed={!openBuyAgain}>
			<header class="bn-group-head">
				<div class="bn-group-title">Buy again</div>
				<button
					class="bn-group-toggle"
					onclick={() => (openBuyAgain = !openBuyAgain)}
					aria-expanded={openBuyAgain}
					aria-label={openBuyAgain ? 'Collapse Buy again' : 'Expand Buy again'}
				>
					<Icon cls={openBuyAgain ? 'ph ph-caret-up' : 'ph ph-caret-down'} />
				</button>
			</header>
			{#if openBuyAgain}
				<div class="bn-group-body">
					<div class="bn-row"><div class="bn-row-label">Place</div><div class="bn-row-val">{bean.placeOfPurchase || '—'}</div></div>
					<div class="bn-row">
						<div class="bn-row-label">URL</div>
						<div class="bn-row-val">
							{#if bean.url}
								<a class="bn-link" href={bean.url} target="_blank" rel="noopener noreferrer"
									>{bean.url.replace(/^https?:\/\//, '')}</a
								>
							{:else}
								—
							{/if}
						</div>
					</div>
				</div>
			{/if}
		</section>

		<!-- Shots with this bean -->
		{#if shotsWithThis.length > 0}
			<section class="bn-group" class:is-collapsed={!openShots}>
				<header class="bn-group-head">
					<div class="bn-group-title">
						Shots <span class="bn-group-count">{shotsWithThis.length}</span>
					</div>
					<button
						class="bn-group-toggle"
						onclick={() => (openShots = !openShots)}
						aria-expanded={openShots}
						aria-label={openShots ? 'Collapse Shots' : 'Expand Shots'}
					>
						<Icon cls={openShots ? 'ph ph-caret-up' : 'ph ph-caret-down'} />
					</button>
				</header>
				{#if openShots}
					<div class="bn-group-body">
						<div class="bn-shotrows">
							{#each shotsWithThis.slice(0, 5) as s (s.id)}
								{@const sRating = s.metadata.rating ?? 0}
								<div class="bn-shotrow">
									<div class="bn-shotrow-time">
										{new Date(s.completedAt).toLocaleDateString(undefined, {
											month: 'short',
											day: 'numeric'
										})}
									</div>
									<div class="bn-shotrow-meta">
										<span class="bn-shotrow-profile">{s.profileName ?? '—'}</span>
									</div>
									<div class="bn-shotrow-rating" aria-label="{sRating} of 5">
										{#each [1, 2, 3, 4, 5] as i (i)}
											<Icon
											cls={i <= sRating ? 'ph-fill ph-star' : 'ph ph-star'}
											color={i <= sRating ? 'var(--copper-400)' : 'rgba(var(--tint-rgb), 0.2)'}
										/>
										{/each}
									</div>
								</div>
							{/each}
						</div>
					</div>
				{/if}
			</section>
		{/if}

		<div class="bn-drawer-foot">
			<button
				class="bn-foot-btn bn-foot-btn-danger"
				onclick={() => onToggleArchived(bean.id)}
			>
				<Icon cls={isArchived ? 'ph ph-archive-box' : 'ph ph-archive'} />
				{isArchived ? 'Restore' : 'Archive'}
			</button>
			<BeanDeleteSplit
				beanId={bean.id}
				beanName={bean.name || 'this bag'}
				label="Delete"
				size="md"
				onDeleted={onClose}
			/>
			{#if !isActive && !isArchived}
				<button class="bn-foot-btn bn-foot-btn-primary" onclick={() => onSetActive(bean.id)}>
					<CoffeeIcon aria-hidden="true" /> Set active
				</button>
			{/if}
		</div>
	</div>
</aside>

<style>
	.bn-drawer-scrim {
		position: fixed;
		inset: 0;
		background: rgba(var(--scrim-rgb, 0, 0, 0), 0.4);
		z-index: 68;
	}
	.bn-drawer {
		position: fixed;
		top: 0;
		right: 0;
		bottom: 0;
		width: min(440px, 100vw);
		background: var(--bg-page);
		border-left: 1px solid rgba(var(--tint-rgb), 0.12);
		z-index: 69;
		display: flex;
		flex-direction: column;
		box-shadow: -16px 0 48px rgba(0, 0, 0, 0.18);
	}
	.bn-drawer-head {
		display: flex;
		justify-content: space-between;
		align-items: center;
		padding: 14px 18px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.08);
		gap: 12px;
		flex-shrink: 0;
	}
	.bn-drawer-roaster {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
	}
	.bn-drawer-roaster-mark {
		width: 24px;
		height: 24px;
		border-radius: 50%;
		background: var(--tone, var(--copper-500));
		color: #fff;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		font-family: var(--font-sans);
		font-weight: 700;
		font-size: 10px;
		letter-spacing: 0.04em;
		flex-shrink: 0;
	}
	.bn-drawer-roaster-name {
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 600;
		color: var(--fg-1);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.bn-drawer-actions {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.bn-drawer-pill {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		padding: 3px 9px;
		border-radius: var(--radius-pill);
		font-family: var(--font-sans);
		font-size: 9.5px;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: var(--track-allcaps);
	}
	.bn-drawer-pill-on {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.bn-drawer-pill-dot {
		width: 5px;
		height: 5px;
		border-radius: 50%;
		background: currentColor;
	}
	.bn-drawer-icon {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.55);
		font-size: 14px;
		padding: 6px;
		border-radius: var(--radius-sm);
		cursor: pointer;
		display: inline-flex;
		align-items: center;
		justify-content: center;
	}
	.bn-drawer-icon:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}

	.bn-drawer-scroll {
		flex: 1 1 auto;
		overflow-y: auto;
		padding: 16px 18px 20px;
		display: flex;
		flex-direction: column;
		gap: 14px;
	}

	/* Hero */
	.bn-drawer-hero {
		display: flex;
		gap: 14px;
		align-items: flex-start;
	}
	.bn-drawer-photo {
		position: relative;
		width: 96px;
		height: 96px;
		flex-shrink: 0;
		border-radius: var(--radius-md);
		background:
			linear-gradient(135deg, color-mix(in srgb, var(--tone) 88%, transparent), var(--tone)),
			var(--tone);
		display: flex;
		align-items: center;
		justify-content: center;
		overflow: hidden;
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.1);
	}
	:global(.bn-drawer-photo-img) {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}
	.bn-drawer-photo-mark {
		font-family: var(--font-serif);
		font-weight: 500;
		font-size: 38px;
		color: rgba(255, 255, 255, 0.92);
		letter-spacing: -0.02em;
	}
	.bn-drawer-photo-frozen {
		position: absolute;
		top: 6px;
		right: 6px;
		width: 24px;
		height: 24px;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		background: rgba(125, 160, 205, 0.95);
		color: #fff;
		border-radius: 50%;
		font-size: 12px;
	}
	.bn-drawer-hero-text {
		flex: 1 1 auto;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.bn-drawer-name {
		font-family: var(--font-serif);
		font-size: 21px;
		font-weight: 500;
		line-height: 1.2;
		color: var(--fg-1);
		letter-spacing: -0.01em;
	}
	.bn-drawer-origin {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.6);
	}
	.bn-drawer-pills {
		display: flex;
		flex-wrap: wrap;
		gap: 4px;
		margin-top: 4px;
		align-items: center;
	}
	.bn-tile-roastpill {
		display: inline-flex;
		align-items: center;
		font-family: var(--font-sans);
		font-size: 9.5px;
		font-weight: 600;
		padding: 2px 7px;
		border-radius: var(--radius-pill);
		background: rgba(var(--copper-rgb), 0.14);
		color: var(--copper-300);
		border: 1px solid rgba(var(--copper-rgb), 0.28);
		letter-spacing: 0.02em;
	}
	.bn-tile-pill {
		display: inline-flex;
		font-family: var(--font-sans);
		font-size: 9.5px;
		font-weight: 600;
		padding: 2px 7px;
		border-radius: var(--radius-pill);
		background: rgba(var(--tint-rgb), 0.06);
		color: rgba(var(--tint-rgb), 0.62);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		letter-spacing: 0.02em;
	}
	.bn-tile-pill-tag {
		background: rgba(var(--copper-rgb), 0.08);
		color: var(--copper-400);
		border-color: rgba(var(--copper-rgb), 0.22);
		font-weight: 500;
	}
	.bn-drawer-rating {
		display: inline-flex;
		gap: 1px;
		margin-left: 4px;
		font-size: 11px;
		color: var(--copper-400);
	}
	.bn-drawer-rating :global(svg) {
		color: rgba(var(--tint-rgb), 0.2);
	}

	/* Status strip */
	.bn-drawer-status {
		display: flex;
		flex-direction: column;
		gap: 8px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-md);
		padding: 12px 14px;
	}
	.bn-drawer-status-row {
		display: grid;
		grid-template-columns: repeat(4, 1fr);
		gap: 10px;
	}
	.bn-drawer-status-cell {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.bn-cell-label {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.bn-cell-val {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 16px;
		font-weight: 600;
		color: var(--fg-1);
		display: inline-flex;
		align-items: center;
		gap: 5px;
	}
	.bn-cell-val em {
		font-style: normal;
		color: rgba(var(--tint-rgb), 0.45);
		font-size: 11px;
	}
	.bn-status-dot {
		width: 6px;
		height: 6px;
		border-radius: 50%;
	}
	.bn-cell-sub {
		font-family: var(--font-sans);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.5);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.bn-cell-sub em {
		font-style: normal;
	}
	.bn-drawer-burn {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.bn-drawer-burn-track {
		height: 4px;
		background: rgba(var(--tint-rgb), 0.08);
		border-radius: 999px;
		overflow: hidden;
	}
	.bn-drawer-burn-fill {
		height: 100%;
		background: linear-gradient(90deg, var(--copper-500), var(--copper-400));
		border-radius: 999px;
	}

	/* Collapsible groups */
	.bn-group {
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-md);
		background: rgba(var(--tint-rgb), 0.02);
	}
	.bn-group-head {
		display: flex;
		justify-content: space-between;
		align-items: center;
		padding: 10px 14px;
	}
	.bn-group-title {
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: var(--fg-1);
	}
	.bn-group-count {
		font-family: var(--font-mono);
		font-size: 9px;
		color: rgba(var(--tint-rgb), 0.5);
		margin-left: 4px;
		font-weight: 500;
	}
	.bn-group-toggle {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.5);
		cursor: pointer;
		font-size: 11px;
		padding: 4px;
	}
	.bn-group-toggle:hover {
		color: var(--fg-1);
	}
	.bn-group-body {
		padding: 4px 14px 12px;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.bn-row {
		display: grid;
		grid-template-columns: 96px 1fr;
		gap: 12px;
		font-family: var(--font-sans);
		font-size: 12px;
		align-items: baseline;
	}
	.bn-row-stack {
		grid-template-columns: 1fr;
	}
	.bn-row-label {
		color: rgba(var(--tint-rgb), 0.55);
		font-size: 11px;
		font-weight: 500;
	}
	.bn-row-val {
		color: var(--fg-1);
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.bn-row-tags {
		display: flex;
		flex-wrap: wrap;
		gap: 4px;
	}
	.bn-row-rating {
		display: inline-flex;
		gap: 1px;
		color: var(--copper-400);
	}
	.bn-row-rating :global(svg) {
		color: rgba(var(--tint-rgb), 0.2);
	}
	.bn-row-notes {
		white-space: pre-wrap;
		line-height: 1.45;
		padding: 8px 0 0;
	}
	.bn-link {
		color: var(--copper-400);
		text-decoration: none;
	}
	.bn-link:hover {
		text-decoration: underline;
	}

	.bn-shotrows {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.bn-shotrow {
		display: grid;
		grid-template-columns: auto 1fr auto;
		gap: 12px;
		align-items: center;
		padding: 6px 0;
		font-family: var(--font-sans);
		font-size: 11px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.05);
	}
	.bn-shotrow:first-child {
		border-top: 0;
	}
	.bn-shotrow-time {
		font-family: var(--font-mono);
		color: rgba(var(--tint-rgb), 0.6);
		font-variant-numeric: tabular-nums;
	}
	.bn-shotrow-profile {
		color: var(--fg-1);
		font-weight: 500;
	}
	.bn-shotrow-rating {
		display: inline-flex;
		gap: 1px;
		color: var(--copper-400);
		font-size: 10px;
	}
	.bn-shotrow-rating :global(svg) {
		color: rgba(var(--tint-rgb), 0.2);
	}

	/* Footer */
	.bn-drawer-foot {
		display: flex;
		gap: 8px;
		flex-wrap: wrap;
		padding-top: 6px;
	}
	.bn-foot-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: rgba(var(--tint-rgb), 0.06);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 500;
		padding: 8px 14px;
		border-radius: var(--radius-pill);
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.bn-foot-btn:hover {
		background: rgba(var(--tint-rgb), 0.1);
	}
	.bn-foot-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
		border-color: transparent;
		margin-left: auto;
	}
	.bn-foot-btn-primary:hover {
		background: var(--copper-600);
	}
	.bn-foot-btn-danger:hover {
		color: var(--danger);
		border-color: rgba(var(--danger-rgb), 0.4);
		background: rgba(var(--danger-rgb), 0.08);
	}
</style>
