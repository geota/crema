import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { railCtaIcon } from '#lib/components/rail-status.js';
import { ICON_NAMES } from './Icon.svelte';

/**
 * `<Icon>` draws nothing for a name that isn't in its map, and only warns in
 * dev, so a missed name is a blank button. Names are mostly built at runtime
 * (`'ph ph-' + icon`), so this reads them from the source.
 */
const SRC = path.resolve('src');
const WEIGHTS = new Set(['fill', 'duotone', 'bold', 'thin', 'light']);

function sources(): string[] {
	return fs
		.readdirSync(SRC, { recursive: true, encoding: 'utf-8' })
		.filter((f) => /\.(svelte|ts)$/.test(f) && !/\.(vitest|test)\.ts$/.test(f))
		.filter((f) => !f.endsWith(path.join('icons', 'Icon.svelte')))
		.map((f) => fs.readFileSync(path.join(SRC, f), 'utf-8'));
}

/** Every icon name a source file spells out, in the forms the app uses. */
function namesIn(source: string): string[] {
	const names: string[] = [];
	const add = (re: RegExp) => {
		for (const m of source.matchAll(re)) names.push(m[1]);
	};
	// 'ph ph-star', 'ph-fill ph-star', 'ph-eye'
	add(/\bph(?:-[a-z]+)? ph-([a-z0-9-]+)/g);
	add(/['"`]ph-([a-z0-9-]+)['"`]/g);
	// icon="drop", icon: 'drop', phoneIcon: 'sliders-horizontal'
	add(/\b\w*[iI]con\s*[:=]\s*['"]([a-z][a-z0-9-]*)['"]/g);
	// <Icon name="x" /> and <Icon name={on ? 'a' : 'b'} />, and
	// 'ph-fill ph-' + (on ? 'a' : 'b')
	for (const m of source.matchAll(/<Icon\b[^>]*\bname=(?:"[^"]*"|\{[^}]*\})|ph-['"`] \+ \([^)]*\)/g)) {
		for (const q of m[0].matchAll(/['"]([a-z][a-z0-9-]*)['"]/g)) names.push(q[1]);
	}
	return names.filter((n) => !WEIGHTS.has(n));
}

describe('Icon names', () => {
	const used = new Set(sources().flatMap(namesIn));

	it('finds the names the source builds at runtime', () => {
		for (const name of ['stop', 'arrows-out', 'sliders-horizontal', 'check-square']) {
			expect(used).toContain(name);
		}
	});

	it('maps every name the source uses', () => {
		const known = new Set(ICON_NAMES);
		expect([...used].filter((n) => !known.has(n)).sort()).toEqual([]);
	});

	it('maps the rail badge, which a helper picks', () => {
		const statuses = ['connected', 'connecting', 'reconnecting', 'disconnected'] as const;
		for (const status of statuses) expect(ICON_NAMES).toContain(railCtaIcon(status));
	});
});
