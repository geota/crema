/**
 * `$lib/utils/error` — shared error helpers.
 *
 * `catch` binds an `unknown`, and a thrown value need not be an `Error`. This
 * is the one place that turns any caught value into a human-readable string,
 * so the BLE layer, the orchestrator and the layout do not each re-implement
 * the `error instanceof Error ? error.message : String(error)` dance.
 */

/** Best-effort human-readable message from an unknown thrown value. */
export function describeError(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}
