import { describe, expect, it } from 'vitest';
import { createIndexedDbKeyProvider, createSecretBox, isWrapped, type KeyProvider } from './secret-box';

// vitest's jsdom has Web Crypto but no IndexedDB, so the tests drive the box
// with an in-memory key and separately check the no-key degradation.
async function memKey(): Promise<CryptoKey> {
	return crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
const withKey = (k: Promise<CryptoKey>): KeyProvider => ({ getKey: () => k });
const noKey: KeyProvider = { getKey: async () => null };

describe('secret-box', () => {
	it('wraps to ciphertext and unwraps back', async () => {
		const box = createSecretBox(withKey(memKey()));
		const w = await box.wrap('tok-123');
		expect(isWrapped(w)).toBe(true);
		expect(w).not.toContain('tok-123');
		expect(await box.unwrap(w)).toBe('tok-123');
		// Fresh IV each time — two wraps of the same value differ.
		expect(await box.wrap('tok-123')).not.toBe(w);
	});
	it('passes pre-wrapping plaintext through and refuses a foreign ciphertext', async () => {
		const a = createSecretBox(withKey(memKey()));
		const b = createSecretBox(withKey(memKey()));
		expect(await a.unwrap('legacy-plain')).toBe('legacy-plain');
		expect(await b.unwrap(await a.wrap('secret'))).toBeNull();
	});
	it('degrades to plaintext when no key is available', async () => {
		const box = createSecretBox(noKey);
		expect(await box.wrap('tok')).toBe('tok');
		expect(await box.unwrap('tok')).toBe('tok');
		expect(await box.unwrap('sb1:AAAA:BBBB')).toBeNull();
	});
});

/**
 * A minimal IndexedDB stand-in — just the surface `createIndexedDbKeyProvider`
 * touches (open → one store; get / add in readonly / readwrite transactions).
 * Requests run on macrotasks, a transaction commits once it has no pending
 * requests, and `serialize` decides whether readwrite transactions queue
 * behind each other (real IndexedDB) or overlap (to force the
 * `ConstraintError` path). Two providers over one fake = two tabs.
 */
function fakeIdb(opts: { serialize: boolean }) {
	const data = new Map<string, unknown>();
	let rwTail: Promise<void> = Promise.resolve();
	const tick = () => new Promise<void>((r) => setTimeout(r, 0));
	function transaction(mode: 'readonly' | 'readwrite') {
		let pending = 0;
		let settled = false;
		let release: () => void = () => {};
		const started =
			mode === 'readwrite' && opts.serialize
				? (() => {
						const prev = rwTail;
						rwTail = new Promise<void>((r) => (release = r));
						return prev;
					})()
				: Promise.resolve();
		const tx = {
			error: null as unknown,
			oncomplete: null as null | (() => void),
			onabort: null as null | (() => void),
			objectStore: () => store
		};
		const finish = (ok: boolean) => {
			if (settled) return;
			settled = true;
			release();
			if (ok) tx.oncomplete?.();
			else tx.onabort?.();
		};
		const maybeCommit = () =>
			void tick().then(() => {
				if (pending === 0) finish(true);
			});
		function request(run: () => { ok: true; result: unknown } | { ok: false; error: unknown }) {
			const req = {
				result: undefined as unknown,
				error: null as unknown,
				onsuccess: null as null | (() => void),
				onerror: null as null | (() => void)
			};
			pending += 1;
			void started.then(tick).then(() => {
				const r = run();
				pending -= 1;
				if (r.ok) {
					req.result = r.result;
					req.onsuccess?.();
					maybeCommit();
				} else {
					req.error = r.error;
					tx.error = r.error;
					req.onerror?.();
					finish(false);
				}
			});
			return req;
		}
		const store = {
			get: (k: string) => request(() => ({ ok: true, result: data.get(k) })),
			add: (v: unknown, k: string) =>
				request(() => {
					if (data.has(k)) return { ok: false, error: { name: 'ConstraintError' } };
					data.set(k, v);
					return { ok: true, result: k };
				})
		};
		return tx;
	}
	const db = {
		objectStoreNames: { contains: () => true },
		createObjectStore: () => {},
		transaction: (_s: string, mode: 'readonly' | 'readwrite') => transaction(mode),
		close: () => {}
	};
	const factory = {
		open: () => {
			const req = { result: db, error: null, onsuccess: null as null | (() => void), onerror: null, onupgradeneeded: null };
			void tick().then(() => req.onsuccess?.());
			return req;
		}
	};
	return factory as unknown as IDBFactory;
}

describe('indexedDbKeyProvider', () => {
	it('two tabs racing on first use end up with the same key (serialised readwrite)', async () => {
		const idb = fakeIdb({ serialize: true });
		const [a, b] = await Promise.all([
			createIndexedDbKeyProvider(() => idb).getKey(),
			createIndexedDbKeyProvider(() => idb).getKey()
		]);
		expect(a).not.toBeNull();
		expect(b).toBe(a);
	});
	it('recovers from a ConstraintError by re-reading the winner', async () => {
		const idb = fakeIdb({ serialize: false });
		const [a, b] = await Promise.all([
			createIndexedDbKeyProvider(() => idb).getKey(),
			createIndexedDbKeyProvider(() => idb).getKey()
		]);
		expect(a).not.toBeNull();
		expect(b).toBe(a);
		// Both boxes can open each other's ciphertext.
		const boxA = createSecretBox(createIndexedDbKeyProvider(() => idb));
		const boxB = createSecretBox(createIndexedDbKeyProvider(() => idb));
		expect(await boxB.unwrap(await boxA.wrap('shared'))).toBe('shared');
	});
	it('reuses the stored key on later calls', async () => {
		const idb = fakeIdb({ serialize: true });
		const first = await createIndexedDbKeyProvider(() => idb).getKey();
		expect(await createIndexedDbKeyProvider(() => idb).getKey()).toBe(first);
	});
});

describe('createSecretBox key caching', () => {
	it('does not pin a missing key for the session — the next call retries', async () => {
		let calls = 0;
		const real = memKey();
		const flaky: KeyProvider = {
			getKey: async () => {
				calls += 1;
				if (calls === 1) return null;
				if (calls === 2) throw new Error('IndexedDB busy');
				return real;
			}
		};
		const box = createSecretBox(flaky);
		expect(await box.wrap('t')).toBe('t'); // no key yet → plaintext
		expect(await box.wrap('t')).toBe('t'); // provider threw → plaintext, not cached
		const w = await box.wrap('t');
		expect(isWrapped(w)).toBe(true);
		await box.wrap('t');
		expect(calls).toBe(3); // a real key is cached
	});
});
