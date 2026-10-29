/**
 * The profile-write generation rule (cohort review 2026-10-28, #2 N2 / #6 #3):
 * a header write opens a generation; a superseded or failed batch's writes are
 * neither sent nor acked; the live batch's first failure is reported once.
 */

import { describe, expect, it } from 'vitest';
import type { Command } from '#lib/core/crema-core.js';
import { ProfileWriteGenerations } from './profile-write-generations.ts';

const write = (target: string): Command =>
	({ type: 'WriteCharacteristic', content: { target, data: [0] } }) as unknown as Command;
const upload = (): Command[] => [
	write('De1ProfileHeader'),
	write('De1ProfileFrame'),
	write('De1ProfileFrame')
];

describe('ProfileWriteGenerations', () => {
	it('opens no batch for an output without profile writes', () => {
		const g = new ProfileWriteGenerations();
		expect(g.begin([write('De1RequestedState'), write('De1MmrWrite')])).toBeNull();
		expect(g.current).toBe(0);
	});

	it('a new header supersedes the older batch: its writes are skipped and never acked', () => {
		const g = new ProfileWriteGenerations();
		const a = g.begin(upload())!;
		expect(g.mayWrite(a)).toBe(true);
		expect(g.settle(a, true)).toBe('ack');
		const b = g.begin(upload())!;
		expect(b.generation).toBe(a.generation + 1);
		expect(g.mayWrite(a)).toBe(false);
		expect(g.settle(a, true), 'a late write of A acks nothing').toBe('drop');
		expect(g.settle(a, false), 'nor reports a failure').toBe('drop');
		expect(g.settle(b, true)).toBe('ack');
	});

	it('the first failure of the live batch is reported once and stops the batch', () => {
		const g = new ProfileWriteGenerations();
		const a = g.begin(upload())!;
		expect(g.settle(a, true)).toBe('ack');
		expect(g.settle(a, false)).toBe('failed');
		expect(g.mayWrite(a)).toBe(false);
		expect(g.settle(a, true)).toBe('drop');
		expect(g.settle(a, false)).toBe('drop');
		// The re-upload starts clean.
		const b = g.begin(upload())!;
		expect(g.mayWrite(b)).toBe(true);
	});

	it('frames without a header join the live generation', () => {
		const g = new ProfileWriteGenerations();
		const a = g.begin(upload())!;
		const more = g.begin([write('De1ProfileFrame')])!;
		expect(more.generation).toBe(a.generation);
	});
});
