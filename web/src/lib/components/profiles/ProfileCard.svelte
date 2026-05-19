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
		onDelete
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
	} = $props();

	const ratio = $derived(ratioLabel(profile));
	const preinf = $derived(preinfuseSeconds(profile.segments));
	/** Built-in profiles cannot be deleted — the overflow menu hides Delete. */
	const deletable = $derived(profile.source === 'custom');

	/** Whether the overflow menu is open. */
	let menuOpen = $state(false);
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
			<div class="pp-card-menu">
				<button
					class="pp-action-icon"
					title="More"
					aria-haspopup="menu"
					aria-expanded={menuOpen}
					onclick={() => (menuOpen = !menuOpen)}
				>
					<i class="ph ph-dots-three" aria-hidden="true"></i>
				</button>
				{#if menuOpen}
					<!-- A click-away backdrop closes the menu. -->
					<button
						class="pp-menu-scrim"
						aria-label="Close menu"
						onclick={() => (menuOpen = false)}
					></button>
					<div class="pp-menu" role="menu">
						<button
							class="pp-menu-item"
							role="menuitem"
							onclick={() => {
								menuOpen = false;
								onTogglePin(profile.id);
							}}
						>
							<i class={profile.pinned ? 'ph ph-star-half' : 'ph ph-star'} aria-hidden="true"
							></i>
							{profile.pinned ? 'Unpin from favorites' : 'Pin to favorites'}
						</button>
						<button
							class="pp-menu-item"
							role="menuitem"
							onclick={() => {
								menuOpen = false;
								onDuplicate(profile.id);
							}}
						>
							<i class="ph ph-copy" aria-hidden="true"></i> Duplicate
						</button>
						<button
							class="pp-menu-item pp-menu-item-danger"
							role="menuitem"
							disabled={!deletable}
							title={deletable ? '' : 'Built-in profiles cannot be deleted'}
							onclick={() => {
								menuOpen = false;
								if (deletable) onDelete(profile.id);
							}}
						>
							<i class="ph ph-trash" aria-hidden="true"></i> Delete
						</button>
					</div>
				{/if}
			</div>
		</div>
	</div>
</div>

<style>
	/* Structural styles (.pp-card, .pp-card-head, .pp-preview, .pp-card-body,
	   …) live in the handoff's profiles-page.css, imported globally by app.css.
	   Only the overflow menu — which the design mocked but never styled — is
	   defined here. */
	.pp-card-menu {
		position: relative;
	}
	.pp-menu-scrim {
		position: fixed;
		inset: 0;
		background: transparent;
		border: 0;
		cursor: default;
		z-index: 20;
	}
	.pp-menu {
		position: absolute;
		right: 0;
		bottom: calc(100% + 6px);
		z-index: 21;
		background: var(--bg-surface-2);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-sm);
		box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
		padding: 4px;
		display: flex;
		flex-direction: column;
		min-width: 180px;
	}
	.pp-menu-item {
		display: flex;
		align-items: center;
		gap: 8px;
		background: transparent;
		border: 0;
		border-radius: 6px;
		padding: 8px 10px;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		cursor: pointer;
		text-align: left;
		transition: background var(--dur-1) var(--ease);
	}
	.pp-menu-item i {
		font-size: 14px;
	}
	.pp-menu-item:hover {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.pp-menu-item:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}
	.pp-menu-item-danger {
		color: var(--warning);
	}
	.pp-menu-item-danger:hover:not(:disabled) {
		background: rgba(217, 119, 87, 0.1);
	}
</style>
