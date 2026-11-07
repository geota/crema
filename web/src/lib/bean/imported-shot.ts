/**
 * `#lib/bean/imported-shot.js` — a core-shaped Beanconqueror `ImportedShot` as
 * the shell's `StoredShot`.
 */

import type { ShotBean, StoredShot } from '#lib/history/model.js';

/** The Rust StoredShot's wire shape (just the fields the shell uses). The struct
 *  tree carries `#[serde(rename_all = "camelCase")]`, so the wire keys are
 *  camelCase. `record.duration` is a u64 ms via the core's `duration_ms` serde
 *  adapter — see `core::de1_domain::shot::duration_ms`. */
export interface RustStoredShotWire {
	readonly completedAt: number;
	readonly record: {
		readonly duration: number;
	};
	readonly metadata: {
		readonly dose?: number | null;
		readonly yieldOut?: number | null;
		readonly rating?: number | null;
		readonly notes?: string | null;
		readonly grinderSetting?: string | null;
		readonly waterG?: number | null;
	};
	/** Brew Log method for non-espresso BC brews (issue #10). */
	readonly brewMethod?: string | null;
	readonly brewTempTarget?: number | null;
	/** The core's 0: an imported brew never debited its bag. */
	readonly bagDebit?: number | null;
}

/** One BC brew mapped to a Rust-shape StoredShot + the resolved snapshot strings. */
export interface CoreImportedShot {
	readonly storedShot: RustStoredShotWire;
	readonly beanId: string | null;
	readonly beanName: string;
	readonly roasterName: string | null;
	readonly roastedOn: string | null;
	readonly roastLevel: number | null;
	readonly grinderModel: string | null;
}

/**
 * Take one core-shaped `ImportedShot` and build the shell's enriched
 * `StoredShot` shape — telemetry stays empty (BC's `flow_profile` sidecar parse
 * is deferred), but the bean snapshot + grinder model + metadata all come
 * through so a History row renders without needing the wasm result again.
 */
export function prepareImportedShot(
	imp: CoreImportedShot,
	beanIdRemap: Map<string, string>
): StoredShot {
	const bean: ShotBean | null = imp.beanName
		? {
				// Through the dedupe remap: a skipped bean's shots link to
				// the surviving library row, not the dropped wire one.
				beanId: imp.beanId ? beanIdRemap.get(imp.beanId) ?? imp.beanId : imp.beanId,
				name: imp.beanName,
				roasterName: imp.roasterName ?? null,
				roastedOn: imp.roastedOn,
				roastLevel: imp.roastLevel
			}
		: null;
	const meta = imp.storedShot.metadata;
	const bagDebit = imp.storedShot.bagDebit;
	return {
		formatVersion: 3,
		id: `shot:bc:${crypto.randomUUID()}`,
		completedAt: imp.storedShot.completedAt,
		profileName: null,
		metadata: {
			dose: meta.dose ?? null,
			yieldOut: meta.yieldOut ?? null,
			rating: meta.rating ?? null,
			notes: meta.notes ?? null,
			grinderSetting: meta.grinderSetting ?? null,
			// Water-in for BC filter brews (issue #10) — the core maps
			// BC's `brew_quantity` here for non-espresso preparations.
			waterG: meta.waterG ?? null
		},
		record: {
			duration: imp.storedShot.record.duration,
			samples: []
		},
		bean,
		grinderModel: imp.grinderModel,
		tags: [],
		// Brew Log method for non-espresso BC brews — these used to be
		// skipped outright; now they land as telemetry-less brew rows.
		...(imp.storedShot.brewMethod ? { brewMethod: imp.storedShot.brewMethod } : {}),
		...(imp.storedShot.brewTempTarget != null
			? { brewTempTarget: imp.storedShot.brewTempTarget }
			: {}),
		...(bean && bagDebit != null ? { bagDebit } : {})
	};
}
