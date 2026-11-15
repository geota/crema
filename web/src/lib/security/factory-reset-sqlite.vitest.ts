import { beforeEach, describe, expect, it } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import { factoryResetLocalData } from './factory-reset';
import { BACKUP_TEMP_PREFIX } from '#lib/backup/temp-names.js';
import {
	DB_POOL_DIR,
	DONE_RECORD_NAME,
	HISTORY_SQLITE_OPFS_PREFIX,
	INDEX_POOL_DIR,
	MARKER_KEY,
	PENDING_KEY_PREFIX
} from '#lib/history-db/names.js';

/**
 * SQLite history H0: a factory reset takes the files a newer build's SQLite
 * history keeps in the origin private file system, though this build writes
 * none of them, so a reset after a rollback still leaves nothing behind. The
 * files go before the `crema.*` keys; a file that stays is reported, and keeps
 * its marker and done-record (S10).
 */

const PENDING_KEY = `${PENDING_KEY_PREFIX}shot-1`;

/** An OPFS root: top-level names, each a file or a directory. */
function fakeRoot(
	names: string[],
	opts: { held?: string[]; listable?: boolean; listFails?: boolean; onRemove?: (name: string) => void } = {}
) {
	const entries = new Set(names);
	const removed: string[] = [];
	const root = {
		removeEntry: async (name: string, o?: { recursive?: boolean }) => {
			expect(o?.recursive, 'pools are directories').toBe(true);
			opts.onRemove?.(name);
			if (opts.held?.includes(name)) throw new DOMException('held by a Worker', 'NoModificationAllowedError');
			if (!entries.delete(name)) throw new DOMException('gone', 'NotFoundError');
			removed.push(name);
		},
		...(opts.listable === false
			? {}
			: {
					keys: async function* () {
						if (opts.listFails) throw new DOMException('broken', 'InvalidStateError');
						yield* [...entries];
					}
				})
	} as unknown as FileSystemDirectoryHandle;
	return { root, entries, removed };
}

const SQLITE_ENTRIES = [
	INDEX_POOL_DIR,
	DB_POOL_DIR,
	`${DB_POOL_DIR}-2`,
	`${INDEX_POOL_DIR}-3`,
	DONE_RECORD_NAME
];
const OTHER_ENTRIES = ['other', '.crema-history', '.crema-historyX', 'crema-history-index', '.opfs-sahpool'];

beforeEach(() => {
	(globalThis as { indexedDB?: IDBFactory }).indexedDB = new IDBFactory();
	localStorage.clear();
	sessionStorage.clear();
	localStorage.setItem(MARKER_KEY, '{"state":"done","db_gen":1}');
	localStorage.setItem(PENDING_KEY, '{"id":"shot-1"}');
	localStorage.setItem('crema.settings.v1', '{}');
});

