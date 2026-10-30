/**
 * `#lib/services/profile-sync.js` — the shell's half of a profile upload: the
 * acks it synthesises for the DE1's profile writes.
 *
 * The core owns the profile sync (`profile_sync.rs`) and the shot start's wait
 * for it (`shot_start.rs`, F16), and the firmware's profile-download guard
 * (`machine_requests.rs`). See `profile-sync.vitest.ts`.
 */

/**
 * The ack the shell synthesises for a profile write, or `null` for none.
 *
 * The DE1 answers a `HeaderWrite` / `FrameWrite` with only an ATT write
 * response, so the shell confirms each one to the core itself: the header as
 * `De1HeaderAck`, every frame as `De1FrameAck`. A write that did not reach
 * the DE1 (`delivered === false`) is never acknowledged, so the core fails
 * the upload instead of reporting a half-written profile as loaded (cohort
 * review 02 P0-2). Android's `De1BleManager.writeCharacteristic` applies the
 * same rule.
 */
export function profileWriteAck(
	target: string,
	delivered: boolean
): 'De1HeaderAck' | 'De1FrameAck' | null {
	if (!delivered) return null;
	if (target === 'De1ProfileHeader') return 'De1HeaderAck';
	if (target === 'De1ProfileFrame') return 'De1FrameAck';
	return null;
}
