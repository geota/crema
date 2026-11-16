#!/usr/bin/env node
// After `vite build` (`pnpm build`): the built service worker imports
// `sw-window-count.js` (web/static), which answers the SQLite history's flip
// how many windows are open (#310 SPEC §11, M2). Workbox's `generateSW` in
// @vite-pwa/sveltekit 2 has no `importScripts` option, so the line goes at the
// top of `build/sw.js`, once. Fails the build when the worker isn't there.
import fs from 'node:fs';
import path from 'node:path';

const sw = path.resolve(process.argv[2] ?? 'build/sw.js');
const line = "importScripts('./sw-window-count.js');\n";
if (!fs.existsSync(sw)) {
	console.error(`sw-window-count: no service worker at ${sw}`);
	process.exit(1);
}
if (!fs.existsSync(path.join(path.dirname(sw), 'sw-window-count.js'))) {
	console.error('sw-window-count: sw-window-count.js is missing beside the service worker');
	process.exit(1);
}
const text = fs.readFileSync(sw, 'utf-8');
if (!text.startsWith(line)) fs.writeFileSync(sw, line + text);
console.log(`sw-window-count: ${path.relative(process.cwd(), sw)} imports sw-window-count.js`);
