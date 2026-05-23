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
 * since the last backflush. "Mark complete" resets the relevant counter.
 *
 * Like the profile / history / settings stores this is a Svelte 5 `$state`
 * class over the shared `$lib/utils/storage` helpers and a single versioned
 * key; obtain the singleton with {@link getMaintenanceStore}.
 */

import { readJson, writeJson } from '$lib/utils/storage';

/** localStorage key for the maintenance counters ({@link MaintenanceState}). */
const MAINTENANCE_KEY = 'crema.maintenance.v1';

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
	/** `totalLitres` at the last filter replacement. */
	filterBaselineLitres: number;
	/** `totalLitres` at the last descale. */
	descaleBaselineLitres: number;
	/** Unix epoch ms of the last backflush. */
	backflushAtMs: number;
	/** Unix epoch ms of the last filter replacement. */
	filterAtMs: number;
	/** Unix epoch ms of the last descale. */
	descaleAtMs: number;
	/** Filter rated capacity, litres — replace when this many litres pass it. */
	filterCapacityLitres: number;
	/** Descale interval, litres — descale is due past this many litres. */
	descaleIntervalLitres: number;
	/** Backflush interval, hours — backflush is due past this many hours. */
	backflushIntervalHours: number;
}

/**
 * The default maintenance state — a fresh install. The baselines and the
 * timestamps start at "now / zero" so the very first readouts are sane: a
 * full filter, no litres since descale, no hours since backflush.
 */
function defaultState(): MaintenanceState {
	const now = Date.now();
	return {
		totalLitres: 0,
		filterBaselineLitres: 0,
		descaleBaselineLitres: 0,
		backflushAtMs: now,
		filterAtMs: now,
		descaleAtMs: now,
		// Sane defaults — a typical inline water filter is rated ~50 L; the
		// DE1's own descale guidance is interval-based; 48 h backflush matches
		// the design's placeholder copy.
		filterCapacityLitres: 50,
		descaleIntervalLitres: 120,
		backflushIntervalHours: 48
	};
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
	/** Whole hours since the last backflush. */
	backflushSinceHours: number;
	/** Whether the backflush interval has not yet been exceeded. */
	backflushOk: boolean;
}

/** The reactive water-accumulation & maintenance store — {@link getMaintenanceStore}. */
export class MaintenanceStore {
	/** The persisted state. Loaded from localStorage, defaults filling any gap. */
	private state = $state<MaintenanceState>({
		...defaultState(),
		...readJson<Partial<MaintenanceState>>(MAINTENANCE_KEY, {})
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

	/** The derived filter / descale / backflush readouts for the UI. */
	get readout(): MaintenanceReadout {
		const s = this.state;
		const filterUsedLitres = Math.max(0, s.totalLitres - s.filterBaselineLitres);
		const filterPercent =
			s.filterCapacityLitres > 0
				? Math.max(0, Math.min(100, 100 - (filterUsedLitres / s.filterCapacityLitres) * 100))
				: 0;
		const descaleSinceLitres = Math.max(0, s.totalLitres - s.descaleBaselineLitres);
		const backflushSinceHours = Math.max(
			0,
			Math.floor((Date.now() - s.backflushAtMs) / 3_600_000)
		);
		return {
			filterUsedLitres,
			filterPercent,
			filterOk: filterUsedLitres < s.filterCapacityLitres,
			descaleSinceLitres,
			descaleOk: descaleSinceLitres < s.descaleIntervalLitres,
			backflushSinceHours,
			backflushOk: backflushSinceHours < s.backflushIntervalHours
		};
	}

	/** Mark the water filter replaced — rebaseline its litre counter. */
	markFilterReplaced(): void {
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

	/** Mark a backflush done — reset its hour counter. */
	markBackflushed(): void {
		this.state = { ...this.state, backflushAtMs: Date.now() };
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
	 * Set the backflush interval, in whole hours. The backflush card flips
	 * to "Backflush due" once `backflushSinceHours` exceeds this value.
	 */
	setBackflushInterval(hours: number): void {
		if (!Number.isFinite(hours) || hours <= 0) return;
		this.state = { ...this.state, backflushIntervalHours: hours };
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
