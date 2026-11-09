// Writes fflate-zipsync.crema.zip and fflate-zipdeflate.crema.zip the way the
// web writes a backup: fflate `zipSync` (sizes in the local header) and `Zip`
// with `ZipDeflate` (#386's streamed export, data descriptors). The photo comes
// first and holds all three zip signatures, stored (level 0), so they show in
// its compressed bytes; it's MakeZip.java's photo. Rerun from web/ with
//   node ../core/de1-domain/tests/fixtures/backup/make-fflate-zips.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

// fflate from web/'s node_modules: run from web/.
const { Zip, ZipDeflate, zipSync } = createRequire(join(process.cwd(), 'package.json'))('fflate');

const here = dirname(fileURLToPath(import.meta.url));
const jsonl = new Uint8Array(readFileSync(join(here, 'android-backup.crema.jsonl')));
const photo = new Uint8Array(8192);
let x = 0x2545f4914f6cdd1dn;
const mask = (1n << 64n) - 1n;
for (let i = 0; i < photo.length; i++) {
	x ^= (x << 13n) & mask;
	x ^= x >> 7n;
	x ^= (x << 17n) & mask;
	photo[i] = Number(x & 0xffn);
}
[[0x50, 0x4b, 0x03, 0x04], [0x50, 0x4b, 0x07, 0x08], [0x50, 0x4b, 0x01, 0x02]].forEach((sig, s) => photo.set(sig, [100, 2000, 5000][s]));
const PHOTO = 'images/bean:android-1';
// fflate stamps entries with the time; pin it so the bytes don't change.
const mtime = new Date(Date.UTC(2026, 10, 8));

writeFileSync(join(here, 'fflate-zipsync.crema.zip'), zipSync({ [PHOTO]: [photo, { level: 0, mtime }], 'backup.jsonl': [jsonl, { mtime }] }));

const parts = [];
const zip = new Zip((err, chunk) => {
	if (err) throw err;
	parts.push(chunk);
});
for (const [name, bytes, level] of [[PHOTO, photo, 0], ['backup.jsonl', jsonl, 6]]) {
	const entry = new ZipDeflate(name, { level, mtime });
	zip.add(entry);
	entry.push(bytes, true);
}
zip.end();
writeFileSync(join(here, 'fflate-zipdeflate.crema.zip'), Buffer.concat(parts));
