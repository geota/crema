<script lang="ts">
	/**
	 * Settings — the `/settings` route, ported from `SettingsPage` in
	 * `settings-page.jsx`.
	 *
	 * A two-pane screen: a left section nav (eight sections) and a right
	 * scrollable content pane. The active section is local UI state. Each
	 * section is its own component under `$lib/components/settings/sections`;
	 * the `St*` primitives live in `$lib/components/settings`.
	 *
	 * ## Real vs. stubbed — by section
	 *
	 * - **Machine** — real: DE1 connection state ← `lib/state`, Connect /
	 *   Disconnect / Re-pair → `CremaApp`. Firmware version / update and the
	 *   peripheral registry are UI-only (the shell has no DE1 firmware read nor
	 *   a device registry).
	 * - **Brew defaults** — real: every value is a persisted app preference in
	 *   `lib/settings` (app-side defaults the Brew screen can read later).
	 * - **Water & maintenance** — UI-only: filter / descale tracking needs DE1
	 *   water counters the shell does not expose.
	 * - **Display & units** — real: units, density, screensaver persisted; the
	 *   theme control is fully end-to-end (sets `data-theme` on `<html>`).
	 * - **Sound & feedback** — real: persisted app preferences.
	 * - **Sharing** — Visualizer upload *preferences* are real & persisted; the
	 *   Visualizer connection / sign-in / upload need the external service and
	 *   are faithful local UI. History export is real (a JSON download).
	 * - **Advanced** — real: telemetry-display + debug-panel toggles persisted;
	 *   "Reset preferences" really resets `lib/settings`. Integrations / factory
	 *   reset are UI-only.
	 * - **About** — real: name + version from `package.json`; project links.
	 *
	 * The `lib/settings` store applies the persisted theme at construction, so
	 * the screen — and a reload — paints under the right theme.
	 */
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { getSettingsStore } from '$lib/settings';
	import MachineSection from '$lib/components/settings/sections/MachineSection.svelte';
	import BrewDefaultsSection from '$lib/components/settings/sections/BrewDefaultsSection.svelte';
	import WaterSection from '$lib/components/settings/sections/WaterSection.svelte';
	import DisplaySection from '$lib/components/settings/sections/DisplaySection.svelte';
	import SoundSection from '$lib/components/settings/sections/SoundSection.svelte';
	import SharingSection from '$lib/components/settings/sections/SharingSection.svelte';
	import AdvancedSection from '$lib/components/settings/sections/AdvancedSection.svelte';
	import CalibrationSection from '$lib/components/settings/sections/CalibrationSection.svelte';
	import AboutSection from '$lib/components/settings/sections/AboutSection.svelte';

	import { INITIAL_SNAPSHOT } from '$lib/state';

	const ctx = getCremaAppContext();
	/** The shared orchestrator, or `null` while the wasm core loads. */
	const app = $derived(ctx().app);
	/** The live UI snapshot — drives the Machine section's connection panel. */
	const snapshot = $derived(app?.state.current ?? INITIAL_SNAPSHOT);
	/** The DE1's coarse connection state — drives the Machine section. */
	const de1State = $derived(snapshot.de1State);

	// Constructing the settings store applies the persisted theme to <html>.
	getSettingsStore();

	/** The eight nav sections — id, label, Phosphor icon. */
	const SECTIONS = [
		{ id: 'machine', label: 'Machine', icon: 'plug-charging' },
		{ id: 'brew', label: 'Brew defaults', icon: 'coffee' },
		{ id: 'water', label: 'Water & maintenance', icon: 'drop' },
		{ id: 'display', label: 'Display & units', icon: 'monitor' },
		{ id: 'sound', label: 'Sound & feedback', icon: 'speaker-simple-high' },
		{ id: 'sharing', label: 'Sharing', icon: 'share-network' },
		{ id: 'calibration', label: 'Calibration', icon: 'gauge' },
		{ id: 'advanced', label: 'Advanced', icon: 'wrench' },
		{ id: 'about', label: 'About', icon: 'info' }
	] as const;

	/** The currently shown section. */
	let active = $state<(typeof SECTIONS)[number]['id']>('machine');
</script>

<svelte:head>
	<title>Crema — Settings</title>
</svelte:head>

