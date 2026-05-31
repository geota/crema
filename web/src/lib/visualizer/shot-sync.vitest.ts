/**
 * `$lib/visualizer/shot-sync.test` — node:test suite for the de-dup
 * signature helpers and the {@link reconcileShots} action planner.
 *
 * Mirrors the existing `oauth.test.ts` style: no vitest dependency, runs
 * via Node's built-in test runner. Invoked with:
 *
 * ```
 * cd web && node \
 *   --experimental-strip-types \
 *   --experimental-detect-module \
 *   --test src/lib/visualizer/shot-sync.test.ts
 * ```
 *
 * The pure helpers now live in Rust (`de1_domain::visualizer_sync`)
 * and are surfaced via wasm-bindgen. This test exercises that wasm
 * directly: it imports the `de1_wasm` bundle via a relative path
 * (node doesn't resolve the SvelteKit `$lib` alias), `await`s its
 * `init()` once, then drives the same adapters
 * `shot-sync-signatures.ts` uses in production. The contract under
 * test is therefore the wasm export — proving the byte-identical
 * hashes the prior pure-TS impl produced.
 *
 * Covers only the pure helpers — `uploadShot` / `pullShots` need a fetch
 * mock and live in an integration test suite (deferred).
 */

import assert from 'node:assert/strict';
import { beforeAll, describe, it } from 'vitest';
import {
	signatureForShot as wasmSignatureForShot,
	signatureForBean as wasmSignatureForBean,
	signatureForRoaster as wasmSignatureForRoaster,
	reconcileShots as wasmReconcileShots
} from '../wasm/de1_wasm.js';
import { initTestWasm } from '../wasm/test-init.ts';
import type { ShotBean, StoredShot } from '../history/model.ts';

// ── Types (mirrored from shot-sync-signatures.ts) ────────────────────

interface WireShot {
	id: string;
	clock: number;
	duration_ms: number;
	profile_title: string | null;
	final_weight_g: number | null;
	notes: string | null;
	rating: number | null;
	updated_at_ms: number | null;
	tag_list: string[];
}

type ReconcileAction =
	| { kind: 'add'; remote: WireShot }
	| { kind: 'update'; localId: string; remote: WireShot }
	| { kind: 'bind'; localId: string; visualizerId: string; remote: WireShot };

// ── Adapters (mirror $lib/visualizer/shot-sync-signatures.ts) ────────
//
// Production calls these through the `$lib/wasm` alias; the alias
// doesn't resolve in node, so the test inlines the same adapter
// shapes against a relative import. If the production adapters in
// `shot-sync-signatures.ts` ever drift from these, the test would
// false-pass on a stale contract — keeping the shapes minimal and
// the wasm functions on the public surface keeps the drift
// surface small.

function signatureForShot(shot: {
	completedAt: number;
	duration: number;
	profileName: string | null;
	finalWeight: number | null;
}): string {
	return wasmSignatureForShot(
		shot.completedAt,
		shot.duration,
		shot.profileName ?? undefined,
		shot.finalWeight ?? undefined
	);
}

function signatureForBean(bean: {
	name: string;
	roasterName: string | null;
	roastedOn: string | null;
}): string {
	return wasmSignatureForBean(
		bean.name,
		bean.roasterName ?? undefined,
		bean.roastedOn ?? undefined
	);
}

function signatureForRoaster(roaster: { name: string }): string {
	return wasmSignatureForRoaster(roaster.name);
}

// Mirror of `toLocalShotRef` in `shot-sync-signatures.ts` — the Rust
// `LocalShotRef` shape the planner deserialises is FLAT camelCase
// (top-level `duration`, `finalWeight`), but the shell's `StoredShot`
// nests them (`record.duration`, `metadata.yieldOut`). The test's
// fixtures have empty `samples`, so we read `metadata.yieldOut`
// directly rather than dragging in `peaksOf` (which calls into wasm
// and would return null for the fixtures anyway).
function toLocalShotRef(shot: StoredShot): {
	id: string;
	completedAt: number;
	duration: number;
	profileName: string | null;
	finalWeight: number | null;
	visualizerId: string | null;
	deletedAt: number | null;
} {
	return {
		id: shot.id,
		completedAt: shot.completedAt,
		duration: shot.record.duration,
		profileName: shot.profileName ?? null,
		finalWeight: shot.metadata?.yieldOut ?? null,
		visualizerId: shot.visualizerId ?? null,
		deletedAt: shot.deletedAt ?? null
	};
}

