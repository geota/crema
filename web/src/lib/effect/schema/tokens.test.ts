/**
 * `$lib/effect/schema/tokens.test` — node:test suite for TokenSetSchema
 * (docs/53 T-04).
 *
 * Run: `cd web && node --experimental-strip-types --experimental-detect-module
 *   --test src/lib/effect/schema/tokens.test.ts`
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { decodeOr } from './decode.ts';
import { TokenSetSchema } from './tokens.ts';

const valid = {
	accessToken: 'tok',
	refreshToken: 'ref',
	expiresAt: 1_700_000_000_000,
	scope: 'read write upload',
	tokenType: 'Bearer'
};

describe('TokenSetSchema', () => {
	it('decodes a full token set', () => {
		assert.deepEqual(decodeOr(TokenSetSchema, valid, null, 'test'), valid);
	});

	it('accepts a null refreshToken (non-rotating Doorkeeper config)', () => {
		const t = { ...valid, refreshToken: null };
		assert.deepEqual(decodeOr(TokenSetSchema, t, null, 'test'), t);
	});

	it('returns null when accessToken is missing (the prior guard)', () => {
		const { accessToken: _omit, ...rest } = valid;
		assert.equal(decodeOr(TokenSetSchema, rest, null, 'test'), null);
	});

	it('returns null when accessToken is the wrong type', () => {
		assert.equal(decodeOr(TokenSetSchema, { ...valid, accessToken: 42 }, null, 'test'), null);
	});

	it('returns null on a non-object', () => {
		assert.equal(decodeOr(TokenSetSchema, 'nope', null, 'test'), null);
	});
});
