/**
 * `$lib/maintenance/store` — the water-accumulation & maintenance store.
 *
 * The DE1 has **no** cumulative water-volume counter — but the legacy de1app
 * derives one by integrating group flow over time (`de1_de1.tcl:570-628`:
 * `volume += GroupFlow × Δt`, with sanity clamps). Crema's web shell does the
 * same here: the orchestrator feeds every telemetry sample's flow + elapsed
 * wall-time into {@link MaintenanceStore.accumulate}, and the running litre
 * total is persisted to `localStorage`.
 *
 * From that one persisted counter plus user-set intervals the store derives
 * the three maintenance readouts the Settings → Water section shows: the
 * water-filter capacity (%), the litres since the last descale, and the hours
 * since the last clean cycle. "Mark complete" resets the relevant counter.
 *
 * ## Naming: Clean (formerly "Backflush")
 *
 * Pre-rename the third tracker was called "Backflush". The DE1 firmware's
 * `MachineState::Clean` *is* the cycle the espresso world informally calls a
 * "backflush" (blind basket + cleaning tablet, pressure pulses puck-side);
 * legacy TCL never uses the word "backflush" at all, and reaprime calls the
 * button "Clean", so the run-cycle action and the tracker share
 * vocabulary with the firmware state.
 *
 * Persistence key bumped `crema.maintenance.v1` → `crema.maintenance.v2` with
 * a one-shot migration from the v1 key on first load — Crema is pre-prod, but
 * the migration is one line and avoids a confused "filter counter reset" on
 * the next session, so we keep it.
 *
 * Like the profile / history / settings stores this is a Svelte 5 `$state`
 * class over the shared `$lib/utils/storage` helpers and a single versioned
 * key; obtain the singleton with {@link getMaintenanceStore}.
 */

import { readJson, writeJson } from '$lib/utils/storage';

/** localStorage key for the maintenance counters ({@link MaintenanceState}). */
const MAINTENANCE_KEY = 'crema.maintenance.v2';
/**
 * Previous key (before the Backflush→Clean rename). Read once on first load
 * to migrate the prior persisted state forward; thereafter only the v2 key is
 * read or written.
 */
const MAINTENANCE_KEY_V1 = 'crema.maintenance.v1';

/**
 * A single telemetry sample's worth of accumulated water dispensed past which
 * the value is treated as a glitch and dropped — mirrors the legacy app's
 * "excessive water volume dispensed" clamp (`de1_de1.tcl`, 1000 ml).
 */
const MAX_SAMPLE_ML = 1000;

/** The persisted maintenance state — counters plus user-set intervals. */
export interface MaintenanceState {
	/** Total litres of water dispensed, ever — the integrated flow counter. */
	totalLitres: number;
	/** `totalLitres` at the last filter clean. The DE1's in-tank filter is
	 * a small mesh screen that catches debris before the pump — the user
	 * pulls it out and rinses it, doesn't replace it (the user-manual
	 * recommends mineral-controlled water; the in-tank piece is reusable).
	 * "Capacity" here is a heuristic for how often a clean is due based
	 * on litres dispensed, not a hard limit. */
	filterBaselineLitres: number;
	/** `totalLitres` at the last descale. */
	descaleBaselineLitres: number;
	/** Unix epoch ms of the last clean cycle (DE1 `MachineState::Clean`). */
	cleanAtMs: number;
	/** Unix epoch ms of the last filter clean. */
	filterAtMs: number;
	/** Unix epoch ms of the last descale. */
	descaleAtMs: number;
	/** Filter clean-interval threshold, litres — clean (rinse) when this
	 * many litres have passed through since the last clean. */
	filterCapacityLitres: number;
	/** Descale interval, litres — descale is due past this many litres. */
	descaleIntervalLitres: number;
	/** Clean cycle interval, hours — clean is due past this many hours. */
	cleanIntervalHours: number;
}

/**
 * The default maintenance state — a fresh install. The baselines and the
 * timestamps start at "now / zero" so the very first readouts are sane: a
 * full filter, no litres since descale, no hours since clean.
 */
