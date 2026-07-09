<script lang="ts">
	import InfoIcon from 'phosphor-svelte/lib/InfoIcon';
	/**
	 * Water & maintenance section — filter life, descaling, clean cycle, and the
	 * water-chemistry block.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real** — the maintenance cards are driven by `$lib/maintenance`: the
	 * DE1 has no water-volume counter, so the shell integrates group flow over
	 * time (the de1app's approach) into a persisted litre total. The filter
	 * capacity, litres-since-descale and hours-since-clean all derive from
	 * that counter plus the stored intervals; "Mark complete" rebaselines the
	 * relevant counter. The Descale / Clean / SteamRinse cards also expose a
	 * "Run now" button that fires the matching `requestMachineState` after a
	 * confirm dialog — the firmware drives the cycle to completion, then the
	 * user manually taps "Mark complete" once the puck-side hardware is reset.
	 *
	 * **UI-only** — the water-chemistry inputs are faithful UI; they are not
	 * app preferences the rest of the app reads, so they are kept as local
	 * state with a `// TODO`.
	 *
	 * ## Backflush → Clean rename
	 *
	 * The DE1 firmware state for the high-pressure puck-side cleaning cycle is
	 * `MachineState::Clean`; reaprime calls the button "Clean" and TCL never
	 * uses the word "backflush" at all. Crema previously called the tracker
	 * "Backflush" — the rename here (and in the store) brings the
	 * user-visible vocabulary in line with the firmware. The cycle is the
	 * same; the wire is the same.
	 */
	import { getMaintenanceStore } from '$lib/maintenance';
	import { MachineState } from '$lib/core/crema-core';
	import type { CremaApp } from '$lib/state';
	import { INITIAL_SNAPSHOT } from '$lib/state';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StSegment from '../StSegment.svelte';
	import StStepper from '../StStepper.svelte';
	import StToggle from '../StToggle.svelte';
	import StMaintenanceCard from '../StMaintenanceCard.svelte';

	let { app }: { app: CremaApp | null } = $props();

	/** Live UI snapshot — drives the connection + machine-state gates on the Run-now buttons. */
	const snapshot = $derived(app?.state.current ?? INITIAL_SNAPSHOT);
	const connected = $derived(snapshot.de1State === 'ready');
	/**
	 * Whether the firmware is in an idle/sleep state that accepts a cycle
	 * request. The DE1 will reject a Descale / Clean / SteamRinse request
	 * mid-shot or mid-steam; restrict the Run-now buttons to Idle or Sleep
	 * to match the firmware's safety gating.
	 */
	const machineIdle = $derived(
		snapshot.machineStateName === MachineState.Idle ||
			snapshot.machineStateName === MachineState.Sleep
	);

	const maintenance = getMaintenanceStore();
	/** The derived filter / descale / clean readouts — reactive. */
	const readout = $derived(maintenance.readout);
	/** The persisted maintenance state — for the intervals and last-done dates. */
	const m = $derived(maintenance.current);

	/** Format a Unix-ms timestamp as a short `18 May` date. */
	function shortDate(ms: number): string {
		return new Date(ms).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
	}

	// TODO: water chemistry is not yet wired into a store the app reads; local
	// state only, so the segment / chips feel real while previewing the IA.
	let waterSource = $state('bottled');
	let hardness = $state(38);
	let tds = $state(148);

	// ── Cycle confirmation modal ────────────────────────────────────────
	//
	// The three Run-now buttons (Descale, Clean, SteamRinse) all go through
	// one shared confirm modal. Type-to-confirm is overkill — the cycle is
	// user-initiated and reversible by power-cycling — so a single click
	// + a clear "Run cycle" / "Cancel" pair is enough. After the user taps
	// "Run cycle" we fire `requestMachineState` and close; we do NOT
	// auto-reset the tracker since the cycle running != the cycle
	// completing properly. The user marks complete manually.

	type CycleKind = 'descale' | 'clean' | 'steamrinse';
	type CycleSpec = {
		title: string;
		copy: string;
		runLabel: string;
		state: MachineState;
	};
	const CYCLES: Record<CycleKind, CycleSpec> = {
		descale: {
			title: 'Run descale cycle?',
			copy:
				'This will pump descaling solution through the group head. The cycle takes ~15 minutes. Make sure the drip tray is empty.',
			runLabel: 'Run cycle',
			state: MachineState.Descale
		},
		clean: {
			title: 'Run cleaning cycle?',
			copy:
				'Install a blind basket with cleaning tablet. The cycle pumps water through the puck-side at high pressure.',
			runLabel: 'Run cycle',
			state: MachineState.Clean
		},
		steamrinse: {
			title: 'Run steam rinse?',
			copy:
				'Clears the steam line. Make sure the wand is over the drip tray.',
			runLabel: 'Run cycle',
			state: MachineState.SteamRinse
		}
	};

	/** Which cycle is being confirmed, or `null` when no modal is open. */
	let pendingCycle = $state<CycleKind | null>(null);
	/** Whether the request is in-flight — disables the Run button. */
	let running = $state(false);

	const pendingSpec = $derived<CycleSpec | null>(
		pendingCycle ? CYCLES[pendingCycle] : null
	);

	function openCycle(kind: CycleKind): void {
		if (!connected || !machineIdle) return;
		pendingCycle = kind;
	}

	function cancelCycle(): void {
		if (running) return;
		pendingCycle = null;
	}

	async function confirmCycle(): Promise<void> {
		if (pendingCycle == null || pendingSpec == null || !app) return;
		running = true;
		try {
			await app.requestMachineState(pendingSpec.state);
			pendingCycle = null;
		} catch {
			// The orchestrator already logged via the event stream; leave the
			// modal open so the user can retry or cancel.
		} finally {
			running = false;
		}
	}

	function onModalKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape' && pendingCycle != null) {
			e.preventDefault();
			cancelCycle();
		}
	}

	/**
	 * Tooltip explaining why a Run-now button is disabled, or `undefined`
	 * when it is enabled. Match the gating order: connection first (the
	 * write goes nowhere without a paired DE1), then firmware state.
	 */
	const runDisabledReason = $derived(
		!connected
			? 'Connect DE1 to run a maintenance cycle'
			: !machineIdle
				? 'Machine must be in Idle/Ready state.'
				: undefined
	);
	const runDisabled = $derived(!connected || !machineIdle);
