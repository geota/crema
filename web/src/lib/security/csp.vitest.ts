import { describe, expect, it } from 'vitest';
import { CSP_DIRECTIVES, GIS_CLIENT_SCRIPT } from './csp';

// Review 05 P3-4: script-src must not allowlist the whole accounts.google.com
// host (JSONP-style endpoints there defeat a host allowlist).
describe('CSP directives', () => {
	const scriptSrc = (CSP_DIRECTIVES['script-src'] ?? []) as string[];

	it('allows the GIS client only by its exact path', () => {
		expect(GIS_CLIENT_SCRIPT).toBe('https://accounts.google.com/gsi/client');
		expect(scriptSrc).toContain(GIS_CLIENT_SCRIPT);
		expect(scriptSrc).not.toContain('https://accounts.google.com');
		expect(scriptSrc.filter((s) => s.includes('accounts.google.com'))).toEqual([GIS_CLIENT_SCRIPT]);
	});

	it('keeps script-src free of unsafe-inline / unsafe-eval / wildcard schemes', () => {
		for (const bad of ['unsafe-inline', 'unsafe-eval', 'https:', 'http:', '*', 'data:', 'blob:']) {
			expect(scriptSrc).not.toContain(bad);
		}
	});
});
