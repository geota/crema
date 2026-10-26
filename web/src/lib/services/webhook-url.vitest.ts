import { describe, expect, it } from 'vitest';
import { isAllowedWebhookUrl } from './webhook-url';

// Review 05 note: the old prefix check accepted look-alike hosts.
describe('isAllowedWebhookUrl', () => {
	it.each([
		'https://hook.example/crema',
		'  https://hooks.zapier.com/x  ',
		'http://localhost:8787/hook',
		'http://127.0.0.1/hook',
		'http://[::1]:3000/'
	])('allows %s', (url) => expect(isAllowedWebhookUrl(url)).toBe(true));

	it.each([
		'http://localhost.evil.com/hook',
		'http://localhost@evil.com/hook',
		'http://127.0.0.1.evil.com/',
		'https://user:pass@hook.example/',
		'http://hook.example/',
		'ftp://hook.example/',
		'javascript:alert(1)',
		'hook.example',
		''
	])('refuses %s', (url) => expect(isAllowedWebhookUrl(url)).toBe(false));
});
