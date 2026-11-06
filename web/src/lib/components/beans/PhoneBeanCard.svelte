<script lang="ts">
	/**
	 * `PhoneBeanCard` — a bag in the phone library (Android `PhoneBeanTile`):
	 * avatar and names, the Active badge and star, roast and freshness, the
	 * burn-down, then Set active, edit and ⋮. The body opens the detail screen;
	 * the action row keeps its own taps.
	 *
	 * The same data and handlers as the wide `BeanTile`; what the wide tile keeps
	 * in its icon row is in the page's ⋮ sheet.
	 */
	import CheckCircleIcon from 'phosphor-svelte/lib/CheckCircleIcon';
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import CoffeeBeanIcon from 'phosphor-svelte/lib/CoffeeBeanIcon';
	import SnowflakeIcon from 'phosphor-svelte/lib/SnowflakeIcon';
	import StarIcon from 'phosphor-svelte/lib/StarIcon';
	import {
		beanDaysOffRoast,
		beanFreshness,
		freshnessColor,
		roastBand5,
		roasterMarkTone,
		type Bean,
		type Roaster
	} from '#lib/bean/index.js';
	import { explanatoryHit, hitForField, SearchField, type SearchHit } from '#lib/bean/search.js';
	import StarRating from '#lib/components/common/StarRating.svelte';
	import HighlightedText from '#lib/components/shared/HighlightedText.svelte';
	import PhoneCardActions from '#lib/shell/PhoneCardActions.svelte';
	import BeanImage from './BeanImage.svelte';

	let {
		bean,
		roaster,
		isActive,
		hit,
		onOpen,
		onToggleFavourite,
		onSetActive,
		onRestore,
		onEdit,
		onMenu
	}: {
		bean: Bean;
		roaster: Roaster | null;
		isActive: boolean;
		hit?: SearchHit;
		onOpen: (id: string) => void;
		onToggleFavourite: (id: string) => void;
		onSetActive: (id: string) => void;
		onRestore: (id: string) => void;
		onEdit: (id: string) => void;
		onMenu: (id: string) => void;
	} = $props();

	const name = $derived(bean.name || 'Untitled bag');
	const mark = $derived(roasterMarkTone(roaster));
	const frozen = $derived(!!bean.frozenOn && !bean.defrostedOn);
	const archived = $derived(bean.archivedAt != null);
	const days = $derived(beanDaysOffRoast(bean));
	const freshColor = $derived(freshnessColor(beanFreshness(bean)));
	const freshLabel = $derived(
		frozen ? 'frozen' : days == null ? 'no roast date' : `${days}d off roast`
	);
	const origin = $derived(
		[bean.origin.country, bean.origin.region, bean.origin.processing]
			.filter((part) => part?.trim())
			.join(' · ')
	);
	const burnPct = $derived(
		bean.bagSize > 0 ? Math.max(0, Math.min(100, (bean.remaining / bean.bagSize) * 100)) : null
	);
	const nameHit = $derived(hitForField(hit, SearchField.Name));
	const roasterHit = $derived(hitForField(hit, SearchField.Roaster));
	const whyHit = $derived(explanatoryHit(hit));
</script>

<article
	class={['pbc', { 'is-active': isActive, 'is-archived': archived }]}
	data-scroll-key={bean.id}
