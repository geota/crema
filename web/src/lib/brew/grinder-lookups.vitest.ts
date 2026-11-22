/**
 * Brew's history lookups are the core's (REVIEW-CORE M1): the picker rows
 * (`grinder_pick_rows`) and the rows' last grinds (`last_grinds_from_rows`)
 * match an old shot by name through the catalogue's aliases, as selecting
 * the bag does. Before, web compared names for equality, so a shot stamped
 * "Niche" said "Nothing saved" for the Niche Zero while selecting the bag
 * loaded its grind.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { blankBean, getBeanStore } from '#lib/bean/index.js';
import { lastGrindsFromRows, newestShotGrind } from '#lib/bean/grinder.js';
import { grinderRowViews } from './grind-view';

const beans = getBeanStore();

beforeEach(() => {
	localStorage.clear();
	beans.clearAll();
});

describe('Brew grinder lookups through the core', () => {
	it('a shot stamped "Niche" counts as the Niche Zero\'s last shot in the picker', () => {
		const niche = beans.addGrinder('', 'niche-zero')!;
		const bag = { ...blankBean('bean:a'), name: 'Guji', grinds: [] };
		const last = [{ grinder: 'Niche', grind: '15', completedAt: 1 }];
		const [row] = grinderRowViews([niche], bag, last);
		expect(row.line).toBe('Nothing saved · last shot 15');
		expect(newestShotGrind(last, niche)?.grind).toBe('15');
	});

	it('words saved, starred and no-bag rows', () => {
		const niche = beans.addGrinder('', 'niche-zero')!;
		const hand = beans.addGrinder('Hand')!;
		const bag = {
			...blankBean('bean:a'),
			name: 'Guji',
			grinds: [{ grinderId: niche.id, grinderName: niche.name, grind: '14', starred: true }]
		};
		const [n, h] = grinderRowViews([niche, hand], bag, []);
		expect(n).toMatchObject({ id: niche.id, starred: true, line: "This bag's grinder · Saved 14" });
		expect(h).toMatchObject({ id: hand.id, starred: false, line: 'Nothing saved' });
		const [none] = grinderRowViews([hand], null, []);
		expect(none.line).toBe('0–20');
	});

	it('groups shot rows the history\'s way: newest per grinder id, older rows by name; an old number reads as text', () => {
		const rows = [
			{ id: 's1', completedAt: 1, grinderId: 'grinder:a', grinderModel: 'A', metadata: { grinderSetting: '10' } },
			{ id: 's2', completedAt: 2, grinderId: 'grinder:a', grinderModel: 'A', metadata: { grinderSetting: ' 11 ' } },
			{ id: 's3', completedAt: 3, grinderId: null, grinderModel: 'Niche', metadata: { grinderSetting: 15 } },
			{ id: 's4', completedAt: 4, grinderId: null, grinderModel: ' ', metadata: { grinderSetting: '9' } },
			{ id: 's5', completedAt: 5, grinderId: 'grinder:b', grinderModel: 'B', metadata: { grinderSetting: '' } }
		];
		expect(lastGrindsFromRows(rows)).toEqual([
			{ grinderId: null, grinder: 'Niche', grind: '15', completedAt: 3 },
			{ grinderId: 'grinder:a', grinder: 'A', grind: '11', completedAt: 2 }
		]);
	});
});
