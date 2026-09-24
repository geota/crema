import { beforeEach, describe, expect, it, vi } from 'vitest';

// jsdom has no IndexedDB, so the real box would pass plaintext through. Swap
// in a box over an in-memory key the test can "lose".
let key: Promise<CryptoKey> | null = null;
vi.mock('$lib/security/secret-box', async (importOriginal) => {
	const real = await importOriginal<typeof import('$lib/security/secret-box')>();
	return {
		...real,
		secretBox: real.createSecretBox({ getKey: () => key ?? Promise.resolve(null) })
	};
});

const { decentTokenMigration, getDecentCredentials, readDecentAccount, writeDecentAccount, DEFAULT_DECENT_ACCOUNT } =
	await import('./account');
const { isWrapped } = await import('$lib/security/secret-box');

const ACCOUNT_KEY = 'crema.decent.v1';
const stored = (): { token: string; needsReauth: boolean } => JSON.parse(localStorage.getItem(ACCOUNT_KEY) ?? '{}');

beforeEach(() => {
	localStorage.clear();
	key = crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
});

describe('Decent account token at rest', () => {
	it('wraps a plaintext token written before wrapping existed, in place, and still reads it', async () => {
		localStorage.setItem(ACCOUNT_KEY, JSON.stringify({ email: 'me@example.com', token: 'plain-token', serials: ['1'] }));
		readDecentAccount();
		await decentTokenMigration();
		const token = stored().token;
		expect(isWrapped(token)).toBe(true);
		expect(token).not.toContain('plain-token');
		expect(await getDecentCredentials()).toEqual({ email: 'me@example.com', token: 'plain-token' });
	});

	it('flags needsReauth when the wrapped token becomes unreadable (key lost)', async () => {
		const { createSecretBox } = await import('$lib/security/secret-box');
		const lostKey = crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
		const wrapped = await createSecretBox({ getKey: () => lostKey }).wrap('tok');
		localStorage.setItem(ACCOUNT_KEY, JSON.stringify({ email: 'me@example.com', token: wrapped, autoUpload: true }));
		// This session's key (from beforeEach) is not the one that wrapped it.
		expect(await getDecentCredentials()).toBeNull();
		expect(readDecentAccount()).toMatchObject({ needsReauth: true, lastUpload: { ok: false } });
		expect(stored().needsReauth).toBe(true);
	});

	it('reads missing list fields as empty', () => {
		writeDecentAccount({ ...DEFAULT_DECENT_ACCOUNT, email: 'e', token: 't' });
		localStorage.setItem(ACCOUNT_KEY, JSON.stringify({ email: 'e', token: 't' }));
		expect(readDecentAccount()).toMatchObject({ rejectedShotIds: [], retryShotIds: [], serials: [] });
	});
});
