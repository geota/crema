/**
 * `$lib/settings` — the app-preferences layer.
 *
 * A single localStorage-backed `$state` store holding every Crema *app*
 * preference: units, theme, sound cues, brew defaults, display density and the
 * advanced toggles. Machine and scale settings are NOT here — they belong to
 * the DE1 / the live `UiSnapshot`; this store is the app's own state, and
 * everything in it survives a reload. See `store.svelte.ts`.
 */

export {
	SettingsStore,
	getSettingsStore,
	DEFAULT_SETTINGS,
	type Settings,
	type ThemePref,
	type WeightUnit,
	type TempUnit,
	type VolumeUnit,
	type PressureUnit,
	type Density,
	type SharingPrivacy
} from './store.svelte';
