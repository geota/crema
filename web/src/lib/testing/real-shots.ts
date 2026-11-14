/**
 * `#lib/testing/real-shots` — real-shaped shots for the history tests (#310).
 *
 * The telemetry is the real session in
 * `core/de1-app/tests/fixtures/session-20260517-122732-shot-pull.jsonl`:
 * its `DE1_SHOT_SAMPLE` notifications decoded, a 180-sample window (40 s at
 * the DE1's 4.4 Hz) with scale channels simulated, as the SPEC's
 * measurements were taken (`.scratch/history-storage/bench/gen.mjs`). The
 * profile is the median built-in. Tests only: it reads the repo from disk.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { TimedSample } from '#lib/core/index.js';
import type { StoredShot } from '#lib/history/model.js';

const REPO = resolve(process.cwd(), '..');

interface Decoded {
	t: number;
	sampleTime: number;
	groupPressure: number;
	groupFlow: number;
	mixTemp: number;
	headTemp: number;
	setMixTemp: number;
	setHeadTemp: number;
	setGroupPressure: number;
	setGroupFlow: number;
	frameNumber: number;
	steamTemp: number;
}

function decode(hex: string, t: number): Decoded {
	const b = Buffer.from(hex, 'hex');
	const u16 = (o: number) => b.readUInt16BE(o);
	return {
		t,
		sampleTime: u16(0),
		groupPressure: u16(2) / 4096,
		groupFlow: u16(4) / 4096,
		mixTemp: u16(6) / 256,
		headTemp: b[8] + b[9] / 256 + b[10] / 65536,
		setMixTemp: u16(11) / 256,
		setHeadTemp: u16(13) / 256,
		setGroupPressure: b[15] / 16,
		setGroupFlow: b[16] / 16,
		frameNumber: b[17],
		steamTemp: b[18]
	};
}

let decoded: Decoded[] | null = null;
let busiest = 0;

function all(): Decoded[] {
	if (decoded) return decoded;
	const lines = readFileSync(`${REPO}/core/de1-app/tests/fixtures/session-20260517-122732-shot-pull.jsonl`, 'utf8')
		.trim()
		.split('\n')
		.map((l) => JSON.parse(l) as { src: string; hex: string; t: number });
	decoded = lines.filter((x) => x.src === 'DE1_SHOT_SAMPLE' && x.hex.length === 38).map((x) => decode(x.hex, x.t));
	let best = 0;
	for (let i = 0; i + 180 < decoded.length; i++) {
		let f = 0;
		for (let j = i; j < i + 180; j++) f += decoded[j].groupFlow;
		if (f > best) {
			best = f;
			busiest = i;
		}
	}
	return decoded;
}

const f32 = (x: number) => Number.parseFloat(Math.fround(x).toPrecision(7));

/** `n` real samples, starting `offset` into the busiest window, with simulated scale channels. */
export function realSamples(n = 180, offset = 0): TimedSample[] {
	const w = all().slice(busiest + offset, busiest + offset + n);
	const t0 = w[0].t;
	let wt = 0;
	let vol = 0;
	return w.map((s, i) => {
		const dt = i ? (s.t - w[i - 1].t) / 1000 : 0;
		vol += s.groupFlow * dt;
		const wf = Math.max(0, s.groupFlow * 0.92 + (((i * 7) % 5) - 2) * 0.03);
		wt += wf * dt;
		const res = s.groupFlow > 0.05 ? s.groupPressure / (s.groupFlow * s.groupFlow) : undefined;
		const resw = wf > 0.05 ? s.groupPressure / (wf * wf) : undefined;
		return {
			elapsed: s.t - t0,
			sample: {
				sampleTime: s.sampleTime,
				groupPressure: f32(s.groupPressure),
				groupFlow: f32(s.groupFlow),
				headTemp: f32(s.headTemp),
				mixTemp: f32(s.mixTemp),
				setMixTemp: f32(s.setMixTemp),
				setHeadTemp: f32(s.setHeadTemp),
				setGroupPressure: f32(s.setGroupPressure),
				setGroupFlow: f32(s.setGroupFlow),
				frameNumber: s.frameNumber,
				steamTemp: f32(s.steamTemp)
			},
			scaleWeight: f32(wt),
			scaleFlowWeight: f32(wf),
			dispensedVolume: f32(vol),
			...(res === undefined ? {} : { resistance: f32(res) }),
			...(resw === undefined ? {} : { resistanceWeight: f32(resw) })
		} as TimedSample;
	});
}

let profile: unknown = undefined;
/** The median built-in profile, as the measurements used. */
export function medianProfile(): unknown {
	if (profile !== undefined) return profile;
	const raw = JSON.parse(readFileSync(`${REPO}/core/de1-domain/profiles/builtin.json`, 'utf8')) as unknown;
	const list = Array.isArray(raw) ? raw : Object.values(raw as Record<string, unknown>);
	profile = [...list].sort((a, b) => JSON.stringify(a).length - JSON.stringify(b).length)[Math.floor(list.length / 2)];
	return profile;
}

/** A real-shaped machine shot `i`, with every field a row carries. */
export function realShot(i: number, samples: TimedSample[] = realSamples(180, (i * 3) % 60)): StoredShot {
	const p = medianProfile() as { title?: string };
	return {
		formatVersion: 3,
		id: `shot:019e5fb5-${String(i).padStart(5, '0')}-7000-8000-000000000001`,
		completedAt: 1_767_323_075_678 - i * 21_600_000,
		profileName: p.title ?? 'Best Practice',
		profile: p,
		stopReason: 'Weight',
		metadata: {
			dose: 18,
			yieldOut: 36.4,
			grinderSetting: '2.5',
			rating: 4,
			notes: `Shot ${i}: slightly sour at the end, go finer next time.`,
			nextPlan: null,
			barista: 'Sam'
		},
		record: { duration: 40_000, samples },
		bean: {
			beanId: `bean:019e5fb5-0000-7000-8000-00000000000${i % 7}`,
			name: 'Gesha Village',
			roasterName: 'Sey',
			roastedOn: '2026-09-01',
			roastLevel: 4,
			tags: ['comp'],
			grinderSetting: '2.5',
			grinder: 'Niche Zero'
		},
		grinderModel: 'Niche Zero',
		tags: ['dial-in'],
		yieldTarget: 36,
		brewTempTarget: 93,
		preinfuseTarget: 8,
		stopOnWeight: true,
		autoTare: true,
		visualizerId: i % 3 === 0 ? `viz-${i}` : null,
		deletedAt: null,
		machine: { serialNumber: '12345', firmwareVersion: '1333', model: 'DE1XL' }
	} as unknown as StoredShot;
}
