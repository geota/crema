<script lang="ts">
	/**
	 * `ProfileCard` — one profile in the `/profiles` library grid, ported from
	 * `ProfileCard` in `profiles-page.jsx`.
	 *
	 * Shows the `QSparkline` hero curve, the serif name, a bean · last-used
	 * line, roast + custom-tag chips, a 4-up metric strip, the notes, and the
	 * action row (Load on Brew, duplicate, edit, overflow). The star pin and the
	 * "Active" pill sit in the corners. All real data — the profile is a
	 * `CremaProfile` from the library store.
	 */
	import QSparkline from '$lib/components/brew/QSparkline.svelte';
	import {
		ratioLabel,
		sparkShape,
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

	const shape = $derived(sparkShape(profile.segments));
	const ratio = $derived(ratioLabel(profile));
	const preinf = $derived(preinfuseSeconds(profile.segments));
	/** Built-in profiles cannot be deleted — the overflow menu hides Delete. */
	const deletable = $derived(profile.source === 'custom');

	/** Whether the overflow menu is open. */
	let menuOpen = $state(false);
</script>

<div class="pp-card" class:is-active={active}>
	{#if active}
		<div class="pp-card-active">Active</div>
	{/if}
	<button
		class="pp-card-pin"
		class:pp-card-pin-off={!profile.pinned}
		title={profile.pinned ? 'Pinned to favorites' : 'Pin to favorites'}
		onclick={() => onTogglePin(profile.id)}
	>
		<i class={profile.pinned ? 'ph-fill ph-star' : 'ph ph-star'} aria-hidden="true"></i>
	</button>

	<div class="pp-card-spark">
		<QSparkline
			{shape}
			width={220}
			height={56}
			color={active ? 'var(--copper-400)' : 'rgba(244,237,224,0.55)'}
		/>
	</div>

	<div class="pp-card-body">
		<div class="pp-card-name">{profile.name || 'Untitled profile'}</div>
		<div class="pp-card-bean">
			{profile.bean || (profile.source === 'builtin' ? 'Built-in profile' : 'No bean set')}
			· {profile.lastUsed ?? 'never used'}
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
	.pp-card {
		position: relative;
		background: var(--espresso-900);
		border: 1px solid rgba(244, 237, 224, 0.05);
		border-radius: var(--radius-lg, 14px);
		padding: 20px 22px 18px;
		display: flex;
		flex-direction: column;
		gap: 14px;
		transition: all var(--dur-1) var(--ease);
	}
	.pp-card:hover {
		border-color: rgba(244, 237, 224, 0.12);
		transform: translateY(-1px);
	}
	.pp-card.is-active {
		border-color: var(--copper-500);
		box-shadow: 0 0 0 1px var(--copper-500);
	}
	.pp-card-active {
		position: absolute;
		top: 14px;
		left: 22px;
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: #1a120c;
		background: var(--copper-500);
		padding: 3px 8px;
		border-radius: 999px;
	}
	.pp-card-pin {
		position: absolute;
		top: 14px;
		right: 18px;
		background: transparent;
		border: 0;
		color: var(--copper-400);
		font-size: 18px;
		cursor: pointer;
		padding: 4px;
		border-radius: 6px;
		transition: all var(--dur-1) var(--ease);
		z-index: 1;
	}
	.pp-card-pin:hover {
		background: rgba(244, 237, 224, 0.05);
	}
	.pp-card-pin.pp-card-pin-off {
		color: rgba(244, 237, 224, 0.25);
	}
	.pp-card-pin.pp-card-pin-off:hover {
		color: rgba(244, 237, 224, 0.6);
	}

	.pp-card-spark {
		display: flex;
		align-items: center;
		justify-content: center;
		height: 64px;
		background: var(--espresso-950);
		border-radius: 10px;
		margin-top: 12px;
		border: 1px solid rgba(244, 237, 224, 0.04);
	}

	.pp-card-body {
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.pp-card-name {
		font-family: var(--font-serif);
		font-size: 22px;
		letter-spacing: -0.01em;
		color: var(--ink-50);
	}
	.pp-card-bean {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(244, 237, 224, 0.5);
	}

	.pp-card-tags {
		display: flex;
		flex-wrap: wrap;
		gap: 4px;
		margin-top: 2px;
	}
	.pp-card-tag {
		display: inline-flex;
		align-items: center;
		padding: 2px 7px;
		background: rgba(193, 116, 75, 0.08);
		border: 1px solid rgba(193, 116, 75, 0.25);
		border-radius: 999px;
		font-family: var(--font-sans);
		font-size: 10px;
		color: var(--copper-400);
	}
	.pp-card-roast {
		display: inline-flex;
		align-items: center;
		padding: 2px 7px;
		background: rgba(244, 237, 224, 0.05);
		border: 1px solid rgba(244, 237, 224, 0.08);
		border-radius: 999px;
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(244, 237, 224, 0.6);
	}

	.pp-card-metrics {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: 6px;
		padding: 10px 0;
		border-top: 1px solid rgba(244, 237, 224, 0.05);
		border-bottom: 1px solid rgba(244, 237, 224, 0.05);
	}
	.pp-metric {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.pp-metric-label {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(244, 237, 224, 0.4);
	}
	.pp-metric-val {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 14px;
		color: var(--ink-50);
	}
	.pp-metric-val em {
		font-style: normal;
		font-size: 10px;
		color: rgba(244, 237, 224, 0.5);
		margin-left: 1px;
	}

	.pp-card-notes {
		font-family: var(--font-sans);
		font-size: 12px;
		line-height: 1.5;
		color: rgba(244, 237, 224, 0.6);
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}

	.pp-card-actions {
		display: flex;
		align-items: center;
		gap: 6px;
		margin-top: 2px;
	}
	.pp-action {
		flex: 1 1 auto;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 6px;
		padding: 8px 12px;
		border-radius: var(--radius-sm);
		border: 1px solid rgba(244, 237, 224, 0.1);
		background: rgba(244, 237, 224, 0.03);
		color: var(--ink-50);
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 500;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.pp-action:hover {
		background: rgba(244, 237, 224, 0.07);
		border-color: rgba(244, 237, 224, 0.18);
	}
	.pp-action-primary.is-on {
		background: rgba(193, 116, 75, 0.12);
		border-color: var(--copper-500);
		color: var(--copper-400);
		cursor: default;
	}
	.pp-action-primary.is-on i {
		color: var(--copper-400);
	}
	.pp-action-icon {
		width: 32px;
		height: 32px;
		flex: 0 0 32px;
		border: 1px solid rgba(244, 237, 224, 0.1);
		background: rgba(244, 237, 224, 0.03);
		border-radius: var(--radius-sm);
		color: rgba(244, 237, 224, 0.6);
		cursor: pointer;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		font-size: 14px;
		transition: all var(--dur-1) var(--ease);
	}
	.pp-action-icon:hover {
		color: var(--ink-50);
		background: rgba(244, 237, 224, 0.07);
	}

	/* Overflow menu */
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
		background: var(--espresso-850);
		border: 1px solid rgba(244, 237, 224, 0.1);
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
		color: var(--ink-50);
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
		background: rgba(244, 237, 224, 0.06);
	}
	.pp-menu-item:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}
	.pp-menu-item-danger {
		color: #d97757;
	}
	.pp-menu-item-danger:hover:not(:disabled) {
		background: rgba(217, 119, 87, 0.1);
	}
</style>