describe('factory reset and the SQLite history (H0)', () => {
	it('names every OPFS entry under the one prefix, and the keys under crema.', () => {
		for (const name of [INDEX_POOL_DIR, DB_POOL_DIR, DONE_RECORD_NAME]) {
			expect(name.startsWith(HISTORY_SQLITE_OPFS_PREFIX)).toBe(true);
		}
		expect(MARKER_KEY.startsWith('crema.')).toBe(true);
		expect(PENDING_KEY_PREFIX.startsWith(`${MARKER_KEY}.`)).toBe(true);
	});

	it('removes both pools, repair generations and the done-record, and nothing else', async () => {
		const { root, entries, removed } = fakeRoot([...SQLITE_ENTRIES, ...OTHER_ENTRIES]);
		const result = await factoryResetLocalData({ privateFiles: async () => root });
		expect([...entries].sort()).toEqual([...OTHER_ENTRIES].sort());
		expect(removed.at(-1), 'the done-record goes last').toBe(DONE_RECORD_NAME);
		expect(result.databasesFailed).toEqual([]);
		expect(localStorage.getItem(MARKER_KEY)).toBeNull();
		expect(localStorage.getItem(PENDING_KEY)).toBeNull();
		expect(localStorage.getItem('crema.settings.v1')).toBeNull();
	});

	it('removes them before it clears the marker and the pending journal', async () => {
		const seen: string[] = [];
		const { root } = fakeRoot(SQLITE_ENTRIES, {
			onRemove: (name) => {
				if (name.startsWith(HISTORY_SQLITE_OPFS_PREFIX)) {
					seen.push(`${name}:${localStorage.getItem(MARKER_KEY) != null}:${localStorage.getItem(PENDING_KEY) != null}`);
				}
			}
		});
		await factoryResetLocalData({ privateFiles: async () => root });
		expect(seen.length).toBe(SQLITE_ENTRIES.length);
		for (const s of seen) expect(s.endsWith(':true:true'), s).toBe(true);
		expect(localStorage.getItem(MARKER_KEY)).toBeNull();
	});

	it('still removes the known pools where the root cannot be listed', async () => {
		const { root, entries } = fakeRoot([INDEX_POOL_DIR, DB_POOL_DIR, DONE_RECORD_NAME, 'other'], { listable: false });
		const result = await factoryResetLocalData({ privateFiles: async () => root });
		expect([...entries]).toEqual(['other']);
		expect(result.databasesFailed).toEqual([]);
	});

	it('succeeds without a private file system', async () => {
		for (const privateFiles of [
			async () => undefined,
			async () => {
				throw new DOMException('no OPFS here', 'SecurityError');
			}
		]) {
			localStorage.setItem(MARKER_KEY, 'x');
			const result = await factoryResetLocalData({ privateFiles });
			expect(result.databasesFailed).toEqual([]);
			expect(localStorage.getItem(MARKER_KEY)).toBeNull();
			expect(localStorage.getItem(PENDING_KEY)).toBeNull();
		}
	});

	it('a pool that stays fails the reset and keeps the marker, pending journal and done-record (S10)', async () => {
		const { root, entries } = fakeRoot([...SQLITE_ENTRIES, 'other'], { held: [DB_POOL_DIR] });
		const result = await factoryResetLocalData({ privateFiles: async () => root });
		expect(result.databasesFailed).toEqual([DB_POOL_DIR]);
		expect([...entries].sort()).toEqual([DB_POOL_DIR, DONE_RECORD_NAME, 'other'].sort());
		expect(localStorage.getItem(MARKER_KEY)).not.toBeNull();
		expect(localStorage.getItem(PENDING_KEY)).not.toBeNull();
		// Everything else is still erased.
		expect(localStorage.getItem('crema.settings.v1')).toBeNull();

		// Once the other tab lets go, the next reset takes the rest.
		const again = await factoryResetLocalData({ privateFiles: async () => fakeRoot([...entries]).root });
		expect(again.databasesFailed).toEqual([]);
		expect(localStorage.getItem(MARKER_KEY)).toBeNull();
	});

	it('a done-record that stays fails the reset and keeps the marker', async () => {
		const { root } = fakeRoot(SQLITE_ENTRIES, { held: [DONE_RECORD_NAME] });
		const result = await factoryResetLocalData({ privateFiles: async () => root });
		expect(result.databasesFailed).toEqual([DONE_RECORD_NAME]);
		expect(localStorage.getItem(MARKER_KEY)).not.toBeNull();
	});

	it('a root that cannot be read or listed fails the reset and keeps the marker', async () => {
		const unlistable = fakeRoot(SQLITE_ENTRIES, { listFails: true });
		const listed = await factoryResetLocalData({ privateFiles: async () => unlistable.root });
		expect(listed.databasesFailed).toEqual([HISTORY_SQLITE_OPFS_PREFIX, BACKUP_TEMP_PREFIX]);
		expect(localStorage.getItem(MARKER_KEY)).not.toBeNull();
		expect(unlistable.entries.has(DONE_RECORD_NAME)).toBe(true);

		const unreadable = await factoryResetLocalData({
			privateFiles: async () => {
				throw new DOMException('broken', 'UnknownError');
			}
		});
		expect(unreadable.databasesFailed).toContain(HISTORY_SQLITE_OPFS_PREFIX);
		expect(unreadable.databasesFailed).toContain(BACKUP_TEMP_PREFIX);
		expect(localStorage.getItem(MARKER_KEY)).not.toBeNull();
	});
});

/** #428: a backup's temporary file is user data too, so the same pass takes it. */
describe('factory reset and temporary backup files', () => {
	const NEW_TEMP = `${BACKUP_TEMP_PREFIX}${Date.now()}-aaaa.zip`;
	const OLD_TEMP = `${BACKUP_TEMP_PREFIX}1-bbbb.zip`;

	it('removes every one, however new, and nothing else', async () => {
		const { root, entries } = fakeRoot([NEW_TEMP, OLD_TEMP, ...SQLITE_ENTRIES, ...OTHER_ENTRIES, 'crema-backup-tmp']);
		const result = await factoryResetLocalData({ privateFiles: async () => root });
		expect([...entries].sort()).toEqual([...OTHER_ENTRIES, 'crema-backup-tmp'].sort());
		expect(result.databasesFailed).toEqual([]);
	});

	it('one that stays is reported, and does not keep the SQLite marker', async () => {
		const { root, entries } = fakeRoot([NEW_TEMP, OLD_TEMP, ...SQLITE_ENTRIES], { held: [NEW_TEMP] });
		const result = await factoryResetLocalData({ privateFiles: async () => root });
		expect(result.databasesFailed).toEqual([NEW_TEMP]);
		expect([...entries]).toEqual([NEW_TEMP]);
		expect(localStorage.getItem(MARKER_KEY)).toBeNull();
		expect(localStorage.getItem(PENDING_KEY)).toBeNull();
	});

	it('a root that cannot be listed hides them, and the reset still succeeds', async () => {
		const { root, entries } = fakeRoot([OLD_TEMP, INDEX_POOL_DIR], { listable: false });
		const result = await factoryResetLocalData({ privateFiles: async () => root });
		expect([...entries]).toEqual([OLD_TEMP]);
		expect(result.databasesFailed).toEqual([]);
	});
});
