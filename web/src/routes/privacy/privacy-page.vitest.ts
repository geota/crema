import { afterEach, describe, expect, it } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import PrivacyPage from './+page.svelte';

/**
 * Review 05 P2-2: the published privacy policy must name every third party
 * the app contacts. If a new service is added to the code, add it here and to
 * the page.
 */
describe('privacy policy page', () => {
	let component: ReturnType<typeof mount> | null = null;
	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function text(): string {
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(PrivacyPage, { target });
		flushSync();
		return target.textContent ?? '';
	}

	it.each([
		'decentespresso.com',
		'fonts.googleapis.com',
		'fonts.gstatic.com',
		't0.gstatic.com/faviconV2',
		'raw.githubusercontent.com',
		'fast.decentespresso.com',
		'api.github.com',
		'accounts.google.com',
		'ipinfo.io',
		'visualizer.coffee',
		'Cloudflare'
	])('names %s', (host) => {
		expect(text()).toContain(host);
	});

	it('says the Decent upload is automatic once linked and what it contains', () => {
		const t = text();
		expect(t).toMatch(/Linking turns on\s+"upload each shot"/);
		expect(t).toMatch(/tasting notes/);
		expect(t).toMatch(/serial number/);
		expect(t).toMatch(/password is not stored/);
	});

	// Final review 05 N-2 / N-5: Android captures, Drive revoke, the full erase,
	// and every field the Decent record carries.
	it('describes the Android Bluetooth captures and their limits', () => {
		const t = text();
		expect(t).not.toMatch(/IndexedDB, web only/);
		expect(t).toMatch(/10 most recent sessions/);
		expect(t).toMatch(/14 days/);
	});

	it('says disconnecting Drive revokes access on Android too', () => {
		expect(text()).toMatch(/asks Google to revoke Crema's\s+access, on the web and on Android/);
	});

	it('lists what Android Erase all data removes', () => {
		const t = text();
		expect(t).toMatch(/Bluetooth captures/);
		expect(t).toMatch(/sync settings and activity logs/);
		expect(t).toMatch(/no backup copy left behind/);
	});

	it('names the firmware version among the Decent upload fields', () => {
		expect(text()).toMatch(/serial number, model and firmware version/);
	});

	it('does not claim data never leaves the device', () => {
		expect(text()).not.toMatch(/This data never leaves your device/);
	});
});
