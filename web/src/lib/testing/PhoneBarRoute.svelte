<script lang="ts">
	/** Test host: a route that claims the phone top bar. NOT used by the app. */
	import { usePhoneBar, type PhoneBarConfig } from '#lib/shell/phone-bar.svelte.js';

	let { config }: { config: () => PhoneBarConfig } = $props();

	usePhoneBar(() => config());
</script>
