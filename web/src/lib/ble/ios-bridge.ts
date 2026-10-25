/**
 * `#lib/ble/ios-bridge.js` — Web Bluetooth on iPhone / iPad through the Beacio
 * Safari extension.
 *
 * Safari has no `navigator.bluetooth`. The Beacio extension exposes its own
 * `navigator.beacio` object instead, and the `@beacio/core/auto` polyfill maps
 * that onto a standard `navigator.bluetooth`, so the rest of `#lib/ble/index.js` runs
 * unchanged.
 *
 * ## Why the polyfill is gated
 *
 * The polyfill is only fetched and run when the extension is actually present
 * (`navigator.beacio.__beacio === true`) and the browser has no Web Bluetooth
 * of its own. Imported unconditionally it would, on every browser without Web
 * Bluetooth (Firefox, desktop Safari, iOS without the extension), install a
 * stub `navigator.bluetooth` whose `requestDevice()` pops Beacio's App Store
 * banner — and `isWebBluetoothSupported()` would start lying. The dynamic
 * import also keeps it out of the main bundle: other browsers never download it.
 *
 * Its built-in auto-reconnect is switched off before it loads: `BleDevice`
 * runs its own reconnect loop, and two loops would race each other's
 * `gatt.connect()`.
 *
 * The extension may announce itself after the app boots
 * (`beacio:extension:ready`), so a listener installs the bridge late too.
 */

/** The extension's handshake object (`navigator.beacio`). */
interface BeacioNavigator {
	beacio?: { __beacio?: unknown };
}

/** Fired on `window` once the extension has injected `navigator.beacio`. */
export const BEACIO_READY_EVENT = 'beacio:extension:ready';

/** How long launch waits on iOS for a late extension, ms. */
export const IOS_BRIDGE_WAIT_MS = 1_500;

let bridged = false;
let installing: Promise<boolean> | null = null;
let lateListener: (() => void) | null = null;

/** Whether `navigator.bluetooth` is the Beacio bridge (not native). */
export function isBridgedBluetooth(): boolean {
	return bridged;
}

/** Whether the Beacio extension is active on this page. */
export function beacioExtensionPresent(): boolean {
	if (typeof navigator === 'undefined') return false;
	return (navigator as Navigator & BeacioNavigator).beacio?.__beacio === true;
}

/** iPhone / iPad, including iPadOS reporting itself as a Mac. */
export function isAppleMobile(): boolean {
	if (typeof navigator === 'undefined') return false;
	return (
		/iPhone|iPad|iPod/.test(navigator.userAgent) ||
		(navigator.userAgent.includes('Macintosh') && navigator.maxTouchPoints > 1)
	);
}

/**
 * Crema running as an installed Home Screen app on iPhone / iPad. iOS loads
 * no Safari extensions there, so Bluetooth can only work in a Safari tab.
 */
export function isIOSHomeScreenApp(): boolean {
	if (!isAppleMobile() || typeof window === 'undefined') return false;
	return (
		(navigator as Navigator & { standalone?: boolean }).standalone === true ||
		(typeof window.matchMedia === 'function' &&
			window.matchMedia('(display-mode: standalone)').matches)
	);
}

/** A link that opens `href` in Safari proper (`x-safari-https://…`), or null. */
export function openInSafariUrl(href: string): string | null {
	try {
		const url = new URL(href);
		return url.protocol === 'https:' || url.protocol === 'http:' ? `x-safari-${url.href}` : null;
	} catch {
		return null;
	}
}

function install(load: () => Promise<unknown>): Promise<boolean> {
	installing ??= (async () => {
		(window as Window & { beacioAutoReconnect?: { enabled: boolean } }).beacioAutoReconnect = {
			enabled: false
		};
		await load();
		bridged = navigator.bluetooth !== undefined;
		return bridged;
	})().catch(() => {
		installing = null;
		return false;
	});
	return installing;
}

/**
 * Install the Beacio bridge when this browser needs it and the extension is
 * there. Resolves `true` once `navigator.bluetooth` is the bridge, `false`
 * when the browser has native Web Bluetooth or no extension shows up.
 *
 * On iPhone / iPad it waits up to `waitMs` for a late extension; elsewhere it
 * never delays. Either way a late `beacio:extension:ready` still installs the
 * bridge, so a Connect tap after that works, and `onLateInstall` runs once it
 * has (the launch reconnect missed it).
 */
export async function installIosBridge(
	opts: { waitMs?: number; load?: () => Promise<unknown>; onLateInstall?: () => void } = {}
): Promise<boolean> {
	if (typeof window === 'undefined' || typeof navigator === 'undefined') return false;
	if (bridged) return true;
	if (navigator.bluetooth !== undefined) return false;
	const load = opts.load ?? (() => import('@beacio/core/auto'));
	if (beacioExtensionPresent()) return install(load);

	const late = new Promise<boolean>((resolve) => {
		if (lateListener !== null) window.removeEventListener(BEACIO_READY_EVENT, lateListener);
		lateListener = () => {
			lateListener = null;
			if (installing !== null) resolve(installing);
			else if (navigator.bluetooth !== undefined || !beacioExtensionPresent()) resolve(false);
			else resolve(install(load));
		};
		window.addEventListener(BEACIO_READY_EVENT, lateListener, { once: true });
	});
	let settled = false;
	void late.then((ok) => {
		if (ok && settled) opts.onLateInstall?.();
	});
	if (!isAppleMobile()) {
		settled = true;
		return false;
	}
	const waitMs = opts.waitMs ?? IOS_BRIDGE_WAIT_MS;
	const result = await Promise.race([
		late,
		new Promise<boolean>((resolve) => setTimeout(() => resolve(false), waitMs))
	]);
	settled = true;
	return result;
}

/** Test seam: forget the installed bridge. */
export function resetIosBridgeForTests(): void {
	bridged = false;
	installing = null;
	if (lateListener !== null && typeof window !== 'undefined') {
		window.removeEventListener(BEACIO_READY_EVENT, lateListener);
	}
	lateListener = null;
}
