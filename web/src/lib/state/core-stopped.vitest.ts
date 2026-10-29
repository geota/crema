/**
 * The shell's notice when the wasm core stops after too many panics (final
 * cohort review 2026-10-28, 01 P3-1): the facade calls `onCoreStopped`
 * listeners once, and the app pins a reload notice and logs the stop.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import { getToasts, dismissToast } from '#lib/components/shared/toast.svelte.js';

afterEach(() => {
	for (const t of getToasts()) dismissToast(t.id);
	vi.restoreAllMocks();
});

describe('CremaApp when the core stops', () => {
	it('pins one reload notice and logs the stop', async () => {
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		let stopped: (() => void) | undefined;
		// Every other core call resolves to an empty output — none runs here.
		const core = new Proxy(
			{
				onCoreStopped(listener: () => void) {
					stopped = listener;
				}
			},
			{
				get: (target, prop) =>
					prop in target ? Reflect.get(target, prop) : async () => ({ events: [], commands: [] })
			}
		);
		const app = new CremaApp(core as never, null);
		expect(stopped).toBeTypeOf('function');
		expect(getToasts()).toHaveLength(0);

		stopped!();

		const toasts = getToasts();
		expect(toasts).toHaveLength(1);
		expect(toasts[0].message).toContain('stopped its engine');
		expect(toasts[0].action?.label).toBe('Reload');
		const { eventLog } = (
			app as unknown as { state: { current: { eventLog: readonly { text: string }[] } } }
		).state.current;
		expect(eventLog.map((l) => l.text).join('\n')).toContain(
			'Core stopped after repeated internal errors'
		);
	});
});
