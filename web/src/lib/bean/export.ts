/**
 * `$lib/bean/export` — emit Crema's data (beans + roasters + shots)
 * in one of two formats:
 *
 *  - **Crema JSONL** — one record per line, header first. Round-trip
 *    lossless against Crema. Doesn't bundle photo blobs (those stay
 *    device-local for v1; same-device re-import keeps the imageRef
 *    pointers valid).
 *  - **Beanconqueror ZIP** — `Beanconqueror.json` + per-bean
 *    `photo_<uuid>.jpg` sibling files. Slightly lossy (Crema-only
 *    fields like tags / Visualizer ids drop), but BC users can
 *    re-import in their app.
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

/**
 * Trigger a browser download for `data` named `filename`. Used by
 * both export paths; cleaned up after the click so the object URL
 * doesn't leak.
 */
function downloadBlob(data: BlobPart, filename: string, mime: string): void {
	const blob = new Blob([data], { type: mime });
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = filename;
	document.body.appendChild(a);
	a.click();
	document.body.removeChild(a);
	// Revoke the URL after the click so the browser releases the blob.
	// 100 ms is generous — most browsers fire the download synchronously
	// from the click, but a few queue it.
	setTimeout(() => URL.revokeObjectURL(url), 100);
}

/** A short timestamp suitable for filenames — `YYYY-MM-DD-HHmm`. */
function fileStamp(now: Date): string {
	const pad = (n: number) => String(n).padStart(2, '0');
	return (
		`${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}` +
		`-${pad(now.getHours())}${pad(now.getMinutes())}`
	);
}

/**
 * Export the user's beans + roasters + shots as a Crema `.jsonl`
 * download. Lossless on Crema → Crema round-trip. Photos stay in
 * IndexedDB on the source device.
 */
export function exportCrema(
	beans: readonly Bean[],
	roasters: readonly Roaster[],
	shots: readonly StoredShot[],
	cremaVersion: string
): void {
	const envelope = JSON.stringify({ beans, roasters, shots });
	const jsonl = exportCremaJsonl(envelope, Date.now(), cremaVersion);
	downloadBlob(
		jsonl,
		`crema-${fileStamp(new Date())}.jsonl`,
		'application/jsonl'
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
		zipped,
		`crema-to-beanconqueror-${fileStamp(new Date())}.zip`,
		'application/zip'
	);
}