function reconcileShots(
	local: readonly StoredShot[],
	remote: readonly WireShot[]
): ReconcileAction[] {
	const slim = local.map(toLocalShotRef);
	const raw = wasmReconcileShots(JSON.stringify({ local: slim, remote }));
	return JSON.parse(raw) as ReconcileAction[];
}

function storedShotFromWire(remote: WireShot): StoredShot {
	const id = `shot:remote:${remote.id}`;
	return {
		formatVersion: 3,
		id,
		completedAt: remote.clock,
		profileName: remote.profile_title,
		metadata: {
			dose: null,
			yieldOut: remote.final_weight_g ?? null,
			rating: remote.rating ?? null,
			notes: remote.notes ?? null
		},
		record: { duration: remote.duration_ms, samples: [] },
		bean: null,
		grinderModel: null,
		tags: [...(remote.tag_list ?? [])],
		visualizerId: remote.id,
		deletedAt: null
	};
}

// ── Mirror of $lib/bean/visualizer-sync :: roastLevelToWire ──────────
//
// The shot-sync inline-bean mapper banding-encodes `bean.roastLevel`
// (1..10) into Visualizer's free-text `roast_level` field using the
// same 5-band thresholds as the bean library's bag write-side. If this
// banding drifts from `bean/visualizer-sync.ts`'s `roastLevelToWire`
// the round-trip between a shot's inline bean and the synced bag's
// own roast_level will desync — keep both in lock-step.
function roastLevelToWire(level: number | null): string | null {
	if (level == null) return null;
	if (level <= 2) return 'Light';
	if (level <= 4) return 'Medium-Light';
	if (level <= 6) return 'Medium';
	if (level <= 8) return 'Medium-Dark';
	return 'Dark';
}

// ── Mirror of $lib/visualizer/shot-sync :: inlineBeanPatch ───────────
//
// The shot-sync module's `inlineBeanPatch` is pure, but it imports via
// `$lib/...` which node strip-types can't resolve. The test mirror
// follows the same pattern the wasm-backed helpers use above.
function inlineBeanPatch(bean: ShotBean | null | undefined): Record<string, unknown> {
	const out: Record<string, unknown> = {};
	if (!bean) return out;
	const roaster = bean.roasterName?.trim();
	if (roaster) out.bean_brand = roaster;
	const type = bean.name?.trim();
	if (type) out.bean_type = type;
	if (bean.roastedOn) out.roast_date = bean.roastedOn;
	const roastLevelStr = roastLevelToWire(bean.roastLevel ?? null);
	if (roastLevelStr) out.roast_level = roastLevelStr;
	const notes = bean.notes?.trim();
	if (notes) out.bean_notes = notes;
	const grinder = bean.grinderSetting?.trim();
	if (grinder) out.grinder_setting = grinder;
	return out;
}

// ── Mirror of $lib/visualizer/shot-sync :: resolveGrinderModel ───────
//
// Snapshot wins (per-shot override or completion-time freeze); the
// settings default is the legacy-fallback for shots that pre-date the
// snapshot field. Trims both sides; `null` when neither carries a value.
function resolveGrinderModel(
	shot: StoredShot,
	settingsDefault: string | undefined | null
): string | null {
	const fromShot = shot.grinderModel?.trim();
	if (fromShot) return fromShot;
	const fromSettings = settingsDefault?.trim();
	if (fromSettings) return fromSettings;
	return null;
}

// ── Wasm bootstrap ────────────────────────────────────────────────────
// The `--target web` bundle's default init `fetch`es the `.wasm` by URL, which
// vitest can't do over `file://`; `initTestWasm` reads the bytes off disk and
// hands them to the initializer (`$lib/wasm/test-init`).
beforeAll(initTestWasm);

