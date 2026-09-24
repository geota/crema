/**
 * The typed wrapper over core's `decentShotRecordJson`. The converter's
 * field-by-field contract is pinned in core (`de1_domain::decent_shot_record`
 * + its golden fixture); these tests cover only what the wrapper adds: the
 * web row → core JSON hand-off, the full-resolution samples override, the
 * recipe-less retry, the typed error, and the two helpers.
 */
import { describe, expect, it } from 'vitest';
import type { StoredShot } from '$lib/history/model';
import { DecentRecordError, decentModelName, decentShotRecord, shotDurationSeconds } from './shot-record';

function sample(elapsed: number) {
	return {
		elapsed,
		sample: {
			sampleTime: 0,
			groupPressure: 8.5,
			groupFlow: 2.1,
			headTemp: 92.4,
			mixTemp: 93.1,
			setMixTemp: 93,
			setHeadTemp: 92,
			setGroupPressure: 9,
			setGroupFlow: 0,
			frameNumber: 2,
			steamTemp: 140
		},
		scaleWeight: 12.5,
		scaleFlowWeight: 1.9
	};
}

const shot: StoredShot = {
	formatVersion: 3,
	id: 'shot:0192',
	completedAt: Date.UTC(2026, 8, 24, 14, 10, 30),
	profileName: 'Blooming Espresso',
	profile: null,
	metadata: { dose: 18, yieldOut: 36.2, rating: 4, notes: 'juicy', nextPlan: 'finer' },
	// Fractional ms, as a live capture can leave them — the wrapper rounds.
	record: { duration: 30_000.4, samples: [sample(0), sample(500.6), sample(1000)] },
	bean: null,
	// A pre-typeshare row: `null` optionals.
	machine: { serialNumber: '6262', firmwareVersion: null, model: null },
	decentId: null
};

const machine = { serialNumber: '6262', firmwareVersion: 'v1.43 build 1352', model: 'DE1PRO' };

describe('decentShotRecord', () => {
	it('hands the persisted row to core and returns the ShotRecord JSON', () => {
		const rec = JSON.parse(decentShotRecord(shot, machine, '0.0.7'));
		expect(rec.id).toBe('crema-shot:0192');
		expect(rec.app).toMatchObject({ name: 'crema', version: '0.0.7' });
		expect(rec.machine).toEqual(machine);
		expect(rec.measurements).toHaveLength(3);
		expect(rec.workflow.name).toBe('Blooming Espresso');
	});

	it('uses full-resolution samples in place of the stored series when given', () => {
		const rec = JSON.parse(decentShotRecord(shot, machine, 'x', [sample(0), sample(100), sample(200), sample(300)]));
		expect(rec.measurements).toHaveLength(4);
	});

	it('retries without a recipe snapshot core cannot read', () => {
		const odd = { ...shot, profile: { version: '2', title: 'Hand-made', steps: [{ name: 'fill', seconds: 10 }] } };
		const rec = JSON.parse(decentShotRecord(odd, machine, 'x'));
		expect(rec.workflow.profile).toMatchObject({ title: 'Blooming Espresso', steps: [] });
	});

	it('throws a typed error for a row core cannot read at all', () => {
		const broken = { ...shot, record: { duration: 1000, samples: [{ elapsed: 0, sample: 'nope' }] } } as unknown as StoredShot;
		expect(() => decentShotRecord(broken, machine, 'x')).toThrow(DecentRecordError);
	});
});

describe('helpers', () => {
	it('names DE1 models 1..7 through core, null otherwise', () => {
		expect(decentModelName(3)).toBe('DE1PRO');
		expect(decentModelName(1)).toBe('DE1');
		expect(decentModelName(0)).toBeNull();
		expect(decentModelName(8)).toBeNull();
		expect(decentModelName(null)).toBeNull();
	});
	it('reports shot length in seconds', () => {
		expect(shotDurationSeconds(shot)).toBeCloseTo(30);
	});
});
