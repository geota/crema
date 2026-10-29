/**
 * The root layout's load must survive a wasm core that fails to load (review
 * 03 P3-7): Kit's root error page re-runs the root layout, so a rejection
 * there ends on Kit's unstyled fallback page instead of CoreLoadFailed.
 */
import { describe, expect, it, vi } from 'vitest';

vi.mock('#lib/core/index.js', () => ({
	loadCore: () => Promise.reject(new Error('WebAssembly.instantiate(): network error'))
}));

describe('root +layout.ts load', () => {
	it('resolves with the core error instead of rejecting', async () => {
		const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {});
		const mod = await import('../../routes/+layout.ts');
		// The load ignores its Kit event; call it bare.
		const data = await (mod.load as unknown as () => Promise<{ coreError: string | null }>)();
		expect(data.coreError).toContain('network error');
		expect(errorLog).toHaveBeenCalled();
	});
});
