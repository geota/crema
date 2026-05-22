<script lang="ts">
	/**
	 * `ProfileCard` — one profile in the `/profiles` library grid, ported from
	 * `ProfileCard` in `profiles-page.jsx`.
	 *
	 * Structure (per SCREENS.md §3): a header row (the "Active" pill + the pin
	 * star), the `ProfilePreview` 3-curve mini-chart, the serif name + last-used
	 * line, roast + custom-tag chips, a 4-up metric grid, the notes (2-line
	 * clamp), and the action row (Load on Brew, duplicate, edit, overflow). All
	 * real data — the profile is a `CremaProfile` from the library store.
	 *
	 * The card carries no bean line: a bag of coffee is not profile-scoped (see
	 * `$lib/bean`), so the meta line shows only the relative last-used time.
	 */
	import ProfilePreview from './ProfilePreview.svelte';
	import {
		ratioLabel,
		relativeLastUsed,
		preinfuseSeconds,
		type CremaProfile
	} from '$lib/profiles';

	let {
		profile,
		active = false,
		onLoad,
		onDuplicate,
		onEdit,
		onTogglePin,
		onDelete,
		onExport
	}: {
		/** The profile to render. */
		profile: CremaProfile;
		/** Whether this profile is the one active on the Brew dashboard. */
		active?: boolean;
		/** Load this profile on Brew (mark it active). */
		onLoad: (id: string) => void;
		/** Duplicate this profile into a new custom profile, then edit it. */
		onDuplicate: (id: string) => void;
		/** Open this profile in the editor. */
		onEdit: (id: string) => void;
		/** Toggle this profile's pinned-to-favorites state. */
		onTogglePin: (id: string) => void;
		/** Delete this profile (custom profiles only). */
		onDelete: (id: string) => void;
		/**
		 * Export this profile as a community-v2 `.json` file. The page
		 * owner handles the bridge call + file download. Optional so
		 * existing call sites (none today) don't break.
		 */
		onExport?: (id: string) => void;
	} = $props();

	const ratio = $derived(ratioLabel(profile));
	const preinf = $derived(preinfuseSeconds(profile.segments));
	/**
	 * Custom profiles delete for real; built-ins hide (since the
	 * binary still ships them). The trash icon stays enabled for both
	 * — only the tooltip + confirm text change.
	 */
	const isBuiltin = $derived(profile.source === 'builtin');
</script>

<div class="pp-card" class:is-active={active}>
	<div class="pp-card-head">
		{#if active}
			<div class="pp-card-active">Active</div>
		{:else}
			<span class="pp-card-head-spacer"></span>
		{/if}
		<button
			class="pp-card-pin"
			class:pp-card-pin-off={!profile.pinned}
			title={profile.pinned ? 'Pinned to favorites' : 'Pin to favorites'}
			onclick={() => onTogglePin(profile.id)}
		>
			<i class={profile.pinned ? 'ph-fill ph-star' : 'ph ph-star'} aria-hidden="true"></i>
		</button>
	</div>

	<ProfilePreview segments={profile.segments} {active} />

	<div class="pp-card-body">
		<div class="pp-card-name">{profile.name || 'Untitled profile'}</div>
		<div class="pp-card-bean">
			{profile.source === 'builtin' ? 'Built-in profile' : 'Custom profile'}
			· {relativeLastUsed(profile.lastUsed)}
		</div>

		<div class="pp-card-tags">
			{#if profile.roast != null}
				<span class="pp-card-roast">{profile.roast}</span>
			{/if}
			{#each profile.tags as t (t)}
				<span class="pp-card-tag">{t}</span>
			{/each}
		</div>

		<div class="pp-card-metrics">
			<div class="pp-metric">
				<div class="pp-metric-label">Ratio</div>
				<div class="pp-metric-val">{ratio}</div>
			</div>
			<div class="pp-metric">
				<div class="pp-metric-label">Dose</div>
				<div class="pp-metric-val">{profile.dose}<em>g</em></div>
			</div>
			<div class="pp-metric">
				<div class="pp-metric-label">Temp</div>
				<div class="pp-metric-val">{profile.brewTemp.toFixed(1)}<em>°C</em></div>
			</div>
			<div class="pp-metric">
				<div class="pp-metric-label">Pre-inf</div>
				<div class="pp-metric-val">{preinf}<em>s</em></div>
			</div>
		</div>

		<div class="pp-card-notes">{profile.notes || 'No notes.'}</div>

		<div class="pp-card-actions">
			<button
				class="pp-action pp-action-primary"
				class:is-on={active}
				onclick={() => !active && onLoad(profile.id)}
			>
				<i
					class={active ? 'ph-fill ph-check-circle' : 'ph ph-coffee'}
					aria-hidden="true"
				></i>
				<span>{active ? 'Loaded on Brew' : 'Load on Brew'}</span>
			</button>
			<button
				class="pp-action-icon"
				title="Duplicate"
				onclick={() => onDuplicate(profile.id)}
			>
				<i class="ph ph-copy" aria-hidden="true"></i>
			</button>
			<button class="pp-action-icon" title="Edit" onclick={() => onEdit(profile.id)}>
				<i class="ph ph-pencil-simple" aria-hidden="true"></i>
			</button>
			{#if onExport}
				<button
					class="pp-action-icon"
					title="Download as community v2 .json — re-importable to Crema, reaprime, Visualizer or de1app"
					onclick={() => onExport(profile.id)}
				>
					<i class="ph ph-download-simple" aria-hidden="true"></i>
				</button>
			{/if}
			<button
				class="pp-action-icon pp-action-icon-danger"
				title={isBuiltin
					? 'Hide this built-in from the library — restore from the Profiles page footer'
					: 'Delete this custom profile'}
				onclick={() => onDelete(profile.id)}
			>
				<i class="ph ph-trash" aria-hidden="true"></i>
			</button>
		</div>
	</div>
</div>

<style>
	/* Structural styles (.pp-card, .pp-card-head, .pp-preview, .pp-card-body,
	   …) live in the handoff's profiles-page.css, imported globally by app.css.
	   The card-overflow menu was retired 2026-05-22 — Download (was Export…)
	   and Delete moved into the icon row, Duplicate is already there, and
	   Pin/Unpin is already on the card head (the star). Only the danger
	   variant of `.pp-action-icon` for the far-right Delete trash needs a
	   local style. */
	.pp-action-icon-danger {
		color: var(--danger);
	}
	.pp-action-icon-danger:hover:not(:disabled) {
		color: var(--danger);
		background: rgba(var(--danger-rgb), 0.12);
	}
	.pp-action-icon:disabled {
		opacity: 0.35;
		cursor: not-allowed;
	}
</style>
