/**
 * `$lib/effect/schema/visualizer.test` — node:test suite for the Visualizer
 * wire schemas (docs/53 T-03).
 *
 * Run with the platform's built-in test runner:
 *
 * ```
 * cd web && node \
 *   --experimental-strip-types \
 *   --experimental-detect-module \
 *   --test src/lib/effect/schema/visualizer.test.ts
 * ```
 *
 * Asserts both the success path (a valid body decodes) and the failure path
 * (a malformed body decodes to `null`) — `null` being the value the prior
 * `as` cast sites already treated as "no usable response" via their
 * `if (!result …)` / `?.` / `??` guards. Schema validation does not change
 * that behavior; it only makes the failure observable.
 */

import assert from 'node:assert/strict';
import { describe, it } from 'vitest';
import {
	ShotUploadResultSchema,
	ShotListResponseSchema,
	decodeResponse
} from './visualizer.ts';

describe('ShotUploadResultSchema', () => {
	it('decodes a valid { id } body', () => {
		const r = decodeResponse(ShotUploadResultSchema, { id: 'abc-123' }, 'test');
		assert.deepEqual(r, { id: 'abc-123' });
	});

	it('ignores extra forward-compat fields', () => {
		const r = decodeResponse(
			ShotUploadResultSchema,
			{ id: 'abc-123', created_at: 999, brand_new_field: true },
			'test'
		);
		assert.deepEqual(r, { id: 'abc-123' });
	});

	it('returns null on a missing id (matches the prior !result.id guard)', () => {
		assert.equal(decodeResponse(ShotUploadResultSchema, {}, 'test'), null);
	});

	it('returns null on a wrong-typed id', () => {
		assert.equal(decodeResponse(ShotUploadResultSchema, { id: 42 }, 'test'), null);
	});

	it('returns null on a null body (204-shaped)', () => {
		assert.equal(decodeResponse(ShotUploadResultSchema, null, 'test'), null);
	});
});

describe('ShotListResponseSchema', () => {
	const page = {
		data: [
			{ id: 'a', clock: 1000, updated_at: 2000 },
			{ id: 'b', clock: 1500, updated_at: 2500 }
		],
		paging: { count: 2, page: 1, limit: 50, pages: 1 }
	};

	it('decodes a valid page', () => {
		const r = decodeResponse(ShotListResponseSchema, page, 'test');
		assert.equal(r?.data.length, 2);
		assert.deepEqual(r?.paging, { count: 2, page: 1, limit: 50, pages: 1 });
	});

	it('returns null when data is not an array', () => {
		assert.equal(
			decodeResponse(ShotListResponseSchema, { ...page, data: 'nope' }, 'test'),
			null
		);
	});

	it('returns null when a summary row is malformed', () => {
		const bad = { ...page, data: [{ id: 'a', clock: 'x', updated_at: 2 }] };
		assert.equal(decodeResponse(ShotListResponseSchema, bad, 'test'), null);
	});

	it('returns null on a null body (the ?. / ?? guards then default it)', () => {
		assert.equal(decodeResponse(ShotListResponseSchema, null, 'test'), null);
	});
});
