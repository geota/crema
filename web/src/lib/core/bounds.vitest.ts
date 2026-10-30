/**
 * F8: the steppers and editors read the core's one bounds table
 * (`de1_domain::bounds`). The values are pinned by the core tests; this checks
 * the bridge and that the components hold no range literals of their own.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { initTestWasm } from '#lib/testing/test-init.js';
import { brewDefaultsBounds, profileEditorBounds, quickControlBounds } from './bounds.js';

beforeAll(async () => {
	await initTestWasm();
});

const src = (path: string): string => readFileSync(resolve(process.cwd(), 'src/lib', path), 'utf8');

describe('core bounds table (F8)', () => {
	it('the tables come from the core', () => {
		const q = quickControlBounds();
		expect([q.doseG.min, q.doseG.max]).toEqual([5, 30]);
		expect([q.flushTimeS.min, q.flushTimeS.max]).toEqual([1, 20]);
		expect([q.steamTempC.min, q.steamTempC.max]).toEqual([135, 170]);
		expect([brewDefaultsBounds().preinfuseS.min, brewDefaultsBounds().preinfuseS.max]).toEqual([0, 60]);
		const e = profileEditorBounds();
		expect([e.segmentTempC.max, e.pressureBar.max, e.yieldG.max, e.tankTempC.max]).toEqual([100, 12, 2000, 45]);
	});

	it('steppers and editors read the core tables, with no range literals left', () => {
		const literal = /(?:min|max)=\{-?[0-9.]+\}/g;
		for (const path of [
			'components/brew/DoseGrindStepper.svelte',
			'components/brew/YieldRatioStepper.svelte',
			'components/brew/SteamStepper.svelte',
			'components/brew/PreinfFlushStepper.svelte',
			'components/profiles/SegmentRow.svelte',
			'components/settings/sections/BrewDefaultsSection.svelte'
		]) {
			expect(src(path).match(literal), path).toBeNull();
		}
		// The profile editor's only literal is the pre-infusion step count's 0.
		expect(src('components/profiles/ProfileEditor.svelte').match(literal)).toEqual(['min={0}']);
		expect(src('components/brew/brew-params.svelte.ts')).toContain('quickControlBounds()');
	});
});
