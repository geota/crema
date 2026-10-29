import { describe, expect, it } from 'vitest';

import type { Event } from '#lib/core/crema-core.js';
import { applyEvent, INITIAL_SNAPSHOT } from './ui-state.svelte';

/**
 * A `ScaleConfig` event exactly as the core serialises it: every absent
 * `Option` is `null`, not missing, although the generated type says `?:`.
 */
function scaleConfig(content: Record<string, unknown>): Event {
	return {
		type: 'ScaleConfig',
		content: {
			anti_mistouch: null,
			active_mode: null,
			enabled_modes: null,
			serial: null,
			firmware: null,
			...content
		}
	} as unknown as Event;
}

describe('scale config log (round-3 clean check C1 P3-2)', () => {
	it('logs the active mode for a settings frame, not "anti-mistouch=null"', () => {
		const s = applyEvent(INITIAL_SNAPSHOT, scaleConfig({ active_mode: 2, enabled_modes: 7 }));
		expect(s.eventLog[0]?.text).toBe('Scale config: active mode=2, enabled modes=7');
		expect(s.scaleActiveMode).toBe(2);
	});

	it('logs anti-mistouch with only the fields the serial frame carried', () => {
		const s = applyEvent(INITIAL_SNAPSHOT, scaleConfig({ anti_mistouch: true, firmware: '1.4.1' }));
		expect(s.eventLog[0]?.text).toBe('Scale config: anti-mistouch=true, fw=1.4.1');
		expect(s.scaleAntiMistouch).toBe(true);
	});

	it('logs nothing and keeps earlier values for an all-null frame', () => {
		const before = applyEvent(INITIAL_SNAPSHOT, scaleConfig({ active_mode: 1, enabled_modes: 3 }));
		const after = applyEvent(before, scaleConfig({}));
		expect(after.eventLog.length).toBe(before.eventLog.length);
		expect(after.scaleActiveMode).toBe(1);
	});
});
