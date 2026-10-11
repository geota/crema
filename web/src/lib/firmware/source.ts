/**
 * `$lib/firmware/source` — where a firmware image comes from on the web.
 *
 * Crema never bundles firmware. The image is fetched at runtime from Decent's
 * own update channel (`fast.decentespresso.com/download/sync/<channel>/fw/
 * bootfwupdate.dat`, the file de1app syncs — `de1plus/updater.tcl:969`) and
 * must match the core's pinned manifest byte for byte before it can be
 * flashed.
 *
 * Decent's server sends no `Access-Control-Allow-Origin` header (probed
 * 2026-10-11), so a browser `fetch` from crema's origin is normally refused.
 * Crema does not proxy the file through anyone else: the fallback is to open
 * the official URL (the browser downloads it) and have the user pick the
 * downloaded file, which is validated exactly like a fetched one.
 */

import { firmwareManifest, validateFirmwareImage } from '$lib/wasm/de1_wasm.js';
import type { FirmwareArtifact, FirmwareImageCheck } from '$lib/core';

/** The pinned official images (stable first). */
export function pinnedFirmware(): FirmwareArtifact[] {
	return JSON.parse(firmwareManifest()) as FirmwareArtifact[];
}

/** Validate bytes against the manifest (the core's `validate_firmware_image`). */
export function checkFirmwareImage(bytes: Uint8Array): FirmwareImageCheck {
	return JSON.parse(validateFirmwareImage(bytes)) as FirmwareImageCheck;
}

export type ImageVerdict =
	| { ok: true; image: Uint8Array; check: FirmwareImageCheck }
	| { ok: false; problem: string; check: FirmwareImageCheck | null };

/** Validate `bytes` as the image the user chose (`wanted`). */
export function verifyImageFor(wanted: FirmwareArtifact, bytes: Uint8Array): ImageVerdict {
	const check = checkFirmwareImage(bytes);
	if (!check.ok || check.artifact == null) {
		return { ok: false, problem: imageProblem(check), check };
	}
	if (check.artifact.id !== wanted.id) {
		return {
			ok: false,
			problem: `This file is build ${check.artifact.build}, not build ${wanted.build}. Choose the build you picked, or go back and pick build ${check.artifact.build}.`,
			check
		};
	}
	return { ok: true, image: bytes, check };
}

/** Plain-English reason a file was refused. */
export function imageProblem(check: FirmwareImageCheck): string {
	const e = check.error;
	switch (e?.type) {
		case 'TooShort':
			return 'This file is too small to be DE1 firmware.';
		case 'BengleImage':
			return 'This is Bengle firmware. Crema only updates classic DE1 machines.';
		case 'NotADe1Image':
			return "This file isn't DE1 firmware. Use the bootfwupdate.dat file from Decent.";
		case 'NotInManifest':
			return `This is DE1 build ${e.content.build}, but not a copy Crema has verified (it may be newer, or damaged). Crema only installs builds it knows byte for byte.`;
		case 'Malformed':
			return `This file failed a firmware check (${e.content.reason}).`;
		default:
			return 'This file could not be checked.';
	}
}

export type DownloadResult =
	| { kind: 'ok'; image: Uint8Array; check: FirmwareImageCheck }
	| { kind: 'rejected'; problem: string }
	| { kind: 'blocked'; detail: string };

/**
 * Try each official URL. `blocked` means the browser refused the request
 * (CORS) or the network failed — fall back to the manual download.
 */
export async function downloadOfficialImage(
	artifact: FirmwareArtifact,
	fetchFn: typeof fetch = fetch,
	onProgress?: (received: number, total: number) => void
): Promise<DownloadResult> {
	let detail = 'no URL';
	for (const url of artifact.urls) {
		let res: Response;
		try {
			res = await fetchFn(url, { mode: 'cors', cache: 'no-store', credentials: 'omit' });
		} catch (error) {
			detail = error instanceof Error ? error.message : String(error);
			continue;
		}
		if (!res.ok) {
			detail = `HTTP ${res.status}`;
			continue;
		}
		const bytes = await readAll(res, artifact.byteLength, onProgress);
		const verdict = verifyImageFor(artifact, bytes);
		if (verdict.ok) return { kind: 'ok', image: verdict.image, check: verdict.check };
		return { kind: 'rejected', problem: verdict.problem };
	}
	return { kind: 'blocked', detail };
}

async function readAll(
	res: Response,
	expected: number,
	onProgress?: (received: number, total: number) => void
): Promise<Uint8Array> {
	if (res.body == null || onProgress == null) return new Uint8Array(await res.arrayBuffer());
	const reader = res.body.getReader();
	const parts: Uint8Array[] = [];
	let received = 0;
	for (;;) {
		const { done, value } = await reader.read();
		if (done) break;
		parts.push(value);
		received += value.byteLength;
		onProgress(received, expected);
	}
	const out = new Uint8Array(received);
	let at = 0;
	for (const p of parts) {
		out.set(p, at);
		at += p.byteLength;
	}
	return out;
}
