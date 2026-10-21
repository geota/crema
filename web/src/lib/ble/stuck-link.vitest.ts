import { describe, expect, it } from 'vitest';
import { STUCK_LINK_HINT, STUCK_LINK_MISSES, stuckLinkHint } from './stuck-link';

/** The stuck-DE1 hint's threshold — mirrored by Android's `stuckLinkHint`. */
describe('stuckLinkHint', () => {
	it('never hints on a first connect', () => {
		expect(stuckLinkHint(false, 10)).toBeNull();
	});

	it('treats one miss after a connection as ordinary', () => {
		expect(stuckLinkHint(true, 1)).toBeNull();
	});

	it('names the stuck link after two misses following a connection', () => {
		expect(stuckLinkHint(true, STUCK_LINK_MISSES)).toBe(STUCK_LINK_HINT);
		expect(stuckLinkHint(true, 5)).toBe(STUCK_LINK_HINT);
	});
});
