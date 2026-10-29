/**
 * The web app manifest must be linked from the page (cohort review 2026-10-25,
 * report 03 P2-2): the PWA plugin's own link injection runs through a Vite
 * HTML hook SvelteKit never calls, so without the tag in `app.html` Chrome
 * and Android never offer "Install app". The plugin writes the manifest under
 * its default name, which the link must match.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

describe('web app manifest link', () => {
	it('app.html links the manifest the PWA plugin emits', () => {
		const html = read('../../app.html');
		expect(html).toMatch(
			/<link rel="manifest" href="%sveltekit\.assets%\/manifest\.webmanifest" \/>/
		);
	});

	it('the plugin keeps its default manifest file name', () => {
		const config = read('../../../vite.config.ts');
		expect(config).not.toMatch(/manifestFilename/);
		expect(config).toMatch(/manifest:\s*\{/);
	});
});
