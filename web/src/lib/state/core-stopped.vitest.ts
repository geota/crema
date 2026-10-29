/**
 * The shell's notice when the wasm core stops after too many panics (final
 * cohort review 2026-10-28, 01 P3-1): the facade calls `onCoreStopped`
 * listeners once, and the app pins a reload notice and logs the stop. The
 * stop notice replaces the engine-reset notice and is assertive (round-2
 * verification V3 #7, V2 P3-1), and the log line carries the stop's context
 * (V2 P3-2).
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import { getToasts, dismissToast } from '#lib/components/shared/toast.svelte.js';

afterEach(() => {
	for (const t of getToasts()) dismissToast(t.id);
	vi.restoreAllMocks();
});

async function makeApp() {
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	let stopped: ((reason: string) => void) | undefined;
	// Every other core call resolves to an empty output — none runs here.
	const core = new Proxy(
		{
			onCoreStopped(listener: (reason: string) => void) {
				stopped = listener;
			}
		},
		{
			get: (target, prop) =>
				prop in target ? Reflect.get(target, prop) : async () => ({ events: [], commands: [] })
		}
	);
	const app = new CremaApp(core as never, null);
	const eventLog = () =>
		(app as unknown as { state: { current: { eventLog: readonly { text: string }[] } } }).state.current
			.eventLog;
	return { app, stop: (reason: string) => stopped!(reason), hasListener: () => stopped !== undefined, eventLog };
}

describe('CremaApp when the core stops', () => {
	it('pins one assertive reload notice and logs the stop with its context', async () => {
		const t = await makeApp();
		expect(t.hasListener()).toBe(true);
		expect(getToasts()).toHaveLength(0);

		t.stop('crema core stopped after 5 resets: panicked at frames.rs:42');

		const toasts = getToasts();
		expect(toasts).toHaveLength(1);
		expect(toasts[0].message).toContain('stopped its engine');
		expect(toasts[0].action?.label).toBe('Reload');
		expect(toasts[0].kind).toBe('error');
		const log = t.eventLog().map((l) => l.text).join('\n');
		expect(log).toContain('Core stopped after repeated internal errors');
		expect(log).toContain('panicked at frames.rs:42');
	});

	it('replaces a pinned engine-reset notice instead of stacking a second one', async () => {
		const t = await makeApp();
		t.app.coreResetNotice.onReset();
		expect(getToasts().map((x) => x.message)).toEqual([
			expect.stringContaining('restarted its engine')
		]);

		t.stop('crema core stopped');

		const toasts = getToasts();
		expect(toasts).toHaveLength(1);
		expect(toasts[0].message).toContain('stopped its engine');
		// A reset reported after the stop does not bring the old notice back.
		t.app.coreResetNotice.onReset();
		expect(getToasts()).toHaveLength(1);
	});

	it('shows the reset notice again after the user closed it with ×', async () => {
		const t = await makeApp();
		t.app.coreResetNotice.onReset();
		dismissToast(getToasts()[0].id); // the × button
		expect(getToasts()).toHaveLength(0);
		t.app.coreResetNotice.onReset();
		expect(getToasts()).toHaveLength(1);
		expect(getToasts()[0].message).toContain('restarted its engine');
	});
});
