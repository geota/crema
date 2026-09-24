/**
 * `$lib/history/upload-targets` — the shot menu's single "Upload" entry
 * across every cloud destination (Visualizer, the Decent account, …).
 *
 * Each destination the user can turn on in Settings → Sharing is an
 * {@link UploadTarget}: whether it is enabled right now, whether this shot
 * is already on it, and where to view it. {@link uploadMenuEntry} folds the
 * list into ONE menu item — "Upload to Visualizer + Decent", "Upload to
 * Decent" (the ones still missing it), or "Re-upload to …" once the shot is
 * everywhere — so adding a destination never adds a menu row. Mirrored on
 * Android (`ui/UploadTargets.kt`).
 */

import { DECENT_HISTORY_URL, decentShotViewUrl } from '$lib/decent/api';
import type { StoredShot } from './model';

export interface UploadTarget {
	id: 'visualizer' | 'decent';
	/** Short display name ("Visualizer", "Decent"). */
	name: string;
	/** Turned on + usable in Settings → Sharing right now. */
	enabled: boolean;
	/** This shot already has a copy there. */
	uploaded: boolean;
	/** Where the uploaded copy lives, when it can be opened. */
	viewUrl: string | null;
	/**
	 * `viewUrl` is the copy's own public page — safe to hand out as a share
	 * link ("anyone with it can view"). `false` when it only points at the
	 * owner's account (a Decent upload whose serial or server id is unknown):
	 * offer "View on X", never "Share link".
	 */
	shareable: boolean;
}

export interface UploadMenuEntry {
	title: string;
	sub: string;
	/** False when no destination is enabled — the row stays visible but dimmed. */
	enabled: boolean;
	/** The destinations a tap pushes to: the missing ones, else (re-upload) every enabled one. */
	targets: UploadTarget[];
	/** True when the tap is a re-upload (the shot is already on every enabled destination). */
	reupload: boolean;
}

function names(ts: UploadTarget[]): string {
	return ts.map((t) => t.name).join(' + ');
}

export function uploadMenuEntry(all: readonly UploadTarget[]): UploadMenuEntry {
	const enabled = all.filter((t) => t.enabled);
	if (enabled.length === 0) {
		return {
			title: 'Upload shot',
			sub: 'Connect Visualizer or your Decent account in Settings → Sharing.',
			enabled: false,
			targets: [],
			reupload: false
		};
	}
	const missing = enabled.filter((t) => !t.uploaded);
	if (missing.length > 0) {
		return {
			title: `Upload to ${names(missing)}`,
			sub: `Push this shot to ${names(missing)}.`,
			enabled: true,
			targets: missing,
			reupload: false
		};
	}
	return {
		title: `Re-upload to ${names(enabled)}`,
		sub: `Refreshes the copy on ${names(enabled)}.`,
		enabled: true,
		targets: enabled,
		reupload: true
	};
}

/**
 * The rows for the destinations that hold the shot and can be opened — a
 * "Share link" row when {@link UploadTarget.shareable}, else "View on X".
 */
export function viewableTargets(all: readonly UploadTarget[]): UploadTarget[] {
	return all.filter((t) => t.uploaded && t.viewUrl !== null);
}

/** The Visualizer destination for a shot — its page is public by link. */
export function visualizerUploadTarget(shot: StoredShot, enabled: boolean): UploadTarget {
	return {
		id: 'visualizer',
		name: 'Visualizer',
		enabled,
		uploaded: !!shot.visualizerId,
		viewUrl: shot.visualizerId ? `https://visualizer.coffee/shots/${shot.visualizerId}` : null,
		shareable: !!shot.visualizerId
	};
}

/**
 * The Decent-account destination for a shot. Shareable only when core can
 * build the public `/shot/<serial>/<id>` page; an upload without one (no
 * serial stamped, or the reply carried no id) opens the account's shot
 * history instead.
 */
export function decentUploadTarget(shot: StoredShot, enabled: boolean): UploadTarget {
	const publicUrl = shot.decentId ? decentShotViewUrl(shot.machine?.serialNumber, shot.decentId) : null;
	return {
		id: 'decent',
		name: 'Decent',
		enabled,
		uploaded: !!shot.decentId,
		viewUrl: publicUrl ?? (shot.decentId ? DECENT_HISTORY_URL : null),
		shareable: publicUrl !== null
	};
}
