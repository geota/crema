/**
 * `#lib/drive/rest.js` — the thin Google Drive v3 REST calls the backup feature
 * needs. Scope `drive.file` means every call is implicitly limited to files
 * THIS app created — listing never sees the user's other Drive contents.
 *
 * Auth: the caller passes a valid access token (from
 * `getDriveAuthStore().accessToken()`, which refreshes transparently).
 */

const UPLOAD_URL = 'https://www.googleapis.com/upload/drive/v3/files';
const FILES_URL = 'https://www.googleapis.com/drive/v3/files';
const BACKUP_MIME = 'application/x-ndjson';
/** Pages a listing may take: 10,000 files, far past what Crema makes. */
export const LIST_MAX_PAGES = 100;

/** A backup file in Drive (the subset of fields we request). */
export interface DriveFile {
	id: string;
	name: string;
	/** RFC-3339 timestamp, newest-first when listed. */
	modifiedTime: string;
	/** Byte size as a string (Drive returns it stringified), if requested. */
	size?: string;
}

async function failOn(res: Response, what: string): Promise<never> {
	let detail = '';
	try {
		const body = (await res.json()) as { error?: { message?: string } };
		detail = body.error?.message ? ` — ${body.error.message}` : '';
	} catch {
		/* non-JSON error body */
	}
	throw new Error(`Drive ${what} failed (${res.status})${detail}.`);
}

/**
 * Upload a backup as a NEW Drive file. Returns the created file. We always
 * create (timestamped names) rather than overwrite, so Drive keeps a short
 * backup history the user can pick from on restore.
 *
 * A resumable upload (#310 P2w): the session is opened with the metadata, then
 * the content goes in one `PUT` straight from `content`, so a disk-backed
 * `File` streams from disk instead of being copied into a request body. Where
 * the session's `Location` isn't readable, it falls back to one multipart
 * request, whose body is also built from `content` without copying it.
 */
export async function uploadBackup(
	accessToken: string,
	name: string,
	content: string | Uint8Array | Blob,
	mime: string = BACKUP_MIME,
	signal?: AbortSignal
): Promise<DriveFile> {
	const media: Blob =
		content instanceof Blob ? content : new Blob([content as BlobPart], { type: mime });
	const fields = 'fields=id,name,modifiedTime,size';
	const start = await fetch(`${UPLOAD_URL}?uploadType=resumable&${fields}`, {
		method: 'POST',
		headers: {
			Authorization: `Bearer ${accessToken}`,
			'Content-Type': 'application/json; charset=UTF-8',
			'X-Upload-Content-Type': mime,
			'X-Upload-Content-Length': String(media.size)
		},
		body: JSON.stringify({ name, mimeType: mime }),
		signal
	});
	if (!start.ok) await failOn(start, 'upload');
	const session = start.headers.get('Location');
	if (!session) return uploadMultipart(accessToken, name, media, mime, signal);
	const res = await fetch(session, {
		method: 'PUT',
		headers: { 'Content-Type': mime },
		body: media,
		signal
	});
	if (!res.ok) await failOn(res, 'upload');
	return whole((await res.json()) as DriveFile, media);
}

/** `file`, once Drive's size for it matches what was sent. */
function whole(file: DriveFile, media: Blob): DriveFile {
	if (file.size != null && Number(file.size) !== media.size) {
		throw new Error(`Drive upload failed: Drive holds ${file.size} bytes of ${media.size}.`);
	}
	return file;
}

/** One multipart request: metadata + media, the media part `content` itself. */
async function uploadMultipart(
	accessToken: string,
	name: string,
	media: Blob,
	mime: string,
	signal?: AbortSignal
): Promise<DriveFile> {
	const boundary = `crema-${Math.random().toString(36).slice(2)}`;
	const enc = new TextEncoder();
	// A Blob body, so a BINARY media part (a `.crema.zip`) rides through intact.
	const head = enc.encode(
		`--${boundary}\r\n` +
			`Content-Type: application/json; charset=UTF-8\r\n\r\n` +
			`${JSON.stringify({ name, mimeType: mime })}\r\n` +
			`--${boundary}\r\n` +
			`Content-Type: ${mime}\r\n\r\n`
	);
	const tail = enc.encode(`\r\n--${boundary}--`);
	const res = await fetch(`${UPLOAD_URL}?uploadType=multipart&fields=id,name,modifiedTime,size`, {
		method: 'POST',
		headers: {
			Authorization: `Bearer ${accessToken}`,
			'Content-Type': `multipart/related; boundary=${boundary}`
		},
		body: new Blob([head, media, tail] as BlobPart[]),
		signal
	});
	if (!res.ok) await failOn(res, 'upload');
	return whole((await res.json()) as DriveFile, media);
}

/** List this app's backup files, newest first: all of them, so an older good
 *  copy can still be picked. */
export async function listBackups(accessToken: string): Promise<DriveFile[]> {
	const q = encodeURIComponent("name contains 'crema-backup' and trashed = false");
	const files: DriveFile[] = [];
	const seen = new Set<string>();
	let pageToken: string | undefined;
	let pages = 0;
	do {
		if (pages++ >= LIST_MAX_PAGES) throw new Error('Drive list failed: it didn’t end.');
		const page = pageToken ? `&pageToken=${encodeURIComponent(pageToken)}` : '';
		const url =
			`${FILES_URL}?q=${q}` +
			`&fields=${encodeURIComponent('nextPageToken,files(id,name,modifiedTime,size)')}` +
			`&orderBy=modifiedTime%20desc&pageSize=100${page}`;
		const res = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
		if (!res.ok) await failOn(res, 'list');
		const data = (await res.json()) as { files?: unknown; nextPageToken?: string };
		// A reply with no list is a failed page, not an empty one.
		if (!Array.isArray(data.files)) throw new Error('Drive list failed: couldn’t read the reply.');
		files.push(...(data.files as DriveFile[]));
		pageToken = data.nextPageToken;
		// A token seen before would go round for ever.
		if (pageToken && seen.has(pageToken)) throw new Error('Drive list failed: it went round.');
		if (pageToken) seen.add(pageToken);
	} while (pageToken);
	return files;
}

/** Download a backup file's raw bytes by id (a `.crema.zip`, or legacy text). The
 *  caller (`restoreBackupData`) sniffs the zip magic vs decoding it as text. */
export async function downloadBackup(accessToken: string, fileId: string): Promise<ArrayBuffer> {
	const res = await fetch(`${FILES_URL}/${encodeURIComponent(fileId)}?alt=media`, {
		headers: { Authorization: `Bearer ${accessToken}` }
	});
	if (!res.ok) await failOn(res, 'download');
	return res.arrayBuffer();
}
