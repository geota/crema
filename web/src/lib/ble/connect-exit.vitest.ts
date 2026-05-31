/**
 * `$lib/ble/connect-exit.vitest` — the BLE connect-`Exit` interpreter
 * (architecture review #4). This was copied verbatim into `De1Manager` and
 * `ScaleManager`; extracting it both dedupes the densest manager duplicate and
 * gives the connect FAILURE path its first automated coverage (the managers'
 * surrounding lifecycle is browser-only). The two step-tagged errors share the
 * `{ step, cause }` shape, so one helper serves both.
 */

import { Cause, Exit } from 'effect';
import { describe, expect, it } from 'vitest';
import { parseConnectExit } from './connect-exit.ts';
import { De1ConnectStepFailed, ScaleConnectStepFailed } from '../effect/errors.ts';

describe('parseConnectExit', () => {
	it('a success Exit → { ok: true }', () => {
		expect(parseConnectExit(Exit.succeed(undefined))).toEqual({ ok: true });
	});

	it('a step-tagged DE1 failure → { ok: false, step, cause }', () => {
		const exit = Exit.fail(new De1ConnectStepFailed({ step: 'subscribe', cause: 'boom' }));
		expect(parseConnectExit(exit)).toEqual({ ok: false, step: 'subscribe', cause: 'boom' });
	});

	it('a step-tagged scale failure carries its step + cause through', () => {
		const cause = new Error('gatt lost');
		const exit = Exit.fail(new ScaleConnectStepFailed({ step: 'identify', cause }));
		expect(parseConnectExit(exit)).toEqual({ ok: false, step: 'identify', cause });
	});

	it('a defect (die) falls back to step "connect" + the whole cause', () => {
		const exit = Exit.die('kaboom');
		const out = parseConnectExit(exit);
		expect(out.ok).toBe(false);
		if (!out.ok) {
			expect(out.step).toBe('connect');
			// The fallback hands back the raw Cause (a defect, not a tagged failure).
			expect(Cause.isCause(out.cause)).toBe(true);
		}
	});
});
