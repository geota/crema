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
	import { getSettingsStore, convertWeight, convertTemp } from '$lib/settings';

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);

	let {
		profile,
		active = false,
		hidden = false,
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
		/**
		 * Whether this card is rendered inside the "Hidden" filter view.
		 * When `true`, the right-most icon flips from trash/hide → eye
		 * (restore) and its tooltip + handler intent match.
		 */
		hidden?: boolean;
		/** Load this profile on Brew (mark it active). */
		onLoad: (id: string) => void;
		/** Duplicate this profile into a new custom profile, then edit it. */
		onDuplicate: (id: string) => void;
		/** Open this profile in the editor. */
		onEdit: (id: string) => void;
		/** Toggle this profile's pinned-to-favorites state. */
		onTogglePin: (id: string) => void;
		/**
		 * Delete / hide / restore — page-owner-routed:
		 * - custom: hard delete.
		 * - built-in (visible): add to hide-set.
		 * - built-in (hidden, via `hidden` prop): remove from hide-set.
		 */
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
	const doseM = $derived(convertWeight(profile.dose, prefs.weightUnit));
	const tempM = $derived(convertTemp(profile.brewTemp, prefs.tempUnit));
	/**
	 * Right-most action icon — three states:
	 *
	 * - custom profile      → trash    + "Delete"
	 * - built-in (visible)  → eye-slash + "Hide from library"
	 * - built-in (hidden)   → eye       + "Restore"
	 *
	 * `hidden` (prop) only ever applies when viewing the "Hidden"
	 * filter, so it implies a built-in. The icon class names are
	 * Phosphor's `ph` prefix.
	 */
	const removeIcon = $derived(
		hidden ? 'ph-eye' : profile.source === 'builtin' ? 'ph-eye-slash' : 'ph-trash'
	);
	const removeTitle = $derived(
		hidden
			? 'Restore to the library'
			: profile.source === 'builtin'
				? 'Hide from library — restore via the Hidden filter pill'
				: 'Delete this custom profile'
	);
	/**
	 * Whether the right-most icon is destructive (red). True for both
	 * hide and delete — destructive in the sense of "the card vanishes
	 * from the grid." The Restore variant uses a neutral colour.
	 */
	const removeIsDanger = $derived(!hidden);
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
				<div class="pp-metric-val">{doseM.value}<em>{doseM.unit}</em></div>
			</div>
			<div class="pp-metric">
				<div class="pp-metric-label">Temp</div>
				<div class="pp-metric-val">{tempM.value}<em>{tempM.unit}</em></div>
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
				class="pp-action-icon"
				class:pp-action-icon-danger={removeIsDanger}
				title={removeTitle}
				onclick={() => onDelete(profile.id)}
			>
				<i class={`ph ${removeIcon}`} aria-hidden="true"></i>
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