function defaultState(): MaintenanceState {
	const now = Date.now();
	return {
		totalLitres: 0,
		filterBaselineLitres: 0,
		descaleBaselineLitres: 0,
		cleanAtMs: now,
		filterAtMs: now,
		descaleAtMs: now,
		// Sane defaults — a typical inline water filter is rated ~50 L; the
		// DE1's own descale guidance is interval-based; 48 h clean matches
		// the design's placeholder copy.
		filterCapacityLitres: 50,
		descaleIntervalLitres: 120,
		cleanIntervalHours: 48
	};
}

/**
 * Shape of the v1 persisted state — same fields as v2 but with the
 * pre-rename keys (`backflushAtMs`, `backflushIntervalHours`). Used once at
 * load time to migrate a pre-rename install forward.
 */
interface MaintenanceStateV1 {
	totalLitres?: number;
	filterBaselineLitres?: number;
	descaleBaselineLitres?: number;
	backflushAtMs?: number;
	filterAtMs?: number;
	descaleAtMs?: number;
	filterCapacityLitres?: number;
	descaleIntervalLitres?: number;
	backflushIntervalHours?: number;
}

/**
 * Read the persisted state, applying the one-shot v1→v2 Backflush→Clean key
 * rename if no v2 record exists but a v1 record does. Returns a partial state
 * suitable for spreading over {@link defaultState}.
 */
function loadPersisted(): Partial<MaintenanceState> {
	const v2 = readJson<Partial<MaintenanceState> | null>(MAINTENANCE_KEY, null);
	if (v2 != null) return v2;
	// Fallback: migrate the old key. Same fields except `backflush*` → `clean*`.
	const v1 = readJson<MaintenanceStateV1 | null>(MAINTENANCE_KEY_V1, null);
	if (v1 == null) return {};
	const migrated: Partial<MaintenanceState> = {
		totalLitres: v1.totalLitres,
		filterBaselineLitres: v1.filterBaselineLitres,
		descaleBaselineLitres: v1.descaleBaselineLitres,
		filterAtMs: v1.filterAtMs,
		descaleAtMs: v1.descaleAtMs,
		filterCapacityLitres: v1.filterCapacityLitres,
		descaleIntervalLitres: v1.descaleIntervalLitres,
		cleanAtMs: v1.backflushAtMs,
		cleanIntervalHours: v1.backflushIntervalHours
	};
	// Strip undefined fields so the spread over defaultState keeps the
	// fresh-install timestamps where the v1 record had nothing.
	for (const k of Object.keys(migrated) as (keyof MaintenanceState)[]) {
		if (migrated[k] === undefined) delete migrated[k];
	}
	return migrated;
}

/** A derived maintenance readout — a counter, its limit, and a due/ok verdict. */
export interface MaintenanceReadout {
	/** Litres since the last filter change. */
	filterUsedLitres: number;
	/** Filter capacity remaining, 0–100 %. */
	filterPercent: number;
	/** Whether the filter still has usable capacity. */
	filterOk: boolean;
	/** Litres dispensed since the last descale. */
	descaleSinceLitres: number;
	/** Whether the descale interval has not yet been exceeded. */
	descaleOk: boolean;
	/** Whole hours since the last clean cycle. */
	cleanSinceHours: number;
	/** Whether the clean interval has not yet been exceeded. */
	cleanOk: boolean;
}

/** The reactive water-accumulation & maintenance store — {@link getMaintenanceStore}. */
export class MaintenanceStore {
	/** The persisted state. Loaded from localStorage, defaults filling any gap. */
	private state = $state<MaintenanceState>({
		...defaultState(),
		...loadPersisted()
	});

	/** The current maintenance state. Reactive: a counter change re-renders readers. */
	get current(): MaintenanceState {
		return this.state;
	}

	/** Persist the maintenance state to localStorage. */
	private persist(): void {
		writeJson(MAINTENANCE_KEY, this.state);
	}

