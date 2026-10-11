/**
 * `$lib/firmware/build-copy` — the firmware card's copy, driven through the
 * real wasm classifier (`classifyFirmwareBuild`) so the core's known-builds
 * table and the shell's copy are tested together.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { FirmwareBuildStatus } from '$lib/core/crema-core';
import { initTestWasm } from '$lib/testing/test-init';
import { firmwareBuildReport, firmwareCardCopy } from './build-copy';

beforeAll(initTestWasm);

const copy = (build: number | null, connected = true) =>
	firmwareCardCopy(firmwareBuildReport(build), connected);

describe('firmwareBuildReport (wasm bridge)', () => {
	it('classifies through the table, not by number', () => {
		const status = (b: number | null) => firmwareBuildReport(b).status;
		expect(status(1358)).toBe(FirmwareBuildStatus.Newest);
		expect(status(1333)).toBe(FirmwareBuildStatus.Stable);
		expect(status(1352)).toBe(FirmwareBuildStatus.BetweenStableAndNewest);
		expect(status(1320)).toBe(FirmwareBuildStatus.OlderThanStable);
		expect(status(1363)).toBe(FirmwareBuildStatus.RolledBack);
		expect(status(1370)).toBe(FirmwareBuildStatus.NewerThanKnown);
		expect(status(null)).toBe(FirmwareBuildStatus.Unknown);
	});
});

describe('firmwareCardCopy', () => {
	it('1358 is the newest release, nightly only, nothing to do', () => {
		const c = copy(1358);
		expect(c.title).toBe('Build 1358 · newest Decent release');
		expect(c.note).toBe(
			"The newest Decent release. Only de1app's nightly channel installs it; stable installs build 1333."
		);
		expect(c.guidance).toContain('Nothing to do');
	});

	it('1333 is Decent stable and never claims to be up to date', () => {
		const c = copy(1333);
		expect(c.title).toBe('Build 1333 · Decent stable');
		expect(c.note).toBe(
			"The build de1app's stable and beta channels install. The newest release is build 1358."
		);
		expect(`${c.title} ${c.note}`).not.toMatch(/up to date/i);
	});

	it('the guidance does not promise 1358 from de1app stable', () => {
		const g = copy(1333).guidance;
		expect(g).toContain("Crema can't install firmware yet");
		expect(g).toContain('de1app');
		expect(g).toContain(
			'Its stable and beta versions install build 1333; build 1358 needs the nightly version.'
		);
	});

	it('a build between stable and newest', () => {
		expect(copy(1352).title).toBe('Build 1352 · newer than Decent stable');
	});

	it('an old build', () => {
		const c = copy(1320);
		expect(c.title).toBe("Build 1320 · older than Decent's current stable");
		expect(c.note).toContain('1333');
	});

	it('1363 is a rolled-back build, not newer', () => {
		const c = copy(1363);
		expect(c.title).toBe('Build 1363 · rolled-back build');
		expect(c.note).toContain("isn't newer than build 1358");
		expect(c.title).not.toMatch(/newer/);
	});

	it('a build above the newest known one', () => {
		const c = copy(1370);
		expect(c.title).toBe('Build 1370 · newer than Crema knows about');
		expect(c.note).toContain('2026-10-11');
	});

	it('connected before the build is read', () => {
		expect(copy(null).title).toBe('Build not read yet');
	});

	it('disconnected', () => {
		const c = copy(1358, false);
		expect(c.title).toBe('No build info');
		expect(c.guidance).toContain('de1app');
	});
});
