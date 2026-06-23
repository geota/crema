/**
 * `$lib/drive/rest` — the thin Google Drive v3 REST calls the backup feature
 * needs. Scope `drive.file` means every call is implicitly limited to files
 * THIS app created — listing never sees the user's other Drive contents.
 *
 * Auth: the caller passes a valid access token (from
 * `getDriveAuthStore().accessToken()`, which refreshes transparently).
 */

const UPLOAD_URL = 'https://www.googleapis.com/upload/drive/v3/files';
const FILES_URL = 'https://www.googleapis.com/drive/v3/files';
const BACKUP_MIME = 'application/x-ndjson';

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
 * Upload a backup as a NEW Drive file (multipart: metadata + media). Returns
 * the created file. We always create (timestamped names) rather than overwrite,
 * so Drive keeps a short backup history the user can pick from on restore.
 */
export async function uploadBackup(
	accessToken: string,
	name: string,
	content: string
): Promise<DriveFile> {
	const boundary = `crema-${Math.random().toString(36).slice(2)}`;
	const metadata = { name, mimeType: BACKUP_MIME };
	const body =
		`--${boundary}\r\n` +
		`Content-Type: application/json; charset=UTF-8\r\n\r\n` +
		`${JSON.stringify(metadata)}\r\n` +
		`--${boundary}\r\n` +
		`Content-Type: ${BACKUP_MIME}\r\n\r\n` +
		`${content}\r\n` +
		`--${boundary}--`;
	const res = await fetch(`${UPLOAD_URL}?uploadType=multipart&fields=id,name,modifiedTime,size`, {
		method: 'POST',
		headers: {
			Authorization: `Bearer ${accessToken}`,
			'Content-Type': `multipart/related; boundary=${boundary}`
		},
		body
	});
	if (!res.ok) await failOn(res, 'upload');
	return (await res.json()) as DriveFile;
}

/** List this app's backup files, newest first. */
export async function listBackups(accessToken: string): Promise<DriveFile[]> {
	const q = encodeURIComponent("name contains 'crema-backup' and trashed = false");
	const url =
		`${FILES_URL}?q=${q}` +
		`&fields=${encodeURIComponent('files(id,name,modifiedTime,size)')}` +
		`&orderBy=modifiedTime%20desc&pageSize=50`;
	const res = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
	if (!res.ok) await failOn(res, 'list');
	const data = (await res.json()) as { files?: DriveFile[] };
	return data.files ?? [];
}

/** Download a backup file's raw `.crema` text by id. */
export async function downloadBackup(accessToken: string, fileId: string): Promise<string> {
	const res = await fetch(`${FILES_URL}/${encodeURIComponent(fileId)}?alt=media`, {
		headers: { Authorization: `Bearer ${accessToken}` }
	});
	if (!res.ok) await failOn(res, 'download');
	return res.text();
}
