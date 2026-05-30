/**
 * `$lib/effect/errors` — the single source of truth for every typed failure
 * in the web app's async subsystems.
 *
 * Every async failure path in `web/src/lib/` maps to exactly one of these
 * `Data.TaggedError`s (see docs/53 §1.4). The list is intended to be closed:
 * new failure modes add a class here, and reviewers grep this file for
 * completeness. Tagged errors carry a `_tag` discriminant so they can be
 * matched with `Effect.catchTag` / `Match.tag` without `instanceof`.
 *
 * These are pure data declarations — no runtime is required to import them,
 * and they are tree-shakeable. Call sites are wired in later phases.
 */

import { Data } from 'effect';

// ── Networking / HTTP boundary ──────────────────────────────────────────

export class NetworkError extends Data.TaggedError('NetworkError')<{
	readonly cause: unknown;
	readonly url: string;
}> {}

export class HttpStatusError extends Data.TaggedError('HttpStatusError')<{
	readonly status: number;
	readonly url: string;
	readonly body?: string;
}> {}

export class ResponseDecodeError extends Data.TaggedError('ResponseDecodeError')<{
	readonly url: string;
	/** A `ParseError` from `Schema.decode`, kept opaque at this boundary. */
	readonly cause: unknown;
}> {}

// ── OAuth / auth ────────────────────────────────────────────────────────

export class NotAuthenticatedError extends Data.TaggedError('NotAuthenticatedError')<{}> {}

export class TokenRefreshFailedError extends Data.TaggedError('TokenRefreshFailedError')<{
	readonly cause: unknown;
}> {}

export class OAuthStateMismatchError extends Data.TaggedError('OAuthStateMismatchError')<{
	readonly expected: string | null;
	readonly got: string | null;
}> {}

export class OAuthNotConfiguredError extends Data.TaggedError('OAuthNotConfiguredError')<{}> {}

// ── Visualizer domain errors ────────────────────────────────────────────

export class VisualizerPremiumGatedError extends Data.TaggedError('VisualizerPremiumGatedError')<{
	readonly endpoint: string;
}> {}

export class VisualizerNotFoundError extends Data.TaggedError('VisualizerNotFoundError')<{
	readonly visualizerId: string;
}> {}

// ── Profile upload (DE1 BLE) ────────────────────────────────────────────

export class NoActiveProfileError extends Data.TaggedError('NoActiveProfileError')<{}> {}

export class ProfileSyncFailedError extends Data.TaggedError('ProfileSyncFailedError')<{
	readonly reason: 'upload-failed' | 'timeout' | 'cancelled-by-newer';
}> {}

export class ProfileUploadTimeoutError extends Data.TaggedError('ProfileUploadTimeoutError')<{}> {}

// ── BLE connect sequence ────────────────────────────────────────────────

export class De1ConnectStepFailed extends Data.TaggedError('De1ConnectStepFailed')<{
	readonly step: string;
	readonly cause: unknown;
}> {}

export class ScaleConnectStepFailed extends Data.TaggedError('ScaleConnectStepFailed')<{
	readonly step: string;
	readonly cause: unknown;
}> {}

export class De1WriteRefusedError extends Data.TaggedError('De1WriteRefusedError')<{
	readonly target: string;
	readonly reason: 'no-uuid' | 'not-connected';
}> {}

// ── Replay ──────────────────────────────────────────────────────────────

export class ReplayAbortedError extends Data.TaggedError('ReplayAbortedError')<{}> {}

export class ReplayParseError extends Data.TaggedError('ReplayParseError')<{
	readonly fileName: string;
	readonly cause: unknown;
}> {}

export class ReplayAlreadyRunningError extends Data.TaggedError('ReplayAlreadyRunningError')<{}> {}

// ── Persistence boundary ────────────────────────────────────────────────

export class PersistenceDecodeError extends Data.TaggedError('PersistenceDecodeError')<{
	readonly key: string;
	/** A `ParseError` from `Schema.decode`. */
	readonly cause: unknown;
}> {}

export class PersistenceQuotaError extends Data.TaggedError('PersistenceQuotaError')<{
	readonly key: string;
}> {}

// ── Webhook fire-and-forget (still typed so logs are queryable) ─────────

export class WebhookFailedError extends Data.TaggedError('WebhookFailedError')<{
	readonly url: string;
	readonly cause: unknown;
}> {}
