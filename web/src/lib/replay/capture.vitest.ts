import { describe, expect, it } from 'vitest';
import { sourceFromName } from './capture';

describe('sourceFromName', () => {
	it('maps the synthesised profile-upload acks the core records', () => {
		// core/de1-app/src/capture.rs names them DE1_HEADER_ACK / DE1_FRAME_ACK;
		// a replayed upload needs both (cohort review 02 P0-2).
		expect(sourceFromName('DE1_HEADER_ACK')).toBe('De1HeaderAck');
		expect(sourceFromName('DE1_FRAME_ACK')).toBe('De1FrameAck');
		expect(sourceFromName('NOT_A_SOURCE')).toBeUndefined();
	});
});
