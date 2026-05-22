<script lang="ts">
	/**
	 * Machine section — the espresso machine's connection, peripherals, and the
	 * hero machine card with the firmware-update block.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real** — the DE1 connection state reads `lib/state`'s `UiSnapshot`
	 * (`de1State`); Connect / Disconnect call `app.connectDe1()` /
	 * `app.disconnectDe1()`. Auto-connect-on-launch and the telemetry rate are
	 * persisted app preferences in `lib/settings`.
	 *
	 * **Real** — the machine card's Firmware line shows the DE1's decoded
	 * firmware label (`Event::Firmware`, folded into `UiSnapshot.de1Firmware`);
	 * the BLE line shows the Web Bluetooth device id from the connection
	 * diagnostics; the Group line shows the live mix ("group") temperature.
	 *
	 * **UI-only** — the firmware-*update* block, Rename, Forget-device, and the
	 * peripherals list need DE1 / extra-BLE-device support the shell does not
	 * have; each is marked with a `// TODO`.
	 *
	 * ## DE1 connection diagnostics
	 *
	 * The "Connection diagnostics" group is the real proof a connected device
	 * is genuinely a DE1. A DE1's Nordic nRF5x BLE module can show in the
	 * browser chooser under a generic name ("nRF5x"), so the name alone is not
	 * proof. The panel surfaces the structural evidence from `lib/state`'s
	 * `de1Diagnostics`: the selected device's name + id, a "GATT verified"
	 * indicator (true only once the `A000` service and the StateInfo /
	 * ShotSample / WaterLevels characteristics resolved), the live decoded
	 * machine state, and a ticking notification count. A valid machine state
	 * plus a rising count is unambiguous proof; a non-DE1 device fails GATT
	 * verification (the connect itself would have failed at the failing step,
	 * named in the event log).
	 */
	import type { CremaApp, UiSnapshot } from '$lib/state';
	import type { De1State } from '$lib/ble';
	import type { FirmwareUpdateStatus } from '$lib/core';
	import { MachineState } from '$lib/core/crema-core';
	import { getSettingsStore } from '$lib/settings';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StSelect from '../StSelect.svelte';
	import StButton from '../StButton.svelte';
	import StStatusDot from '../StStatusDot.svelte';

	let {
		app,
		de1State,
		snapshot
	}: { app: CremaApp | null; de1State: De1State; snapshot: UiSnapshot } = $props();

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);

	/** The DE1 connection-diagnostics snapshot — proof the device is a DE1. */
	const diag = $derived(snapshot.de1Diagnostics);

	/** A DE1 link counts as up once connecting through ready / reconnecting. */
	const connected = $derived(
		(['connecting', 'subscribing', 'ready', 'reconnecting'] as const).includes(
			de1State as 'connecting'
		)
	);

	/** Human label for the coarse DE1 connection state. */
	const stateLabel = $derived(
		(
			{
				idle: 'Not connected',
				connecting: 'Connecting…',
				subscribing: 'Subscribing…',
				ready: 'Connected',
				reconnecting: 'Reconnecting…',
				disconnected: 'Disconnected',
				failed: 'Connection failed'
			} as const
		)[de1State]
	);

	/** Connect a DE1 — a Web-Bluetooth gesture handler. */
	function connect(): void {
		void app?.connectDe1();
	}
	/** Disconnect the DE1. */
	function disconnect(): void {
		void app?.disconnectDe1();
	}

	/** Live "data is flowing" — at least one DE1 notification received. */
	const dataFlowing = $derived(diag.notificationCount > 0);

	/**
	 * The notification-count line — the running total plus, once the first
	 * notification has arrived, how long ago the last one landed.
	 */
	const notificationsLabel = $derived.by(() => {
		if (diag.notificationCount === 0) {
			return connected ? 'Waiting for first notification…' : 'No notifications';
		}
		let line = `${diag.notificationCount} received`;
		if (diag.lastNotificationAtMs !== null) {
			const ageMs = Math.max(0, performance.now() - diag.lastNotificationAtMs);
			line += `, last ${(ageMs / 1000).toFixed(1)}s ago`;
		}
		return line;
	});

	/** The decoded machine state + substate, or a placeholder before the first. */
	const machineStateLabel = $derived(snapshot.machineState ?? '— (no StateInfo yet)');

	// ── Machine card meta (D4) ────────────────────────────────────────────
	/**
	 * The DE1's firmware label. Prefers the MMR FirmwareVersion build number
	 * (`v1352`-style — Decent's user-facing release tag, read from MMR
	 * `0x800010` shortly after pairing), and falls back to the BLE Version
	 * label (`v1.5.559 (API 4)`-style) while the MMR reply is in flight or
	 * if the MMR read failed. The dash is the initial state before either
	 * arrives. Same format as the update-status string below the card, so
	 * the two readouts read consistently.
	 */
	const firmwareLabel = $derived.by(() => {
		const build = snapshot.de1MachineInfo.FirmwareVersion;
		if (build !== undefined) return `v${build}`;
		return snapshot.de1Firmware ?? '—';
	});
	/** The Web Bluetooth device id of the connected DE1, or a dash. */
	const bleLabel = $derived(diag.deviceId ?? '—');
	/**
	 * Machine identity: derived from the connect-time MMR reads of
	 * `MachineModel` (0x80000C) and `CpuBoardVersion` (0x800008). Both
	 * land via the normal `Event::MmrValue` path into
	 * `snapshot.de1MachineInfo`. Falls back to a dash until they arrive.
	 *
	 * `MachineModel` is a small integer per the legacy lookup
	 * (`vars.tcl:3883`); `CpuBoardVersion` is encoded as
	 * `board_revision × 1000` (raw 1100 → PCB v1.1, 1300 → PCB v1.3).
	 */
	const MACHINE_MODEL_NAMES = [
		'unknown',
		'DE1',
		'DE1+',
		'DE1PRO',
		'DE1XL',
		'DE1CAFE',
		'DE1XXL',
		'DE1XXXL'
	] as const;
	const modelLabel = $derived.by(() => {
		const m = snapshot.de1MachineInfo.MachineModel;
		if (m === undefined) return '—';
		return MACHINE_MODEL_NAMES[m] ?? `model ${m}`;
	});
	const boardLabel = $derived.by(() => {
		const cpu = snapshot.de1MachineInfo.CpuBoardVersion;
		if (cpu === undefined) return '—';
		return `PCB v${(cpu / 1000).toFixed(1)}`;
	});

	// ── Firmware-update check (read-only) ─────────────────────────────────
	// Mirrors the legacy de1app's local comparison (`vars.tcl:3787-3797`):
	// the BLE shell reads MMR register 0x800010 (FirmwareVersion) at connect
	// time — the canonical "v1352" build number — caches it in the core, and
	// this UI compares against a hardcoded LATEST_KNOWN_FIRMWARE_BUILD bumped
	// per Crema release. No network, no file picker, no upload — see
	// `docs/17-firmware-update-plan.md` for the (v2-deferred) upload plan.
	let firmwareStatus: FirmwareUpdateStatus | null = $state(null);
	let firmwareChecking = $state(false);

	const firmwareStatusLabel = $derived.by(() => {
		if (firmwareStatus === null) {
			return 'No update info';
		}
		switch (firmwareStatus.type) {
			case 'Unknown':
				return 'Connect a DE1 first';
			case 'UpToDate':
				return `Up to date — v${firmwareStatus.content.installed}`;
			case 'UpdateAvailable':
				return `Update available — v${firmwareStatus.content.latest}`;
			case 'NewerInstalled':
				return `Newer than Crema knows — v${firmwareStatus.content.installed}`;
		}
	});

	const firmwareStatusNotes = $derived.by(() => {
		if (firmwareStatus === null) {
			return "Click to compare your DE1's installed firmware against the latest Crema knows about.";
		}
		switch (firmwareStatus.type) {
			case 'Unknown':
				return 'Crema needs the DE1 connected and its FirmwareVersion MMR read.';
			case 'UpToDate':
				return 'Your DE1 is on the latest firmware this Crema build was tested against.';
			case 'UpdateAvailable':
				return `Use the legacy de1app to apply firmware v${firmwareStatus.content.latest}. Crema's own update flow ships in a later release.`;
			case 'NewerInstalled':
				return "Your DE1's firmware is ahead of this Crema build. Nothing to do.";
		}
	});

	async function updateFirmware(): Promise<void> {
		if (!app) return;
		firmwareChecking = true;
		try {
			firmwareStatus = await app.firmwareUpdateStatus();
		} finally {
			firmwareChecking = false;
		}
	}
	// Rename / Re-pair were UI-only stubs (no underlying device registry).
	// Removed to keep the card focused on connect / disconnect / sleep — the
	// real actions the shell can actually perform.
