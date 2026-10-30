<script lang="ts">
	/**
	 * Test host: publishes the shell's app context (as `+layout.svelte` does)
	 * around a component under test. NOT used by the app.
	 */
	import type { Component } from 'svelte';
	import { setCremaAppContext } from '#lib/shell/app-context.js';
	import type { CremaApp } from '#lib/state/index.js';

	let {
		app,
		component: Inner,
		props = {}
	}: { app: CremaApp | null; component: Component<any>; props?: Record<string, unknown> } = $props();

	setCremaAppContext(() => ({
		app,
		runtime: null,
		services: null,
		loadState: 'ready',
		loadError: ''
	}));
</script>

<Inner {...props} />
