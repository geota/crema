<script lang="ts">
	/**
	 * `PhoneDevicesSheet` — the top bar's Bluetooth sheet (Android
	 * `PhoneDevicesSheet`): the DE1 and scale with their link state and the one
	 * action that state allows. The states and the tap policy are the rail's
	 * (`rail-status.ts`), and the actions call the same `CremaApp` methods.
	 *
	 * A Grinder row (Beta) follows the scale when "Show in Devices and the
	 * rail" is on and one of the user's grinders connects over Bluetooth
	 * (`#lib/grinder/devices-entry`). While connected its status names the
	 * grind, never the battery.
	 */
	import BluetoothSlashIcon from 'phosphor-svelte/lib/BluetoothSlashIcon';
	import FunnelIcon from 'phosphor-svelte/lib/FunnelIcon';
	import ScalesIcon from 'phosphor-svelte/lib/ScalesIcon';
	import SlidersHorizontalIcon from 'phosphor-svelte/lib/SlidersHorizontalIcon';
	import { connectBlocker } from '#lib/ble/connect-blocker.js';
	import type { CremaApp } from '#lib/state/index.js';
	import { railAction, railStatus, type RailDevice, type RailStatus } from '#lib/components/rail-status.js';
	import { NO_GRINDER } from '#lib/state/ui-state.svelte.js';
	import { getGrinderLinkStore } from '#lib/grinder/connected-link.svelte.js';
	import {
		grinderDeviceDetail,
		grinderDeviceName,
		grinderInDevices,
		linkedGrinderRecords
	} from '#lib/grinder/devices-entry.svelte.js';
	import BetaChip from '#lib/components/shared/BetaChip.svelte';
	import PhoneSheet from './PhoneSheet.svelte';

	let { app, open, onClose }: { app: CremaApp | null; open: boolean; onClose: () => void } =
		$props();

	const ui = $derived(app?.state.current ?? null);
	const blocker = $derived(open ? connectBlocker() : null);

	/** The status line per link status; a scale that was never there is "Not paired". */
	function statusText(device: RailDevice, status: RailStatus): string {
		if (status === 'connected') return 'Connected';
		if (status === 'connecting') return 'Connecting…';
		if (status === 'reconnecting') return 'Reconnecting…';
		return device === 'de1' ? 'Not connected' : 'Not paired';
	}

	const rows = $derived.by(() => {
		const battery = ui?.scaleBattery;
		const grinder = ui?.grinder ?? NO_GRINDER;
		const grinderRow = grinderInDevices()
			? [
					{
						device: 'grinder' as const,
						kind: 'Grinder',
						name: grinderDeviceName(grinder, getGrinderLinkStore().links, linkedGrinderRecords()),
						icon: FunnelIcon,
						status: railStatus(grinder.state),
						detail: grinderDeviceDetail(grinder),
						beta: true
					}
				]
			: [];
		return [
			{
				device: 'de1' as const,
				kind: 'Machine',
				name: ui?.de1Diagnostics.deviceName ?? 'Decent DE1',
				icon: SlidersHorizontalIcon,
				status: railStatus(ui?.de1State ?? 'idle'),
				detail: null,
				beta: false
			},
			{
				device: 'scale' as const,
				kind: 'Scale',
				name: ui?.scaleName ?? 'Scale',
				icon: ScalesIcon,
				status: railStatus(ui?.scaleState ?? 'idle'),
				detail: battery != null ? `${Math.round(battery)}%` : null,
				beta: false
			},
			...grinderRow
		];
	});

	/**
	 * Connect from the tap, so Web Bluetooth's user gesture holds. A grinder
	 * that stopped sending data reconnects the same device, as its card does.
	 */
	function connect(device: RailDevice): void {
		if (!app) return;
		if (device === 'de1') void app.connectDe1();
		else if (device === 'scale') void app.connectScale();
		else if (ui?.grinder.problem?.kind === 'no-data') void app.reconnectGrinder();
		else void app.connectGrinder();
	}
	function disconnect(device: RailDevice): void {
		if (device === 'de1') void app?.disconnectDe1();
		else if (device === 'scale') void app?.disconnectScale();
		else void app?.disconnectGrinder();
	}
