/**
 * `static/theme-boot.js` paints the stored theme before the app bundle loads
 * (final cohort review 07 N11). It cannot import the settings store, so these
 * tests run the real file under jsdom and pin it to the store: the storage
 * key and shape (via a real `set('theme', …)`), `resolveTheme` and
 * `THEME_COLOR`.
 */
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getSettingsStore, THEME_COLOR, type ThemePref } from './store.svelte';

const script = fs.readFileSync(path.resolve('./static/theme-boot.js'), 'utf-8');

function stubPrefersLight(light: boolean): void {
	vi.stubGlobal(
		'matchMedia',
		vi.fn((query: string) => ({
			matches: query === '(prefers-color-scheme: light)' && light,
			media: query,
			addEventListener: () => {},
			removeEventListener: () => {}
		}))
	);
}

function runBoot(): { theme: string | null; color: string | null } {
	document.documentElement.setAttribute('data-theme', 'dark');
	const meta = document.querySelector('meta[name="theme-color"]');
	meta?.setAttribute('content', THEME_COLOR.dark);
	new Function(script)();
	return {
		theme: document.documentElement.getAttribute('data-theme'),
		color: meta?.getAttribute('content') ?? null
	};
}

beforeEach(() => {
	const meta = document.createElement('meta');
	meta.name = 'theme-color';
	document.head.appendChild(meta);
	localStorage.clear();
});

afterEach(() => {
	document.head.innerHTML = '';
	localStorage.clear();
	vi.unstubAllGlobals();
});

describe('theme-boot.js', () => {
	it('keeps dark with nothing stored', () => {
		stubPrefersLight(true);
		expect(runBoot()).toEqual({ theme: 'dark', color: THEME_COLOR.dark });
	});

	it.each<[ThemePref, boolean, 'dark' | 'light']>([
		['light', false, 'light'],
		['dark', true, 'dark'],
		['system', true, 'light'],
		['system', false, 'dark']
	])('reads what the store persisted: %s (OS light: %s) -> %s', (pref, osLight, expected) => {
		stubPrefersLight(osLight);
		getSettingsStore().set('theme', pref);
		expect(runBoot()).toEqual({ theme: expected, color: THEME_COLOR[expected] });
		getSettingsStore().reset();
	});

	it('reads a pre-unification flat blob', () => {
		stubPrefersLight(false);
		localStorage.setItem('crema.settings.v1', JSON.stringify({ theme: 'light' }));
		expect(runBoot().theme).toBe('light');
	});

	it('falls back to dark on unreadable storage', () => {
		stubPrefersLight(true);
		localStorage.setItem('crema.settings.v1', '{not json');
		expect(runBoot()).toEqual({ theme: 'dark', color: THEME_COLOR.dark });
	});

	it('the store keeps theme-color in sync on a live switch', () => {
		stubPrefersLight(false);
		const meta = document.querySelector('meta[name="theme-color"]');
		getSettingsStore().set('theme', 'light');
		expect(meta?.getAttribute('content')).toBe(THEME_COLOR.light);
		getSettingsStore().set('theme', 'dark');
		expect(meta?.getAttribute('content')).toBe(THEME_COLOR.dark);
	});

	it('uses the same colours as the store', () => {
		expect(script).toContain(`dark: '${THEME_COLOR.dark}'`);
		expect(script).toContain(`light: '${THEME_COLOR.light}'`);
	});
});
