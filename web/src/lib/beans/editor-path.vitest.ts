import { describe, expect, it } from 'vitest';
import { beanEditPath } from './editor-path';

describe('beanEditPath (cohort review 06 #17)', () => {
	it('encodes ids that carry URL syntax', () => {
		expect(beanEditPath('imp/ort?x#1', '?from=brew')).toBe('beans/imp%2Fort%3Fx%231/edit?from=brew');
	});

	it('leaves a plain id alone', () => {
		expect(beanEditPath('b-123')).toBe('beans/b-123/edit');
	});
});
