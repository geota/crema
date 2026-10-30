/**
 * `#lib/services/profile-sync.vitest` — the acks the shell synthesises for
 * the DE1's profile writes (cohort review 02 P0-2). The wait for an upload
 * moved to the core's shot start (F16).
 */

import { describe, expect, it } from 'vitest';
import { profileWriteAck } from './profile-sync.ts';

describe('profileWriteAck (cohort review 02 P0-2)', () => {
	it('acks a delivered header as De1HeaderAck and a delivered frame as De1FrameAck', () => {
		expect(profileWriteAck('De1ProfileHeader', true)).toBe('De1HeaderAck');
		expect(profileWriteAck('De1ProfileFrame', true)).toBe('De1FrameAck');
	});

	it('never acks a write that did not reach the DE1', () => {
		expect(profileWriteAck('De1ProfileHeader', false)).toBeNull();
		expect(profileWriteAck('De1ProfileFrame', false)).toBeNull();
	});

	it('acks nothing for other targets', () => {
		expect(profileWriteAck('De1RequestedState', true)).toBeNull();
		expect(profileWriteAck('De1MmrWrite', true)).toBeNull();
	});
});