>
	<button type="button" class="pbc-open" aria-label="Open {name}" onclick={() => onOpen(bean.id)}
	></button>
	<div class="pbc-head">
		<span class="pbc-avatar" style:--tone={mark.tone}>
			<BeanImage ref={bean.imageRef} className="pbc-photo">
				{#snippet fallback()}<span class="pbc-mono">{mark.mark}</span>{/snippet}
			</BeanImage>
		</span>
		<div class="pbc-names">
			{#if roaster}
				<div class="pbc-roaster">
					<HighlightedText segments={roasterHit?.snippet} fallback={roaster.name} />
				</div>
			{/if}
			<div class="pbc-name">
				<HighlightedText segments={nameHit?.snippet} fallback={name} />
			</div>
			{#if origin}<div class="pbc-origin">{origin}</div>{/if}
			{#if whyHit}
				<div class="pbc-why" title="Matched your search">
					<span>{whyHit.label}</span>
					<HighlightedText segments={whyHit.snippet} />
				</div>
			{/if}
		</div>
		{#if isActive}
			<span class="pbc-badge"><CheckIcon weight="bold" aria-hidden="true" /> Active</span>
		{/if}
		<button
			type="button"
			class={['pbc-star', { 'is-on': bean.favourite }]}
			aria-label={bean.favourite ? `Unpin ${name}` : `Pin ${name}`}
			aria-pressed={bean.favourite}
			onclick={() => onToggleFavourite(bean.id)}
		>
			<StarIcon weight={bean.favourite ? 'fill' : 'regular'} aria-hidden="true" />
		</button>
	</div>

	<div class="pbc-pills">
		{#if bean.roastLevel != null}<span class="pbc-roast">{roastBand5(bean.roastLevel)}</span>{/if}
		{#if frozen}
			<span class="pbc-tag is-frozen"><SnowflakeIcon aria-hidden="true" /> Frozen</span>
		{/if}
		{#if bean.decaf}<span class="pbc-tag">Decaf</span>{/if}
		{#if bean.mix === 'blend'}<span class="pbc-tag">Blend</span>{/if}
		{#if bean.rating > 0}
			<StarRating rating={bean.rating} size={13} emptyColor="var(--ph-outline)" />
		{/if}
		<span class="pbc-fresh" style:color={freshColor}><i></i>{freshLabel}</span>
	</div>

	{#if burnPct != null}
		<div class="pbc-burn">
			<div class="pbc-burn-row">
				<span>Remaining</span>
				<strong>{bean.remaining.toFixed(0)} / {bean.bagSize.toFixed(0)} g</strong>
			</div>
			<div class="pbc-track">
				<i
					class={{ 'is-frozen': frozen }}
					style:width="{burnPct}%"
				></i>
			</div>
		</div>
	{/if}

	<PhoneCardActions
		label={isActive ? 'Active bean' : archived ? 'Restore' : 'Set active'}
		icon={isActive ? CheckCircleIcon : CoffeeBeanIcon}
		on={isActive}
		{name}
		onprimary={() => {
			if (isActive) return;
			if (archived) onRestore(bean.id);
			else onSetActive(bean.id);
		}}
		onedit={() => onEdit(bean.id)}
		onmenu={() => onMenu(bean.id)}
	/>
</article>

<style>
	.pbc {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 11px;
		padding: 14px;
		border-radius: 16px;
		background: var(--bg-surface);
		font-family: var(--font-sans);
	}
	.pbc.is-active {
		background: color-mix(in srgb, var(--copper-500) 8%, var(--bg-surface));
		box-shadow: inset 0 0 0 1.5px var(--copper-500);
	}
	.pbc.is-archived {
		opacity: 0.6;
	}
	/* The body's tap target, under everything that has its own. */
	.pbc-open {
		position: absolute;
		inset: 0;
		border: 0;
		border-radius: 16px;
		background: transparent;
		cursor: pointer;
	}
	.pbc-open:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.pbc-head {
		display: flex;
		align-items: flex-start;
		gap: 12px;
		pointer-events: none;
	}
	.pbc-avatar {
		flex: none;
		display: flex;
		align-items: center;
		justify-content: center;
		width: 46px;
		height: 46px;
		overflow: hidden;
		border-radius: 12px;
		background: var(--tone);
	}
	:global(.pbc-photo) {
		display: block;
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.pbc-mono {
		font-size: 17px;
		font-weight: 500;
		color: #fff;
	}
	.pbc-names {
		flex: 1 1 auto;
		min-width: 0;
	}
	.pbc-roaster {
		font-size: 12px;
		font-weight: 500;
		color: var(--fg-3);
	}
	.pbc-name {
		overflow: hidden;
		font-size: 16px;
		font-weight: 500;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.pbc-origin {
		margin-top: 1px;
		overflow: hidden;
		font-size: 12px;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.pbc-why {
		display: flex;
		gap: 6px;
		margin-top: 2px;
		overflow: hidden;
		font-size: 11px;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.pbc-why span {
		color: var(--fg-muted);
	}
	.pbc-badge {
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 5px;
		height: 24px;
		margin-top: 8px;
		padding: 0 10px;
		border-radius: 999px;
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-size: 10px;
		font-weight: 600;
		letter-spacing: 0.04em;
		text-transform: uppercase;
	}
	.pbc-badge :global(svg) {
		font-size: 11px;
	}
	.pbc-star {
		flex: none;
		position: relative;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 44px;
		height: 44px;
		margin: -2px -8px 0 -8px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		color: var(--fg-3);
		font-size: 20px;
		cursor: pointer;
		pointer-events: auto;
	}
	.pbc-star.is-on {
		color: var(--fg-accent);
	}
	.pbc-star:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.pbc-pills {
		display: flex;
		align-items: center;
		gap: 6px;
		min-height: 24px;
		pointer-events: none;
	}
	.pbc-roast,
	.pbc-tag {
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 4px;
		height: 24px;
		padding: 0 10px;
		font-size: 10.5px;
		font-weight: 600;
		letter-spacing: 0.03em;
		text-transform: uppercase;
	}
	.pbc-roast {
		border-radius: 999px;
		background: rgba(var(--copper-rgb), 0.12);
		color: var(--fg-accent);
	}
	.pbc-tag {
		border-radius: 7px;
		background: var(--ph-highest);
		color: var(--fg-3);
	}
	.pbc-tag.is-frozen {
		background: rgba(74, 111, 165, 0.26);
		color: #4a6fa5;
	}
	:global([data-theme='dark']) .pbc-tag.is-frozen {
		color: #8fb0dd;
	}
	.pbc-fresh {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		margin-left: auto;
		font-family: var(--font-mono);
		font-size: 11px;
		font-weight: 500;
		white-space: nowrap;
	}
	.pbc-fresh i {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: currentColor;
	}
	.pbc-burn {
		display: flex;
		flex-direction: column;
		gap: 6px;
		pointer-events: none;
	}
	.pbc-burn-row {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		font-size: 11px;
		letter-spacing: 0.03em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	.pbc-burn-row strong {
		font-family: var(--font-mono);
		font-size: 12px;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
		letter-spacing: 0;
		text-transform: none;
		color: var(--fg-1);
	}
	.pbc-track {
		height: 7px;
		overflow: hidden;
		border-radius: 999px;
		background: var(--ph-highest);
	}
	.pbc-track i {
		display: block;
		height: 100%;
		border-radius: 999px;
		background: var(--copper-500);
	}
	.pbc-track i.is-frozen {
		background: var(--tel-flow);
	}
	.pbc :global(.pcact) {
		position: relative;
	}
</style>
