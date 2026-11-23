/**
 * The grind before a shot and web's discards (review #554 S2, core #556): an
 * aborted shot's discard gives its grind back to the core, so the retry gets
 * it, and the discard's Undo claims it again; a cleaning run gives it back
 * too; a kept shot keeps it.
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getToasts, dismissToast } from '#lib/components/shared/toast.svelte.js';
import { getActiveShotStore } from '#lib/state/active-shot.svelte.js';
import { getHistoryStore } from './store.svelte';

const { commitShotCompletion } = await import('./shot-persistence');
type Event = Parameters<typeof commitShotCompletion>[0];
type Ctx = Parameters<typeof commitShotCompletion>[1];

const calls: string[] = [];

function complete(id: string, disposition: 'Record' | 'DiscardAborted' | 'SkipCleaning'): void {
	getActiveShotStore().set({
		bean: null,
		profileName: null,
		dose: 18,
		brewParams: null,
		grinderModel: null,
		source: 'live'
	} as never);
	const event = { content: { duration: 4_000, disposition } } as unknown as Event;
	commitShotCompletion(event, {
		snapshot: { activeProfileName: null },
		shotStartedAtMs: null,
		lastNotificationAtMs: null,
		sliceJsonl: async () => '',
		fireWebhook: vi.fn(),
		takeStoredShot: () => ({
			formatVersion: 3,
			id,
			completedAt: 1_700_000_000_000,
			metadata: { dose: 18 },
			record: { duration: 4_000, samples: [{ elapsed: 0, sample: { groupPressure: 9 } }] }
		}),
		shotGrind: {
			release: (shotId: string) => calls.push(`release ${shotId}`),
			claim: (shotId: string) => calls.push(`claim ${shotId}`)
		},
		runtime: null
	} as unknown as Ctx);
}

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	calls.length = 0;
	getHistoryStore().clearAllShots();
	getToasts().forEach((t) => dismissToast(t.id));
});

describe('the grind before a discarded shot', () => {
	it('an aborted discard gives it back, and its Undo claims it again', () => {
		complete('shot:1', 'DiscardAborted');
		expect(calls).toEqual(['release shot:1']);
		getToasts().at(-1)?.action?.run();
		expect(getHistoryStore().get('shot:1')).toBeDefined();
		expect(calls).toEqual(['release shot:1', 'claim shot:1']);
	});

	it('a cleaning run gives it back', () => {
		complete('shot:2', 'SkipCleaning');
		expect(calls).toEqual(['release shot:2']);
	});

	it('a kept shot keeps it', () => {
		complete('shot:3', 'Record');
		expect(calls).toEqual([]);
	});
});