</script>

<svelte:window onkeydown={onModalKeydown} />

<StSectionHead
	eyebrow="Health"
	title="Water & maintenance"
	sub="Track filter life, descaling, and water chemistry. Crema reminds you before things start affecting taste."
/>

<div class="st-maint-grid">
	<!-- 3-cycle row across the top; the Water filter card spans the full
	     3-column row below them. Cycles share a Run button; the filter is
	     a Mark-complete-only physical action. The layout puts the three
	     wire-triggered actions together and leaves the filter as a wide
	     "long shallow" tile with no orphan column on the second row. -->
	<StMaintenanceCard
		icon="wind"
		title="Steam rinse"
		state="Manual"
		stateOk={true}
		metric="—"
		metricLabel="purge the steam line"
		detail="Clears any residual milk from the steam wand. Run it after every steam session."
		onRun={() => openCycle('steamrinse')}
		runLabel="Run"
		{runDisabled}
		{runDisabledReason}
	/>
	<StMaintenanceCard
		icon="snowflake"
		title="Descale cycle"
		state={readout.descaleOk ? 'On schedule' : 'Descale due'}
		stateOk={readout.descaleOk}
		metric={`${readout.descaleSinceLitres.toFixed(0)} L`}
		metricLabel="since last descale"
		detail={`Threshold: ${m.descaleIntervalLitres} L · last descaled ${shortDate(m.descaleAtMs)}`}
		onComplete={() => maintenance.markDescaled()}
		onRun={() => openCycle('descale')}
		runLabel="Run"
		{runDisabled}
		{runDisabledReason}
	/>
	<StMaintenanceCard
		icon="sparkle"
		title="Clean cycle"
		state={readout.cleanOk ? 'On schedule' : 'Clean due'}
		stateOk={readout.cleanOk}
		metric={`${readout.cleanSinceHours} hr`}
		metricLabel="since last cycle"
		detail={`Recommended every ${m.cleanIntervalHours} hr · last done ${shortDate(m.cleanAtMs)}`}
		onComplete={() => maintenance.markCleaned()}
		onRun={() => openCycle('clean')}
		runLabel="Run"
		{runDisabled}
		{runDisabledReason}
	/>
	<StMaintenanceCard
		icon="funnel"
		title="Water filter"
		state={readout.filterOk
			? `${Math.round(readout.filterPercent)}% capacity left`
			: 'Clean now'}
		stateOk={readout.filterOk}
		metric={`${Math.round(readout.filterPercent)}%`}
		metricLabel="capacity left"
		detail={`${readout.filterUsedLitres.toFixed(1)} L of ${m.filterCapacityLitres} L used · last cleaned ${shortDate(m.filterAtMs)}`}
		onComplete={() => maintenance.markFilterCleaned()}
		wide
	/>
