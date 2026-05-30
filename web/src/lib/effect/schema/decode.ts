/**
 * `$lib/effect/schema/decode` — the shared decode-or-fallback primitive used
 * at every untrusted boundary (HTTP responses, localStorage, IndexedDB,
 * capture files).
 *
 * The Phase 2 contract (docs/53 §2.3, §4.2): schema validation must preserve
 * the *failure mode* of the guard it replaces. Every prior guard returned some
 * fallback on bad input (a default shape, `null`, `[]`); `decodeOr` does the
 * same — and additionally **logs** the parse error so previously-silent
 * corruption becomes observable. That logging is the only behavior change.
 */

import { Either, Schema } from 'effect';

/**
 * Decode `input` against `schema`. On success returns the decoded value; on
 * failure logs a warning tagged with `context` and returns `fallback`.
 *
 * `fallback` may be a wider type than the schema's output (e.g. `null` for a
 * `TokenSet` schema), so the result is `A | F`.
 */
export function decodeOr<A, I, F>(
	schema: Schema.Schema<A, I>,
	input: unknown,
	fallback: F,
	context: string
): A | F {
	return Either.getOrElse(Schema.decodeUnknownEither(schema)(input), (error) => {
		console.warn(`[crema] decode failed (${context})`, error);
		return fallback;
	});
}
