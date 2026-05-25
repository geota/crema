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
	import { bleStateLabel, type De1State } from '$lib/ble';
	import type { FirmwareUpdateStatus } from '$lib/core';
	import { MachineState, MmrRegister } from '$lib/core/crema-core';
	import { getSettingsStore } from '$lib/settings';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StSelect from '../StSelect.svelte';
	import StStepper from '../StStepper.svelte';
	import StButton from '../StButton.svelte';
	import FirmwareUpdateModal from '../FirmwareUpdateModal.svelte';

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
	const stateLabel = $derived(bleStateLabel(de1State));

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
		if (diag.notificationCount === 0) return '—';
		let line = `${diag.notificationCount} received`;
		if (diag.lastNotificationAtMs !== null) {
			const ageMs = Math.max(0, performance.now() - diag.lastNotificationAtMs);
			line += `, last ${(ageMs / 1000).toFixed(1)}s ago`;
		}
		return line;
	});

	/** The decoded machine state + substate, or a dash before the first. */
	const machineStateLabel = $derived(snapshot.machineState ?? '—');
	/** Connection state — dash for the idle "not connected" baseline. */
	const connectionLabel = $derived(de1State === 'idle' ? '—' : stateLabel);
	/** Scale-connected two-state for the Peripherals dot indicator. */
	const scaleConnected = $derived(snapshot.scaleState === 'ready');

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
	/**
	 * Whether the connected DE1 has the Bengle cup-warmer plate hardware.
	 * Per docs/21 §3.5 the cup-warmer setter is gated by `MachineModel ∈
	 * {DE1XL, DE1XXL, DE1XXXL, DE1CAFE}` — indices 4..7 in the model
	 * table above. Returns `false` until the `MachineModel` MMR read
	 * lands so the card stays hidden on first paint.
	 */
	const isBengle = $derived.by<boolean>(() => {
		const m = snapshot.de1MachineInfo.MachineModel;
		return m !== undefined && m >= 4 && m <= 7;
	});
	/**
	 * Live cup-warmer plate temperature read from
	 * `de1MachineInfo[CupWarmerTemp]`. `0` = off. Undefined until the
	 * MMR read lands; the stepper falls back to `0` so it paints as
	 * "Off" before then.
	 */
	const cupWarmerC = $derived(snapshot.de1MachineInfo[MmrRegister.CupWarmerTemp] ?? 0);
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

	// ── Firmware update — type-to-confirm gate (#55 stub) ─────────────────
	//
	// The gate exists today; the underlying flashing flow does not. Per
	// docs/40 "Top deferred safety checks" #2 the entry point still needs
	// the same destructive-action UX guard the mains writes use, so a
	// future "Update" button can't ship without it. When #55 lands, the
	// onConfirm handler is the single seam to wire to the real flow.
	let showUpdateModal = $state(false);
	let updateStubNotice = $state<string | null>(null);

	/** Latest build Crema knows about — pulled off the read-only status. */
	const latestKnownBuild = $derived.by(() => {
		if (firmwareStatus === null) return undefined;
		switch (firmwareStatus.type) {
			case 'UpToDate':
				return firmwareStatus.content.installed;
			case 'UpdateAvailable':
				return firmwareStatus.content.latest;
			case 'NewerInstalled':
				return firmwareStatus.content.installed;
			default:
				return undefined;
		}
	});

	function openUpdateModal(): void {
		updateStubNotice = null;
		showUpdateModal = true;
	}
	function closeUpdateModal(): void {
		showUpdateModal = false;
	}
	function onUpdateConfirm(): void {
		// TODO(#55): wire to firmware-update v2 implementation
		showUpdateModal = false;
		updateStubNotice = 'Firmware update is not yet implemented (#55).';
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
		<div class="fw-head">
			<div class="t-eyebrow" style="color:var(--copper-400)">Firmware</div>
			{#if !connected}
				<span class="fw-conn-pill" title="This setting needs a connected DE1."
					>Connect DE1</span
				>
			{/if}
		</div>
		<div class="st-machinecard-fw-ver">{firmwareStatusLabel}</div>
		<div class="st-machinecard-fw-notes">{firmwareStatusNotes}</div>
		<div class="st-machinecard-fw-actions">
			<StButton
				label={firmwareChecking ? 'Checking…' : 'Check for updates'}
				icon="arrow-circle-up"
				variant="primary"
				disabled={!connected || firmwareChecking}
				onClick={updateFirmware}
			/>
			<StButton
				label="Update firmware…"
				icon="download-simple"
				disabled={!connected}
				onClick={openUpdateModal}
			/>
		</div>
		{#if updateStubNotice !== null}
			<div class="st-machinecard-fw-stub" role="status" aria-live="polite">
				{updateStubNotice}
			</div>
		{/if}
	</div>
</div>

<StGroup title="Connection">
	<!--
		"Auto-connect on launch" removed 2026-05-22 — Web Bluetooth requires
		a user gesture for every connect attempt, so the advertised behaviour
		is architecturally impossible in a PWA. The future
		`navigator.bluetooth.getDevices()`-driven "Offer reconnect on launch"
		can land as a separate row when ready (settings-audit.md §20).
	-->
	<StRow
		title="Telemetry rate"
		sub="How often the chart redraws. Higher = smoother curves."
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
					if (v) void app?.markUserPresent();
				}}
				label="Keep DE1 awake"
			/>
		{/snippet}
	</StRow>
	<!--
		Group Head Controller — the firmware setting that determines whether
		host-initiated shot / steam / hot-water starts require a touch on the
		on-machine button to confirm. Real machine setting (writes through
		MMR `0x803820` via `app.setGhcMode`); the toggle reads the current
		value off `de1MachineInfo[GhcMode]`. ON = 4 (legacy de1app's "all
		on"); OFF = 0. The connect-time MMR sweep populates the initial
		state.
	-->
	<StRow
		title="Group head controller (GHC)"
		needsConnection={!connected}
		sub="When on, the DE1 lights up its front buttons and waits for you to
		tap one to confirm any host-initiated shot, steam, or hot-water start.
		Turn off to let Crema start sessions directly from the Coffee button."
	>
		{#snippet control()}
			{@const ghcOn = (snapshot.de1MachineInfo[MmrRegister.GhcMode] ?? 0) > 0}
			<StToggle
				on={ghcOn}
				disabled={!connected}
				onChange={(v) => {
					void app?.setGhcMode(v ? 4 : 0);
				}}
				label="GHC mode"
			/>
		{/snippet}
	</StRow>
</StGroup>

{#if isBengle}
	<!--
		Cup warmer — Bengle hardware only. The MMR `CupWarmerTemp`
		(`0x803874`) is a 2-byte °C setpoint; `0` turns the plate off.
		The card is omitted entirely on non-Bengle models (per the
		"don't show a disabled stub" rule). The stepper's display value
		is converted by the temp dimension so it respects the user's
		Settings → Display unit pref; canonical °C stays on the wire.
	-->
	<StGroup title="Cup warmer">
		<StRow
			title="Plate temperature"
			needsConnection={!connected}
			sub={cupWarmerC === 0
				? 'Off. Set above 0 to warm cups before pouring.'
				: 'The DE1 holds the cup-warmer plate at this temperature.'}
		>
			{#snippet control()}
				<StStepper
					value={cupWarmerC}
					dimension="temp"
					step={1}
					min={0}
					max={80}
					onCommit={(v) => {
						if (!connected) return;
						void app?.setCupWarmerTemperature(Math.round(v));
					}}
				/>
			{/snippet}
		</StRow>
	</StGroup>
{/if}

<StGroup title="DE1 connection diagnostics">
	<!-- Each row's value cell follows one of two patterns:
	     - Two-state (GATT verified): a small dot — empty grey by default,
	       filled green when on. No words; the dot says everything.
	     - Multi-state (connection state, device name, machine state,
	       notification count): the value text in the `.st-diag-mono`
	       hint style. A dash placeholder while the value is absent. -->
	<StRow title="Connection state" sub="The coarse DE1 link state.">
		{#snippet control()}
			<span class="st-diag-mono">{connectionLabel}</span>
		{/snippet}
	</StRow>
	<StRow
		title="Selected device"
		sub="The name and browser device id of the device the chooser picked."
	>
		{#snippet control()}
			<span class="st-diag-mono">{diag.deviceName ?? '—'}</span>
		{/snippet}
		{#snippet hint()}
			<!-- Only show the id hint when a device is actually selected;
			     otherwise the row would render two stacked dashes (one for
			     the missing name, one for the missing id). -->
			{#if diag.deviceId !== null}
				<span class="st-diag-id">{diag.deviceId}</span>
			{/if}
		{/snippet}
	</StRow>
	<StRow
		title="DE1 GATT verified"
		sub="True once service A000 and the StateInfo (A00E), ShotSample (A00D) and WaterLevels (A011) characteristics resolved. A non-DE1 board fails here."
	>
		{#snippet control()}
			<span
				class="st-diag-dot"
				class:is-on={diag.gattVerified}
				aria-label={diag.gattVerified ? 'Verified' : 'Not verified'}
			></span>
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
			<span class="st-diag-mono">{notificationsLabel}</span>
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Peripherals">
	<!-- TODO: a peripheral registry (grinder) is not in the shell; the
	     scale's live state lives on the Scale page. Faithful UI only. The
	     "Weight-aware tamper" entry was removed — there is no BLE-smart
	     tamper product Crema would ever pair with, so it was dead UI. -->
	<StRow title="Scale" sub="Used for stop-on-weight and auto-tare. Manage on the Scale page.">
		{#snippet control()}
			<span
				class="st-diag-dot"
				class:is-on={scaleConnected}
				aria-label={scaleConnected ? 'Scale connected' : 'Scale not paired'}
			></span>
		{/snippet}
		{#snippet hint()}
			<!-- Pair lives here (matching the Grinder row's pattern) so the
			     Scale page hero stays clean — pairing is a "set it once"
			     action that belongs in Settings, not in the every-shot Scale
			     workflow. Triggers the Web Bluetooth chooser on click. -->
			<StButton
				label={scaleConnected ? 'Re-pair' : 'Pair'}
				icon="bluetooth"
				disabled={!app}
				onClick={() => app?.connectScale()}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Grinder"
		sub="Doesn't report dose; Crema logs a manual grind setting."
		notImplemented
	>
		{#snippet control()}
			<!-- Grinder is not implemented — empty dot always. -->
			<span class="st-diag-dot" aria-label="Grinder not paired"></span>
		{/snippet}
		{#snippet hint()}
			<StButton label="Pair" icon="bluetooth" disabled />
		{/snippet}
	</StRow>
	<!--
		Equipment-level grinder model — free-text default that flows into
		the Brew page's bean card (bean-level `grinder` overrides it) and
		rides on Visualizer uploads (`grinder_model`). Captured into each
		shot at completion; per-shot edit lives on the History detail
		panel. Sits under the Pair row because conceptually it's "the
		other half" of the grinder relationship: pair when we can talk
		to it, name it when we can't.
	-->
	<StRow
		title="Grinder model"
		sub="Used as the default on the Brew bean card and on Visualizer uploads. Free text — beans can override per-bag; shots can override per-shot."
	>
		{#snippet control()}
			<input
				type="text"
				class="mc-grinder-model"
				placeholder="e.g. Niche Zero"
				value={prefs.grinderModel}
				oninput={(e) =>
					settings.set(
						'grinderModel',
						(e.currentTarget as HTMLInputElement).value
					)}
			/>
		{/snippet}
	</StRow>
</StGroup>

{#if showUpdateModal}
	<FirmwareUpdateModal
		installedBuild={snapshot.de1MachineInfo.FirmwareVersion}
		latestBuild={latestKnownBuild}
		onConfirm={onUpdateConfirm}
		onCancel={closeUpdateModal}
	/>
{/if}

<style>
	/* Diagnostics value cells — sized to match the Selected-device hint
	   line (the user's reference "best style"): small, mono, faint. The
	   row's title is still the prominent thing; the value reads as data.
	   The same `.st-diag-mono` is used for both the value and the hint
	   so the row visually flows top-to-bottom with one consistent
	   weight. */
	.st-diag-mono {
		font-family: var(--font-mono);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.6);
		font-variant-numeric: tabular-nums;
	}
	/* Two-state dot — empty grey by default, filled green when on. Used
	   for the GATT-verified row, the Scale peripheral, and the Grinder
	   placeholder. Replaces the per-row StStatusDot label text on those
	   genuinely binary states. */
	.st-diag-dot {
		display: inline-block;
		width: 9px;
		height: 9px;
		border-radius: 50%;
		background: transparent;
		border: 1.5px solid rgba(var(--tint-rgb), 0.3);
		transition: background var(--dur-1, 140ms) var(--ease, ease);
	}
	.st-diag-dot.is-on {
		background: var(--success, #4ea869);
		border-color: var(--success, #4ea869);
	}
	.st-diag-id {
		font-family: var(--font-mono);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.45);
		word-break: break-all;
		max-width: 220px;
		display: inline-block;
	}
	/* Note: the BLE id clamp formerly lived here (.st-machinecard-ble);
	   replaced by `.st-machinecard-info-val` which has its own ellipsis
	   clamp on the new info card. */

	/* Firmware card header — eyebrow + the "Connect DE1" pill side-by-side.
	   The pill duplicates the look of `StRow`'s `.st-row-pill-conn` so the
	   firmware card carries the same offline cue as the rows below. */
	.fw-head {
		display: flex;
		align-items: center;
		gap: 8px;
		flex-wrap: wrap;
	}
	/* Free-text grinder-model input under Peripherals → Grinder. Mirrors
	   the Advanced webhook-URL field's metrics so the two single-line
	   text inputs in Settings look like the same control. */
	.mc-grinder-model {
		width: 280px;
		max-width: 100%;
		padding: 6px 10px;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.06);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 6px;
		outline: none;
	}
	.mc-grinder-model:focus {
		border-color: rgba(var(--tint-rgb), 0.4);
	}
	.fw-conn-pill {
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.08);
		border: 1px solid rgba(var(--copper-rgb), 0.22);
		border-radius: 999px;
		padding: 2px 8px;
		white-space: nowrap;
	}
</style>
