<script lang="ts">
	/**
	 * Layout reset for `/terms` — the `@` breaks the inheritance chain
	 * from the root layout so the page renders standalone (no sidebar,
	 * no Web-Bluetooth gate). Google Play review needs a directly
	 * linkable Terms of Service that loads in any browser including
	 * Firefox / Safari where the rest of the app shows a "Web Bluetooth
	 * unavailable" notice.
	 */
	import '../../app.css';
	let { children } = $props();
</script>

<svelte:head>
	<title>Terms of Service · Crema</title>
	<meta name="robots" content="index,follow" />
</svelte:head>

{@render children?.()}
