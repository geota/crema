<script lang="ts" module>
	/** What the Profile block shows in Manual. */
	export type RecipeHeader = {
		/** "Recipe · V60". */
		eyebrow: string;
		name: string;
		/** "15 g · 250 g water · 100 °C · ~3:00". */
		meta: string;
		/** A built-in: Duplicate to edit; your own: Edit recipe. */
		builtin: boolean;
	};
</script>

<script lang="ts">
	import ArrowsClockwiseIcon from 'phosphor-svelte/lib/ArrowsClockwiseIcon';
	import CopyIcon from 'phosphor-svelte/lib/CopyIcon';
	import PencilSimpleIcon from 'phosphor-svelte/lib/PencilSimpleIcon';
	import SlidersHorizontalIcon from 'phosphor-svelte/lib/SlidersHorizontalIcon';
	/**
	 * `BrewHeader` — the brew dashboard's top strip. The bean lives as a second
	 * block to the right of the Profile block:
	 *
	 *   [ Profile ▸ name · specs ] | [ Grinder ▸ name · grind ] | [ Bean ▸ name · prep ]  [Quick Controls]
	 *
	 * The Grinder block (#488, board WideBrew2) shows the active grinder and
	 * Brew's grind with its unit, plus the unsaved-grind marker ("● Bag 4.2")
	 * while the grind differs from the bag's saved one.
	 *
	 * Each block is a single tap target that opens a searchable
	 * {@link HeaderPicker} of all items (pinned first), whose footer carries
	 * Open-library / Add (+) / Edit (✎). Presentational + callbacks — all data +
	 * wiring lives in `BrewDashboard.svelte`. Errors and maintenance are not
	 * here: they are `BrewStatusCard`, at the foot of the left column.
	 *
	 * Brew modes (§2): the Profile picker lists Espresso profiles, then Manual
	 * recipes, and its search filters both. In Manual (`recipe` set) the Profile block shows
	 * the recipe ("Recipe · V60", its name, dose · water · temp · time) and
	 * Quick Controls gives way to Duplicate to edit (Edit recipe for your own).
	 * While a shot or guided brew runs (`pickLock`) the rows are disabled.
	 */
	import StarIcon from 'phosphor-svelte/lib/StarIcon';
	import HeaderBlock from './HeaderBlock.svelte';
	import HeaderPicker, { type PickerItem, type PickerSection } from './HeaderPicker.svelte';
	import type { BrewPickLock } from '#lib/core/crema-core.js';
	import GrinderPicker, { type GrinderPickRow } from '#lib/components/grinders/GrinderPicker.svelte';
	import GrindMarker from '#lib/components/grinders/GrindMarker.svelte';
	import type { Grinder, GrinderChoice } from '#lib/bean/grinder.js';

	let {
		// Profile block
		profileName,
		profileMeta = '',
		profileSpec = '',
		profileTags = '',
		loadedSubline = null,
		syncLabel = null,
		syncTitle = '',
		canEditProfile = true,
		profileItems,
		activeProfileId = null,
		// Bean block
		beanName,
		beanMeta,
		beanPrep = '',
		beanTags = '',
		freshLabel = null,
		freshColor = 'rgba(var(--tint-rgb), 0.4)',
		beanItems,
		activeBeanId = null,
		beanRank,
		// Grinder block
		grinderName,
		grinderStarred = false,
		grindLabel,
		grindMarker = null,
		grindMarkerTitle = '',
		grinderRows,
		activeGrinderId = null,
		allGrinders,
		onSelectGrinder,
		onAddGrinder,
		onEditBagGrinds,
		onDevices,
		// Right cluster
		quickSheetOpen = false,
		// Manual mode
		recipe = null,
		recipeItems = [],
		activeRecipeId = null,
		pickLock = null,
		onSelectRecipe,
		onAddRecipe,
		onRecipeLibrary,
		onEditRecipe,
		// Callbacks
		onEditProfile,
		onSelectProfile,
		onAddProfile,
		onProfileLibrary,
		onEditBean,
		onSelectBean,
		onAddBean,
		onBeanLibrary,
		onOpenQuick
	}: {
		profileName: string;
		/** Line 1 — shot specs (pre-infuse · ratio · target · temp). */
		profileMeta?: string;
		/** Line 2 — beverage type · roast · author. */
		profileSpec?: string;
		/** Line 3 — tags (faint). */
		profileTags?: string;
		loadedSubline?: string | null;
		/** "Uploading… 3/9" sync chip text, or null when idle. */
		syncLabel?: string | null;
		syncTitle?: string;
		canEditProfile?: boolean;
		profileItems: PickerItem[];
		activeProfileId?: string | null;
		beanName: string;
		beanMeta: string;
		/** Line 2 — roast · roast-type · mix · grind. */
		beanPrep?: string;
		/** Line 3 — tags (faint). */
		beanTags?: string;
		/** "4d off roast" freshness label for the block's top-right, or null. */
		freshLabel?: string | null;
		/** Colour for the freshness dot + label (best/ok/bad/neutral). */
		freshColor?: string;
		beanItems: PickerItem[];
		activeBeanId?: string | null;
		/** Relevance ranker for the bean dropdown — see `HeaderPicker.rank`. */
		beanRank?: (query: string) => Map<string, number> | null;
		/** The active grinder's name, or "No grinder". */
		grinderName: string;
		/** The active grinder is the selected bag's starred one. */
		grinderStarred?: boolean;
		/** "Grind 480 μm". */
		grindLabel: string;
		/** "Bag 4.2" while the grind is unsaved, else null. */
		grindMarker?: string | null;
		grindMarkerTitle?: string;
		grinderRows: readonly GrinderPickRow[];
		activeGrinderId?: string | null;
		allGrinders: readonly Grinder[];
		onSelectGrinder: (id: string | null) => void;
		onAddGrinder: (choice: GrinderChoice) => void;
		onEditBagGrinds?: () => void;
		onDevices: () => void;
		quickSheetOpen?: boolean;
		/** Set in Manual: the Profile block shows this recipe. */
		recipe?: RecipeHeader | null;
		/** The picker's Manual recipes rows. */
		recipeItems?: PickerItem[];
		activeRecipeId?: string | null;
		/** The picker lock (§4), or null. */
		pickLock?: BrewPickLock | null;
		onSelectRecipe?: (id: string) => void;
		onAddRecipe?: () => void;
		onRecipeLibrary?: () => void;
		/** Duplicate (a built-in) or edit the selected recipe; omitted while a brew runs. */
		onEditRecipe?: () => void;
		onEditProfile?: () => void;
		onSelectProfile: (id: string) => void;
		onAddProfile?: () => void;
		onProfileLibrary?: () => void;
		onEditBean?: () => void;
		onSelectBean: (id: string) => void;
		onAddBean?: () => void;
		onBeanLibrary?: () => void;
		onOpenQuick?: () => void;
	} = $props();

	let openPicker = $state<'profile' | 'grinder' | 'bean' | null>(null);
	let profileBlockEl = $state<HTMLElement | null>(null);
	let grinderBlockEl = $state<HTMLElement | null>(null);
	let beanBlockEl = $state<HTMLElement | null>(null);

	function toggle(which: 'profile' | 'grinder' | 'bean'): void {
		openPicker = openPicker === which ? null : which;
	}

	/** The Profile picker: Espresso profiles, then Manual recipes (§2, §4). */
	const pickerSections = $derived<PickerSection[]>([
		{
			id: 'profiles',
			label: 'Espresso profiles',
			items: profileItems,
			activeId: recipe ? null : activeProfileId,
			locked: pickLock ? pickLock.note : null
		},
		{
			id: 'recipes',
			label: 'Manual recipes',
			items: recipeItems,
			activeId: activeRecipeId,
			locked: pickLock?.recipesLocked ? pickLock.note : null
		}
	]);
	// Close the open picker on any click outside its block (the tap target + the
	// panel both live inside the block, so opening / in-panel clicks don't self-close).
	function onWindowClick(e: MouseEvent): void {
		if (!openPicker) return;
		const el = openPicker === 'profile' ? profileBlockEl : openPicker === 'grinder' ? grinderBlockEl : beanBlockEl;
		if (el && !el.contains(e.target as Node)) openPicker = null;
	}
