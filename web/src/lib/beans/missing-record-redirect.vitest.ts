import { describe, expect, it } from 'vitest';
import { missingRecordRedirect } from './missing-record-redirect.ts';

describe('missingRecordRedirect (review 03 P3-6)', () => {
	it('redirects an id that never resolved', () => {
		const shouldRedirect = missingRecordRedirect();
		expect(shouldRedirect('gone', false)).toBe(true);
	});

	it('does not redirect when the shown record is deleted from the editor', () => {
		const shouldRedirect = missingRecordRedirect();
		expect(shouldRedirect('b1', true)).toBe(false);
		// The editor's delete empties the record; its own goto(returnTo) must win.
		expect(shouldRedirect('b1', false)).toBe(false);
	});

	it('still redirects a different unknown id after a param change', () => {
		const shouldRedirect = missingRecordRedirect();
		shouldRedirect('b1', true);
		expect(shouldRedirect('b2', false)).toBe(true);
	});

	it('ignores an empty id (route params not ready)', () => {
		expect(missingRecordRedirect()('', false)).toBe(false);
	});
});
