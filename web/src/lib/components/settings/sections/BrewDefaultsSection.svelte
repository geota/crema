<script lang="ts">
	/**
	 * Brew defaults section — the starting point for new profiles and the
	 * shot-behaviour toggles.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real persistence** — every value here is an app preference persisted in
	 * `lib/settings`. They are app-side defaults the Brew screen can read later;
	 * persisting them now is the whole job. The Steam group additionally pushes
	 * to the DE1 while connected (the connect path re-seeds it otherwise).
	 */
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { getSettingsStore } from '$lib/settings';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StStepper from '../StStepper.svelte';

	const settings = getSettingsStore();
	const appCtx = getCremaAppContext();
	const prefs = $derived(settings.current);

	/** The CremaApp accessor — `null` until the orchestrator boots. */
	const app = $derived(appCtx().app);

	/**
	 * Set + push to core. The setting is the single source of truth; the
	 * core call is the imperative side-effect at the action site (no
	 * `$effect` indirection — the value is pushed at the exact moment
	 * the user toggles).
	 */
	function setAutoTare(on: boolean): void {
		settings.set('autoTareOnShotStart', on);
		void app?.applyAutoTare(on);
	}
	function setStopOnWeight(on: boolean): void {
		settings.set('stopOnWeight', on);
		void app?.applyStopOnWeight(on);
	}
	function setVolumeStopWithScale(on: boolean): void {
		settings.set('volumeStopWithScale', on);
		void app?.applyVolumeStopWithScale(on);
	}
	function setMaxShotDuration(s: number): void {
		settings.set('maxShotDurationS', s);
		void app?.applyMaxShotDuration(s);
	}
	function setSteamEcoMode(on: boolean): void {
		settings.set('steamEcoMode', on);
		// The push to the DE1 only takes effect while connected; if the
		// user toggles offline the persisted pref still updates and the
		// next toggle while connected will sync. (No deferred-replay yet;
		// pragmatic for a setting users rarely change mid-session.)
		void app?.applySteamEcoMode(on);
	}
	function setSteamTwoTapStop(on: boolean): void {
		settings.set('steamTwoTapStop', on);
		// Written now while connected; the connect path re-seeds it, so an
		// offline toggle still lands on the next connect.
		void app?.applySteamTwoTapStop(on);
	}
	/**
	 * Steam temp/time — the same persisted Quick-Controls overrides the Brew
	 * Quick Sheet edits, so both surfaces stay in sync. Written now while
	 * connected; the connect path re-seeds them, so offline edits land on the
	 * next connect. The two share the one cuuid_0B packet with hot-water
	 * temp/volume — persist first, then read-modify-write all four from the
	 * freshly-updated settings.
	 */
	function pushSteamHotwater(): void {
		const c = settings.current;
		void app?.setSteamHotwater({
			steamTempC: c.qcSteamTempC,
			steamTimeoutS: c.qcSteamTimeS,
			hotWaterTempC: c.qcHotWaterTempC,
			hotWaterVolumeMl: c.qcHotWaterVolumeMl
		});
	}
	function setSteamTemp(v: number): void {
		settings.set('qcSteamTempC', v);
		pushSteamHotwater();
	}
	function setSteamTime(v: number): void {
		settings.set('qcSteamTimeS', v);
		pushSteamHotwater();
	}
	// Steam flow is a standalone MMR write (same re-seed-on-connect semantics).
	function setSteamFlow(v: number): void {
		settings.set('qcSteamFlowMlS', v);
		void app?.setSteamFlow(v);
	}
</script>

<StSectionHead
	eyebrow="Behaviour"
	title="Brew defaults"
	sub="The starting point for new profiles, and what the Quick Controls strip resets to."
/>

