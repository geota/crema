// Imported by the service worker (vite.config.ts prepends the importScripts
// line to the built sw.js): answers how many Crema windows are open, for the
// SQLite history's flip (#310 SPEC §11, M2). Tabs of builds before W1 hold no
// stage lock, so the flip runs only when the service worker sees exactly one
// window, the flipping tab itself. Read only: it counts clients and nothing else.
self.addEventListener('message', (event) => {
	const port = event.ports && event.ports[0];
	if (!port || !event.data || event.data.type !== 'CREMA_WINDOW_COUNT') return;
	event.waitUntil(
		self.clients
			.matchAll({ type: 'window', includeUncontrolled: true })
			.then((all) => port.postMessage({ count: all.length }))
			.catch(() => port.postMessage({ count: null }))
	);
});
