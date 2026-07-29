import { describe, expect, it } from 'vitest';

import { getCremaUiState, refreshStopTargetsProjection } from './ui-state.svelte';

/**
 * The stop-condition UI renders ONLY from the core's projection, so the
 * projection reaching the snapshot is what makes the card exist at all.
 *
 * Regression cover for the bug shipped in `8a71b01d`: every refresh hook lived
 * on `CremaApp`'s setters, but the profile store pushes targets to the core
 * directly and bypasses them. Nothing called the refresh on a freshly loaded
 * page, `stopTargets` stayed `null`, and the card's `{#if rows.length > 0 ||
 * none}` gate rendered nothing — the whole card silently missing. The
 * typechecker was perfectly happy; only opening the page showed it.
 */
describe('refreshStopTargetsProjection', () => {
	/** The two fields the FFI returns as JSON, shaped like the real bridge. */
	const fakeCore = (p: Partial<Record<string, unknown>> = {}) => ({
		stopTargetsProjection: async () => ({
			armed: false,
			weight: null,
			volume: null,
			maxTime: null,
			weightConfigured: null,
			weightBlocked: null,
			...p
		})
	});

	it('lands a projection in the snapshot so the card can render', async () => {
		await refreshStopTargetsProjection(
			fakeCore({ weight: 36, maxTime: 80 }) as Parameters<
				typeof refreshStopTargetsProjection
			>[0]
		);
		const s = getCremaUiState().current.stopTargets;
		expect(s).not.toBeNull();
		expect(s?.weightG).toBe(36);
		expect(s?.maxTimeS).toBe(80);
	});

	it('reports "nothing will stop this shot" as a real state, not as absence', async () => {
		await refreshStopTargetsProjection(
			fakeCore() as Parameters<typeof refreshStopTargetsProjection>[0]
		);
		const s = getCremaUiState().current.stopTargets;
		// Non-null with every leg null: the card needs to tell this apart from
		// "the core hasn't reported yet", which is what renders nothing.
		expect(s).not.toBeNull();
		expect(s?.weightG).toBeNull();
		expect(s?.volumeMl).toBeNull();
		expect(s?.maxTimeS).toBeNull();
	});

	it('carries a blocked weight target and its reason', async () => {
		await refreshStopTargetsProjection(
			fakeCore({ weight: null, weightConfigured: 36, weightBlocked: 'no-scale' }) as Parameters<
				typeof refreshStopTargetsProjection
			>[0]
		);
		const s = getCremaUiState().current.stopTargets;
		expect(s?.weightG).toBeNull();
		expect(s?.weightConfiguredG).toBe(36);
		expect(s?.weightBlocked).toBe('no-scale');
	});
});
