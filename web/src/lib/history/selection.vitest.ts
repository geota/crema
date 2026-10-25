import { describe, expect, it } from 'vitest';
import { selectedShot } from './selection';

const a = { id: 'a' };
const b = { id: 'b' };
const c = { id: 'c' };

describe('selectedShot', () => {
	it('keeps the picked shot while the filtered list still shows it', () => {
		expect(selectedShot([a, b, c], 'b')).toBe(b);
	});

	it('falls back to the first filtered shot when the pick is filtered out', () => {
		// A search for "mia" hid shot c; the pane must not keep showing it.
		expect(selectedShot([a, b], 'c')).toBe(a);
	});

	it('shows the first filtered shot when nothing is picked', () => {
		expect(selectedShot([b, c], null)).toBe(b);
	});

	it('shows nothing when the filter matches nothing', () => {
		expect(selectedShot([], 'a')).toBeUndefined();
	});
});
