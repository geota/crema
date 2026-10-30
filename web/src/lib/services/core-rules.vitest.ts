/**
 * F17: web's Visualizer refresh classification and retry-queue drain follow
 * the core's rules (`classify_token_refresh_failure`,
 * `visualizer_queue_decision`), which Android's queue follows too.
 */

import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { HttpStatusError, NetworkError, TokenRefreshFailedError } from '../effect/errors.ts';
import { classifyRefreshFailure } from './oauth.ts';
import { queueDecision } from './visualizer-call.ts';

const httpError = (status: number, body?: string) =>
	new HttpStatusError({ status, url: 'https://visualizer.coffee/oauth/token', body });

describe('F17 web on the core rules', () => {
	it('only a 400 / 401 invalid_grant signs out', () => {
		const grant = '{"error":"invalid_grant"}';
		expect(classifyRefreshFailure(httpError(400, grant))).toBeInstanceOf(TokenRefreshFailedError);
		expect(classifyRefreshFailure(httpError(401, grant))).toBeInstanceOf(TokenRefreshFailedError);
		expect(classifyRefreshFailure(httpError(503))).toBeInstanceOf(HttpStatusError);
		expect(classifyRefreshFailure(httpError(403, '<html>proxy</html>'))).toBeInstanceOf(NetworkError);
		expect(classifyRefreshFailure(httpError(401, '{"error":"invalid_client"}'))).toBeInstanceOf(NetworkError);
	});

	it('the drain asks the core what to do with a failure', () => {
		const network = new NetworkError({ cause: 'offline', url: 'https://visualizer.coffee/api' });
		expect(queueDecision('create', network, 1)).toEqual({
			kind: 'Retry',
			details: { delay_ms: 2000, stop_pass: false }
		});
		expect(queueDecision('create', network, 3)).toEqual({ kind: 'Drop', details: { stop_pass: false } });
		expect(queueDecision('create', httpError(429), 1)).toEqual({
			kind: 'Retry',
			details: { delay_ms: 120000, stop_pass: true }
		});
		const src = readFileSync(path.resolve('src/lib/services/upload-queue.ts'), 'utf-8');
		expect(src).toMatch(/queueDecision\(entry\.op, e, made\)/);
		expect(src).not.toMatch(/isRecoverable|quotaLimit|AUTH_PARK_MS|MAX_ATTEMPTS\b/);
	});
});