// ── Fixtures ─────────────────────────────────────────────────────────

function shot(over: Partial<StoredShot> = {}): StoredShot {
	return {
		formatVersion: 3,
		id: 'shot:local-1',
		completedAt: 1_700_000_000_000,
		profileName: 'best of decent',
		metadata: {
			dose: 18,
			yieldOut: 36,
			rating: 4,
			notes: null
		},
		record: { duration: 30_000, samples: [] },
		bean: null,
		grinderModel: null,
		tags: [],
		visualizerId: null,
		deletedAt: null,
		...over
	};
}

function wire(over: Partial<WireShot> = {}): WireShot {
	return {
		id: 'remote-shot-1',
		clock: 1_700_000_000_000,
		duration_ms: 30_000,
		profile_title: 'best of decent',
		final_weight_g: 36,
		notes: null,
		rating: null,
		updated_at_ms: null,
		tag_list: [],
		...over
	};
}

// ── Tests ─────────────────────────────────────────────────────────────

describe('signatureForShot', () => {
	it('is stable across identical inputs', () => {
		const inputs = {
			completedAt: 1_700_000_000_000,
			duration: 30_000,
			profileName: 'best of decent',
			finalWeight: 36
		};
		assert.equal(signatureForShot(inputs), signatureForShot(inputs));
	});

	it('matches the pinned djb2 hex digest from the legacy TS impl', () => {
		// The legacy TS impl emitted exactly this digest for the
		// canonical reference input; the Rust port MUST produce a
		// byte-identical hash.
		const sig = signatureForShot({
			completedAt: 1_700_000_000_000,
			duration: 30_000,
			profileName: 'best of decent',
			finalWeight: 36
		});
		assert.equal(sig, '65946a11');
	});

	it('changes when the start time differs', () => {
		const a = signatureForShot({
			completedAt: 1_700_000_000_000,
			duration: 30_000,
			profileName: 'best of decent',
			finalWeight: 36
		});
		const b = signatureForShot({
			completedAt: 1_700_000_001_000,
			duration: 30_000,
			profileName: 'best of decent',
			finalWeight: 36
		});
		assert.notEqual(a, b);
	});

	it('changes when the duration differs', () => {
		const a = signatureForShot({
			completedAt: 1_700_000_000_000,
			duration: 30_000,
			profileName: 'best of decent',
			finalWeight: 36
		});
		const b = signatureForShot({
			completedAt: 1_700_000_000_000,
			duration: 31_000,
			profileName: 'best of decent',
			finalWeight: 36
		});
		assert.notEqual(a, b);
	});

	it('changes when the final weight differs', () => {
		const a = signatureForShot({
			completedAt: 1_700_000_000_000,
			duration: 30_000,
			profileName: 'best of decent',
			finalWeight: 36
		});
		const b = signatureForShot({
			completedAt: 1_700_000_000_000,
			duration: 30_000,
			profileName: 'best of decent',
			finalWeight: 37.5
		});
		assert.notEqual(a, b);
	});

	it('is stable under sub-rounding float jitter', () => {
		const a = signatureForShot({
			completedAt: 1_700_000_000_000,
			duration: 30_000,
			profileName: 'best of decent',
			finalWeight: 36.001
		});
		const b = signatureForShot({
			completedAt: 1_700_000_000_000,
			duration: 30_000,
			profileName: 'best of decent',
			finalWeight: 36.0
		});
		assert.equal(a, b);
	});

	it('treats a null profile as a distinct slot', () => {
		const named = signatureForShot({
			completedAt: 1_700_000_000_000,
			duration: 30_000,
			profileName: 'named',
			finalWeight: 36
		});
		const unnamed = signatureForShot({
			completedAt: 1_700_000_000_000,
			duration: 30_000,
			profileName: null,
			finalWeight: 36
		});
		assert.notEqual(named, unnamed);
	});
});

