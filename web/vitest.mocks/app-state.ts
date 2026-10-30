/**
 * Vitest stand-in for SvelteKit's `$app/state` virtual module (see
 * `app-navigation.ts`). Components read `page.url`; tests that need a
 * particular URL set `page.url` before mounting.
 */
export const page: { url: URL; params: Record<string, string> } = {
	url: new URL('http://localhost/'),
	params: {}
};
