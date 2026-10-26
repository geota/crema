/**
 * `#lib/services/webhook-url.js` — which webhook URLs the shell will POST to.
 *
 * `https://` anywhere, or plain `http://` only to this machine
 * (`localhost`, `127.0.0.1`, `[::1]`) for development. The URL is parsed and
 * the HOSTNAME compared, so `http://localhost.evil.com` and
 * `http://localhost@evil.com` are refused (a prefix check accepted both).
 * Shared by the Settings field and the send path (`./webhooks`), so a URL
 * that arrives some other way (an import, a hand-edited localStorage row)
 * is re-checked before anything is sent.
 */

const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

export function isAllowedWebhookUrl(raw: string): boolean {
	let url: URL;
	try {
		url = new URL(raw.trim());
	} catch {
		return false;
	}
	if (url.username || url.password) return false;
	if (url.protocol === 'https:') return url.hostname.length > 0;
	if (url.protocol === 'http:') return LOOPBACK_HOSTS.has(url.hostname);
	return false;
}
