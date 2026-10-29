/*
 * Crema: paint the stored theme before the first frame (final cohort review
 * 07 N11). The app is client-rendered (ssr = false), so without this the
 * settings store sets `data-theme` only after the bundle loads, and light or
 * System users saw a dark first frame and a dark status bar on every launch.
 *
 * A same-origin blocking script, not an inline one: the CSP is in hash mode
 * and covers only SvelteKit's own bootstrap. Keep it tiny and dependency-free.
 * Mirrors `resolveTheme` / `THEME_COLOR` in `src/lib/settings/store.svelte.ts`
 * (pinned by `src/lib/settings/theme-boot.vitest.ts`).
 */
(function () {
	var KEY = 'crema.settings.v1';
	var THEME_COLOR = { dark: '#1e1b16', light: '#faf7f1' };
	var pref = 'dark';
	try {
		var raw = localStorage.getItem(KEY);
		if (raw) {
			var stored = JSON.parse(raw);
			// Nested cross-shell shape `{ common: { themeMode } }`; a
			// pre-unification flat blob kept `theme` at the top level.
			var mode =
				stored && stored.common && typeof stored.common === 'object'
					? stored.common.themeMode
					: stored && stored.theme;
			if (mode === 'light' || mode === 'system') pref = mode;
		}
	} catch (e) {
		// Unreadable storage: keep the dark default; the store reports it.
	}
	var light =
		pref === 'light' ||
		(pref === 'system' &&
			typeof matchMedia === 'function' &&
			matchMedia('(prefers-color-scheme: light)').matches);
	var theme = light ? 'light' : 'dark';
	document.documentElement.setAttribute('data-theme', theme);
	var meta = document.querySelector('meta[name="theme-color"]');
	if (meta) meta.setAttribute('content', THEME_COLOR[theme]);
})();
