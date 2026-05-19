/**
 * Live `data-theme` signal.
 *
 * The settings store flips `data-theme` on `<html>` when the Display toggle
 * changes. CSS reacts to that on its own, but the canvas chart components
 * (`LiveChart`, `StaticShotChart`, `ProfileCurveEditor`, `ProfilePreview`)
 * bake colours into the canvas at draw time and only rebuild on *structural*
 * change — so a theme flip would leave their already-drawn colours stale.
 *
 * This module exposes the current theme as a `$state` rune kept in sync by a
 * single `MutationObserver`. A chart effect that reads `theme.current` re-runs
 * on every flip and can call `chart.redraw()` to repaint with the new tokens.
 */

type Theme = 'light' | 'dark';

function read(): Theme {
	if (typeof document === 'undefined') return 'dark';
	return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
}

class ThemeSignal {
	current = $state<Theme>(read());

	constructor() {
		if (typeof document === 'undefined') return;
		new MutationObserver(() => {
			this.current = read();
		}).observe(document.documentElement, {
			attributes: true,
			attributeFilter: ['data-theme']
		});
	}
}

/** Singleton — read `theme.current` inside an `$effect` to react to flips. */
export const theme = new ThemeSignal();
