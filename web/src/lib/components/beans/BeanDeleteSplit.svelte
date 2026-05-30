<script lang="ts">
	/**
	 * `BeanDeleteSplit` — the bag delete control: a compact danger split button
	 * (trash + caret) that carries the local-vs-also-remote choice in its menu,
	 * replacing the old confirm/modal flow. Self-contained: it talks to the bean
	 * store directly (same pattern the old delete dialogs used) and reports back
	 * via `onDeleted` so the host can close a drawer / navigate.
	 *
	 *  - Primary click → delete from this device only.
	 *  - Caret menu → the same, plus "delete on Visualizer too" when the bag is
	 *    uploaded and Visualizer is connected.
	 */
	import { getBeanStore } from '$lib/bean';
	import { isConnected as isVisualizerConnected } from '$lib/visualizer';
	import SplitButton from '$lib/components/shared/SplitButton.svelte';

	let {
		beanId,
		beanName,
		size = 'sm',
		label,
		onDeleted
	}: {
		beanId: string;
		beanName: string;
		/** `sm` (default) for card action rows; `md` for the editor footer. */
		size?: 'sm' | 'md';
		/** Optional text label (e.g. "Delete bean"); omit for an icon-only button. */
		label?: string;
		onDeleted?: () => void;
	} = $props();

	const library = getBeanStore();

	/** The remote option only shows when the bag is on Visualizer + connected. */
	const remoteAvailable = $derived(
		isVisualizerConnected() && !!library.getBean(beanId)?.visualizerId
	);

	function run(remote: boolean): void {
		const msg = remote
			? `Delete "${beanName}" from this device and Visualizer? This cannot be undone.`
			: `Delete "${beanName}" from this device? This cannot be undone.`;
		if (!confirm(msg)) return;
		library.deleteBean(beanId, { remote });
		onDeleted?.();
	}

	const items = $derived([
		{
			icon: 'ph-duotone ph-device-mobile',
			title: 'Delete from this device',
			sub: 'Removes the bag locally. Any uploaded Visualizer copy stays.',
			onclick: () => run(false)
		},
		{
			icon: 'ph-duotone ph-cloud-slash',
			title: 'Delete here and on Visualizer',
			sub: remoteAvailable
				? 'Also removes the uploaded copy from Visualizer.'
				: 'Unavailable — this bag is not on Visualizer, or you are signed out.',
			disabled: !remoteAvailable,
			onclick: () => run(true)
		}
	]);
</script>

<SplitButton
	variant="danger"
	{size}
	icon="ph ph-trash"
	{label}
	primaryAriaLabel="Delete {beanName}"
	title="Delete from this device"
	caretAriaLabel="Choose delete scope"
	menuHead="Delete bag"
	onPrimary={() => run(false)}
	{items}
/>