describe('signatureForBean', () => {
	it('is case-insensitive on name + roaster', () => {
		const a = signatureForBean({
			name: 'Yirgacheffe',
			roasterName: 'Counter Culture',
			roastedOn: '2026-05-08'
		});
		const b = signatureForBean({
			name: 'yirgacheffe',
			roasterName: 'COUNTER CULTURE',
			roastedOn: '2026-05-08'
		});
		assert.equal(a, b);
		// Pinned digest — cross-impl contract.
		assert.equal(a, '481def0f');
	});

	it('changes when the roast date differs', () => {
		const a = signatureForBean({
			name: 'Yirgacheffe',
			roasterName: 'CC',
			roastedOn: '2026-05-08'
		});
		const b = signatureForBean({
			name: 'Yirgacheffe',
			roasterName: 'CC',
			roastedOn: '2026-05-09'
		});
		assert.notEqual(a, b);
	});
});

describe('signatureForRoaster', () => {
	it('strips punctuation + collapses whitespace', () => {
		const a = signatureForRoaster({ name: 'Onyx Coffee Lab' });
		const b = signatureForRoaster({ name: 'onyx-coffee_lab' });
		const c = signatureForRoaster({ name: '  ONYX   COFFEE.LAB ' });
		assert.equal(a, b);
		assert.equal(a, c);
		// Pinned digest — cross-impl contract.
		assert.equal(a, 'cf16b46a');
	});

	it('treats different roasters as different signatures', () => {
		const a = signatureForRoaster({ name: 'Onyx' });
		const b = signatureForRoaster({ name: 'Counter Culture' });
		assert.notEqual(a, b);
	});
});

describe('reconcileShots', () => {
	it('adds new remotes when no local matches', () => {
		const actions = reconcileShots([], [wire({ id: 'r-1' })]);
		assert.equal(actions.length, 1);
		assert.equal(actions[0].kind, 'add');
	});

	it('updates locals whose visualizerId matches', () => {
		const local = shot({ id: 'shot:l-1', visualizerId: 'r-1' });
		const remote = wire({ id: 'r-1' });
		const actions = reconcileShots([local], [remote]);
		assert.equal(actions.length, 1);
		assert.equal(actions[0].kind, 'update');
		if (actions[0].kind === 'update') {
			assert.equal(actions[0].localId, 'shot:l-1');
		}
	});

	it('binds unbound locals by signature collision', () => {
		const local = shot({
			id: 'shot:l-1',
			completedAt: 1_700_000_000_000,
			record: { duration: 30_000, samples: [] },
			profileName: 'best of decent',
			metadata: { yieldOut: 36, dose: null, rating: null, notes: null },
			visualizerId: null
		});
		const remote = wire({
			id: 'r-1',
			clock: 1_700_000_000_000,
			duration_ms: 30_000,
			profile_title: 'best of decent',
			final_weight_g: 36
		});
		const actions = reconcileShots([local], [remote]);
		assert.equal(actions.length, 1);
		assert.equal(actions[0].kind, 'bind');
		if (actions[0].kind === 'bind') {
			assert.equal(actions[0].localId, 'shot:l-1');
			assert.equal(actions[0].visualizerId, 'r-1');
		}
	});

	it('skips tombstoned locals when matching signatures', () => {
		const local = shot({ id: 'shot:l-1', deletedAt: Date.now() });
		const remote = wire({ id: 'r-1' });
		const actions = reconcileShots([local], [remote]);
		// Tombstoned local should NOT bind — instead we ADD the remote.
		assert.equal(actions[0].kind, 'add');
	});

	it('plans the right actions in order for a mixed pull', () => {
		const bound = shot({ id: 'shot:bound', visualizerId: 'r-known' });
		const unbound = shot({
			id: 'shot:unbound',
			completedAt: 1_700_000_010_000,
			record: { duration: 25_000, samples: [] },
			profileName: 'p',
			metadata: { yieldOut: 40, dose: null, rating: null, notes: null },
			visualizerId: null
		});
		const remotes: WireShot[] = [
			wire({ id: 'r-known' }),
			wire({
				id: 'r-new',
				clock: 1_700_000_020_000,
				duration_ms: 28_000,
				profile_title: 'q',
				final_weight_g: 42
			}),
			wire({
				id: 'r-bind',
				clock: 1_700_000_010_000,
				duration_ms: 25_000,
				profile_title: 'p',
				final_weight_g: 40
			})
		];
		const actions = reconcileShots([bound, unbound], remotes);
		assert.equal(actions.length, 3);
		assert.equal(actions[0].kind, 'update');
		assert.equal(actions[1].kind, 'add');
		assert.equal(actions[2].kind, 'bind');
	});
});

