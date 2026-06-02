<script lang="ts">
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
	function tapKey(e: KeyboardEvent, which: 'profile' | 'bean'): void {
		if (e.key === 'Enter' || e.key === ' ') {
			e.preventDefault();
			toggle(which);
		}
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
	<!-- Profile block (left; margin-right:auto pushes the rest right). -->
	<div class="bh-block bh-block-profile" bind:this={profileBlockEl}>
		<div
			class="bh-block-tap"
			class:is-open={openPicker === 'profile'}
			role="button"
			tabindex="0"
			aria-haspopup="dialog"
			aria-expanded={openPicker === 'profile'}
			onclick={() => toggle('profile')}
			onkeydown={(e) => tapKey(e, 'profile')}
		>
			<span class="t-eyebrow">Profile</span>
			<div class="bh-title-row">
				<span class="bh-title">{profileName}</span>
				{#if syncLabel}
					<span class="bh-sync" title={syncTitle}>
						<i class="ph ph-arrows-clockwise" aria-hidden="true"></i>{syncLabel}
					</span>
				{/if}
			</div>
			{#if profileMeta}<span class="bh-meta">{profileMeta}</span>{/if}
			{#if profileSpec}<span class="bh-meta bh-spec">{profileSpec}</span>{/if}
			{#if profileTags}<span class="bh-tags">{profileTags}</span>{/if}
			{#if loadedSubline}<span class="bh-loaded">{loadedSubline}</span>{/if}
		</div>
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
	</div>

	<!-- Middle banner slot: mode pill / error / maintenance. -->
	{#if status}
		<div class="bh-status">{@render status()}</div>
	{/if}

	<!-- Bean block (right of the divider). -->
	<span class="bh-divider" aria-hidden="true"></span>
	<div class="bh-block bh-block-bean" bind:this={beanBlockEl}>
		<div
			class="bh-block-tap"
			class:is-open={openPicker === 'bean'}
			role="button"
			tabindex="0"
			aria-haspopup="dialog"
			aria-expanded={openPicker === 'bean'}
			onclick={() => toggle('bean')}
			onkeydown={(e) => tapKey(e, 'bean')}
		>
			<div class="bh-bean-eyebrow">
				<span class="t-eyebrow">Bean</span>
				{#if freshLabel}
					<span class="bh-fresh" style="color:{freshColor}">
						<span class="bh-fresh-dot" style="background:{freshColor}"></span>{freshLabel}
					</span>
				{/if}
			</div>
			<div class="bh-title-row">
				<span class="bh-title bh-bean-title">{beanName}</span>
			</div>
			{#if beanMeta}<span class="bh-meta bh-bean-meta">{beanMeta}</span>{/if}
			{#if beanPrep}<span class="bh-meta bh-bean-prep">{beanPrep}</span>{/if}
			{#if beanTags}<span class="bh-tags">{beanTags}</span>{/if}
		</div>
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
	</div>

	<!-- Right cluster: Quick Controls pill (Add / Edit live in the pickers). -->
	<div class="crema-dash-head-r bh-actions">
		{#if !quickSheetOpen}
			<button class="qcpill is-dark" onclick={onOpenQuick}>
				<i class="ph ph-sliders-horizontal" aria-hidden="true"></i>
				<span>Quick Controls</span>
			</button>
		{/if}
	</div>
</div>
