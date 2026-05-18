// The Crema shell is browser-only: it relies on wasm and Web Bluetooth, neither
// of which exists on the server. Disable SSR and prerendering globally so the
// whole app renders as a client-side SPA (adapter-static `fallback`).
export const ssr = false;
export const prerender = false;
