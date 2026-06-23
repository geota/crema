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
import type { VisualizerSyncPrefs } from '$lib/core/crema-core';

/** New unified persistence key. */
const SYNC_KEY = 'crema.visualizer.sync.v1';
/** Legacy app-settings key — the four upload-option prefs used to live here. */
const SETTINGS_KEY = 'crema.settings.v1';
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
	/** Master gate for BACKGROUND library (bean/roaster/shot) pushes on mutation;
	 *  manual "Sync now" always works regardless. Distinct from {@link autoUpload}. */
	autoSync: boolean;
	/** Auto-upload a finished shot to Visualizer as it completes (the shot-
	 *  completion push gate). Was the app-settings `visualizerAutoUpload`. */
	autoUpload: boolean;
	/** Default privacy for uploaded shots. Was the app-settings `visualizerPrivacy`. */
	privacy: 'public' | 'unlisted' | 'private';
	/** Send the profile JSON alongside each shot. Was `visualizerIncludeProfile`. */
	includeProfile: boolean;
	/** Send tasting notes alongside each shot. Was `visualizerIncludeNotes`. */
	includeNotes: boolean;
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
	/**
	 * Incremental pull cursor for shots (unix ms); `null` pulls everything.
	 * Advanced ONLY by a successful pull — never by a push. A single cursor
	 * shared with the push path silently starved the pull: pushing (incl. the
	 * default `backup` direction) jumped the cursor to "now", so the next pull
	 * skipped every pre-existing remote shot and reported "No new shots".
	 */
	shotPullCursor: number | null;
	/** Cached premium-status flag. `null` until probed. */
	premium: boolean | null;
	/** Recent sync log — capped at 20 entries, newest first. */
	log: SyncLogEntry[];
}

const MAX_LOG_ENTRIES = 20;

