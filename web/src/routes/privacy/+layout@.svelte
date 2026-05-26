<script lang="ts">
	/**
	 * Layout reset for `/privacy` — the `@` breaks the inheritance chain
	 * from the root layout so the page renders standalone (no sidebar,
	 * no Web-Bluetooth gate). Google Play review needs a directly
	 * linkable Privacy Policy that loads in any browser including
	 * Firefox / Safari where the rest of the app shows a "Web Bluetooth
	 * unavailable" notice.
	 */
	import '../../app.css';
	let { children } = $props();
</script>

<svelte:head>
	<title>Privacy Policy · Crema</title>
	<meta name="robots" content="index,follow" />
</svelte:head>

{@render children?.()}
