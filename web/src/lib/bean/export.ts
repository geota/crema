/**
 * `$lib/bean/export` — emit the bean library in one of two formats:
 *
 *  - **Crema JSONL** — one record per line, header first. Round-trip
 *    lossless against Crema. Covers **beans + roasters + photo blobs**.
 *    Shots live in their own export (Settings → Sharing → History
 *    export) so the library bundle stays small and library-focused.
 *  - **Beanconqueror ZIP** — `Beanconqueror.json` + per-bean
 *    `photo_<uuid>.jpg` sibling files. Slightly lossy (Crema-only
 *    fields like tags / Visualizer ids drop), but BC users can
 *    re-import in their app. BC's own format is brew-aware, so
 *    shot history rides along here by convention.
 *
 * Format selection lives at the export action (split-button on
 * `/beans`); per-format download triggers via a temporary
 * `<a download>` anchor.
 */

import { strToU8, zipSync } from 'fflate';
import {
	exportCremaJsonl,
	exportBeanconquerorMainJson
} from '$lib/wasm/de1_wasm';
import type { Bean, Roaster } from './model';
import type { StoredShot } from '$lib/history';
import { getBeanImageStore } from './image-storage';
import { downloadBlob, filenameStamp } from '$lib/utils/download';

/**
 * Export the user's bean library as a Crema bundle.
 *
 * Output is a `.crema.zip` containing:
 *  - `crema.jsonl` — the line-delimited core export (header + beans +
 *    roasters), produced by the Rust core. Shots are intentionally
 *    excluded; they have their own export path.
 *  - `images/<beanId>` — one blob per bean with an `imageRef`,
 *    extension-free (the blob's MIME survives on re-import). Re-import
 *    rebinds the `imageRef` and replays the blob into IndexedDB.
 *
 * Beans with no `imageRef` add no entry; an export with zero photos
 * is still a single-entry zip so the import path is uniform.
 */
export async function exportCrema(
	beans: readonly Bean[],
	roasters: readonly Roaster[],
	cremaVersion: string
): Promise<void> {
	const envelope = JSON.stringify({ beans, roasters, shots: [] });
	const jsonl = exportCremaJsonl(envelope, Date.now(), cremaVersion);
	const entries: Record<string, Uint8Array> = {
		'crema.jsonl': strToU8(jsonl)
	};
	const imageStore = getBeanImageStore();
	for (const bean of beans) {
		if (!bean.imageRef) continue;
		try {
			const blob = await imageStore.get(bean.imageRef);
			if (!blob) continue;
			const buf = new Uint8Array(await blob.arrayBuffer());
			entries[`images/${bean.id}`] = buf;
		} catch {
			// IDB miss / blob read error — skip this bean's photo,
			// don't fail the whole export. The .jsonl still carries the
			// `imageRef` pointer so subsequent re-imports onto the same
			// device with the blob intact still link up.
		}
	}
	const zipped = zipSync(entries);
	downloadBlob(
		`crema-${filenameStamp()}.crema.zip`,
		new Blob([zipped], { type: 'application/zip' })
	);
}

/**
 * Export as a Beanconqueror-compatible `.zip`:
 *   - `Beanconqueror.json` at the root (main file).
 *   - One `photo_<uuid>.jpg` per bean with a stored `imageRef`,
 *     placed at the root next to the main file (BC's "full with
 *     photos" export shape, just packaged inside the zip instead of
 *     as sibling files on disk).
 *
 * The receiving BC user drops the zip directly; the photos get
 * unpacked alongside the json and re-bound by `attachments[]`.
 */
export async function exportBeanconqueror(
	beans: readonly Bean[],
	roasters: readonly Roaster[],
	shots: readonly StoredShot[]
): Promise<void> {
	const envelope = JSON.stringify({ beans, roasters, shots });
	const mainJson = exportBeanconquerorMainJson(envelope, Date.now());

	// Pull photo blobs out of IDB for any bean with an imageRef.
	// The BC main JSON already carries the `attachments` filenames
	// (restored from `metadata.beanconqueror.photo_filenames`); we
	// just need to bundle the bytes under the same names.
	const photoFiles: Record<string, Uint8Array> = {};
	const imageStore = getBeanImageStore();
	for (const bean of beans) {
		if (!bean.imageRef) continue;
		const meta = (bean.metadata ?? {}) as Record<string, unknown>;
		const bc = (meta.beanconqueror ?? {}) as Record<string, unknown>;
		const photoFilenames = Array.isArray(bc.photo_filenames)
			? (bc.photo_filenames as string[])
			: [];
		if (photoFilenames.length === 0) continue;
		try {
			const blob = await imageStore.get(bean.imageRef);
			if (!blob) continue;
			const buf = new Uint8Array(await blob.arrayBuffer());
			// Each bean's first photo_filename slot owns the bytes —
			// BC's "with photos" export only ever wrote one photo per
			// bag, so this matches the round-trip exactly.
			photoFiles[photoFilenames[0]] = buf;
		} catch {
			// IDB miss — skip this bean's photo, don't fail the export.
		}
	}

	const zipEntries: Record<string, Uint8Array> = {
		'Beanconqueror.json': strToU8(mainJson),
		...photoFiles
	};
	const zipped = zipSync(zipEntries);
	downloadBlob(
		`crema-to-beanconqueror-${filenameStamp()}.zip`,
		new Blob([zipped], { type: 'application/zip' })
	);
}
