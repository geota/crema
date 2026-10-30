/**
 * F15: `createCremaApp` pushes the settings to the core at start-up and
 * registers the settings store's `onReplaced` hook, so a later restore or
 * Restore defaults reaches the core at once. Driven through the real
 * `createCremaApp` with the core swapped for one that records what it is
 * handed — removing either line in `createCremaApp` fails this.
 */

import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { CommonSettings } from '#lib/core/crema-core.js';

const pushed: CommonSettings[] = [];
const recordingCore = new Proxy(
	{},
	{
		get: (_t, key) => {
			if (key === 'then') return undefined;
			if (key === 'applyCommonSettings')
				return async (json: string) => void pushed.push(JSON.parse(json) as CommonSettings);
			// An empty CoreOutput, also under `output` for the calls that wrap one.
			const empty = { events: [], commands: [] };
			return async () => ({ ...empty, output: empty });
		}
	}
);

vi.mock('#lib/core/index.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/core/index.js')>()),
	loadCore: async () => recordingCore
}));

beforeAll(async () => {
	await initTestWasm();
});

afterEach(async () => {
	const { getSettingsStore } = await import('#lib/settings/index.js');
	getSettingsStore().onReplaced = null;
});

describe('createCremaApp settings wiring (F15)', () => {
	it('pushes the settings at start-up and after every whole-bundle write', async () => {
		const { getSettingsStore } = await import('#lib/settings/index.js');
		const { createCremaApp } = await import('./app.svelte.js');
		const store = getSettingsStore();
		store.set('maxShotDurationS', 40);

		const app = await createCremaApp(null);
		expect(pushed).toHaveLength(1);
		expect(pushed[0].maxShotDurationS).toBe(40);

		store.replaceAll({ ...store.current, maxShotDurationS: 25 });
		expect(pushed.at(-1)?.maxShotDurationS).toBe(25);
		store.reset();
		expect(pushed.at(-1)?.maxShotDurationS).toBe(0);
		expect(pushed).toHaveLength(3);
		app.idleSleep.stop?.();
	});
});
