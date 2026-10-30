/**
 * AC-23: the wasm relay bridge reproduces the seeded vectors
 * (core/de1-relay/tests/vectors/v4.json) byte for byte.
 *
 * Drives the test-seeds bundle (`pnpm wasm:test`, built into web/.wasm-test,
 * outside src/): a `RelayRemoteConnection` built with the vectors' seeds
 * consumes the hub's vector messages and must produce the remote's vector
 * messages, for a pairing and a resume. The production bundle has no
 * `*ForTests` constructors (CI's release-symbols check).
 *
 * Without the test bundle the suite is skipped locally; CI sets
 * RELAY_WASM_TEST=1, which turns a missing bundle into a failure.
 */
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { beforeAll, describe, expect, it } from 'vitest';

import { readStep, type RelayStepData, type RelayStepHandle } from './index.js';

const bundleDir = path.resolve('.wasm-test');
const bundleJs = path.join(bundleDir, 'de1_wasm.js');
const required = process.env.RELAY_WASM_TEST === '1';

interface TestKeys {
	publicKey(): Uint8Array;
	deviceId(): string;
	shortId(): string;
	privateForStorage(): Uint8Array;
	free(): void;
}
interface TestConnection {
	start(): RelayStepHandle;
	onText(raw: Uint8Array, nowMs: number): RelayStepHandle;
	onBinary(bytes: Uint8Array, nowMs: number): RelayStepHandle;
	send(message: Uint8Array, nowMs: number): RelayStepHandle;
	code(): string | undefined;
	peerPublicKey(): Uint8Array | undefined;
	channelBinding(): Uint8Array | undefined;
	free(): void;
}
interface TestBundle {
	initSync(opts: { module: Buffer }): unknown;
	RelayKeys: { generateForTests(seed: Uint8Array): TestKeys; generate(): TestKeys };
	RelayRemoteConnection: {
		pairForTests(keys: TestKeys, name: string, nowMs: number, seed: Uint8Array): TestConnection;
		resumeForTests(keys: TestKeys, hub: Uint8Array, nowMs: number, seed: Uint8Array): TestConnection;
	};
}

type Vectors = Record<string, any>;
const vectors: Vectors = JSON.parse(
	readFileSync(path.resolve('../core/de1-relay/tests/vectors/v4.json'), 'utf-8')
);
const T0: number = vectors.t0_ms;

/** `SHA-256("de1-relay test seed " ‖ label ‖ " " ‖ n)` (spec §3.7). */
function seed(label: string, n: number): Uint8Array {
	return new Uint8Array(createHash('sha256').update(`de1-relay test seed ${label} ${n}`).digest());
}

const unhex = (s: string): Uint8Array => new Uint8Array(Buffer.from(s, 'hex'));
const hex = (b: Uint8Array): string => Buffer.from(b).toString('hex');

/** The single message a step sends, as hex. */
function sent(step: RelayStepData): string {
	expect(step.send).toHaveLength(1);
	return hex(step.send[0].bytes);
}

const present = existsSync(bundleJs);
if (!present && required) {
	throw new Error(`RELAY_WASM_TEST=1 but ${bundleJs} is missing: run \`pnpm wasm:test\``);
}

describe.skipIf(!present)('relay wasm bridge reproduces v4.json', () => {
	let w: TestBundle;
	beforeAll(async () => {
		w = (await import(/* @vite-ignore */ bundleJs)) as TestBundle;
		w.initSync({ module: readFileSync(path.join(bundleDir, 'de1_wasm_bg.wasm')) });
	});

	it('derives the vector keys from the test seeds', () => {
		const remote = w.RelayKeys.generateForTests(seed('remote-static', 0));
		expect(hex(remote.publicKey())).toBe(vectors.keys.remote.public);
		expect(remote.deviceId()).toBe(vectors.keys.remote.device_id);
		expect(remote.shortId()).toBe(vectors.keys.remote.short_id);
		remote.free();
	});

	it('pairs byte for byte', () => {
		const v = vectors.pairing;
		const keys = w.RelayKeys.generateForTests(seed('remote-static', 0));
		const conn = w.RelayRemoteConnection.pairForTests(keys, vectors.names.remote, T0, seed('remote-conn', 0));
		const hello = readStep(conn.start());
		expect(hello.send[0].text).toBe(true);
		expect(sent(hello)).toBe(v.client_hello);
		const shown = readStep(conn.onBinary(unhex(v.server_hello), T0));
		expect(sent(shown)).toBe(v.pake_a);
		// ShowCode carries only the expiry; the code comes from code().
		expect(shown.events.some((e) => e.type === 'ShowCode')).toBe(true);
		expect(JSON.stringify(shown.events)).not.toContain(v.code);
		expect(conn.code()).toBe(v.code);
		expect(sent(readStep(conn.onBinary(unhex(v.pake_b), T0)))).toBe(v.noise_1);
		expect(sent(readStep(conn.onBinary(unhex(v.noise_2), T0)))).toBe(v.noise_3);
		const done = readStep(conn.onBinary(unhex(v.pair_complete), T0));
		expect(done.events.some((e) => e.type === 'Paired')).toBe(true);
		expect(done.close?.code).toBe(1000);
		expect(hex(conn.peerPublicKey()!)).toBe(vectors.keys.hub.public);
		// A pairing session is never Established: no channel binding exposed.
		expect(conn.channelBinding()).toBeUndefined();
		conn.free();
		keys.free();
	});

	it('resumes byte for byte and receives the session messages', () => {
		const v = vectors.resume;
		const keys = w.RelayKeys.generateForTests(seed('remote-static', 0));
		const conn = w.RelayRemoteConnection.resumeForTests(
			keys,
			unhex(vectors.keys.hub.public),
			T0,
			seed('remote-conn', 1)
		);
		expect(sent(readStep(conn.start()))).toBe(v.client_hello);
		expect(sent(readStep(conn.onBinary(unhex(v.server_hello), T0)))).toBe(v.ik_1);
		const established = readStep(conn.onBinary(unhex(v.ik_2), T0));
		expect(sent(established)).toBe(v.client_finished);
		expect(established.events.some((e) => e.type === 'Established')).toBe(true);
		expect(hex(conn.channelBinding()!)).toBe(v.channel_binding);
		const welcome = readStep(conn.onBinary(unhex(v.welcome_record), T0));
		expect(new TextDecoder().decode(welcome.deliver[0])).toBe(v.welcome_frame);
		const delivered: Uint8Array[] = [];
		for (const record of v.large_message.records as string[]) {
			delivered.push(...readStep(conn.onBinary(unhex(record), T0)).deliver);
		}
		expect(delivered).toHaveLength(1);
		expect(delivered[0].length).toBe(v.large_message.len);
		expect(delivered[0].every((b, i) => b === i % 251)).toBe(true);
		conn.free();
		keys.free();
	});

	it('production keys draw fresh entropy', () => {
		const a = w.RelayKeys.generate();
		const b = w.RelayKeys.generate();
		expect(hex(a.publicKey())).not.toBe(hex(b.publicKey()));
		a.free();
		b.free();
	});
});
