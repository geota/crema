import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { SawModelLoad } from '$lib/core/crema-core';
import {
	SAW_MODEL_KEY,
	SAW_MODEL_QUARANTINE_AT_KEY,
	SAW_MODEL_QUARANTINE_KEY,
	readSawModelBlob,
	seedSawModel
} from './saw-model-store';

/** A stand-in for the wasm core: records what it was handed, answers `verdict`. */
function fakeCore(verdict: (json: string | null) => SawModelLoad) {
	const seen: (string | null)[] = [];
	return {
		seen,
		loadSawModelJson: async (json: string | null) => {
			seen.push(json);
			return verdict(json);
		}
	};
}

const AT = new Date('2026-10-02T12:00:00.000Z');

describe('SAW model seed', () => {
	beforeEach(() => localStorage.clear());
	afterEach(() => vi.restoreAllMocks());

	it('absent: hands the core null and writes nothing', async () => {
		const core = fakeCore(() => ({ type: 'Absent' }));
		const outcome = await seedSawModel(core, () => AT);
		expect(outcome.type).toBe('Absent');
		expect(core.seen).toEqual([null]);
		expect(localStorage.getItem(SAW_MODEL_QUARANTINE_KEY)).toBeNull();
	});

	it('valid: unwraps the stored string and leaves the store alone', async () => {
		localStorage.setItem(SAW_MODEL_KEY, JSON.stringify('{"pairHistory":{}}'));
		const core = fakeCore(() => ({ type: 'Loaded' }));
		await seedSawModel(core, () => AT);
		expect(core.seen).toEqual(['{"pairHistory":{}}']);
		expect(localStorage.getItem(SAW_MODEL_KEY)).not.toBeNull();
		expect(localStorage.getItem(SAW_MODEL_QUARANTINE_KEY)).toBeNull();
	});

	it('corrupt: quarantines the raw text with a timestamp, clears the store, warns', async () => {
		const bad = '{"pairHistory":{"p::s":[{"drip":1.35,';
		localStorage.setItem(SAW_MODEL_KEY, JSON.stringify(bad));
		const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
		const core = fakeCore((json) => ({
			type: 'Corrupt',
			content: { raw: json ?? '', error: 'EOF while parsing' }
		}));

		const outcome = await seedSawModel(core, () => AT);

		expect(outcome.type).toBe('Corrupt');
		expect(localStorage.getItem(SAW_MODEL_QUARANTINE_KEY)).toBe(bad);
		expect(localStorage.getItem(SAW_MODEL_QUARANTINE_AT_KEY)).toBe(AT.toISOString());
		expect(localStorage.getItem(SAW_MODEL_KEY)).toBeNull();
		expect(warn).toHaveBeenCalledOnce();
		expect(String(warn.mock.calls[0][0])).toContain('quarantined');
	});

	it('newest capture wins, as in Decenza', async () => {
		localStorage.setItem(SAW_MODEL_QUARANTINE_KEY, 'older');
		localStorage.setItem(SAW_MODEL_KEY, JSON.stringify('{newer'));
		vi.spyOn(console, 'warn').mockImplementation(() => undefined);
		await seedSawModel(
			fakeCore((json) => ({
				type: 'Corrupt',
				content: { raw: json ?? '', error: 'x' }
			})),
			() => AT
		);
		expect(localStorage.getItem(SAW_MODEL_QUARANTINE_KEY)).toBe('{newer');
	});

	it('a damaged outer wrapper reaches the core as raw text, not as absent', () => {
		localStorage.setItem(SAW_MODEL_KEY, '"{\\"pairHist');
		expect(readSawModelBlob()).toBe('"{\\"pairHist');
	});
});
