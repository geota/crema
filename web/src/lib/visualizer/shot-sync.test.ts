/**
 * `$lib/visualizer/shot-sync.test` — node:test suite for the de-dup
 * signature helpers and the {@link reconcileShots} action planner
 * (docs/36 §3).
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
 * mock and live in an integration test suite (deferred — see docs/37).
 */

import assert from 'node:assert/strict';
import { describe, it, before } from 'node:test';
import {
	default as initWasm,
	signatureForShot as wasmSignatureForShot,
	signatureForBean as wasmSignatureForBean,
	signatureForRoaster as wasmSignatureForRoaster,
	reconcileShots as wasmReconcileShots
} from '../wasm/de1_wasm.js';
import type { StoredShot } from '../history/model.ts';

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

function reconcileShots(
	local: readonly StoredShot[],
	remote: readonly WireShot[]
): ReconcileAction[] {
	const raw = wasmReconcileShots(JSON.stringify({ local, remote }));
	return JSON.parse(raw) as ReconcileAction[];
}

function storedShotFromWire(remote: WireShot): StoredShot {
	const id = `shot:remote:${remote.id}`;
	return {
		id,
		completedAt: remote.clock,
		profileName: remote.profile_title,
		duration: remote.duration_ms,
		dose: null,
		peakWeight: null,
		finalWeight: remote.final_weight_g,
		peakPressure: 0,
		peakTemp: 0,
		series: [],
		bean: null,
		rating: remote.rating ?? 0,
		notes: remote.notes ?? '',
		tags: [...(remote.tag_list ?? [])],
		visualizerId: remote.id,
		deletedAt: null
	};
}

// ── Wasm bootstrap ────────────────────────────────────────────────────

// The wasm bundle is built with `--target web`, so its default
// initializer calls `fetch(<url to .wasm>)`. Node's `fetch` does not
// support `file://` URLs (undici raises "not implemented... yet"),
// so the test reads the .wasm file off disk and hands the bytes to
// the initializer directly. The bundler-mode init accepts a Buffer
// (or ArrayBuffer / WebAssembly.Module) as a single-arg shortcut and
// skips the fetch path entirely.
before(async () => {
	const { readFile } = await import('node:fs/promises');
	const wasmUrl = new URL('../wasm/de1_wasm_bg.wasm', import.meta.url);
	const bytes = await readFile(wasmUrl);
	await initWasm({ module_or_path: bytes });
});

// ── Fixtures ─────────────────────────────────────────────────────────

function shot(over: Partial<StoredShot> = {}): StoredShot {
	return {
		id: 'shot:local-1',
		completedAt: 1_700_000_000_000,
		profileName: 'best of decent',
		duration: 30_000,
		dose: 18,
		peakWeight: 36,
		finalWeight: 36,
		peakPressure: 9,
		peakTemp: 93,
		series: [],
		bean: null,
		rating: 4,
		notes: '',
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
			duration: 30_000,
			profileName: 'best of decent',
			finalWeight: 36,
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
			duration: 25_000,
			profileName: 'p',
			finalWeight: 40,
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
		assert.equal(local.notes, 'lovely');
		assert.equal(local.rating, 5);
		assert.equal(local.deletedAt, null);
		assert.deepEqual(local.series, []);
		assert.ok(local.id.startsWith('shot:remote:'));
	});
});
