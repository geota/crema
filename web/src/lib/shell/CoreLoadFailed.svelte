<script lang="ts">
	/**
	 * `CoreLoadFailed` — what the shell shows when the wasm core cannot load
	 * (review 03 P3-7). The root layout's load awaits the core; a rejected fetch
	 * or instantiate used to surface as SvelteKit's unstyled default error page,
	 * because Kit's root error page re-runs the root layout. The layout now
	 * catches the failure and renders this instead of the app.
	 */
	import ArrowClockwiseIcon from 'phosphor-svelte/lib/ArrowClockwiseIcon';
	import WarningCircleIcon from 'phosphor-svelte/lib/WarningCircleIcon';

	let { message, onReload = () => location.reload() }: { message: string; onReload?: () => void } =
		$props();
</script>

<main class="clf-shell">
	<section class="clf-card" role="alert">
		<WarningCircleIcon aria-hidden="true" class="clf-icon" />
		<h1 class="t-h3">Failed to load the core</h1>
		<p class="t-body-sm">
			Crema could not start its engine, so nothing can run yet. This is usually a dropped
			connection while the app was downloading. Reloading normally fixes it.
		</p>
		<p class="t-body-sm clf-detail">{message}</p>
		<button type="button" class="clf-btn" onclick={onReload}>
			<ArrowClockwiseIcon aria-hidden="true" />
			<span>Reload</span>
		</button>
	</section>
</main>

<style>
	.clf-shell {
		display: flex;
		align-items: center;
		justify-content: center;
		min-height: 100vh;
		padding: var(--space-6);
	}
	.clf-card {
		max-width: 420px;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--space-3);
		text-align: center;
		background: var(--bg-surface);
		border: 1px solid var(--hairline);
		border-radius: var(--radius-lg);
		box-shadow: var(--shadow-md);
		padding: var(--space-6);
	}
	.clf-card :global(.clf-icon) {
		font-size: 32px;
		color: var(--danger);
	}
	.clf-detail {
		color: rgba(var(--tint-rgb), 0.55);
		overflow-wrap: anywhere;
	}
	.clf-btn {
		display: inline-flex;
		align-items: center;
		gap: var(--space-2);
		margin-top: var(--space-2);
		padding: var(--space-2) var(--space-4);
		border-radius: var(--radius-sm);
		border: 1px solid var(--hairline);
		background: var(--bg-surface);
		color: var(--copper-400);
		font-family: var(--font-sans);
		cursor: pointer;
	}
</style>
