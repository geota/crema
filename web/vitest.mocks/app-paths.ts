/**
 * Vitest stand-in for SvelteKit's `$app/paths` virtual module — see
 * `app-navigation.ts`. No base path in tests: `resolve` is identity.
 */
export function resolve(path: string): string {
	return path;
}
