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
	it('1358 is the newest release, no update offered', () => {
		const c = copy(1358);
		expect(c.title).toBe('Build 1358 · newest Decent release');
		expect(c.note).toBe('Decent has no newer build. Its stable channel is on build 1333.');
		expect(c.offerUpdate).toBe(false);
	});

	it('1333 is Decent stable, never "up to date", and offers 1358', () => {
		const c = copy(1333);
		expect(c.title).toBe('Build 1333 · Decent stable');
		expect(c.note).toBe('Newer: build 1358 (nightly).');
		expect(c.offerUpdate).toBe(true);
		expect(`${c.title} ${c.note}`).not.toMatch(/up to date/i);
	});

	it('no copy says Crema cannot install firmware any more', () => {
		for (const b of [1320, 1333, 1352, 1358, 1363, 1370, null]) {
			const c = copy(b);
			expect(`${c.title} ${c.note}`).not.toMatch(/can't install|cannot install/i);
		}
	});

	it('a build between stable and newest', () => {
		const c = copy(1352);
		expect(c.title).toBe('Build 1352 · newer than Decent stable');
		expect(c.offerUpdate).toBe(true);
	});

	it('an old build', () => {
		const c = copy(1320);
		expect(c.title).toBe('Build 1320 · older than Decent stable');
		expect(c.note).toContain('1333');
		expect(c.offerUpdate).toBe(true);
	});

	it('1363 is a rolled-back build, not newer', () => {
		const c = copy(1363);
		expect(c.title).toBe('Build 1363 · rolled-back build');
		expect(c.note).toContain("isn't newer than build 1358");
		expect(c.title).not.toMatch(/newer/);
		expect(c.offerUpdate).toBe(true);
	});

	it('a build above the newest known one', () => {
		const c = copy(1370);
		expect(c.title).toBe('Build 1370 · newer than Crema knows about');
		expect(c.note).toContain('2026-10-11');
		expect(c.offerUpdate).toBe(false);
	});

	it('connected before the build is read', () => {
		expect(copy(null).title).toBe('Build not read yet');
	});

	it('disconnected', () => {
		const c = copy(1358, false);
		expect(c.title).toBe('No build info');
		expect(c.offerUpdate).toBe(false);
	});
});
