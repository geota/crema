/**
 * Follow mode in words (spec grinder-ble §5.2, §5.4): what the Grind cell and
 * the header lines show for each of the core's follow states, and when the
 * stale time starts.
 */
import { describe, expect, it } from 'vitest';
import { FollowStatus, type GrinderFollowView } from '#lib/core/crema-core.js';
import { followDisplay, GrinderFollowStore } from './follow.svelte';

const shown = (view: GrinderFollowView, over: Partial<Parameters<typeof followDisplay>[0]> = {}) =>
	followDisplay({
		view,
		staleSince: null,
		liveValue: null,
		variableSpeed: false,
		nameOf: (id) => (id === 'g:motto' ? 'Motto 80' : null),
		...over
	});

describe('followDisplay', () => {
	it('following: the steady value, from grinder, and the speed for a variable-speed grinder', () => {
		const view = { status: FollowStatus.Following, grinder_id: 'g:motto', grind: '480', moving: false, grind_rpm: 700 };
		expect(shown(view)).toMatchObject({ following: true, value: '480', moving: false, source: 'from grinder' });
		expect(shown(view, { variableSpeed: true }).source).toBe('from grinder · 700 rpm');
	});

	it('the collar moving: the live value, greyed', () => {
		const view = { status: FollowStatus.Following, grinder_id: 'g:motto', grind: '480', moving: true };
		expect(shown(view, { liveValue: '472' })).toMatchObject({ value: '472', moving: true });
	});

	it('waiting for a steady value still hides the stepper', () => {
		const d = shown({ status: FollowStatus.Waiting, grinder_id: 'g:motto' }, { liveValue: '480' });
		expect(d).toMatchObject({ following: true, value: '480' });
	});

	it('paused, stale, suggest and off', () => {
		expect(shown({ status: FollowStatus.Paused, grinder_id: 'g:motto' })).toMatchObject({
			following: false,
			note: 'Following paused',
			canResume: true
		});
		const stale = shown({ status: FollowStatus.Stale, grinder_id: 'g:motto', grind: '480' }, { staleSince: Date.UTC(2026, 0, 1, 12, 3) });
		expect(stale.following).toBe(false);
		expect(stale.note).toMatch(/^Motto 80 offline · last seen /);
		expect(shown({ status: FollowStatus.Suggest, grinder_id: 'g:motto' }).suggest).toEqual({
			grinderId: 'g:motto',
			name: 'Motto 80'
		});
		// A suggestion for a record that isn't live is no suggestion.
		expect(shown({ status: FollowStatus.Suggest, grinder_id: 'g:gone' }).suggest).toBeNull();
		expect(shown({ status: FollowStatus.Off })).toMatchObject({
			following: false,
			note: null,
			suggest: null,
			source: null
		});
	});
});

describe('GrinderFollowStore', () => {
	it('marks the time the linked grinder went stale, once, and clears it when it comes back', () => {
		const s = new GrinderFollowStore();
		s.update({ status: FollowStatus.Stale }, null, 1_000);
		s.update({ status: FollowStatus.Stale, grind: '480' }, null, 5_000);
		expect(s.staleSince).toBe(1_000);
		s.update({ status: FollowStatus.Following, grind: '480' }, 'g:motto', 9_000);
		expect(s.staleSince).toBeNull();
		expect(s.following).toBe(true);
		expect(s.followedGrinderId).toBe('g:motto');
	});
});