<StGroup title="Targets">
	<StRow title="Default dose" sub="Dose of ground coffee per shot.">
		{#snippet control()}
			<StStepper
				value={prefs.defaultDoseG}
				dimension="weight"
				step={0.1}
				min={5}
				max={30}
				onCommit={(v) => settings.set('defaultDoseG', v)}
			/>
		{/snippet}
	</StRow>
	<StRow title="Default ratio" sub="Yield-to-dose target for new profiles.">
		{#snippet control()}
			<StStepper
				value={prefs.defaultRatio}
				unit=":1"
				decimals={1}
				step={0.1}
				min={1}
				max={5}
				onCommit={(v) => settings.set('defaultRatio', v)}
			/>
		{/snippet}
	</StRow>
	<StRow title="Default brew temp" sub="Group temperature when no profile is loaded.">
		{#snippet control()}
			<StStepper
				value={prefs.defaultBrewTempC}
				dimension="temp"
				step={0.5}
				min={80}
				max={100}
				onCommit={(v) => settings.set('defaultBrewTempC', v)}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Default pre-infusion"
		sub="Pre-infusion time when no profile is loaded. Click the dot to disable."
	>
		{#snippet control()}
			<StStepper
				value={prefs.defaultPreinfusionS}
				unit="s"
				step={1}
				min={0}
				max={60}
				onCommit={(v) => settings.set('defaultPreinfusionS', v)}
				dot
				dotOn={prefs.defaultPreinfusionS > 0}
				onDot={() =>
					settings.set('defaultPreinfusionS', prefs.defaultPreinfusionS > 0 ? 0 : 8)}
			/>
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Shot behaviour">
	<StRow
		title="Auto-tare on shot start"
		sub="Zero the connected scale when an espresso shot starts. Fires on shots started from Crema and from the DE1's group-head touch."
	>
		{#snippet control()}
			<StToggle
				on={prefs.autoTareOnShotStart}
				onChange={setAutoTare}
				label="Auto-tare on shot start"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Stop on weight"
		sub="End the shot when the cup reaches the active profile's target yield (or the Quick Controls override). Requires a connected scale and a yield > 0."
	>
		{#snippet control()}
			<StToggle
				on={prefs.stopOnWeight}
				onChange={setStopOnWeight}
				label="Stop on weight"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Volume stop with scale"
		sub="Also stop at the profile's max volume while a scale is connected. Off = the volume limit only applies without a scale, so it can't pre-empt stop on weight."
	>
		{#snippet control()}
			<StToggle
				on={prefs.volumeStopWithScale}
				onChange={setVolumeStopWithScale}
				label="Volume stop with scale"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Require scale to start"
		sub="Refuse to start a shot that has a weight target while no scale is connected — instead of the default warn-and-continue. For anyone on autopilot who'd rather be stopped than surprised."
	>
		{#snippet control()}
			<StToggle
				on={prefs.requireScale}
				onChange={(v) => settings.set('requireScale', v)}
				label="Require scale to start"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Max shot duration"
		sub="Safety guardrail — abort the shot if it runs longer than this. Click the dot to disable."
	>
		{#snippet control()}
			<StStepper
				value={prefs.maxShotDurationS}
				unit="s"
				step={5}
				min={0}
				max={300}
				onCommit={setMaxShotDuration}
				dot
				dotOn={prefs.maxShotDurationS > 0}
				onDot={() => setMaxShotDuration(prefs.maxShotDurationS > 0 ? 0 : 60)}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Group flush after steam"
		sub="Rinse the group head with a short flush after steaming — keeps the brew path fresh if you steam before pulling the shot. The steam wand's own quick purge is the machine's — see two-tap steam stop below."
	>
		{#snippet control()}
			<StToggle
				on={prefs.autoPurgeAfterSteam}
				onChange={(v) => settings.set('autoPurgeAfterSteam', v)}
				label="Group flush after steam"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Group flush before each shot"
		sub="Adds 4–6s to each shot but stabilizes temp."
	>
		{#snippet control()}
			<StToggle
				on={prefs.groupFlushBeforeShot}
				onChange={(v) => settings.set('groupFlushBeforeShot', v)}
				label="Group flush before each shot"
			/>
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Steam">
	<StRow
		title="Steam temperature"
		sub="Steam boiler target. Off disables the steam heater entirely."
	>
		{#snippet control()}
			<StStepper
				value={prefs.qcSteamTempC}
				dimension="temp"
				step={1}
				min={135}
				max={170}
				onCommit={setSteamTemp}
			/>
		{/snippet}
	</StRow>
	<StRow title="Steam time" sub="How long the wand runs before stopping on its own.">
		{#snippet control()}
			<StStepper
				value={prefs.qcSteamTimeS}
				unit="s"
				step={1}
				min={1}
				max={60}
				onCommit={setSteamTime}
			/>
		{/snippet}
	</StRow>
	<StRow title="Steam flow" sub="Wand flow rate — lower for slower, gentler texturing.">
		{#snippet control()}
			<StStepper
				value={prefs.qcSteamFlowMlS}
				unit="ml/s"
				decimals={1}
				step={0.1}
				min={0.2}
				max={3.0}
				onCommit={setSteamFlow}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Steam eco mode"
		sub="Lower-flow, lower-temp steam — gentler on small milk jugs and easier to texture. Off uses the default full-power steam profile."
	>
		{#snippet control()}
			<StToggle
				on={prefs.steamEcoMode}
				onChange={setSteamEcoMode}
				label="Steam eco mode"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Two-tap steam stop"
		sub="First stop tap ends steaming without the wand's auto-purge; tap again to purge once the pitcher is out of the way. Written to the machine."
	>
		{#snippet control()}
			<StToggle
				on={prefs.steamTwoTapStop}
				onChange={setSteamTwoTapStop}
				label="Two-tap steam stop"
			/>
		{/snippet}
	</StRow>
</StGroup>
