import { describe, expect, it } from 'vitest';

import { blankProfile } from '#lib/profiles/model.js';
import { getCremaUiState } from './ui-state.svelte';

/**
 * The stop targets come from the core's desired profile (DESIGN.md §3.10):
 * the shell pushes the profile and its Quick Controls, and nothing else.
 *
 * #314: dialling the yield to 40 g on a 36 g profile, then Reset or another
 * profile, left the weight stop at 40 g, because Reset never pushed the dial
 * and only the stepper's write did.
 */
describe('stop targets from the desired profile', () => {
	async function makeApp() {
		const { loadCore } = await import('#lib/core/index.js');
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const core = await loadCore();
		core.reset();
		await core.setDesiredProfile(null, {}, 0);
		return new CremaApp(core, null);
	}
	const configured = () => getCremaUiState().current.stopTargets?.weightConfiguredG;
	const dotOff = () => getCremaUiState().current.stopTargets?.weightTargetDisabled;
	const p36 = { ...blankProfile(), id: 'p36', yieldOut: 36 };
	const p30 = { ...blankProfile(), id: 'p30', yieldOut: 30 };

	it('a dialled yield, Reset and another profile each move the weight stop (#314)', async () => {
		const app = await makeApp();
		await app.setDesiredProfile(p36, { yield: 40 });
		expect(configured()).toBe(40);
		await app.setDesiredProfile(p36, {});
		expect(configured()).toBe(36);
		await app.setDesiredProfile(p30);
		expect(configured()).toBe(30);
	});

	it('the yield dot reads the core, which turns it back on for another profile', async () => {
		const app = await makeApp();
		await app.setDesiredProfile(p36);
		expect(dotOff()).toBe(false);
		await app.applyWeightTargetDisabled(true);
		expect(dotOff()).toBe(true);
		await app.setDesiredProfile(p36, { yield: 38 });
		expect(dotOff()).toBe(true);
		await app.setDesiredProfile(p30);
		expect(dotOff()).toBe(false);
	});

	it('a profile with no yield of its own stops at the dialled yield (#318)', async () => {
		const app = await makeApp();
		await app.setDesiredProfile({ ...blankProfile(), id: 'none', yieldOut: 0 }, { yield: 36 });
		expect(configured()).toBe(36);
		expect(dotOff()).toBe(false);
	});
});