</script>

<svelte:window onclick={onWindowClick} />

<div class="crema-dash-head bh-header">
	<!-- Profile block (left; margin-right:auto pushes the rest right). The
	     block anatomy is the shared HeaderBlock (issue #16 round 4: the
	     History detail renders the SAME component over a shot snapshot). -->
	<HeaderBlock
		cls="bh-block-profile"
		variant="profile"
		eyebrow={recipe ? recipe.eyebrow : 'Profile'}
		title={recipe ? recipe.name : profileName}
		meta={recipe ? recipe.meta : profileMeta}
		spec={recipe ? '' : profileSpec}
		tags={recipe ? '' : profileTags}
		tapLabel={recipe ? `${recipe.eyebrow}: ${recipe.name}. Choose profile or recipe` : undefined}
		open={openPicker === 'profile'}
		onTap={() => toggle('profile')}
		bind:el={profileBlockEl}
	>
		{#snippet titleExtra()}
			{#if syncLabel && !recipe}
				<span class="bh-sync" title={syncTitle}>
					<ArrowsClockwiseIcon aria-hidden="true" />{syncLabel}
				</span>
			{/if}
		{/snippet}
		{#snippet footer()}
			{#if loadedSubline && !recipe}<span class="bh-loaded">{loadedSubline}</span>{/if}
		{/snippet}
		{#snippet picker()}
			<HeaderPicker
				open={openPicker === 'profile'}
				label="Choose profile or recipe"
				sections={pickerSections}
				searchPlaceholder="Search profiles and recipes…"
				addLabel={recipe ? 'Add recipe' : 'Add profile'}
				canEdit={recipe ? onEditRecipe != null : canEditProfile}
				onSelect={(id, section) => (section === 'recipes' ? onSelectRecipe?.(id) : onSelectProfile(id))}
				onAdd={recipe ? onAddRecipe : onAddProfile}
				onEdit={recipe ? onEditRecipe : onEditProfile}
				onLibrary={recipe ? onRecipeLibrary : onProfileLibrary}
				onClose={() => (openPicker = null)}
			/>
		{/snippet}
	</HeaderBlock>

	<!-- Grinder block (#488): Profile | Grinder | Bean, evenly spaced. -->
	<span class="bh-divider" aria-hidden="true"></span>
	<HeaderBlock
		cls="bh-block-grinder"
		variant="bean"
		eyebrow="Grinder"
		title={grinderName}
		tapLabel="Grinder: {grinderName}, {grindLabel}. Choose grinder"
		open={openPicker === 'grinder'}
		onTap={() => toggle('grinder')}
		bind:el={grinderBlockEl}
	>
		{#snippet titleExtra()}
			{#if grinderStarred}<StarIcon weight="fill" class="bh-grinder-star" aria-label="this bag's grinder" />{/if}
		{/snippet}
		{#snippet footer()}
			<span class="bh-meta bh-grind-line">
				<span>{grindLabel}</span>
				{#if grindMarker}<GrindMarker text={grindMarker} title={grindMarkerTitle} interactive={false} />{/if}
			</span>
		{/snippet}
		{#snippet picker()}
			<GrinderPicker
				open={openPicker === 'grinder'}
				rows={grinderRows}
				activeId={activeGrinderId}
				bagName={activeBeanId ? beanName : null}
				{allGrinders}
				onSelect={onSelectGrinder}
				onAdd={onAddGrinder}
				onEditBag={activeBeanId ? onEditBagGrinds : undefined}
				{onDevices}
				onClose={() => (openPicker = null)}
			/>
		{/snippet}
	</HeaderBlock>

	<!-- Bean block (right of the divider). -->
	<span class="bh-divider" aria-hidden="true"></span>
	<HeaderBlock
		cls="bh-block-bean"
		variant="bean"
		eyebrow="Bean"
		{freshLabel}
		{freshColor}
		title={beanName}
		meta={beanMeta}
		spec={beanPrep}
		tags={beanTags}
		open={openPicker === 'bean'}
		onTap={() => toggle('bean')}
		bind:el={beanBlockEl}
	>
		{#snippet picker()}
			<HeaderPicker
				open={openPicker === 'bean'}
				label="Choose bean"
				items={beanItems}
				activeId={activeBeanId}
				searchPlaceholder="Search beans, origin, notes…"
				addLabel="Add bean"
				rank={beanRank}
				onSelect={onSelectBean}
				onAdd={onAddBean}
				onEdit={onEditBean}
				onLibrary={onBeanLibrary}
				onClose={() => (openPicker = null)}
			/>
		{/snippet}
	</HeaderBlock>

	<!-- Right cluster: Quick Controls pill (Add / Edit live in the pickers). -->
	<div class="crema-dash-head-r bh-actions">
		<!-- Stays mounted while the sheet is open (it sits under the sheet's
		     scrim) so the sheet can hand focus back to it on close. -->
		{#if recipe}
			<!-- Manual: the recipe's own edit door in Quick Controls' place. -->
			<button type="button" class="qcpill is-dark" disabled={!onEditRecipe} onclick={onEditRecipe}>
				{#if recipe.builtin}<CopyIcon aria-hidden="true" />{:else}<PencilSimpleIcon aria-hidden="true" />{/if}
				<span>{recipe.builtin ? 'Duplicate to edit' : 'Edit recipe'}</span>
			</button>
		{:else}
			<button
				type="button"
				class="qcpill is-dark"
				aria-haspopup="dialog"
				aria-expanded={quickSheetOpen}
				onclick={onOpenQuick}
			>
				<SlidersHorizontalIcon aria-hidden="true" />
				<span>Quick Controls</span>
			</button>
		{/if}
	</div>
</div>

<style>
	/* Profile | Grinder | Bean, evenly spaced (#488): the Grinder block takes
	   the same free space after it as the Profile block does. */
	:global(.bh-header .bh-block-grinder) {
		margin-right: auto;
	}
	:global(.bh-grinder-star) {
		color: var(--fg-accent);
		font-size: 14px;
		flex: none;
	}
	.bh-grind-line {
		display: inline-flex;
		align-items: center;
		gap: 10px;
		overflow: visible;
	}
</style>
