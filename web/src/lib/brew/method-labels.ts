/**
 * `$lib/brew/method-labels` — the web's display labels for the core's
 * curated method presets, keyed by preset id. Its own module so the
 * method vocabulary (`methods.ts`) and the custom-method store
 * (`custom-methods.svelte.ts`) can both read it without a cycle.
 */

/** Display labels, keyed by the core preset id. Android's `BrewMethods.kt` matches. */
export const PRESET_METHOD_LABELS: Readonly<Record<string, string>> = {
	espresso: 'Espresso',
	pourover: 'V60 / pourover',
	aeropress: 'AeroPress',
	french_press: 'French press',
	moka: 'Moka',
	cold_brew: 'Cold brew',
	drip: 'Drip machine',
	siphon: 'Siphon',
	clever: 'Clever / Switch',
	chemex: 'Chemex',
	kalita_wave: 'Kalita Wave'
};
