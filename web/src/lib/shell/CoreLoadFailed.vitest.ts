import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import CoreLoadFailed from './CoreLoadFailed.svelte';

describe('CoreLoadFailed (review 03 P3-7)', () => {
	let component: ReturnType<typeof mount> | null = null;
	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	it('names the failure, shows the cause and reloads on tap', () => {
		const onReload = vi.fn();
		const target = document.body.appendChild(document.createElement('div'));
		component = mount(CoreLoadFailed, {
			target,
			props: { message: 'WebAssembly.instantiate(): expected magic word', onReload }
		});
		flushSync();
		expect(target.querySelector('h1')?.textContent).toBe('Failed to load the core');
		expect(target.textContent).toContain('expected magic word');
		const button = [...target.querySelectorAll('button')].find((b) => b.textContent?.includes('Reload'));
		button?.click();
		expect(onReload).toHaveBeenCalledTimes(1);
	});
});
