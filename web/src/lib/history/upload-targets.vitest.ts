import { describe, expect, it } from 'vitest';
import {
	decentUploadTarget,
	uploadMenuEntry,
	viewableTargets,
	type UploadTarget
} from './upload-targets';
import type { StoredShot } from './model';

const viz = (o: Partial<UploadTarget> = {}): UploadTarget => ({
	id: 'visualizer', name: 'Visualizer', enabled: true, uploaded: false, viewUrl: null, shareable: false, ...o
});
const dec = (o: Partial<UploadTarget> = {}): UploadTarget => ({
	id: 'decent', name: 'Decent', enabled: true, uploaded: false, viewUrl: null, shareable: false, ...o
});

describe('uploadMenuEntry', () => {
	it('is one dimmed row when nothing is enabled', () => {
		const e = uploadMenuEntry([viz({ enabled: false }), dec({ enabled: false })]);
		expect(e.enabled).toBe(false);
		expect(e.title).toBe('Upload shot');
		expect(e.targets).toEqual([]);
	});
	it('names only the enabled destinations still missing the shot', () => {
		expect(uploadMenuEntry([viz(), dec()]).title).toBe('Upload to Visualizer + Decent');
		expect(uploadMenuEntry([viz({ uploaded: true }), dec()]).title).toBe('Upload to Decent');
		expect(uploadMenuEntry([viz(), dec({ enabled: false })]).title).toBe('Upload to Visualizer');
		expect(uploadMenuEntry([viz({ uploaded: true }), dec()]).targets.map((t) => t.id)).toEqual(['decent']);
	});
	it('turns into a re-upload of every enabled destination once the shot is everywhere', () => {
		const e = uploadMenuEntry([viz({ uploaded: true }), dec({ uploaded: true, enabled: false })]);
		expect(e.reupload).toBe(true);
		expect(e.title).toBe('Re-upload to Visualizer');
		expect(e.targets.map((t) => t.id)).toEqual(['visualizer']);
	});
	it('lists a View row only for destinations that hold the shot', () => {
		expect(viewableTargets([viz({ uploaded: true, viewUrl: 'v' }), dec({ uploaded: true, viewUrl: null }), dec()]).map((t) => t.id)).toEqual(['visualizer']);
	});
});

describe('decentUploadTarget', () => {
	const base = {
		formatVersion: 3,
		id: 'shot:1',
		completedAt: 0,
		profileName: 'P',
		metadata: {},
		record: { duration: 30_000, samples: [] }
	} satisfies StoredShot;
	it('offers a share link only for a real public URL', () => {
		const t = decentUploadTarget({ ...base, decentId: '77', machine: { serialNumber: '6262' } }, true);
		expect(t).toMatchObject({ uploaded: true, shareable: true, viewUrl: 'https://decentespresso.com/shot/6262/77' });
	});
	it('falls back to the account history (not shareable) when the serial or id is unknown', () => {
		for (const shot of [
			{ ...base, decentId: '77' },
			{ ...base, decentId: 'uploaded:123', machine: { serialNumber: '6262' } }
		]) {
			const t = decentUploadTarget(shot, true);
			expect(t).toMatchObject({ uploaded: true, shareable: false, viewUrl: 'https://decentespresso.com/support/espressomachine' });
		}
		expect(decentUploadTarget(base, true)).toMatchObject({ uploaded: false, viewUrl: null, shareable: false });
	});
});
