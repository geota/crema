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
 * Covers only the pure helpers — `uploadShot` / `pullShots` need a fetch
 * mock and live in an integration test suite (deferred — see docs/37).
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
	reconcileShots,
	signatureForBean,
	signatureForRoaster,
	signatureForShot,
	storedShotFromWire,
	type WireShot
} from './shot-sync-signatures.ts';
import type { StoredShot } from '../history/model.ts';

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
		updated_at: null,
		...over
	};
}

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
		assert.deepEqual(actions[0], { kind: 'update', localId: 'shot:l-1', remote });
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
		assert.deepEqual(actions[0], {
			kind: 'bind',
			localId: 'shot:l-1',
			visualizerId: 'r-1',
			remote
		});
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
