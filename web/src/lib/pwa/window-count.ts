/**
 * `#lib/pwa/window-count.js` — how many Crema windows the service worker sees
 * (`clients.matchAll({ type: 'window', includeUncontrolled: true })`), for the
 * SQLite history's flip (#310 SPEC §11, M2): tabs of builds before W1 hold no
 * stage lock, so the flip needs exactly one window, its own.
 *
 * Null when there's no service worker, or it doesn't answer in time (an
 * older one, without `sw-window-count.js`): then there's no flip.
 */
export const WINDOW_COUNT_TYPE = 'CREMA_WINDOW_COUNT';

export async function serviceWorkerWindowCount(
	container: ServiceWorkerContainer | undefined = typeof navigator !== 'undefined' ? navigator.serviceWorker : undefined,
	ms = 2_000
): Promise<number | null> {
	if (!container) return null;
	const reg = await container.getRegistration().catch(() => undefined);
	const sw = reg?.active;
	if (!sw) return null;
	return new Promise<number | null>((resolve) => {
		const ch = new MessageChannel();
		const t = setTimeout(() => {
			ch.port1.close();
			resolve(null);
		}, ms);
		ch.port1.onmessage = (e) => {
			clearTimeout(t);
			ch.port1.close();
			const n = (e.data as { count?: unknown } | null)?.count;
			resolve(typeof n === 'number' && Number.isSafeInteger(n) ? n : null);
		};
		sw.postMessage({ type: WINDOW_COUNT_TYPE }, [ch.port2]);
	});
}
