/**
 * The core's stop supervisor, as the app shell carries it out (`DESIGN.md`
 * §3.3): `StopNotHonoured` alerts the user, and `ReadState` reads the state
 * back.
 */
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { Effect } from 'effect';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { CremaCore, CoreOutput } from '#lib/core/index.js';

beforeAll(async () => {
	await initTestWasm();
});

async function app() {
	const core: CremaCore = await (await import('#lib/core/index.js')).loadCore();
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const runtime = {
		runPromiseExit: (effect: Effect.Effect<unknown, unknown>) => Effect.runPromiseExit(effect),
		runPromise: async () => undefined,
		runFork: () => undefined
	};
	const app = new CremaApp(core, runtime as never);
	return app as unknown as { applyCoreOutput(out: CoreOutput): void };
}

describe('the stop supervisor in the shell', () => {
	it('alerts the user when the machine ignores the stop', async () => {
		const { toast } = await import('#lib/components/shared/toast.svelte.js');
		const error = vi.spyOn(toast, 'error');
		const a = await app();
		a.applyCoreOutput({
			events: [
				{
					type: 'StopNotHonoured',
					content: { message: "Crema can't stop the machine. Press Stop on the machine." }
				}
			],
			commands: []
		} as CoreOutput);
		expect(error).toHaveBeenCalledWith(
			"Crema can't stop the machine. Press Stop on the machine.",
			15_000
		);
	});

	it('reads the state back on ReadState', async () => {
		const { De1Manager } = await import('#lib/ble/index.js');
		const read = vi.spyOn(De1Manager.prototype, 'readState').mockResolvedValue(true);
		const a = await app();
		a.applyCoreOutput({ events: [], commands: [{ type: 'ReadState' }] } as CoreOutput);
		await Promise.resolve();
		expect(read).toHaveBeenCalledTimes(1);
	});
});
