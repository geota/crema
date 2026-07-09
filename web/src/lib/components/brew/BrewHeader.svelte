<script lang="ts">
	import ArrowsClockwiseIcon from 'phosphor-svelte/lib/ArrowsClockwiseIcon';
	import SlidersHorizontalIcon from 'phosphor-svelte/lib/SlidersHorizontalIcon';
	/**
	 * `BrewHeader` — the brew dashboard's top strip. The bean lives as a second
	 * block to the right of the Profile block:
	 *
	 *   [ Profile ▸ name · identity · specs ]  … status …  | [ Bean ▸ name · provenance · prep ]  [Quick Controls]
	 *
	 * Each block is a single tap target that opens a searchable
	 * {@link HeaderPicker} of all items (pinned first), whose footer carries
	 * Open-library / Add (+) / Edit (✎). Presentational + callbacks — all data +
	 * wiring lives in `BrewDashboard.svelte`; the middle `status` snippet is
	 * where the parent renders the mode pill / error / maintenance banner.
	 */
	import type { Snippet } from 'svelte';
	import HeaderBlock from './HeaderBlock.svelte';
	import HeaderPicker, { type PickerItem } from './HeaderPicker.svelte';

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
		// Right cluster + middle
		quickSheetOpen = false,
		status,
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
		quickSheetOpen?: boolean;
		status?: Snippet;
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

	let openPicker = $state<'profile' | 'bean' | null>(null);
	let profileBlockEl = $state<HTMLElement | null>(null);
	let beanBlockEl = $state<HTMLElement | null>(null);

	function toggle(which: 'profile' | 'bean'): void {
		openPicker = openPicker === which ? null : which;
	}
	// Close the open picker on any click outside its block (the tap target + the
	// panel both live inside the block, so opening / in-panel clicks don't self-close).
	function onWindowClick(e: MouseEvent): void {
		if (!openPicker) return;
		const el = openPicker === 'profile' ? profileBlockEl : beanBlockEl;
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
		eyebrow="Profile"
		title={profileName}
		meta={profileMeta}
		spec={profileSpec}
		tags={profileTags}
		open={openPicker === 'profile'}
		onTap={() => toggle('profile')}
		bind:el={profileBlockEl}
	>
		{#snippet titleExtra()}
			{#if syncLabel}
				<span class="bh-sync" title={syncTitle}>
					<ArrowsClockwiseIcon aria-hidden="true" />{syncLabel}
				</span>
			{/if}
		{/snippet}
		{#snippet footer()}
			{#if loadedSubline}<span class="bh-loaded">{loadedSubline}</span>{/if}
		{/snippet}
		{#snippet picker()}
			<HeaderPicker
				open={openPicker === 'profile'}
				items={profileItems}
				activeId={activeProfileId}
				searchPlaceholder="Search profiles…"
				addLabel="Add profile"
				canEdit={canEditProfile}
				onSelect={onSelectProfile}
				onAdd={onAddProfile}
				onEdit={onEditProfile}
				onLibrary={onProfileLibrary}
				onClose={() => (openPicker = null)}
			/>
		{/snippet}
	</HeaderBlock>

	<!-- Middle banner slot: mode pill / error / maintenance. -->
	{#if status}
		<div class="bh-status">{@render status()}</div>
	{/if}

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
				items={beanItems}
				activeId={activeBeanId}
				searchPlaceholder="Search beans…"
				addLabel="Add bean"
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
		{#if !quickSheetOpen}
			<button class="qcpill is-dark" onclick={onOpenQuick}>
				<SlidersHorizontalIcon aria-hidden="true" />
				<span>Quick Controls</span>
			</button>
		{/if}
	</div>
</div>