<div class="qcontain st-page">
	<div class="st-shell">
		<!-- Left section nav -->
		<nav class="st-nav" aria-label="Settings sections">
			<div class="st-nav-head">
				<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Preferences</div>
				<div class="st-nav-title">Settings</div>
			</div>
			<div class="st-nav-items">
				{#each SECTIONS as s (s.id)}
					<button
						type="button"
						class="st-nav-item"
						class:is-active={active === s.id}
						aria-current={active === s.id ? 'page' : undefined}
						onclick={() => (active = s.id)}
					>
						<i class={'ph-duotone ph-' + s.icon} aria-hidden="true"></i>
						<span>{s.label}</span>
					</button>
				{/each}
			</div>
			<div class="st-nav-foot">
				<div class="st-nav-foot-key">Crema · Settings</div>
			</div>
		</nav>

		<!-- Right content pane -->
		<div class="st-content">
			{#if active === 'machine'}
				<MachineSection {app} {de1State} {snapshot} />
			{:else if active === 'brew'}
				<BrewDefaultsSection />
			{:else if active === 'water'}
				<WaterSection />
			{:else if active === 'display'}
				<DisplaySection />
			{:else if active === 'sound'}
				<SoundSection />
			{:else if active === 'sharing'}
				<SharingSection />
			{:else if active === 'calibration'}
				<CalibrationSection {app} />
			{:else if active === 'advanced'}
				<AdvancedSection {app} />
			{:else if active === 'about'}
				<AboutSection />
			{/if}
		</div>
	</div>
</div>

<style>
	/* ── Page shell ──────────────────────────────────────────────────────
	   Ported from settings-page.css, kept consistent with Steps 2–4: a dark
	   espresso surface, the two-pane grid, scoped class names matching the
	   design's `St*` markup. */
	.st-page {
		background: var(--bg-page);
		color: var(--fg-1);
		display: flex;
		min-height: 100vh;
		overflow-y: auto;
	}
	.st-shell {
		display: grid;
		grid-template-columns: 240px 1fr;
		width: 100%;
	}

	/* ── Left section nav ───────────────────────────────────────────────── */
	.st-nav {
		background: var(--bg-page);
		border-right: 1px solid rgba(var(--tint-rgb), 0.05);
		/* Shared page-header rhythm — the "Preferences" header block sits at
		   the same left / top origin as every other route's title. See
		   --page-pad-* in app.css. */
		padding: var(--page-pad-top) 20px 20px var(--page-pad-x);
		display: flex;
		flex-direction: column;
		gap: 14px;
		position: sticky;
		top: 0;
		align-self: start;
		min-height: 100vh;
	}
	.st-nav-head {
		margin-bottom: 4px;
	}
	.st-nav-title {
		font-family: var(--font-serif);
		font-size: 26px;
		letter-spacing: -0.015em;
		color: var(--fg-1);
		margin-top: 2px;
	}
	.st-nav-items {
		display: flex;
		flex-direction: column;
		gap: 2px;
		flex: 1 1 auto;
	}
	.st-nav-item {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 9px 10px;
		background: transparent;
		border: 0;
		border-radius: var(--radius-sm);
		color: rgba(var(--tint-rgb), 0.7);
		font-family: var(--font-sans);
		font-size: 13px;
		cursor: pointer;
		text-align: left;
		transition: all var(--dur-1) var(--ease);
		position: relative;
	}
	.st-nav-item:hover {
		background: rgba(var(--tint-rgb), 0.03);
		color: var(--fg-1);
	}
	.st-nav-item i {
		font-size: 16px;
		color: rgba(var(--tint-rgb), 0.5);
		flex: 0 0 auto;
	}
	.st-nav-item.is-active {
		background: rgba(var(--tint-rgb), 0.05);
		color: var(--fg-1);
	}
	.st-nav-item.is-active i {
		color: var(--copper-400);
	}
	.st-nav-item.is-active::before {
		content: '';
		position: absolute;
		/* Pull the marker to the nav column's left edge — matches the nav's
		   left padding (--page-pad-x). */
		left: calc(-1 * var(--page-pad-x));
		top: 8px;
		bottom: 8px;
		width: 2px;
		background: var(--copper-500);
		border-radius: 0 2px 2px 0;
	}
	.st-nav-foot {
		border-top: 1px solid rgba(var(--tint-rgb), 0.05);
		padding-top: 12px;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.st-nav-foot-key {
		font-family: var(--font-mono);
		font-size: 10px;
		letter-spacing: 0.1em;
		color: rgba(var(--tint-rgb), 0.35);
		text-transform: uppercase;
	}

	/* ── Right content ──────────────────────────────────────────────────── */
	.st-content {
		padding: 36px 56px 60px;
		max-width: 820px;
	}

	/* ── Section head — :global because the markup lives in StSectionHead /
	   AboutSection child components. ──────────────────────────────────────── */
	:global(.st-content .st-shead) {
		margin-bottom: 28px;
		padding-bottom: 18px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.05);
	}
	/* Section title uses the shared .t-page-title role; only the layout
	   nudge is screen-specific. */
	:global(.st-content .st-shead-title) {
		margin-top: 2px;
	}
	:global(.st-content .st-shead-sub) {
		font-family: var(--font-sans);
		font-size: 13px;
		line-height: 1.5;
		color: rgba(var(--tint-rgb), 0.55);
		margin-top: 8px;
		max-width: 580px;
	}

	/* ── Group ──────────────────────────────────────────────────────────── */
	:global(.st-content .st-group) {
		margin-top: 28px;
	}
	:global(.st-content .st-group-head) {
		margin-bottom: 12px;
	}
	:global(.st-content .st-group-title) {
		font-family: var(--font-sans);
		font-size: 11px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 700;
		color: rgba(var(--tint-rgb), 0.55);
	}
	:global(.st-content .st-group-sub) {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.45);
		margin-top: 3px;
	}
	:global(.st-content .st-group-rows) {
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.05);
		border-radius: var(--radius-md);
		overflow: hidden;
	}

	/* ── Row ────────────────────────────────────────────────────────────── */
	:global(.st-content .st-row) {
		display: grid;
		grid-template-columns: 1fr auto;
		gap: 18px;
		align-items: center;
		padding: 14px 18px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.04);
	}
	:global(.st-content .st-row:last-child) {
		border-bottom: 0;
	}
	:global(.st-content .st-row-text) {
		display: flex;
		flex-direction: column;
		gap: 3px;
	}
	:global(.st-content .st-row-title) {
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
		font-weight: 500;
	}
	:global(.st-content .st-row-sub) {
		font-family: var(--font-sans);
		font-size: 11px;
		line-height: 1.45;
		color: rgba(var(--tint-rgb), 0.5);
	}
	:global(.st-content .st-row-control) {
		display: flex;
		align-items: center;
		gap: 10px;
		flex-shrink: 0;
	}
	:global(.st-content .st-row-hint) {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.4);
	}

	/* ── Controls ───────────────────────────────────────────────────────── */
	:global(.st-content .st-select-wrap) {
		position: relative;
		display: inline-block;
	}
	:global(.st-content .st-select) {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 6px 28px 6px 12px;
		cursor: pointer;
		appearance: none;
		-webkit-appearance: none;
	}
	:global(.st-content .st-select-wrap i) {
		position: absolute;
		right: 8px;
		top: 50%;
		transform: translateY(-50%);
		color: rgba(var(--tint-rgb), 0.4);
		font-size: 12px;
		pointer-events: none;
	}

	:global(.st-content .st-segment) {
		display: inline-flex;
		background: rgba(var(--tint-rgb), 0.04);
		border-radius: var(--radius-pill);
		padding: 3px;
	}
	:global(.st-content .st-segment > button) {
		background: transparent;
		border: 0;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
		color: rgba(var(--tint-rgb), 0.55);
		padding: 5px 12px;
		border-radius: 999px;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	:global(.st-content .st-segment > button.is-active) {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	:global(.st-content .st-segment > button:disabled) {
		opacity: 0.4;
		cursor: not-allowed;
	}

	:global(.st-content .st-btn) {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 7px 12px;
		border-radius: var(--radius-sm);
		font-family: var(--font-sans);
		font-size: 12px;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
		border: 1px solid transparent;
		text-decoration: none;
	}
	:global(.st-content .st-btn i) {
		font-size: 13px;
	}
	:global(.st-content .st-btn:disabled) {
		opacity: 0.4;
		cursor: not-allowed;
	}
	:global(.st-content .st-btn-secondary) {
		background: rgba(var(--tint-rgb), 0.04);
		border-color: rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
	}
	:global(.st-content .st-btn-secondary:hover:not(:disabled)) {
		background: rgba(var(--tint-rgb), 0.08);
	}
	:global(.st-content .st-btn-primary) {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	:global(.st-content .st-btn-primary:hover:not(:disabled)) {
		background: var(--copper-600);
	}
	:global(.st-content .st-btn-danger) {
		background: rgba(167, 64, 64, 0.1);
		border-color: rgba(167, 64, 64, 0.35);
		color: var(--danger);
	}
	:global(.st-content .st-btn-danger:hover:not(:disabled)) {
		background: rgba(167, 64, 64, 0.18);
	}

	:global(.st-content .st-valuechip) {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 13px;
		padding: 6px 12px;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	:global(.st-content .st-valuechip:hover) {
		background: rgba(var(--tint-rgb), 0.08);
		border-color: rgba(var(--tint-rgb), 0.18);
	}
	:global(.st-content .st-valuechip i) {
		color: rgba(var(--tint-rgb), 0.4);
		font-size: 11px;
	}
	/* The inline-edit input the value chip swaps to — same metrics as the chip. */
	:global(.st-content .st-valuechip-input) {
		width: 96px;
		background: rgba(var(--tint-rgb), 0.06);
		border: 1px solid var(--copper-500);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 13px;
		padding: 6px 10px;
	}

	:global(.st-content .st-statusdot) {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-1);
	}
	:global(.st-content .st-statusdot-d) {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--success);
		box-shadow: 0 0 0 2.5px rgba(107, 140, 95, 0.2);
	}
	:global(.st-content .st-statusdot-d.off) {
		background: rgba(var(--tint-rgb), 0.25);
		box-shadow: none;
	}

	/* ── Machine hero card ──────────────────────────────────────────────── */
	:global(.st-content .st-machinecard) {
		display: grid;
		grid-template-columns: 120px 1fr 280px;
		gap: 24px;
		align-items: stretch;
		padding: 20px 22px;
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-lg, 14px);
	}
	:global(.st-content .st-machinecard-img) {
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--bg-page);
		border-radius: var(--radius-md);
		border: 1px solid rgba(var(--tint-rgb), 0.04);
	}
	:global(.st-content .st-machinecard-info) {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	:global(.st-content .st-machinecard-name) {
		font-family: var(--font-serif);
		font-size: 22px;
		letter-spacing: -0.01em;
		color: var(--fg-1);
	}
	:global(.st-content .st-machinecard-meta) {
		display: flex;
		gap: 14px;
		flex-wrap: wrap;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	:global(.st-content .st-machinecard-meta strong) {
		color: var(--fg-1);
		font-weight: 500;
	}
	:global(.st-content .st-machinecard-actions) {
		display: flex;
		gap: 8px;
		margin-top: 8px;
		flex-wrap: wrap;
	}
	:global(.st-content .st-machinecard-fw) {
		background: rgba(193, 116, 75, 0.06);
		border: 1px solid rgba(193, 116, 75, 0.3);
		border-radius: var(--radius-md);
		padding: 14px;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	:global(.st-content .st-machinecard-fw-ver) {
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
		font-weight: 500;
	}
	:global(.st-content .st-machinecard-fw-notes) {
		font-family: var(--font-sans);
		font-size: 11px;
		line-height: 1.5;
		color: rgba(var(--tint-rgb), 0.55);
		margin-bottom: 4px;
	}

	/* ── Maintenance cards ──────────────────────────────────────────────── */
	:global(.st-content .st-maint-grid) {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 12px;
		margin-bottom: 8px;
	}
	:global(.st-content .st-maint) {
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.05);
		border-radius: var(--radius-md);
		padding: 16px;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	:global(.st-content .st-maint-head) {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	:global(.st-content .st-maint-head i) {
		font-size: 18px;
		color: var(--copper-400);
	}
	:global(.st-content .st-maint-title) {
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: var(--fg-1);
	}
	:global(.st-content .st-maint-state) {
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--success);
		font-weight: 500;
	}
	:global(.st-content .st-maint-state.is-warn) {
		color: var(--warning);
	}
	:global(.st-content .st-maint-metric) {
		display: flex;
		align-items: baseline;
		gap: 6px;
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 22px;
		color: var(--fg-1);
		margin-top: 4px;
	}
	:global(.st-content .st-maint-metric em) {
		font-style: normal;
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
	}
	:global(.st-content .st-maint-detail) {
		font-family: var(--font-sans);
		font-size: 11px;
		line-height: 1.5;
		color: rgba(var(--tint-rgb), 0.45);
	}
	:global(.st-content .st-maint-action) {
		margin-top: 4px;
		background: transparent;
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 11px;
		padding: 6px 10px;
		cursor: pointer;
		align-self: flex-start;
	}
	:global(.st-content .st-maint-action:hover) {
		background: rgba(var(--tint-rgb), 0.05);
	}

	/* ── Visualizer / Sharing ───────────────────────────────────────────── */
	:global(.st-content .st-visualizer) {
		display: grid;
		grid-template-columns: 80px 1fr auto;
		gap: 20px;
		align-items: center;
		padding: 20px 22px;
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-lg, 14px);
		margin-bottom: 8px;
	}
	:global(.st-content .st-visualizer-glyph) {
		width: 64px;
		height: 64px;
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-md);
		display: flex;
		align-items: center;
		justify-content: center;
	}
	:global(.st-content .st-visualizer-name) {
		font-family: var(--font-serif);
		font-size: 22px;
		letter-spacing: -0.01em;
		color: var(--fg-1);
		margin-top: 2px;
	}
	:global(.st-content .st-visualizer-meta) {
		font-family: var(--font-sans);
		font-size: 12px;
		line-height: 1.5;
		color: rgba(var(--tint-rgb), 0.5);
		margin-top: 4px;
	}
	:global(.st-content .st-visualizer-meta strong) {
		color: var(--fg-1);
		font-weight: 500;
	}
	:global(.st-content .st-visualizer-meta-row) {
		margin-top: 6px;
	}
	:global(.st-content .st-visualizer-link) {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-family: var(--font-mono);
		font-size: 11px;
		color: var(--copper-400);
		text-decoration: none;
		cursor: pointer;
	}
	:global(.st-content .st-visualizer-link i) {
		font-size: 11px;
	}
	:global(.st-content .st-visualizer-actions) {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	/* ── Other integration cards ────────────────────────────────────────── */
	:global(.st-content .st-otherint) {
		margin-top: 28px;
	}
	:global(.st-content .st-otherint-grid) {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 12px;
		margin-top: 12px;
	}
	:global(.st-content .st-otherint-card) {
		display: flex;
		flex-direction: column;
		gap: 8px;
		padding: 16px;
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.05);
		border-radius: var(--radius-md);
	}
	:global(.st-content .st-otherint-card > i:first-child) {
		font-size: 22px;
		color: var(--copper-400);
	}
	:global(.st-content .st-otherint-title) {
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		color: var(--fg-1);
	}
	:global(.st-content .st-otherint-sub) {
		font-family: var(--font-sans);
		font-size: 11px;
		line-height: 1.5;
		color: rgba(var(--tint-rgb), 0.5);
		flex: 1 1 auto;
	}
	:global(.st-content .st-otherint-card .st-btn) {
		align-self: flex-start;
		margin-top: 4px;
	}

	/* ── About ──────────────────────────────────────────────────────────── */
	:global(.st-content .st-about) {
		display: flex;
		align-items: center;
		gap: 18px;
		padding: 20px 22px;
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.05);
		border-radius: var(--radius-md);
		margin-bottom: 8px;
	}
	:global(.st-content .st-about-mark .cside-mark-glyph) {
		width: 56px;
		height: 56px;
		border-radius: 50%;
		display: block;
	}
	:global(.st-content .st-about-line) {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
		line-height: 1.6;
	}
	:global(.st-content .st-about-line strong) {
		font-family: var(--font-serif);
		font-size: 18px;
		color: var(--fg-1);
		letter-spacing: -0.01em;
		font-weight: 500;
	}

	/* ── Mini toggle disabled state (the shared .qmini-tog) ─────────────── */
	:global(.st-content .qmini-tog:disabled) {
		opacity: 0.35;
		cursor: not-allowed;
	}

	@media (max-width: 980px) {
		.st-shell {
			grid-template-columns: 200px 1fr;
		}
		.st-content {
			padding: 28px 32px 48px;
		}
		:global(.st-content .st-machinecard) {
			grid-template-columns: 1fr;
		}
		:global(.st-content .st-visualizer) {
			grid-template-columns: 1fr;
		}
		:global(.st-content .st-maint-grid),
		:global(.st-content .st-otherint-grid) {
			grid-template-columns: 1fr;
		}
	}
</style>
