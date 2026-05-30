<script lang="ts">
	/**
	 * `RoasterDeleteSplit` — the roaster delete control, a compact danger split
	 * button (trash + caret) replacing the old `RoasterDeleteDialog`.
	 *
	 * Roaster delete has two orthogonal axes, both surfaced in the menu:
	 *  - **detach vs cascade** — keep the linked bags (clear their roaster) or
	 *    delete them too (only relevant when bags link here);
	 *  - **local vs also-remote** — also delete the uploaded Visualizer copies.
	 *
	 * The primary click is the safe default: detach + local (or plain local
	 * delete when nothing links here). Self-contained: talks to the bean store
	 * directly and reports via `onDeleted`.
	 */
	import { getBeanStore } from '$lib/bean';
	import { isConnected as isVisualizerConnected } from '$lib/visualizer';
	import SplitButton from '$lib/components/shared/SplitButton.svelte';

	let {
		roasterId,
		roasterName,
		linkedBeanCount,
		size = 'sm',
		label,
		onDeleted
	}: {
		roasterId: string;
		roasterName: string;
		linkedBeanCount: number;
		size?: 'sm' | 'md';
		label?: string;
		onDeleted?: () => void;
	} = $props();

	const library = getBeanStore();

	/**
	 * Remote is meaningful when connected AND something here is on Visualizer —
	 * the roaster itself, or (for the cascade) one of its linked bags.
	 */
	const remoteAvailable = $derived(
		isVisualizerConnected() &&
			(!!library.getRoaster(roasterId)?.visualizerId ||
				library.beans.some((b) => b.roasterId === roasterId && !!b.visualizerId))
	);

	const beansLabel = $derived(`${linkedBeanCount} bag${linkedBeanCount === 1 ? '' : 's'}`);

	function detach(remote: boolean): void {
		const msg = remote
			? `Delete "${roasterName}" (and its Visualizer copy)? Linked bags are kept and detached.`
			: `Delete "${roasterName}" from this device? Linked bags are kept and detached.`;
		if (!confirm(msg)) return;
		library.deleteRoaster(roasterId, { remote });
		onDeleted?.();
	}
	function cascade(remote: boolean): void {
		const msg = remote
			? `Delete "${roasterName}" and its ${beansLabel}, here and on Visualizer? This cannot be undone.`
			: `Delete "${roasterName}" and its ${beansLabel} from this device? This cannot be undone.`;
		if (!confirm(msg)) return;
		library.deleteRoasterAndBeans(roasterId, { remote });
		onDeleted?.();
	}

	type Item = {
		icon: string;
		title: string;
		sub: string;
		disabled?: boolean;
		onclick: () => void;
	};

	const items = $derived.by<Item[]>(() => {
		if (linkedBeanCount === 0) {
			// Nothing to cascade — just the local/remote scope choice.
			return [
				{
					icon: 'ph-duotone ph-device-mobile',
					title: 'Delete from this device',
					sub: 'Removes the roaster locally. Any Visualizer copy stays.',
					onclick: () => detach(false)
				},
				{
					icon: 'ph-duotone ph-cloud-slash',
					title: 'Delete here and on Visualizer',
					sub: remoteAvailable
						? 'Also removes the uploaded copy from Visualizer.'
						: 'Unavailable — not on Visualizer, or signed out.',
					disabled: !remoteAvailable,
					onclick: () => detach(true)
				}
			];
		}
		// Linked bags exist — offer detach vs cascade, each in local/remote scope.
		return [
			{
				icon: 'ph-duotone ph-link-break',
				title: `Detach ${beansLabel}, delete roaster`,
				sub: `Keeps the ${beansLabel} (shown as "No roaster"); removes the roaster locally.`,
				onclick: () => detach(false)
			},
			{
				icon: 'ph-duotone ph-cloud-slash',
				title: `Detach ${beansLabel}, delete roaster + Visualizer`,
				sub: remoteAvailable
					? 'Detaches the bags; removes the roaster locally and on Visualizer.'
					: 'Unavailable — not on Visualizer, or signed out.',
				disabled: !remoteAvailable,
				onclick: () => detach(true)
			},
			{
				icon: 'ph-duotone ph-trash',
				title: `Delete roaster + ${beansLabel}`,
				sub: 'Removes the roaster and every linked bag from this device.',
				onclick: () => cascade(false)
			},
			{
				icon: 'ph-duotone ph-cloud-slash',
				title: `Delete roaster + ${beansLabel} + Visualizer`,
				sub: remoteAvailable
					? 'Removes the roaster and its bags here and on Visualizer.'
					: 'Unavailable — nothing here is on Visualizer, or signed out.',
				disabled: !remoteAvailable,
				onclick: () => cascade(true)
			}
		];
	});
</script>

<SplitButton
	variant="danger"
	{size}
	icon="ph ph-trash"
	{label}
	primaryAriaLabel="Delete {roasterName}"
	title={linkedBeanCount === 0
		? 'Delete from this device'
		: 'Detach bags, delete roaster from this device'}
	caretAriaLabel="Choose delete scope"
	menuHead="Delete roaster"
	onPrimary={() => detach(false)}
	{items}
/>
