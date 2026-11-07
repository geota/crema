import { beforeAll, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { bagStock, emptyBagBannerText, lowStockHint, showsEmptyBagBanner } from './stock';

beforeAll(async () => {
	await initTestWasm();
});

describe('bagStock', () => {
	it('reads an unsized bag at 0 as untracked', () => {
		expect(bagStock({ remaining: 0, bagSize: 0 }).type).toBe('Untracked');
	});

	it('reads a snapshot with no weights as untracked', () => {
		expect(bagStock({ remaining: null, bagSize: null }).type).toBe('Untracked');
	});

	it('reads a sized bag at 0 as empty', () => {
		expect(bagStock({ remaining: 0, bagSize: 250 }).type).toBe('Empty');
	});

	it('reads a bag with brews to spare as ok', () => {
		expect(bagStock({ remaining: 100, bagSize: 250 }, [18]).type).toBe('Ok');
	});

	it('counts low in the bag’s own doses', () => {
		expect(bagStock({ remaining: 45, bagSize: 250 }, [18]).type).toBe('Ok');
		expect(bagStock({ remaining: 45, bagSize: 250 }, [30])).toEqual({
			type: 'Low',
			content: { brews: 1 }
		});
	});
});

describe('lowStockHint', () => {
	it('stays quiet unless the bag is low', () => {
		expect(lowStockHint({ type: 'Ok' })).toBeNull();
		expect(lowStockHint({ type: 'Empty' })).toBeNull();
		expect(lowStockHint({ type: 'Untracked' })).toBeNull();
	});

	it('counts the brews left', () => {
		expect(lowStockHint({ type: 'Low', content: { brews: 2 } })).toBe('2 brews left — reorder?');
		expect(lowStockHint({ type: 'Low', content: { brews: 1 } })).toBe('1 brew left — reorder?');
	});

	it('says so when less than a dose remains', () => {
		expect(lowStockHint({ type: 'Low', content: { brews: 0 } })).toBe(
			'Less than a brew left — reorder?'
		);
	});
});

describe('showsEmptyBagBanner', () => {
	it('shows on an empty bag between shots', () => {
		expect(showsEmptyBagBanner({ type: 'Empty' }, false)).toBe(true);
	});

	it('makes way during a running shot', () => {
		expect(showsEmptyBagBanner({ type: 'Empty' }, true)).toBe(false);
	});

	it('never shows on an untracked bag', () => {
		expect(showsEmptyBagBanner({ type: 'Untracked' }, false)).toBe(false);
	});

	it('never shows on a low or ok bag', () => {
		expect(showsEmptyBagBanner({ type: 'Low', content: { brews: 0 } }, false)).toBe(false);
		expect(showsEmptyBagBanner({ type: 'Ok' }, false)).toBe(false);
	});
});

describe('emptyBagBannerText', () => {
	it('names the bag', () => {
		expect(emptyBagBannerText('Gesha')).toBe('Gesha is at 0 g. Shots still log to it.');
	});

	it('covers a bag with no name', () => {
		expect(emptyBagBannerText('  ')).toBe('This bag is at 0 g. Shots still log to it.');
	});
});
