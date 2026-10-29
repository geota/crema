/**
 * `#lib/core/stopped-guard.js` — stop calling into a wasm bridge that has
 * stopped.
 *
 * The bridge survives a panicked call by resetting its core, at most
 * `MAX_PANIC_RECOVERIES` times (de1-wasm). The next panic stops it:
 * `core_stopped()` turns true and every call throws "crema core stopped …"
 * until the page reloads (final cohort review 2026-10-28, 01 P3-1). That
 * throw comes from inside the wasm, so, like a trap, it skips the call's
 * epilogue and leaks its shadow-stack frame: a few hundred `on_tick` calls
 * would exhaust the stack and turn the clear error into "memory access out
 * of bounds". So once a call fails on a stopped bridge, this guard answers
 * every later call itself, with {@link CoreStoppedError}, without entering
 * the wasm, and tells the shell once.
 */

/** What every call on a stopped core fails with, from the shell's side. */
export class CoreStoppedError extends Error {
	override name = 'CoreStoppedError';
}

/** The part of the wasm `CremaBridge` the guard reads. */
export interface StoppableBridge {
	core_stopped(): boolean;
}

/** Methods that never touch the core, so they stay callable when stopped. */
const PASS_THROUGH: ReadonlySet<PropertyKey> = new Set([
	'core_stopped',
	'panic_recoveries',
	'free',
	'constructor'
]);

/**
 * Wrap `bridge` so that, once a call fails on a stopped bridge, `onStopped`
 * runs once and every later call throws {@link CoreStoppedError} without
 * calling into the wasm. Calls that fail for any other reason (a trap the
 * bridge will recover from, a `Result` error) are rethrown untouched.
 */
export function guardStoppedBridge<T extends StoppableBridge>(
	bridge: T,
	onStopped: () => void
): T {
	let stoppedMessage: string | null = null;
	const wrappers = new Map<PropertyKey, (...args: unknown[]) => unknown>();
	return new Proxy(bridge, {
		get(target, prop) {
			const value: unknown = Reflect.get(target, prop, target);
			if (typeof value !== 'function' || PASS_THROUGH.has(prop)) {
				return typeof value === 'function' ? value.bind(target) : value;
			}
			let wrapper = wrappers.get(prop);
			if (!wrapper) {
				const method = value as (...args: unknown[]) => unknown;
				wrapper = (...args: unknown[]) => {
					if (stoppedMessage !== null) throw new CoreStoppedError(stoppedMessage);
					try {
						return method.apply(target, args);
					} catch (err) {
						if (!target.core_stopped()) throw err;
						stoppedMessage = err instanceof Error ? err.message : String(err);
						onStopped();
						throw new CoreStoppedError(stoppedMessage);
					}
				};
				wrappers.set(prop, wrapper);
			}
			return wrapper;
		}
	});
}
