<script lang="ts">
	import { onMount } from 'svelte';

	type SeamStatus = 'loading' | 'ok' | 'failed';

	let status = $state<SeamStatus>('loading');
	let detail = $state('initialising…');

	onMount(async () => {
		// Scaffold seam proof: dynamically import the wasm-pack bundle, run its
		// async init, and construct the core bridge. This confirms the wasm
		// builds, loads and instantiates in the browser. The real app facade
		// (src/lib/core) is a later step — see docs/09-web-shell.md.
		try {
			const wasm = await import('$lib/wasm/de1_wasm.js');
			await wasm.default();
			const bridge = new wasm.CremaBridge();
			// Touch a method so the instance is provably live.
			bridge.builtin_profiles_json();
			bridge.free();
			status = 'ok';
			detail = 'de1-wasm CremaBridge instantiated';
		} catch (err) {
			status = 'failed';
			detail = err instanceof Error ? err.message : String(err);
		}
	});
</script>

<svelte:head>
	<title>Crema</title>
</svelte:head>

<main class="min-h-screen bg-base-200 flex items-center justify-center p-6">
	<div class="card w-full max-w-md bg-base-100 shadow-xl">
		<div class="card-body items-center text-center">
			<h1 class="card-title text-2xl">Crema</h1>
			<p class="text-base-content/60 text-sm">Web shell scaffold</p>

			{#if status === 'loading'}
				<div class="alert mt-2">
					<span class="loading loading-spinner loading-sm"></span>
					<span>Loading core…</span>
				</div>
			{:else if status === 'ok'}
				<div class="alert alert-success mt-2">
					<span>core loaded ✓</span>
				</div>
			{:else}
				<div class="alert alert-error mt-2">
					<span>core failed ✗</span>
				</div>
			{/if}

			<p class="text-base-content/50 mt-1 font-mono text-xs break-all">{detail}</p>
		</div>
	</div>
</main>
