import { describe, expect, it } from 'vitest';
import { safeExternalUrl } from './safe-url';

// Review 05 P3-3: imported bean / roaster links must never reach an href
// unless they are http(s).
describe('safeExternalUrl', () => {
	it('keeps http and https links', () => {
		expect(safeExternalUrl('https://onyxcoffeelab.com/shop')).toBe('https://onyxcoffeelab.com/shop');
		expect(safeExternalUrl('  HTTP://example.com ')).toBe('http://example.com/');
	});
	it('adds https:// to a bare host instead of making a relative link', () => {
		expect(safeExternalUrl('onyxcoffeelab.com')).toBe('https://onyxcoffeelab.com/');
		expect(safeExternalUrl('www.example.com/coffee?x=1')).toBe('https://www.example.com/coffee?x=1');
		expect(safeExternalUrl('example.com:8443/a')).toBe('https://example.com:8443/a');
	});
	it.each([
		'javascript:alert(1)',
		'JavaScript:alert(document.cookie)',
		' javascript:alert(1)',
		'javascript://%0aalert(1)',
		'data:text/html,<script>alert(1)</script>',
		'vbscript:msgbox(1)',
		'blob:https://crema.maceiras.dev/abc',
		'mailto:a@b.c',
		'file:///etc/passwd',
		'',
		'   ',
		null,
		undefined
	])('drops %s', (raw) => {
		expect(safeExternalUrl(raw)).toBeNull();
	});
});