	/**
	 * Integrate one telemetry sample into the litre counter — the orchestrator
	 * calls this from its `Telemetry` handler. `flowMlPerS` is the DE1's group
	 * flow; `deltaSeconds` is the wall-clock time since the previous sample.
	 *
	 * Mirrors the legacy app's clamps: a negative result (a flow / clock
	 * glitch) and an implausibly large one (`> MAX_SAMPLE_ML`) are dropped.
	 */
	accumulate(flowMlPerS: number, deltaSeconds: number): void {
		if (
			!Number.isFinite(flowMlPerS) ||
			!Number.isFinite(deltaSeconds) ||
			deltaSeconds <= 0
		) {
			return;
		}
		const ml = flowMlPerS * deltaSeconds;
		if (ml <= 0 || ml > MAX_SAMPLE_ML) return;
		this.state = { ...this.state, totalLitres: this.state.totalLitres + ml / 1000 };
		this.persist();
	}

	/** The derived filter / descale / clean readouts for the UI. */
	get readout(): MaintenanceReadout {
		const s = this.state;
		const filterUsedLitres = Math.max(0, s.totalLitres - s.filterBaselineLitres);
		const filterPercent =
			s.filterCapacityLitres > 0
				? Math.max(0, Math.min(100, 100 - (filterUsedLitres / s.filterCapacityLitres) * 100))
				: 0;
		const descaleSinceLitres = Math.max(0, s.totalLitres - s.descaleBaselineLitres);
		const cleanSinceHours = Math.max(
			0,
			Math.floor((Date.now() - s.cleanAtMs) / 3_600_000)
		);
		return {
			filterUsedLitres,
			filterPercent,
			filterOk: filterUsedLitres < s.filterCapacityLitres,
			descaleSinceLitres,
			descaleOk: descaleSinceLitres < s.descaleIntervalLitres,
			cleanSinceHours,
			cleanOk: cleanSinceHours < s.cleanIntervalHours
		};
	}

	/** Mark the water filter cleaned — rebaseline its litre counter. */
	markFilterCleaned(): void {
		this.state = {
			...this.state,
			filterBaselineLitres: this.state.totalLitres,
			filterAtMs: Date.now()
		};
		this.persist();
	}

	/** Mark a descale done — rebaseline the descale litre counter. */
	markDescaled(): void {
		this.state = {
			...this.state,
			descaleBaselineLitres: this.state.totalLitres,
			descaleAtMs: Date.now()
		};
		this.persist();
	}

	/** Mark a clean cycle done — reset its hour counter. */
	markCleaned(): void {
		this.state = { ...this.state, cleanAtMs: Date.now() };
		this.persist();
	}

	/**
	 * Set the water-filter rated capacity, in litres. Drives the filter
	 * card's "% capacity left" readout and the replace-now threshold.
	 */
	setFilterCapacity(litres: number): void {
		if (!Number.isFinite(litres) || litres <= 0) return;
		this.state = { ...this.state, filterCapacityLitres: litres };
		this.persist();
	}

	/**
	 * Set the descale interval, in litres. The descale card flips to
	 * "Descale due" once `descaleSinceLitres` exceeds this value.
	 */
	setDescaleInterval(litres: number): void {
		if (!Number.isFinite(litres) || litres <= 0) return;
		this.state = { ...this.state, descaleIntervalLitres: litres };
		this.persist();
	}

	/**
	 * Set the clean cycle interval, in whole hours. The clean card flips
	 * to "Clean due" once `cleanSinceHours` exceeds this value.
	 */
	setCleanInterval(hours: number): void {
		if (!Number.isFinite(hours) || hours <= 0) return;
		this.state = { ...this.state, cleanIntervalHours: hours };
		this.persist();
	}
}

/** The process-wide singleton — one maintenance store shared by every route. */
let store: MaintenanceStore | undefined;

/** Get the shared {@link MaintenanceStore}, creating it on first call. */
export function getMaintenanceStore(): MaintenanceStore {
	if (!store) store = new MaintenanceStore();
	return store;
}
