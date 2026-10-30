/**
 * `#lib/testing/dom-stubs` — import FIRST in a vitest file that mounts a
 * component drawing charts (uPlot). jsdom has no `matchMedia`, 2D canvas,
 * `ResizeObserver` or `Path2D`; these no-op stand-ins let such a component
 * mount so a test can check its wiring. NOT a test file; not used by the app.
 */

if (typeof window !== 'undefined' && !window.matchMedia) {
	window.matchMedia = ((query: string) => ({
		matches: false,
		media: query,
		onchange: null,
		addListener: () => {},
		removeListener: () => {},
		addEventListener: () => {},
		removeEventListener: () => {},
		dispatchEvent: () => false
	})) as unknown as typeof window.matchMedia;
}

if (typeof HTMLCanvasElement !== 'undefined') {
	HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement) {
		const canvas = this;
		return new Proxy(
			{},
			{
				get: (_t, key) =>
					key === 'canvas' ? canvas : () => ({ width: 0, actualBoundingBoxAscent: 0 }),
				set: () => true
			}
		);
	} as unknown as typeof HTMLCanvasElement.prototype.getContext;
}

const g = globalThis as Record<string, unknown>;
if (!g.ResizeObserver) {
	g.ResizeObserver = class {
		observe(): void {}
		unobserve(): void {}
		disconnect(): void {}
	};
}
if (!g.Path2D) {
	g.Path2D = class {
		moveTo(): void {}
		lineTo(): void {}
		rect(): void {}
		arc(): void {}
		closePath(): void {}
		bezierCurveTo(): void {}
		quadraticCurveTo(): void {}
		addPath(): void {}
	};
}

export {};
