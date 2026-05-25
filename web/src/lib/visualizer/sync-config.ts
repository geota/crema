/**
 * `$lib/visualizer/sync-config` — persisted per-entity sync direction +
 * cadence settings.
 *
 * One unified shape replaces the bean-only `crema.beans.sync.v1` config
 * with `crema.visualizer.sync.v1`. The old key is read once on first
 * load for migration, then tombstoned after a release.
 *
 * Each of beans / roasters / shots has its own direction selector
 * (`off`, `backup`, `pull`, `two-way`) and its own `lastSyncAt`
 * timestamp. A master `autoSync` toggle gates whether mutations fire
 * background pushes; manual "Sync now" always works regardless.
 */

import { readJson, writeJson } from '$lib/utils/storage';

/** New unified persistence key. */
const SYNC_KEY = 'crema.visualizer.sync.v1';
/** Legacy bean-only key — read once for migration, then tombstoned. */
const LEGACY_BEAN_KEY = 'crema.beans.sync.v1';

/** One direction per entity. */
export type SyncDirection = 'off' | 'backup' | 'pull' | 'two-way';

/** A single sync-log entry surfaced in the Sync UI. */
export interface SyncLogEntry {
	direction: 'push' | 'pull' | 'skip' | 'delete';
	entity: 'shot' | 'bean' | 'roaster';
	id: string;
	name: string;
	at: number;
	error?: string;
}

export interface VisualizerSyncConfig {
	autoSync: boolean;
	direction: {
		beans: SyncDirection;
		roasters: SyncDirection;
		shots: SyncDirection;
	};
	lastSyncAt: {
		beans: number | null;
		roasters: number | null;
		shots: number | null;
	};
	/** Cached premium-status flag. `null` until probed. */
	premium: boolean | null;
	/** Recent sync log — capped at 20 entries, newest first. */
	log: SyncLogEntry[];
}

const MAX_LOG_ENTRIES = 20;

export const DEFAULT_SYNC_CONFIG: VisualizerSyncConfig = {
	autoSync: true,
	direction: {
		beans: 'two-way',
		roasters: 'two-way',
		shots: 'backup'
	},
	lastSyncAt: { beans: null, roasters: null, shots: null },
	premium: null,
	log: []
};

interface LegacyBeanSyncShape {
	lastSyncAt?: number | null;
	premium?: boolean | null;
}

function migrateFromLegacy(legacy: LegacyBeanSyncShape): VisualizerSyncConfig {
	return {
		...DEFAULT_SYNC_CONFIG,
		premium: legacy?.premium ?? null,
		lastSyncAt: {
			beans: legacy?.lastSyncAt ?? null,
			roasters: legacy?.lastSyncAt ?? null,
			shots: null
		}
	};
}

/**
 * Read the persisted sync config, migrating the legacy bean-only shape
 * on first load. Always returns a fully-populated record; missing
 * fields fall back to the default.
 */
export function readSyncConfig(): VisualizerSyncConfig {
	const raw = readJson<VisualizerSyncConfig | null>(SYNC_KEY, null);
	if (!raw || typeof raw !== 'object') {
		const legacy = readJson<LegacyBeanSyncShape | null>(LEGACY_BEAN_KEY, null);
		if (legacy && typeof legacy === 'object') {
			const migrated = migrateFromLegacy(legacy);
			writeSyncConfig(migrated);
			return migrated;
		}
		return DEFAULT_SYNC_CONFIG;
	}
	// Fill in any missing fields from the default — defensive deserialize.
	return {
		...DEFAULT_SYNC_CONFIG,
		...raw,
		direction: { ...DEFAULT_SYNC_CONFIG.direction, ...(raw.direction ?? {}) },
		lastSyncAt: { ...DEFAULT_SYNC_CONFIG.lastSyncAt, ...(raw.lastSyncAt ?? {}) },
		log: Array.isArray(raw.log) ? raw.log.slice(0, MAX_LOG_ENTRIES) : []
	};
}

/** Replace the persisted config wholesale. */
export function writeSyncConfig(config: VisualizerSyncConfig): void {
	writeJson(SYNC_KEY, config);
}

/** Patch-merge the persisted config. */
export function updateSyncConfig(patch: Partial<VisualizerSyncConfig>): VisualizerSyncConfig {
	const next = { ...readSyncConfig(), ...patch };
	writeSyncConfig(next);
	return next;
}

/** Append a sync-log entry, capped at {@link MAX_LOG_ENTRIES}. */
export function appendSyncLog(entry: SyncLogEntry): void {
	const config = readSyncConfig();
	const log = [entry, ...config.log].slice(0, MAX_LOG_ENTRIES);
	writeSyncConfig({ ...config, log });
}

/** Reset the sync log. */
export function clearSyncLog(): void {
	const config = readSyncConfig();
	writeSyncConfig({ ...config, log: [] });
}

/** Helper — does this direction include push? */
export function directionPushes(d: SyncDirection): boolean {
	return d === 'backup' || d === 'two-way';
}

/** Helper — does this direction include pull? */
export function directionPulls(d: SyncDirection): boolean {
	return d === 'pull' || d === 'two-way';
}
