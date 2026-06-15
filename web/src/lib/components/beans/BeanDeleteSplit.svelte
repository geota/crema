<script lang="ts">
	/**
	 * `BeanDeleteSplit` — the bag delete control: a compact danger split button
	 * (trash + caret) that carries the local-vs-also-remote choice in its menu,
	 * replacing the old confirm/modal flow. Self-contained: it deletes from the
	 * bean store directly and reports back via `onDeleted` so the host can close a
	 * drawer / navigate.
	 *
	 *  - Primary click → delete from this device only.
	 *  - Caret menu → the same, plus "delete on Visualizer too" when the bag is
	 *    uploaded and Visualizer is connected.
	 *
	 * The remote DELETE runs through the shared `bestEffortRemoteDelete` on the
	 * app runtime (Option 3, T-16) — the store stays pure-local; the free-tier
	 * skip + best-effort warn live in that helper.
	 */
	import { onMount } from 'svelte';
	import { getBeanStore } from '$lib/bean';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { bestEffortRemoteDelete } from '$lib/visualizer';
	import SplitButton from '$lib/components/shared/SplitButton.svelte';
	import { confirmDialog } from '$lib/components/shared/confirm-dialog.svelte';

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
	const appCtx = getCremaAppContext();

	/** Connection gate (Option 3): `TokenVault.getTokens !== null`, read once. */
	let connected = $state(false);
	onMount(() => {
		void (async () => {
			connected = (await appCtx().services?.tokens.isConnected()) ?? false;
		})();
	});
	// SV1: a sign-in / sign-out from any surface propagates here (the
	// subscription's first emission also seeds the current state).
	$effect(() => appCtx().services?.tokens.onConnectionChange((c) => (connected = c)));

	/** The remote option only shows when the bag is on Visualizer + connected. */
	const remoteAvailable = $derived(
		connected && !!library.getBean(beanId)?.visualizerId
	);

	async function run(remote: boolean): Promise<void> {
		const msg = remote
			? `Delete "${beanName}" from this device and Visualizer? This cannot be undone.`
			: `Delete "${beanName}" from this device? This cannot be undone.`;
		if (!(await confirmDialog({ message: msg, confirmLabel: 'Delete', danger: true }))) return;
		// Capture the remote id BEFORE the local delete removes the row.
		const visualizerId = remote ? (library.getBean(beanId)?.visualizerId ?? null) : null;
		library.deleteBean(beanId);
		if (visualizerId) void bestEffortRemoteDelete(appCtx().services, [visualizerId]);
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
