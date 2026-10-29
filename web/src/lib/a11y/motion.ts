/**
 * Reduced-motion helpers for JavaScript-driven motion. The global CSS rule
 * (`styles/mode-controls.css`) cannot reach a `scrollTo({ behavior: 'smooth' })`
 * call, which sets the behaviour explicitly (final cohort review 07 N9).
 */

const REDUCE = '(prefers-reduced-motion: reduce)';

/** Whether the user asked the OS for reduced motion. */
export function prefersReducedMotion(): boolean {
	return typeof matchMedia === 'function' && matchMedia(REDUCE).matches;
}

/**
 * The `behavior` for a programmatic scroll: `'smooth'`, or `'auto'` (an
 * instant jump) when reduced motion is on. Read at call time, so a change
 * in the OS setting applies to the next scroll.
 */
export function scrollBehavior(): ScrollBehavior {
	return prefersReducedMotion() ? 'auto' : 'smooth';
}
