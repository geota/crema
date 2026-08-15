/**
 * `$lib/brew/cues` — guided-brew cue rendering (issue #10).
 *
 * The core decides WHEN a cue is due (`Event::BrewCueDue` /
 * `BrewStepChanged`); this module decides what it sounds and feels
 * like: short WebAudio tones + a vibration pattern, each gated by its
 * Settings toggle. Never a DE1 write — cues terminate here.
 *
 * The AudioContext is created/resumed by {@link primeBrewCues}, called
 * from the session's Start/Arm tap so it exists inside a user gesture
 * (autoplay policy). On browsers without audio or vibration everything
 * degrades to a silent no-op — the session stays fully usable from the
 * screen alone.
 */

import { getSettingsStore } from '$lib/settings';

export type BrewCueKind = 'approach' | 'boundary' | 'step' | 'done';

let audioCtx: AudioContext | null = null;

/** Create/resume the audio context inside a user gesture. */
export function primeBrewCues(): void {
	if (typeof window === 'undefined') return;
	try {
		audioCtx ??= new AudioContext();
		if (audioCtx.state === 'suspended') void audioCtx.resume();
	} catch {
		audioCtx = null;
	}
}

/** One gentle sine blip at `freq` Hz for `ms`, starting `at` s from now. */
function blip(freq: number, ms: number, at = 0): void {
	if (!audioCtx || audioCtx.state !== 'running') return;
	const t0 = audioCtx.currentTime + at;
	const osc = audioCtx.createOscillator();
	const gain = audioCtx.createGain();
	osc.type = 'sine';
	osc.frequency.value = freq;
	// A soft attack/release envelope so the blip doesn't click.
	gain.gain.setValueAtTime(0, t0);
	gain.gain.linearRampToValueAtTime(0.14, t0 + 0.015);
	gain.gain.setValueAtTime(0.14, t0 + ms / 1000 - 0.03);
	gain.gain.linearRampToValueAtTime(0, t0 + ms / 1000);
	osc.connect(gain).connect(audioCtx.destination);
	osc.start(t0);
	osc.stop(t0 + ms / 1000 + 0.02);
}

/** Render one cue as sound + haptic, per the Settings toggles. */
export function playBrewCue(kind: BrewCueKind): void {
	const s = getSettingsStore().current;
	if (s.brewCueSound) {
		switch (kind) {
			case 'approach':
				// "Get ready" — one short mid blip.
				blip(660, 120);
				break;
			case 'boundary':
				// "Stop now" — two firm high blips.
				blip(880, 140);
				blip(880, 140, 0.2);
				break;
			case 'step':
				// "Next step" — a rising pair.
				blip(523, 130);
				blip(784, 150, 0.16);
				break;
			case 'done':
				// "Brew finished" — a rising triad.
				blip(523, 130);
				blip(659, 130, 0.15);
				blip(784, 220, 0.3);
				break;
		}
	}
	if (s.brewCueHaptics && typeof navigator !== 'undefined' && 'vibrate' in navigator) {
		try {
			switch (kind) {
				case 'approach':
					navigator.vibrate(60);
					break;
				case 'boundary':
					navigator.vibrate([80, 60, 80]);
					break;
				case 'step':
					navigator.vibrate(120);
					break;
				case 'done':
					navigator.vibrate([100, 60, 100, 60, 160]);
					break;
			}
		} catch {
			// Some engines throw on vibrate in odd states — a cue is
			// best-effort by definition.
		}
	}
}
