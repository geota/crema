import { describe, expect, it } from 'vitest';
import { prepareImportedShot, type CoreImportedShot } from './imported-shot';

function imported(storedShot: Partial<CoreImportedShot['storedShot']>, beanName = 'Gesha') {
	return {
		storedShot: {
			completedAt: 1_700_000_000_000,
			record: { duration: 28_000 },
			metadata: { dose: 18 },
			...storedShot
		},
		beanId: 'bean:a',
		beanName,
		roasterName: null,
		roastedOn: null,
		roastLevel: null,
		grinderModel: null
	} satisfies CoreImportedShot;
}

describe('prepareImportedShot', () => {
	it('keeps the core’s record that the brew took nothing', () => {
		const shot = prepareImportedShot(imported({ bagDebit: 0 }), new Map());
		expect(shot.bagDebit).toBe(0);
	});

	it('records nothing on a row with no bag', () => {
		const shot = prepareImportedShot(imported({ bagDebit: 0 }, ''), new Map());
		expect(shot.bagDebit).toBeUndefined();
	});
});
