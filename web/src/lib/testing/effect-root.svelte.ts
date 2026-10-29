/**
 * Test helper: run `fn` inside a real Svelte effect, the way an attachment or
 * an `$effect` would, so a test can catch reactive self-loops
 * (`effect_update_depth_exceeded`) that a plain call cannot. Returns the
 * teardown, which also runs `fn`'s cleanup.
 */
import { flushSync } from 'svelte';

export function runInEffect(fn: () => (() => void) | void): () => void {
	const destroy = $effect.root(() => {
		$effect(() => fn());
	});
	flushSync();
	return destroy;
}
