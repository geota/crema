/**
 * Phosphor icon-font subsetter.
 *
 * The `@phosphor-icons/web` package ships the *entire* icon set — ~1530 icons
 * per weight, three weights (regular / duotone / fill) — as ~440 KiB of woff2
 * plus ~130 KiB of CSS that maps every icon name to a codepoint. The app uses
 * fewer than 10 % of them. This script scans the source for the icons actually
 * referenced and emits a subset: trimmed woff2 fonts (only the used glyphs) and
 * a trimmed `phosphor.css` (only the used `.ph-*` rules), into the gitignored
 * `src/lib/icons/` (same codegen convention as `src/lib/wasm/`).
 *
 * It runs automatically from a Vite `buildStart` hook (see `vite.config.ts`) in
 * both `dev` and `build`, and can be run by hand with `pnpm icons`. A
 * fingerprint of the used-icon set short-circuits regeneration so warm dev
 * restarts are a no-op.
 *
 * ## Why scanning is safe here
 *
 * Most icons appear as literal classes — `<i class="ph ph-coffee">` — which a
 * `ph-<name>` scan catches directly. But several components take an icon *name*
 * as a prop and build the class at runtime (`ph-duotone ph-${icon}` in
 * `ModeChip` / `ModeHeadStatus`, the sort-pill / split-button icons, the route
 * data tables). Those names never appear as `ph-…` in source — only as bare
 * string literals (`icon: 'cloud'`, `'sparkle'`, `'bluetooth'`, …).
 *
 * So the used-set is the union of (a) every `ph-<name>` class token and (b)
 * every bare string literal that *is* a real Phosphor icon name. (b) is
 * deliberately generous: a non-icon string that happens to match an icon name
 * (`'star'`, `'check'`, `'vault'`) costs one extra glyph — harmless — whereas a
 * missed name would render a blank box in production. We bias toward keeping.
 *
 * The one thing the scan cannot see is an icon name assembled from *fragments*
 * (`` `arrow-${dir}` ``). The app does not do this today; if it ever does, add
 * the whole name to {@link SAFELIST} below.
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import subsetFont from 'subset-font';

/** Weights imported by `tokens.css` (regular + duotone + fill), in that order. */
const WEIGHTS = ['regular', 'duotone', 'fill'];

/** Source files to scan for icon references. */
const SCAN_EXT = /\.(svelte|ts|js|css|html)$/;

/** Directories to skip while scanning (generated / vendored / huge). */
const SKIP_DIR = /(?:^|\/)(?:node_modules|\.svelte-kit|lib\/wasm|lib\/icons)(?:\/|$)/;

/** `ph-<token>` values that are weight modifiers, not icon names. */
const WEIGHT_TOKENS = new Set(['fill', 'duotone', 'bold', 'thin', 'light', 'regular']);

/**
 * Icon names referenced in a way the scan cannot see (e.g. a class assembled
 * from fragments at runtime). Empty today — every dynamic icon in the app is
 * passed as a whole-name string literal, which the scan already catches. Add a
 * name here if you introduce fragment-assembled icon classes.
 */
const SAFELIST = [];

/** Bump when the parsing/emit logic changes, to invalidate stale fingerprints. */
const SCRIPT_VERSION = 1;

// ---- CSS parsing -----------------------------------------------------------

// Flat top-level `selector { decls }` blocks. Phosphor's CSS has no nesting
// (no @media / no nested rules), so a non-recursive match is sufficient.
const RULE = /([^{}]+)\{([^{}]*)\}/g;
const PER_ICON = /^\.ph(?:-fill|-duotone)?\.ph-([a-z0-9-]+):(?:before|after)$/;
const CONTENT_CP = /content:\s*"\\([0-9a-fA-F]+)"/;
const FACE_FAMILY = /font-family:\s*"([^"]+)"/;
const FACE_DISPLAY = /font-display:\s*([a-z-]+)/;
const FACE_WOFF2 = /url\(\s*"\.\/([^"]+\.woff2)"/;