</script>

<PhoneSheet {open} {onClose} title="Devices" subtitle="Bluetooth connections">
	{#if blocker}
		<div class="pdev-blocked" role="status">
			<BluetoothSlashIcon aria-hidden="true" />
			<span>
				{blocker.message}
				{#if blocker.openInSafari}<a href={blocker.openInSafari}>Open in Safari</a>{/if}
			</span>
		</div>
	{/if}
	{#each rows as row (row.device)}
		{@const RowIcon = row.icon}
		{@const action = railAction(row.status)}
		{@const on = row.status === 'connected'}
		<div class="pdev-row">
			<span class={['pdev-tile', { 'is-on': on }]}><RowIcon aria-hidden="true" /></span>
			<div class="pdev-text">
				<span class="pdev-kind">{row.kind}{#if row.beta}<BetaChip small />{/if}</span>
				<span class="pdev-name">{row.name}</span>
				<span class="pdev-stat">
					<span class={['pdev-dot', { 'is-on': on }]}></span>
					{statusText(row.device, row.status)}{#if on && row.detail}{` · ${row.detail}`}{/if}
				</span>
			</div>
			{#if action === 'menu'}
				<button
					type="button"
					class="pdev-btn"
					aria-label="Disconnect {row.kind.toLowerCase()}"
					onclick={() => disconnect(row.device)}>Disconnect</button
				>
			{:else if action === 'retry'}
				<button
					type="button"
					class="pdev-btn"
					aria-label="Retry {row.kind.toLowerCase()} now"
					onclick={() => app?.retryReconnect(row.device)}>Retry now</button
				>
			{:else}
				<button
					type="button"
					class="pdev-btn is-primary"
					disabled={!app || action === 'none'}
					aria-label="Pair {row.kind.toLowerCase()}"
					onclick={() => connect(row.device)}>Pair</button
				>
			{/if}
		</div>
	{/each}
</PhoneSheet>

<style>
	.pdev-blocked {
		display: flex;
		align-items: center;
		gap: 10px;
		margin-bottom: 8px;
		padding: 11px 14px;
		border-radius: 12px;
		background: rgba(var(--danger-rgb), 0.16);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12.5px;
		line-height: 1.4;
	}
	.pdev-blocked :global(svg) {
		flex: none;
		font-size: 18px;
	}
	.pdev-blocked a {
		color: var(--fg-accent);
	}
	.pdev-row {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 12px 0;
	}
	.pdev-row + .pdev-row {
		border-top: 1px solid rgba(var(--tint-rgb), 0.1);
	}
	.pdev-tile {
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 40px;
		height: 40px;
		border-radius: 12px;
		background: rgba(var(--tint-rgb), 0.06);
		color: var(--fg-3);
		font-size: 20px;
	}
	.pdev-tile.is-on {
		background: rgba(var(--success-rgb), 0.18);
		color: var(--success);
	}
	.pdev-text {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.pdev-kind {
		display: flex;
		align-items: center;
		gap: 6px;
		font-family: var(--font-sans);
		font-size: 9.5px;
		font-weight: 600;
		letter-spacing: 0.06em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	.pdev-name {
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 15px;
		font-weight: 500;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.pdev-stat {
		display: flex;
		align-items: center;
		gap: 6px;
		font-family: var(--font-mono);
		font-size: 12px;
		color: var(--fg-3);
	}
	.pdev-dot {
		flex: none;
		width: 7px;
		height: 7px;
		border-radius: 50%;
		background: rgba(var(--tint-rgb), 0.3);
	}
	.pdev-dot.is-on {
		background: var(--success);
	}
	.pdev-btn {
		position: relative;
		flex: none;
		min-height: 36px;
		padding: 0 14px;
		border: 0;
		border-radius: var(--radius-pill);
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-2);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		cursor: pointer;
	}
	.pdev-btn::before {
		content: '';
		position: absolute;
		inset: -4px 0;
	}
	.pdev-btn.is-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.pdev-btn:disabled {
		opacity: 0.45;
		cursor: not-allowed;
	}
	.pdev-btn:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
</style>
