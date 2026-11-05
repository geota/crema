/**
 * `#lib/shell/phone.svelte.js` — the phone-width breakpoint as reactive state.
 *
 * Every phone CSS rule in the web shell flips at `max-width: 600px`; this is
 * the same line for the few places that need it in script (the History and
 * Settings list → detail push). Read `phoneLayout.current` inside a template,
 * `$derived` or handler so it updates when the window crosses the line.
 */
import { MediaQuery } from 'svelte/reactivity';

export const PHONE_QUERY = '(max-width: 600px)';

/** True at phone width. The fallback only matters without `matchMedia`. */
export const phoneLayout = new MediaQuery(PHONE_QUERY, false);