describe('storedShotFromWire', () => {
	it('populates tags from the wire tag_list', () => {
		const remote = wire({ id: 'r-tagged', tag_list: ['daily-driver', 'lever'] });
		const local = storedShotFromWire(remote);
		assert.deepEqual(local.tags, ['daily-driver', 'lever']);
	});

	it('defaults tags to [] when the wire tag_list is empty', () => {
		const remote = wire({ id: 'r-untagged', tag_list: [] });
		const local = storedShotFromWire(remote);
		assert.deepEqual(local.tags, []);
	});

	it('produces a stub local shot with visualizerId set', () => {
		const remote = wire({ id: 'r-77', notes: 'lovely', rating: 5 });
		const local = storedShotFromWire(remote);
		assert.equal(local.visualizerId, 'r-77');
		assert.equal(local.metadata.notes, 'lovely');
		assert.equal(local.metadata.rating, 5);
		assert.equal(local.deletedAt, null);
		assert.deepEqual(local.record.samples, []);
		assert.ok(local.id.startsWith('shot:remote:'));
	});
});

describe('inlineBeanPatch', () => {
	it('returns an empty object when the snapshot is null', () => {
		assert.deepEqual(inlineBeanPatch(null), {});
		assert.deepEqual(inlineBeanPatch(undefined), {});
	});

	it('returns an empty object when every snapshot field is empty', () => {
		const bean: ShotBean = {
			roasterName: '',
			name: '',
			roastedOn: null,
			roastLevel: null
		};
		assert.deepEqual(inlineBeanPatch(bean), {});
	});

	it('maps the v1 ShotBean fields onto bean_brand / bean_type / roast_date / roast_level', () => {
		const bean: ShotBean = {
			beanId: 'bean:abc',
			roasterName: 'Onyx Coffee Lab',
			name: 'Geisha Esmeralda',
			roastedOn: '2026-05-16',
			roastLevel: 2
		};
		assert.deepEqual(inlineBeanPatch(bean), {
			bean_brand: 'Onyx Coffee Lab',
			bean_type: 'Geisha Esmeralda',
			roast_date: '2026-05-16',
			roast_level: 'Light'
		});
	});

	it('maps the new snapshot fields onto bean_notes + grinder_setting', () => {
		const bean: ShotBean = {
			roasterName: 'Sey',
			name: 'Kenya Kii',
			roastedOn: null,
			roastLevel: 4,
			notes: 'Floral, blackcurrant.',
			grinderSetting: '2.5'
		};
		assert.deepEqual(inlineBeanPatch(bean), {
			bean_brand: 'Sey',
			bean_type: 'Kenya Kii',
			roast_level: 'Medium-Light',
			bean_notes: 'Floral, blackcurrant.',
			grinder_setting: '2.5'
		});
	});

	it('trims whitespace on string fields and omits empty results', () => {
		const bean: ShotBean = {
			roasterName: '   ',
			name: '  Heirloom  ',
			roastedOn: '2026-05-08',
			roastLevel: null,
			notes: '   ',
			grinderSetting: '  '
		};
		assert.deepEqual(inlineBeanPatch(bean), {
			bean_type: 'Heirloom',
			roast_date: '2026-05-08'
		});
	});

	it('bands roastLevel 1..10 through the 5-band roastLevelToWire', () => {
		const cases: Array<[number, string]> = [
			[1, 'Light'],
			[2, 'Light'],
			[3, 'Medium-Light'],
			[4, 'Medium-Light'],
			[5, 'Medium'],
			[6, 'Medium'],
			[7, 'Medium-Dark'],
			[8, 'Medium-Dark'],
			[9, 'Dark'],
			[10, 'Dark']
		];
		for (const [level, wireValue] of cases) {
			const bean: ShotBean = {
				roasterName: 'R',
				name: 'T',
				roastedOn: null,
				roastLevel: level
			};
			assert.equal(inlineBeanPatch(bean).roast_level, wireValue);
		}
	});

	it('skips fields with falsy / empty sources so an empty bean cannot clobber server values', () => {
		// All-empty snapshot, except a roastedOn — only that one slot
		// rides out. Crucially, the function does NOT emit
		// `bean_brand: ''` / `bean_notes: ''` / etc — those would
		// overwrite the user's Visualizer-side edits with blanks.
		const bean: ShotBean = {
			roasterName: '',
			name: '',
			roastedOn: '2026-05-08',
			roastLevel: null,
			notes: '',
			grinderSetting: ''
		};
		assert.deepEqual(inlineBeanPatch(bean), { roast_date: '2026-05-08' });
	});

	it('does NOT include beanId — that is the FK into the local library, never serialised', () => {
		// The snapshot's `beanId` is a local-library foreign key — it
		// routes through `coffee_bag_id` via `resolveCoffeeBagId` (which
		// reads the LIVE bean's `visualizerId`), never inlined directly.
		// Tags + the visualizer link are no longer carried on the snapshot
		// at all (tags live on `StoredShot.tags`; visualizer id is resolved
		// live every upload).
		const bean: ShotBean = {
			beanId: 'bean:xyz',
			roasterName: 'R',
			name: 'T',
			roastedOn: null,
			roastLevel: null
		};
		const out = inlineBeanPatch(bean);
		assert.equal(out.bean_brand, 'R');
		assert.equal(out.bean_type, 'T');
		assert.ok(!('coffee_bag_id' in out));
		assert.ok(!('tag_list' in out));
		assert.ok(!('beanId' in out));
		// `grinder_model` is shot-level, not bean-level — it rides via
		// the separate `resolveGrinderModel` helper and the typed slot
		// on `patchShot`. So `inlineBeanPatch` does NOT emit it.
		assert.ok(!('grinder_model' in out));
	});
});

