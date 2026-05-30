/**
 * `$lib/effect/schema/tokens` — `Schema` for the persisted Visualizer OAuth
 * `TokenSet` (docs/53 §1.5).
 *
 * Replaces the shallow `typeof raw.accessToken === 'string'` guard in
 * `visualizer/token-store.ts`. The shape mirrors the `TokenSet` interface in
 * `visualizer/oauth.ts`; `tokenSetFromWire` always produces all five fields,
 * so any legitimately-persisted token validates, and a corrupt one decodes to
 * the `null` fallback (forcing a clean re-auth — the prior failure mode).
 */

import { Schema } from 'effect';

export const TokenSetSchema = Schema.Struct({
	accessToken: Schema.String,
	refreshToken: Schema.NullOr(Schema.String),
	expiresAt: Schema.Number,
	scope: Schema.String,
	tokenType: Schema.String
});

export type TokenSet = Schema.Schema.Type<typeof TokenSetSchema>;