</div>

<StGroup
	title="Water chemistry"
	sub="Tap or bottled? Crema uses this to estimate scale buildup."
>
	<StRow title="Water source" notImplemented>
		{#snippet control()}
			<StSegment
				value={waterSource}
				options={[
					{ value: 'tap', label: 'Tap' },
					{ value: 'filtered', label: 'Filtered' },
					{ value: 'bottled', label: 'Bottled' },
					{ value: 'rpavlis', label: 'RPavlis' }
				]}
				onChange={(v) => (waterSource = v)}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Hardness (ppm CaCO₃)"
		sub="Helps schedule descaling cycles accurately."
		notImplemented
	>
		{#snippet control()}
			<StStepper
				value={hardness}
				unit="ppm"
				step={1}
				min={0}
				max={500}
				onCommit={(v) => (hardness = v)}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Total dissolved solids"
		sub="Affects extraction; optional but useful for tuning."
		notImplemented
	>
		{#snippet control()}
			<StStepper
				value={tds}
				unit="ppm"
				step={1}
				min={0}
				max={1000}
				onCommit={(v) => (tds = v)}
			/>
		{/snippet}
	</StRow>
</StGroup>

<!--
	Maintenance intervals — the three thresholds the maintenance cards above
	compare their counters against. Persisted via the maintenance store; each
	stepper writes through its setter so a change takes effect immediately.
	The litre values stay in canonical L (the maintenance store's native
	unit); a future unit-aware variant of the `volume` dimension would let
	these display as fl-oz for users on imperial units.
-->
<StGroup
	title="Maintenance intervals"
	sub="When the maintenance cards above flip to 'due'. Adjust to your filter spec, water source, and usage."
>
	<StRow
		title="Filter capacity"
		sub="Clean the inline filter after this many litres pass through it."
	>
		{#snippet control()}
			<span class="ws-armed-row">
				<StToggle
					on={m.filterEnabled ?? true}
					onChange={(v) => maintenance.setFilterEnabled(v)}
					label="Filter reminder armed"
				/>
				<StStepper
					value={m.filterCapacityLitres}
					unit="L"
					step={5}
					min={5}
					max={500}
					onCommit={(v) => maintenance.setFilterCapacity(v)}
				/>
			</span>
		{/snippet}
	</StRow>
	<StRow
		title="Descale interval"
		sub="Run a descale after this many litres dispensed."
	>
		{#snippet control()}
			<span class="ws-armed-row">
				<StToggle
					on={m.descaleEnabled ?? true}
					onChange={(v) => maintenance.setDescaleEnabled(v)}
					label="Descale reminder armed"
				/>
				<StStepper
					value={m.descaleIntervalLitres}
					unit="L"
					step={10}
					min={10}
					max={1000}
					onCommit={(v) => maintenance.setDescaleInterval(v)}
				/>
			</span>
		{/snippet}
	</StRow>
	<StRow
		title="Clean cycle interval"
		sub="Run a clean cycle after this many hours of use."
	>
		{#snippet control()}
			<span class="ws-armed-row">
				<StToggle
					on={m.cleanEnabled ?? true}
					onChange={(v) => maintenance.setCleanEnabled(v)}
					label="Clean reminder armed"
				/>
				<StStepper
					value={m.cleanIntervalHours}
					unit="hr"
					step={1}
					min={1}
					max={500}
					onCommit={(v) => maintenance.setCleanInterval(v)}
				/>
			</span>
		{/snippet}
	</StRow>
</StGroup>

{#if pendingCycle != null && pendingSpec != null}
	<!--
		Cycle-confirmation modal — scrim + centred panel. Click the scrim or
		press Escape to close. A single "Run cycle" button fires the
		`requestMachineState` write; we do NOT auto-reset the tracker (the
		cycle starting is not proof that it completed properly — the user
		marks it complete manually after they've reinstalled the regular
		basket). Type-to-confirm is overkill here: the cycle is user-
		initiated and reversible by power-cycling, so a click + confirm is
		enough.
	-->
	<div
		class="cyc-scrim"
		role="presentation"
		onclick={cancelCycle}
		onkeydown={(e) => {
			if (e.key === 'Enter' || e.key === ' ') cancelCycle();
		}}
	>
		<div
			class="cyc-panel"
			role="dialog"
			aria-modal="true"
			aria-labelledby="cyc-title"
			tabindex="-1"
			onclick={(e) => e.stopPropagation()}
			onkeydown={(e) => e.stopPropagation()}
		>
			<div class="cyc-head">
				<div class="t-eyebrow">Maintenance cycle</div>
				<h2 id="cyc-title">{pendingSpec.title}</h2>
			</div>
			<div class="cyc-banner" role="alert">
				<InfoIcon aria-hidden="true" />
				<span>{pendingSpec.copy}</span>
			</div>
			<div class="cyc-actions">
				<button
					type="button"
					class="st-btn st-btn-secondary"
					onclick={cancelCycle}
					disabled={running}
				>
					Cancel
				</button>
				<button
					type="button"
					class="st-btn st-btn-primary"
					onclick={confirmCycle}
					disabled={running || !connected || !machineIdle}
				>
					{running ? 'Starting…' : pendingSpec.runLabel}
				</button>
			</div>
		</div>
	</div>
{/if}

<style>
	/* Arming toggle + interval stepper share the row's control slot. */
	.ws-armed-row {
		display: inline-flex;
		align-items: center;
		gap: 12px;
	}

	.cyc-scrim {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.55);
		backdrop-filter: blur(4px);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 1000;
		padding: 24px;
	}
	.cyc-panel {
		background: var(--bg-surface);
		color: var(--fg-1);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 12px;
		box-shadow:
			0 24px 48px rgba(0, 0, 0, 0.35),
			0 4px 12px rgba(0, 0, 0, 0.2);
		max-width: 440px;
		width: 100%;
		padding: 24px;
		display: flex;
		flex-direction: column;
		gap: 20px;
		outline: none;
	}
	.cyc-head h2 {
		margin: 4px 0 0 0;
		font-size: 18px;
		font-weight: 600;
	}
	.cyc-banner {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		padding: 12px 14px;
		border-radius: 8px;
		font-size: 13px;
		line-height: 1.45;
		background: rgba(var(--copper-rgb), 0.08);
		border: 1px solid rgba(var(--copper-rgb), 0.25);
		color: var(--fg-1);
	}
	.cyc-banner :global(svg) {
		flex-shrink: 0;
		font-size: 18px;
		margin-top: 1px;
		color: var(--copper-400);
	}
	.cyc-actions {
		display: flex;
		gap: 8px;
		justify-content: flex-end;
	}
</style>