/** Recursively collect scannable source files under `dir`. */
function walk(dir, out = []) {
	for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
		const p = path.join(dir, entry.name);
		if (SKIP_DIR.test(p.replace(/\\/g, '/'))) continue;
		if (entry.isDirectory()) walk(p, out);
		else if (SCAN_EXT.test(entry.name)) out.push(p);
	}
	return out;
}

/** Scan the source tree → the set of icon names actually referenced. */
function scanUsedNames(srcDir, validNames) {
	const used = new Set();
	const add = (name) => {
		if (validNames.has(name)) used.add(name);
	};
	for (const file of walk(srcDir)) {
		const text = fs.readFileSync(file, 'utf8');
		// (a) explicit `ph-<name>` class tokens (minus weight modifiers).
		for (const m of text.matchAll(/\bph-([a-z0-9-]+)/g)) {
			if (!WEIGHT_TOKENS.has(m[1])) add(m[1]);
		}
		// (b) bare string literals that are real icon names (dynamic `icon=` props).
		for (const m of text.matchAll(/["'`]([a-z][a-z0-9-]{1,40})["'`]/g)) add(m[1]);
	}
	for (const name of SAFELIST) add(name);
	return used;
}

/**
 * Parse one weight's `style.css`: rewrite its `@font-face` to woff2-only, keep
 * the base rules verbatim, keep only the used per-icon rules (preserving their
 * declarations — duotone's `opacity` / `margin-left` layering), and collect the
 * codepoints of the kept glyphs.
 */
function processWeight(weightDir, used) {
	const css = fs.readFileSync(path.join(weightDir, 'style.css'), 'utf8');
	const codepoints = new Set();
	const parts = [];
	let woff2File = null;
	let family = null;

	for (const [, rawSelector, rawDecls] of css.matchAll(RULE)) {
		const selector = rawSelector.trim();

		if (selector === '@font-face') {
			family = rawDecls.match(FACE_FAMILY)?.[1] ?? 'Phosphor';
			woff2File = rawDecls.match(FACE_WOFF2)?.[1] ?? null;
			const display = rawDecls.match(FACE_DISPLAY)?.[1] ?? 'block';
			parts.push(
				`@font-face {\n` +
					`\tfont-family: "${family}";\n` +
					`\tsrc: url("./${woff2File}") format("woff2");\n` +
					`\tfont-weight: normal;\n` +
					`\tfont-style: normal;\n` +
					`\tfont-display: ${display};\n}`
			);
			continue;
		}

		const icon = selector.match(PER_ICON);
		if (icon) {
			if (used.has(icon[1])) {
				const cp = rawDecls.match(CONTENT_CP)?.[1];
				if (cp) codepoints.add(cp.toLowerCase());
				parts.push(`${selector} {${rawDecls}}`); // verbatim — keeps opacity/margin
			}
			continue; // unused icon → dropped
		}

		// Base / non-icon rule (.ph, .ph-duotone, …) — keep verbatim.
		parts.push(`${selector} {${rawDecls}}`);
	}

	if (!woff2File) throw new Error(`no @font-face woff2 url found in ${weightDir}/style.css`);
	return { css: parts.join('\n'), codepoints, woff2File, family };
}

/**
 * Subset a weight's font to `codepoints` and write the woff2 to `outDir`.
 * Subsets from the uncompressed `.ttf` source (harfbuzz reads sfnt reliably)
 * and emits woff2. Returns the output byte size.
 */
async function subsetWeight(weightDir, woff2File, codepoints, outDir) {
	const ttfFile = woff2File.replace(/\.woff2$/, '.ttf');
	const source = fs.readFileSync(path.join(weightDir, ttfFile));
	const text = [...codepoints].map((cp) => String.fromCodePoint(parseInt(cp, 16))).join('');
	const subset = await subsetFont(source, text, { targetFormat: 'woff2' });
	fs.writeFileSync(path.join(outDir, woff2File), subset);
	return subset.length;
}

const HEADER =
	'/* GENERATED by scripts/subset-phosphor.mjs — do not edit.\n' +
	'   A subset of @phosphor-icons/web containing only the icons this app uses.\n' +
	'   Regenerate with `pnpm icons` (also runs automatically on dev/build). */';

/**
 * Generate the Phosphor subset (CSS + woff2) into `src/lib/icons/`.
 *
 * @param {{ root?: string, quiet?: boolean, force?: boolean }} [opts]
 * @returns {Promise<{ changed: boolean, kept: number, total: number, perWeight?: object[] }>}
 */
export async function generatePhosphorSubset({ root = process.cwd(), quiet = false, force = false } = {}) {
	const log = quiet ? () => {} : (...a) => console.log('[phosphor-subset]', ...a);
	const pkgDir = path.join(root, 'node_modules/@phosphor-icons/web');
	const srcDir = path.join(pkgDir, 'src');
	const outDir = path.join(root, 'src/lib/icons');

	if (!fs.existsSync(srcDir)) {
		throw new Error(`@phosphor-icons/web not found at ${srcDir} (run pnpm install)`);
	}

	// Canonical icon-name set = the names the regular weight defines.
	const regularCss = fs.readFileSync(path.join(srcDir, 'regular/style.css'), 'utf8');
	const validNames = new Set(
		[...regularCss.matchAll(/\.ph\.ph-([a-z0-9-]+):before/g)].map((m) => m[1])
	);

	const used = scanUsedNames(path.join(root, 'src'), validNames);

	// Fingerprint: skip regeneration when the used-set + package version + this
	// script are unchanged. Keeps warm dev restarts ~instant.
	const pkgVersion = JSON.parse(fs.readFileSync(path.join(pkgDir, 'package.json'), 'utf8')).version;
	const fingerprint = crypto
		.createHash('sha1')
		.update(JSON.stringify({ SCRIPT_VERSION, pkgVersion, names: [...used].sort() }))
		.digest('hex');
	const cssPath = path.join(outDir, 'phosphor.css');
	const fpPath = path.join(outDir, '.subset-fingerprint');

	if (!force && fs.existsSync(cssPath) && fs.existsSync(fpPath)) {
		if (fs.readFileSync(fpPath, 'utf8').trim() === fingerprint) {
			log(`up to date — ${used.size}/${validNames.size} icons (skipped)`);
			return { changed: false, kept: used.size, total: validNames.size };
		}
	}

	fs.mkdirSync(outDir, { recursive: true });
	const cssParts = [HEADER];
	const perWeight = [];
	for (const weight of WEIGHTS) {
		const weightDir = path.join(srcDir, weight);
		const { css, codepoints, woff2File, family } = processWeight(weightDir, used);
		const bytes = await subsetWeight(weightDir, woff2File, codepoints, outDir);
		cssParts.push(css);
		perWeight.push({ weight, family, glyphs: codepoints.size, bytes });
		log(`${weight}: ${codepoints.size} glyphs → ${woff2File} (${(bytes / 1024).toFixed(1)} KiB)`);
	}

	fs.writeFileSync(cssPath, cssParts.join('\n\n') + '\n');
	fs.writeFileSync(fpPath, fingerprint);
	log(`kept ${used.size}/${validNames.size} icon names → ${path.relative(root, cssPath)}`);
	return { changed: true, kept: used.size, total: validNames.size, perWeight };
}

// CLI entry: `node scripts/subset-phosphor.mjs` / `pnpm icons`.
if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
	generatePhosphorSubset({ force: process.argv.includes('--force') }).catch((err) => {
		console.error('[phosphor-subset] FAILED:', err);
		process.exit(1);
	});
}
