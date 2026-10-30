/**
 * `#lib/testing/reactive-box` — one piece of `$state` for tests that drive a
 * rune-backed class (a `$derived` seed getter) from outside a component. NOT a
 * test file.
 */
export class ReactiveBox<T> {
	value = $state() as T;

	constructor(initial: T) {
		this.value = initial;
	}
}
