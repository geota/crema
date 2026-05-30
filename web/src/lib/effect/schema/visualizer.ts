/**
 * `$lib/effect/schema/visualizer` — `Schema`s for the Visualizer HTTP wire
 * shapes the web app consumes (docs/53 §1.5).
 *
 * These validate **untrusted** JSON coming back from the network. Each schema
 * is deliberately permissive about *extra* fields and validates only the
 * fields the calling code actually reads — so a forward-compatible server
 * response (new fields) is never rejected, and a malformed one is caught.
 *
 * The schemas mirror the generated OpenAPI types in `visualizer/openapi.d.ts`
 * (regenerate via `pnpm openapi`); that file remains the source of truth for
 * the full shapes.
 */

import { Schema } from 'effect';
import { decodeOr } from './decode.ts';

// ── `POST /oauth/token` response ────────────────────────────────────────

/**
 * The Doorkeeper token-endpoint wire body. Every field is optional: success
 * carries `access_token` (+ friends), failure carries `error` /
 * `error_description`. `tokenSetFromWire` does the success/failure disambiguation
 * downstream — this schema only validates field *types*.
 */
export const TokenWireSchema = Schema.Struct({
	access_token: Schema.optional(Schema.String),
	token_type: Schema.optional(Schema.String),
	expires_in: Schema.optional(Schema.Number),
	refresh_token: Schema.optional(Schema.String),
	scope: Schema.optional(Schema.String),
	error: Schema.optional(Schema.String),
	error_description: Schema.optional(Schema.String)
});

// ── `POST /shots/upload` result ─────────────────────────────────────────

/** `{ id }` — the only field `uploadShot` reads off the upload result. */
export const ShotUploadResultSchema = Schema.Struct({
	id: Schema.String
});
export type ShotUploadResult = Schema.Schema.Type<typeof ShotUploadResultSchema>;

// ── `POST /coffee_bags` / `POST /roasters` results ──────────────────────

/** `{ id }` — the remote id `BeanSync.uploadBean` binds onto the local bag. */
export const BeanUploadResultSchema = Schema.Struct({
	id: Schema.String
});
export type BeanUploadResult = Schema.Schema.Type<typeof BeanUploadResultSchema>;

/** `{ id }` — the remote id `BeanSync.uploadRoaster` binds onto the roaster. */
export const RoasterUploadResultSchema = Schema.Struct({
	id: Schema.String
});
export type RoasterUploadResult = Schema.Schema.Type<typeof RoasterUploadResultSchema>;

// ── `GET /me` account ───────────────────────────────────────────────────

/** The `/me` response: the four fields the Settings UI projects + reads. */
export const VisualizerAccountSchema = Schema.Struct({
	id: Schema.String,
	name: Schema.String,
	public: Schema.Boolean,
	avatar_url: Schema.String
});
export type VisualizerAccountWire = Schema.Schema.Type<typeof VisualizerAccountSchema>;

// ── `GET /shots` page ───────────────────────────────────────────────────

/** A shot summary row: the `{ id, clock, updated_at }` triple. */
export const ShotSummarySchema = Schema.Struct({
	id: Schema.String,
	clock: Schema.Number,
	updated_at: Schema.Number
});

/** Pagination envelope. */
export const PagingSchema = Schema.Struct({
	count: Schema.Number,
	page: Schema.Number,
	limit: Schema.Number,
	pages: Schema.Number
});

/** `{ data, paging }` — one page of shot summaries. */
export const ShotListResponseSchema = Schema.Struct({
	data: Schema.Array(ShotSummarySchema),
	paging: PagingSchema
});
export type ShotListResponse = Schema.Schema.Type<typeof ShotListResponseSchema>;

// ── Decode helper ───────────────────────────────────────────────────────

/**
 * Decode an untrusted HTTP body against `schema`, returning `null` when it
 * doesn't validate. This preserves the prior call sites' failure mode exactly
 * — they already guarded the cast result with `if (!result …)` / `?.` / `??`,
 * so a `null` here flows down the same path a bad cast did — while making the
 * parse failure **observable** (the one intentional change in Phase 2: silent
 * corruption becomes a logged warning).
 */
export function decodeResponse<A, I>(
	schema: Schema.Schema<A, I>,
	input: unknown,
	context: string
): A | null {
	return decodeOr(schema, input, null, `visualizer ${context}`);
}
