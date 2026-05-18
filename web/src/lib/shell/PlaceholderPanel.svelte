<script lang="ts">
	/**
	 * `PlaceholderPanel` — the Step-1 stand-in for a real screen.
	 *
	 * Every route renders one of these for now: a panel styled with the design
	 * tokens that names the screen. The real screen contents land in later
	 * porting steps; this just proves the shell, routing and shared `CremaApp`
	 * all work.
	 */
	import { getCremaAppContext } from '$lib/shell/app-context';

	let {
		title,
		icon,
		blurb
	}: { title: string; icon: string; blurb: string } = $props();

	// Read the shared-app context to show the core's load state — proof that
	// the one `CremaApp` is reachable from every route.
	const ctx = getCremaAppContext();
	const loadState = $derived(ctx().loadState);
	const loadError = $derived(ctx().loadError);
</script>

<svelte:head>
	<title>Crema — {title}</title>
</svelte:head>

<main class="placeholder">
	<section class="panel">
		<i class={'ph-duotone ph-' + icon} aria-hidden="true"></i>
		<h1 class="t-h2">{title}</h1>
		<p class="t-body-sm">{blurb}</p>
		<div class="status">
			<span class="t-eyebrow">Shared core</span>
			{#if loadState === 'loading'}
				<span class="t-body-sm">Loading…</span>
			{:else if loadState === 'ready'}
				<span class="t-body-sm core-ready">Ready</span>
			{:else}
				<span class="t-body-sm core-failed">Failed — {loadError}</span>
			{/if}
		</div>
	</section>
</main>

<style>
	.placeholder {
		display: flex;
		align-items: center;
		justify-content: center;
		min-height: 100vh;
		padding: var(--space-8);
	}
	.panel {
		max-width: 480px;
		width: 100%;
		display: flex;
		flex-direction: column;
		gap: var(--space-3);
		background: var(--bg-surface);
		border: 1px solid var(--hairline);
		border-radius: var(--radius-lg);
		box-shadow: var(--shadow-md);
		padding: var(--space-8);
	}
	.panel i {
		font-size: 40px;
		color: var(--fg-accent);
	}
	.status {
		display: flex;
		align-items: baseline;
		gap: var(--space-3);
		margin-top: var(--space-4);
		padding-top: var(--space-4);
		border-top: 1px solid var(--hairline);
	}
	.core-ready {
		color: var(--success);
	}
	.core-failed {
		color: var(--danger);
	}
</style>