export const DEFAULT_SYNC_CONFIG: VisualizerSyncConfig = {
	autoSync: true,
	autoUpload: true,
	privacy: 'unlisted',
	includeProfile: true,
	includeNotes: false,
	direction: {
		beans: 'two-way',
		roasters: 'two-way',
		shots: 'backup'
	},
	lastSyncAt: { beans: null, roasters: null, shots: null },
	shotPullCursor: null,
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
 * One-shot migration: the four upload-option prefs (`autoUpload`, `privacy`,
 * `includeProfile`, `includeNotes`) used to live in the app Settings store
 * (`crema.settings.v1`). When a pre-unification config lacks them, pull the
 * persisted Settings values across (defaults if absent) and persist once so
 * later reads are self-contained. Race-free: reads the raw Settings localStorage
 * directly (those keys ride along untouched until this runs), never the store.
 */
function migrateUploadOptions(raw: Partial<VisualizerSyncConfig>): VisualizerSyncConfig {
	const s = readJson<Record<string, unknown>>(SETTINGS_KEY, {});
	const migrated: VisualizerSyncConfig = {
		...DEFAULT_SYNC_CONFIG,
		...raw,
		autoUpload:
			typeof s.visualizerAutoUpload === 'boolean'
				? s.visualizerAutoUpload
				: DEFAULT_SYNC_CONFIG.autoUpload,
		privacy:
			(s.visualizerPrivacy as VisualizerSyncConfig['privacy']) ?? DEFAULT_SYNC_CONFIG.privacy,
		includeProfile:
			typeof s.visualizerIncludeProfile === 'boolean'
				? s.visualizerIncludeProfile
				: DEFAULT_SYNC_CONFIG.includeProfile,
		includeNotes:
			typeof s.visualizerIncludeNotes === 'boolean'
				? s.visualizerIncludeNotes
				: DEFAULT_SYNC_CONFIG.includeNotes,
		direction: { ...DEFAULT_SYNC_CONFIG.direction, ...(raw.direction ?? {}) },
		lastSyncAt: { ...DEFAULT_SYNC_CONFIG.lastSyncAt, ...(raw.lastSyncAt ?? {}) },
		log: Array.isArray(raw.log) ? raw.log.slice(0, MAX_LOG_ENTRIES) : []
	};
	writeJson(SYNC_KEY, migrated);
	return migrated;
}

/**
 * Read the persisted sync config, migrating the legacy bean-only shape and the
 * Settings-resident upload options on first load. Always returns a
 * fully-populated record; missing fields fall back to the default.
 */
export function readSyncConfig(): VisualizerSyncConfig {
	const raw = readJson<Partial<VisualizerSyncConfig> | null>(SYNC_KEY, null);
	if (!raw || typeof raw !== 'object') {
		const legacy = readJson<LegacyBeanSyncShape | null>(LEGACY_BEAN_KEY, null);
		if (legacy && typeof legacy === 'object') {
			const migrated = migrateFromLegacy(legacy);
			writeSyncConfig(migrated);
			return migrated;
		}
		return DEFAULT_SYNC_CONFIG;
	}
	// Pull the four upload options across from app Settings the first time this
	// config predates the unification (detected on the RAW value, before the
	// default-fill below would mask the gap).
	if (raw.autoUpload === undefined) return migrateUploadOptions(raw);
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
	for (const cb of listeners) cb(config);
}

/** Patch-merge the persisted config. */
export function updateSyncConfig(patch: Partial<VisualizerSyncConfig>): VisualizerSyncConfig {
	const next = { ...readSyncConfig(), ...patch };
	writeSyncConfig(next);
	return next;
}

/**
 * In-tab change subscription. Every `writeSyncConfig` / `updateSyncConfig` /
 * `appendSyncLog` / `clearSyncLog` call fans out to subscribers — sections
 * that hold a `$state` snapshot can refresh themselves when another part of
 * the app mutates the config (e.g. Sharing's Test handler probing the
 * premium tier).
 */
type SyncConfigListener = (config: VisualizerSyncConfig) => void;
const listeners = new Set<SyncConfigListener>();

export function onSyncConfigChange(cb: SyncConfigListener): () => void {
	listeners.add(cb);
	return () => {
		listeners.delete(cb);
	};
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

/**
 * Project the persisted config to the shared cross-shell {@link VisualizerSyncPrefs}
 * shape — the eight user preferences a whole-app backup carries. Runtime/device
 * state (cursors, premium cache, log, lastSyncAt) is deliberately excluded.
 */
export function visualizerSyncPrefs(): VisualizerSyncPrefs {
	const c = readSyncConfig();
	return {
		autoUpload: c.autoUpload,
		autoSync: c.autoSync,
		privacy: c.privacy,
		includeProfile: c.includeProfile,
		includeNotes: c.includeNotes,
		shotsDirection: c.direction.shots,
		beansDirection: c.direction.beans,
		roastersDirection: c.direction.roasters
	};
}

/**
 * Apply restored {@link VisualizerSyncPrefs} (from a whole-app backup) into the
 * persisted config — the shared shape both shells serialise identically, so a
 * bundle from either device applies here verbatim. Tokens / runtime state are
 * never in the bundle.
 */
export function applyVisualizerSyncPrefs(p: VisualizerSyncPrefs): void {
	// Forward-compat: tolerate a partial blob (an older backup predating a field)
	// by filling any missing field from the canonical defaults.
	const d = DEFAULT_SYNC_CONFIG;
	updateSyncConfig({
		autoUpload: p.autoUpload ?? d.autoUpload,
		autoSync: p.autoSync ?? d.autoSync,
		privacy: (p.privacy ?? d.privacy) as VisualizerSyncConfig['privacy'],
		includeProfile: p.includeProfile ?? d.includeProfile,
		includeNotes: p.includeNotes ?? d.includeNotes,
		direction: {
			beans: (p.beansDirection ?? d.direction.beans) as SyncDirection,
			roasters: (p.roastersDirection ?? d.direction.roasters) as SyncDirection,
			shots: (p.shotsDirection ?? d.direction.shots) as SyncDirection
		}
	});
}