</script>

<StSectionHead
	eyebrow="Hardware"
	title="Machine"
	sub="Your espresso machine, scale, and grinder. Crema talks to these over Bluetooth or WiFi."
/>

<!-- Hero machine card -->
<div class="st-machinecard">
	<div class="st-machinecard-img">
		<svg viewBox="0 0 110 130" width="80" height="100" aria-hidden="true">
			<rect
				x="20"
				y="20"
				width="70"
				height="80"
				rx="6"
				fill="#3a2a1d"
				stroke="rgba(var(--tint-rgb), 0.18)"
			/>
			<rect
				x="28"
				y="28"
				width="54"
				height="34"
				rx="3"
				fill="#0d0907"
				stroke="rgba(var(--tint-rgb), 0.12)"
			/>
			<circle cx="55" cy="45" r="2" fill="var(--copper-400)" />
			<rect x="42" y="68" width="26" height="6" rx="2" fill="rgba(var(--tint-rgb), 0.18)" />
			<rect x="48" y="74" width="14" height="20" rx="2" fill="rgba(var(--tint-rgb), 0.10)" />
			<ellipse cx="55" cy="110" rx="32" ry="4" fill="rgba(0,0,0,0.4)" />
		</svg>
	</div>
	<!-- Machine info card — mirrors the Firmware card's visual treatment
	     (background tint, hairline, padded body, eyebrow at top, button
	     pushed to bottom via flex). Sleep / Wake lives on the floating
	     top-right PowerButton; this card is single-purpose connection. -->
	<div class="st-machinecard-info">
		<div class="t-eyebrow">{stateLabel}</div>
		{#if connected}
			<div class="st-machinecard-info-name">DE1 · Crema Bar</div>
		{/if}
		<div class="st-machinecard-info-meta">
			<!-- Identity meta: firmware build (MMR 0x800010), machine model
			     (MMR 0x80000C), CPU board revision (MMR 0x800008), and the
			     Web Bluetooth device id from the connect diagnostics. Each
			     row is a label/value pair so the values line up under each
			     other. All read "—" until the MMR replies land — the
			     placeholder class renders the dash smaller/lighter so the
			     four dashes look uniform with the BLE one. -->
			<div class="st-machinecard-info-row">
				<span class="st-machinecard-info-key">Firmware</span>
				<span
					class="st-machinecard-info-val"
					class:is-placeholder={firmwareLabel === '—'}>{firmwareLabel}</span
				>
			</div>
			<div class="st-machinecard-info-row">
				<span class="st-machinecard-info-key">Model</span>
				<span
					class="st-machinecard-info-val"
					class:is-placeholder={modelLabel === '—'}>{modelLabel}</span
				>
			</div>
			<div class="st-machinecard-info-row">
				<span class="st-machinecard-info-key">Board</span>
				<span
					class="st-machinecard-info-val"
					class:is-placeholder={boardLabel === '—'}>{boardLabel}</span
				>
			</div>
			<div class="st-machinecard-info-row">
				<span class="st-machinecard-info-key">BLE</span>
				<span
					class="st-machinecard-info-val st-machinecard-info-val-mono"
					class:is-placeholder={bleLabel === '—'}>{bleLabel}</span
				>
			</div>
		</div>
		<div class="st-machinecard-info-actions">
			{#if connected}
				<StButton
					label="Disconnect"
					icon="bluetooth-slash"
					onClick={disconnect}
				/>
			{:else}
				<StButton
					label="Connect"
					icon="bluetooth"
					variant="primary"
					disabled={!app}
					onClick={connect}
				/>
			{/if}
		</div>
	</div>
	<div class="st-machinecard-fw">
		<div class="t-eyebrow" style="color:var(--copper-400)">Firmware</div>
		<div class="st-machinecard-fw-ver">{firmwareStatusLabel}</div>
		<div class="st-machinecard-fw-notes">{firmwareStatusNotes}</div>
		<StButton
			label={firmwareChecking ? 'Checking…' : 'Check for updates'}
			icon="arrow-circle-up"
			variant="primary"
			disabled={!app || firmwareChecking}
			onClick={updateFirmware}
		/>
	</div>
</div>

<StGroup title="Connection">
	<StRow
		title="Auto-connect on launch"
		sub="Crema reconnects to your last-used DE1 when you open the app."
	>
		{#snippet control()}
			<StToggle
				on={prefs.autoConnectOnLaunch}
				onChange={(v) => settings.set('autoConnectOnLaunch', v)}
				label="Auto-connect on launch"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Telemetry rate"
		sub="How often the chart samples. Higher = smoother curves, more battery."
	>
		{#snippet control()}
			<StSelect
				value={String(prefs.telemetryRateHz)}
				options={[
					{ value: '10', label: '10 Hz' },
					{ value: '25', label: '25 Hz' },
					{ value: '50', label: '50 Hz (recommended)' },
					{ value: '100', label: '100 Hz' }
				]}
				onChange={(v) => settings.set('telemetryRateHz', Number(v))}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Keep DE1 awake while Crema is open"
		sub="Resets the DE1's sleep timer on every touch / keystroke so the
		machine doesn't sleep mid-session. Off → the DE1 follows its own
		~30 min sleep timer regardless of tablet activity (useful for shared
		/ café machines)."
	>
		{#snippet control()}
			<StToggle
				on={prefs.suppressDe1Sleep}
				onChange={(v) => {
					settings.set('suppressDe1Sleep', v);
					void app?.setSuppressDe1Sleep(v);
					if (v) void app?.markUserPresent();
				}}
				label="Keep DE1 awake"
			/>
		{/snippet}
	</StRow>
</StGroup>

<StGroup
	title="DE1 connection diagnostics"
	sub="Proof the connected device is genuinely a DE1. A DE1's Nordic nRF5x module can appear in the chooser as a generic name, so this verifies the GATT layout and shows live data flowing."
>
	<StRow title="Connection state" sub="The coarse DE1 link state.">
		{#snippet control()}
			<StStatusDot ok={connected} label={stateLabel} />
		{/snippet}
	</StRow>
	<StRow
		title="Selected device"
		sub="The name and browser device id of the device the chooser picked."
	>
		{#snippet control()}
			<span class="st-diag-mono">
				{diag.deviceName ?? '—'}
			</span>
		{/snippet}
		{#snippet hint()}
			<span class="st-diag-id">{diag.deviceId ?? 'no device'}</span>
		{/snippet}
	</StRow>
	<StRow
		title="DE1 GATT verified"
		sub="True once service A000 and the StateInfo (A00E), ShotSample (A00D) and WaterLevels (A011) characteristics resolved. A non-DE1 board fails here."
	>
		{#snippet control()}
			<StStatusDot
				ok={diag.gattVerified}
				label={diag.gattVerified ? 'Verified DE1' : 'Not verified'}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Machine state"
		sub="The live decoded DE1 state and substate, from the StateInfo notification stream."
	>
		{#snippet control()}
			<span class="st-diag-mono">{machineStateLabel}</span>
		{/snippet}
	</StRow>
	<StRow
		title="Notifications received"
		sub="DE1 notifications decoded since connecting — a rising count is live proof the device is streaming valid DE1 data."
	>
		{#snippet control()}
			<StStatusDot ok={dataFlowing} label={notificationsLabel} />
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Peripherals" sub="Bluetooth devices Crema can talk to.">
	<!-- TODO: a peripheral registry (grinder / tamper) is not in the shell;
	     the scale's live state lives on the Scale page. Faithful UI only. -->
	<StRow title="Scale" sub="Used for stop-on-weight and auto-tare. Manage on the Scale page.">
		{#snippet control()}
			<StStatusDot ok={false} label="See Scale page" />
		{/snippet}
	</StRow>
	<StRow title="Grinder" sub="Doesn't report dose; Crema logs a manual grind setting.">
		{#snippet control()}
			<StStatusDot ok={false} label="Not paired" />
		{/snippet}
		{#snippet hint()}
			<StButton label="Pair" icon="bluetooth" disabled />
		{/snippet}
	</StRow>
	<StRow title="Weight-aware tamper" sub="Pressure logged with each shot.">
		{#snippet control()}
			<StStatusDot ok={false} label="Not paired" />
		{/snippet}
		{#snippet hint()}
			<StButton label="Pair" icon="bluetooth" disabled />
		{/snippet}
	</StRow>
</StGroup>

<style>
	/* Diagnostics value cells — mono so the device id and decoded machine
	   state read as data, consistent with the design's mono usage. */
	.st-diag-mono {
		font-family: var(--font-mono);
		font-size: 12px;
		color: var(--fg-1);
		font-variant-numeric: tabular-nums;
	}
	.st-diag-id {
		font-family: var(--font-mono);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.4);
		word-break: break-all;
		max-width: 220px;
		display: inline-block;
	}
	/* Note: the BLE id clamp formerly lived here (.st-machinecard-ble);
	   replaced by `.st-machinecard-info-val` which has its own ellipsis
	   clamp on the new info card. */
</style>
