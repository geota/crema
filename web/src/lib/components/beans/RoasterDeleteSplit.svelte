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
	 * delete when nothing links here). Self-contained: deletes from the bean
	 * store directly and reports via `onDeleted`. Remote DELETEs run
	 * `BeanSync.deleteRoaster` / `.deleteBean` on the app runtime (Option 3,
	 * T-16); the store stays pure-local. Best-effort + free-tier skip mirror the
	 * old `deleteRemoteRoaster` / `deleteRemoteBean` they replaced.
	 */
	import { onMount } from 'svelte';
	import { getBeanStore, readSyncSettings } from '$lib/bean';
	import { getCremaAppContext } from '$lib/shell/app-context';
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

	/**
	 * Remote is meaningful when connected AND something here is on Visualizer —
	 * the roaster itself, or (for the cascade) one of its linked bags.
	 */
	const remoteAvailable = $derived(
		connected &&
			(!!library.getRoaster(roasterId)?.visualizerId ||
				library.beans.some((b) => b.roasterId === roasterId && !!b.visualizerId))
	);

	const beansLabel = $derived(`${linkedBeanCount} bag${linkedBeanCount === 1 ? '' : 's'}`);

	/**
	 * Best-effort Visualizer deletes for a captured set of remote ids. Free-tier
	 * (premium === false) skips silently; failures are logged, not surfaced —
	 * mirrors the old `deleteRemoteRoaster` / `deleteRemoteBean`. Ids are
	 * captured by the caller BEFORE the local delete removes the rows.
	 */
	async function fireRemoteDeletes(roasterVizId: string | null, beanVizIds: string[]): Promise<void> {
		if (readSyncSettings().premium === false) return;
		const api = appCtx().services;
		if (!api) return;
		const warn = (e: unknown): void =>
			console.warn('Visualizer delete failed:', e instanceof Error ? e.message : String(e));
		// Bags first, then the roaster — matches the old cascade's order
		// (`deleteRoasterAndBeans` deleted each bag before the roaster).
		for (const id of beanVizIds) {
			await api.beans.deleteBean(id).catch(warn);
		}
		if (roasterVizId) await api.beans.deleteRoaster(roasterVizId).catch(warn);
	}

	function detach(remote: boolean): void {
		const msg = remote
			? `Delete "${roasterName}" (and its Visualizer copy)? Linked bags are kept and detached.`
			: `Delete "${roasterName}" from this device? Linked bags are kept and detached.`;
		if (!confirm(msg)) return;
		// Detach deletes only the roaster remotely; bags are kept (detached).
		const roasterVizId = remote ? (library.getRoaster(roasterId)?.visualizerId ?? null) : null;
		library.deleteRoaster(roasterId);
		if (remote) void fireRemoteDeletes(roasterVizId, []);
		onDeleted?.();
	}
	function cascade(remote: boolean): void {
		const msg = remote
			? `Delete "${roasterName}" and its ${beansLabel}, here and on Visualizer? This cannot be undone.`
			: `Delete "${roasterName}" and its ${beansLabel} from this device? This cannot be undone.`;
		if (!confirm(msg)) return;
		// Capture the roaster + every linked bag's remote id BEFORE the cascade
		// removes the rows, then delete locally and fire the remote DELETEs.
		const roasterVizId = remote ? (library.getRoaster(roasterId)?.visualizerId ?? null) : null;
		const beanVizIds = remote
			? library.beans
					.filter((b) => b.roasterId === roasterId && !!b.visualizerId)
					.map((b) => b.visualizerId as string)
			: [];
		library.deleteRoasterAndBeans(roasterId);
		if (remote) void fireRemoteDeletes(roasterVizId, beanVizIds);
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