describe('resolveGrinderModel', () => {
	it('returns null when the shot has no snapshot and the settings default is empty', () => {
		assert.equal(resolveGrinderModel(shot(), ''), null);
		assert.equal(resolveGrinderModel(shot(), null), null);
		assert.equal(resolveGrinderModel(shot(), undefined), null);
	});

	it('snapshots the shot value wins when present', () => {
		assert.equal(
			resolveGrinderModel(shot({ grinderModel: 'Niche Zero' }), 'Eureka Mignon'),
			'Niche Zero'
		);
	});

	it('falls back to the settings default when the snapshot is null', () => {
		assert.equal(
			resolveGrinderModel(shot({ grinderModel: null }), 'Eureka Mignon Specialita'),
			'Eureka Mignon Specialita'
		);
	});

	it('treats whitespace-only values as empty (trim both sides)', () => {
		assert.equal(resolveGrinderModel(shot({ grinderModel: '   ' }), '   '), null);
		assert.equal(
			resolveGrinderModel(shot({ grinderModel: '   ' }), '  Niche Zero  '),
			'Niche Zero'
		);
		assert.equal(
			resolveGrinderModel(shot({ grinderModel: '  Mythos One  ' }), 'Niche Zero'),
			'Mythos One'
		);
	});

	it('legacy shots without a grinderModel field cascade to the settings default', () => {
		// Pre-#81 records have no `grinderModel` at all — defensive
		// loader leaves it `null`, the cascade still resolves cleanly.
		const legacy = shot();
		// Force-clear via cast since the fixture defaults `grinderModel: null`.
		delete (legacy as { grinderModel?: unknown }).grinderModel;
		assert.equal(resolveGrinderModel(legacy, 'Niche Zero'), 'Niche Zero');
		assert.equal(resolveGrinderModel(legacy, ''), null);
	});
});
