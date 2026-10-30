/**
 * Vitest stand-in for SvelteKit's `$app/navigation` virtual module — the
 * real one exists only inside the SvelteKit runtime, so any import chain
 * that reaches it (e.g. `#lib/bean/bag-empty-prompt.js`) failed to collect
 * under vitest. Tests don't navigate; a resolved no-op is enough.
 */
export function goto(): Promise<void> {
	return Promise.resolve();
}

/** Navigation hooks: never fire, since tests don't navigate. */
export function afterNavigate(): void {}

export function beforeNavigate(): void {}
