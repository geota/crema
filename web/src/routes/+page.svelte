<script lang="ts">
	/**
	 * Brew — the `/` route, the app's centrepiece.
	 *
	 * Renders the {@link BrewDashboard}: the profile header, the live
	 * telemetry timer / readouts / chart, and the docked Quick Sheet
	 * (variant G). The dashboard's display side is driven by the shared
	 * `CremaApp`'s live `UiSnapshot`; before the wasm core finishes loading we
	 * render the dashboard against the empty `INITIAL_SNAPSHOT`, so it shows a
	 * clean idle / empty state rather than a spinner.
	 */
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { INITIAL_SNAPSHOT } from '$lib/state';
	import { BrewDashboard } from '$lib/components/brew';

	// The shared orchestrator — `app` is null until the core loads.
	const ctx = getCremaAppContext();
	const app = $derived(ctx().app);

	// The live UI snapshot, or the empty initial snapshot before the core is
	// ready — the dashboard renders its idle / empty state from the latter.
	const ui = $derived(app?.state.current ?? INITIAL_SNAPSHOT);
</script>

<svelte:head>
	<title>Crema — Brew</title>
</svelte:head>

<BrewDashboard {ui} />

<style>
	/* The Brew dashboard is a fixed full-height layout: the header / main /
	   foot bands fill the viewport and the docked Quick Sheet is absolutely
	   positioned against `.qcontain` (which already sets `overflow: hidden`).
	   Pin it to the viewport height AND make it a flex column — `.crema-dash`
	   is a `flex: 1` child, so without the column context it collapses to its
	   content height and the chart ends up only as tall as the left timer
	   column. The flex column lets the dash fill the viewport, its own
	   `flex: 1` main band stretch, and the foot stay a thin bottom strip. */
	:global(.shell-content:has(.qcontain)) {
		height: 100vh;
	}
	:global(.qcontain) {
		height: 100vh;
		display: flex;
		flex-direction: column;
	}

	/* The handoff CSS anchors the Quick Controls sheet at `bottom: 96px` — its
	   bottom edge lands at the top of the dash-foot. We dock it flush to the
	   page bottom instead (`bottom: 0`), so the sheet sits lower and reaches
	   less far up the dashboard. The closed transform is just the sheet's own
	   height; `.qsheet.is-open` (higher specificity, from the design CSS)
	   still wins to slide it back to `translateY(0)`. */
	:global(.qsheet) {
		bottom: 0;
		transform: translateY(100%);
	}
</style>
