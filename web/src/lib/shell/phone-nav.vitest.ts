import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { goto } from '$app/navigation';
import { page } from '$app/state';
import { selectedShot } from '#lib/history/index.js';
import {
	leaveBean,
	leaveSection,
	leaveShot,
	openBean,
	openSection,
	openShot,
	phoneBeanId,
	phoneShotId
} from './phone-nav';
import { phoneLayout } from './phone.svelte.js';

vi.mock('$app/navigation', () => ({ goto: vi.fn(() => Promise.resolve()) }));
vi.mock('./phone.svelte.js', () => ({ phoneLayout: { current: true } }));

const gotoMock = vi.mocked(goto);

function setWidth(phone: boolean): void {
	(phoneLayout as { current: boolean }).current = phone;
}

describe('phone list → detail navigation', () => {
	let back: ReturnType<typeof vi.spyOn>;

	beforeEach(() => {
		gotoMock.mockClear();
		back = vi.spyOn(history, 'back').mockImplementation(() => {});
		setWidth(true);
		page.url = new URL('http://localhost/history');
		page.state = {};
	});

	afterEach(() => {
		back.mockRestore();
	});

	describe('Beans', () => {
		beforeEach(() => {
			page.url = new URL('http://localhost/beans?tab=bags&roaster=r1');
		});

		it('pushes an entry naming the bag, not a shallow one, keeping the list URL', () => {
			openBean('bean:1');
			expect(gotoMock).toHaveBeenCalledExactlyOnceWith('http://localhost/beans?tab=bags&roaster=r1', {
				state: { beanDetail: 'bean:1' },
				persistState: true,
				reset: false
			});
		});

		it('never pushes above 600 px, and no entry shows a detail there', () => {
			setWidth(false);
			page.state = { beanDetail: 'bean:1' };
			openBean('bean:1');
			expect(gotoMock).not.toHaveBeenCalled();
			expect(phoneBeanId()).toBeNull();
		});

		it('steps back from a pushed entry', () => {
			page.state = { beanDetail: 'bean:1' };
			expect(phoneBeanId()).toBe('bean:1');
			leaveBean();
			expect(back).toHaveBeenCalledOnce();
		});
	});

	describe('History', () => {
		it('pushes an entry naming the shot, not a shallow one at phone width', () => {
			openShot('shot:1');
			expect(gotoMock).toHaveBeenCalledExactlyOnceWith('http://localhost/history', {
				state: { historyShot: 'shot:1' },
				persistState: true,
				reset: false
			});
		});

		it('never pushes above 600 px', () => {
			setWidth(false);
			openShot('shot:1');
			expect(gotoMock).not.toHaveBeenCalled();
		});

		it('keeps a ?bean= filter in the pushed entry', () => {
			page.url = new URL('http://localhost/history?bean=b1');
			openShot('shot:1');
			expect(gotoMock.mock.calls[0]?.[0]).toBe('http://localhost/history?bean=b1');
		});

		it('names the shot from the entry, then a deep link, only at phone width', () => {
			expect(phoneShotId()).toBeNull();
			page.url = new URL('http://localhost/history?shot=deep');
			expect(phoneShotId()).toBe('deep');
			page.state = { historyShot: 'tapped' };
			expect(phoneShotId()).toBe('tapped');
			setWidth(false);
			expect(phoneShotId()).toBeNull();
		});

		it('steps back from an entry the list pushed', () => {
			page.state = { historyShot: 'shot:1' };
			leaveShot();
			expect(back).toHaveBeenCalledOnce();
			expect(gotoMock).not.toHaveBeenCalled();
		});

		it('replaces a deep link with the list, dropping only ?shot=', () => {
			page.url = new URL('http://localhost/history?bean=b1&shot=deep');
			leaveShot();
			expect(back).not.toHaveBeenCalled();
			expect(gotoMock).toHaveBeenCalledOnce();
			const [url, opts] = gotoMock.mock.calls[0] ?? [];
			expect(String(url)).toBe('http://localhost/history?bean=b1');
			expect(opts).toEqual({ replace: true, reset: false });
		});
	});

	describe('Settings', () => {
		it('pushes the section hash, tagged, at phone width', () => {
			openSection('water');
			expect(gotoMock).toHaveBeenCalledExactlyOnceWith('/settings#water', {
				state: { fromSettingsList: true },
				persistState: true,
				reset: false
			});
		});

		it('leaves the URL alone above 600 px', () => {
			setWidth(false);
			openSection('water');
			expect(gotoMock).not.toHaveBeenCalled();
		});

		it('steps back from a section the list opened', () => {
			page.state = { fromSettingsList: true };
			leaveSection();
			expect(back).toHaveBeenCalledOnce();
			expect(gotoMock).not.toHaveBeenCalled();
		});

		it('replaces a deep-linked section with the list', () => {
			leaveSection();
			expect(back).not.toHaveBeenCalled();
			expect(gotoMock).toHaveBeenCalledExactlyOnceWith('/settings', { replace: true, reset: false });
		});
	});
});

describe('selectedShot under the phone detail screen', () => {
	const a = { id: 'a' };
	const b = { id: 'b' };
	const lookup = (id: string) => [a, b].find((s) => s.id === id);

	it('shows the entry shot as an explicit pick, even when the filter hides it', () => {
		const scope = 'scope';
		expect(selectedShot([a], 'b', { pin: { id: 'b', scope }, scope, lookup })).toBe(b);
	});

	it('still falls back to the first shot once the entry shot is gone', () => {
		const scope = 'scope';
		expect(selectedShot([a], 'gone', { pin: { id: 'gone', scope }, scope, lookup })).toBe(a);
	});

	it('leaves a browsed pick as it was: a filter that hides it hands over', () => {
		expect(selectedShot([a], 'b', { pin: null, scope: 'scope', lookup })).toBe(a);
	});
});
