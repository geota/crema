/**
 * `#lib/firmware/firmware-update` — the state behind Settings → Machine →
 * "Update firmware…": alpha warning → choose a build → download and verify →
 * type UPDATE → flash (progress, confirmed cancel) → power-cycle → result /
 * retry.
 *
 * The flash itself is the core's state machine driven by {@link FlashDriver};
 * this class owns only the flow's steps, the image the user picked, and the
 * persisted marker that survives a page reload while the flash holds the DE1
 * (audit M3; r2 LOW 2 / LOW 7): the power-cycle wait (written as soon as verify
 * starts, unconfirmed), or the deadline of an erase that may still be running,
 * tied to the DE1 it was for.
 */

import type {
	CremaCore,
	FirmwareArtifact,
	FlashBlocker,
	FlashShellConditions,
	FlashStatus
} from '#lib/core/index.js';
import { FlashPhase } from '#lib/core/index.js';
import { describeError } from '#lib/utils/error.js';
import { FlashDriver, type FlashCore, type FlashIo, type FlashKeepAlive } from './flash-driver';
import { downloadOfficialImage, pinnedFirmware, verifyImageFor } from './source';

/** The visible step of the flow. */
export type FlowStep = 'closed' | 'warning' | 'choose' | 'source' | 'confirm' | 'flash';

/** The longest an erase can still be running (the core's `ERASE_TIMEOUT_MS`). */
const ERASE_HOLD_MAX_MS = 60_000;
/** The longest the DE1 can still be verifying (the core's `VERIFY_TIMEOUT_MS`). */
const VERIFY_HOLD_MAX_MS = 120_000;

/** localStorage key of the flash marker. */
export const AWAITING_KEY = 'crema.firmware.flashMarker.v2';

/** What survives a reload while a flash holds the DE1. */
export interface FlashMarker {
	build: number;
	unconfirmed: boolean;
	/** Wall-clock ms until which the DE1 may still be erasing; absent = power-cycle wait. */
	eraseHoldUntil?: number;
	/**
	 * Wall-clock ms until which the DE1 may still be verifying the image. A
	 * reload before then restores the verify step ("don't switch it off"),
	 * not the power-cycle step (report 06 #7).
	 */
	verifyHoldUntil?: number;
	/** The DE1's Web Bluetooth device id, when known (this browser session only). */
	machine?: string | null;
	/** The DE1's serial number (survives browser restarts), when known. */
	serial?: number;
}

/**
 * The marker to persist for `s`, or `null` to clear: the power-cycle wait; the
 * verify phase as an unconfirmed wait (a tab closed there must still keep the
 * lock); an erase that may still run, as a wall-clock deadline.
 */
export function markerFor(
	s: FlashStatus | null,
	machine: string | null,
	nowWallMs: number
): FlashMarker | null {
	if (s === null || !s.locksWrites) return null;
	const id = s.machineSerial ? { machine, serial: s.machineSerial } : { machine };
	if (s.phase === FlashPhase.AwaitingPowerCycle) {
		return { build: s.targetBuild, unconfirmed: s.verifyUnconfirmed, ...id };
	}
	if (s.phase === FlashPhase.Verifying) {
		const left = s.verifyHoldMsLeft;
		return typeof left === 'number' && left > 0
			? { build: s.targetBuild, unconfirmed: true, verifyHoldUntil: nowWallMs + left, ...id }
			: { build: s.targetBuild, unconfirmed: true, ...id };
	}
	if (s.eraseHoldMsLeft !== undefined && s.eraseHoldMsLeft !== null) {
		return {
			build: s.targetBuild,
			unconfirmed: false,
			eraseHoldUntil: nowWallMs + s.eraseHoldMsLeft,
			...id
		};
	}
	return null;
}

/** Minimal storage surface (localStorage in the app, a map in tests). */
export interface MarkerStore {
	getItem(key: string): string | null;
	setItem(key: string, value: string): void;
	removeItem(key: string): void;
}

export interface FirmwareUpdateDeps {
	core: FlashCore & Pick<CremaCore, 'firmwareFlashBlockers'>;
	io: FlashIo;
	/** What the shell knows: link, ready, guided brew, power. */
	conditions: () => Promise<FlashShellConditions>;
	/** The installed build (MMR `0x800010`), when read. */
	installedBuild: () => number | null;
	log: (line: string) => void;
	keepAlive: FlashKeepAlive;
	fetchFn?: typeof fetch;
	/** Where the power-cycle marker lives (default: localStorage). */
	store?: MarkerStore | null;
	now?: () => number;
	/** The flash let go of the DE1 link (the shell runs what it skipped). */
	onRelease?: () => void;
	/** The connected DE1's id (Web Bluetooth `device.id`), or null. */
	machineId?: () => string | null;
	/** Whether the DE1 link is up right now. */
	linkUp?: () => boolean;
	/** Why this browser must not flash at all (e.g. the iOS Beacio bridge), or null. */
	unavailableReason?: () => string | null;
	/** Wall clock for the persisted erase-hold deadline. */
	wallNow?: () => number;
	/** Logged at each start: the transport and the DE1. */
	transportLabel?: () => string;
}

/** The phases in which the flow must stay open. */
const RUNNING: ReadonlySet<FlashPhase> = new Set([
	FlashPhase.Preparing,
	FlashPhase.Erasing,
	FlashPhase.Uploading,
	FlashPhase.Verifying
]);

function defaultStore(): MarkerStore | null {
	try {
		return typeof localStorage === 'undefined' ? null : localStorage;
	} catch {
		return null;
	}
}

export class FirmwareUpdateController {
	step = $state<FlowStep>('closed');
	/** The "I understand" checkbox on the alpha warning. */
	acknowledged = $state(false);
	selectedId = $state<string | null>(null);
	downloading = $state(false);
	downloadProgress = $state(0);
	/** The browser refused the download (CORS): offer the manual path. */
	manualNeeded = $state(false);
	sourceProblem = $state<string | null>(null);
	image = $state.raw<Uint8Array | null>(null);
	blockers = $state<FlashBlocker[]>([]);
	status = $state<FlashStatus | null>(null);
	startProblem = $state<string | null>(null);
	/** The "Stop the update?" confirmation is showing. */
	confirmingCancel = $state(false);
	/** The "Exit without confirming?" confirmation is showing. */
	confirmingExit = $state(false);

	readonly artifacts: FirmwareArtifact[];
	private readonly driver: FlashDriver;
	private readonly store: MarkerStore | null;
	/** Bumped when the user leaves the source step, so a late download is ignored. */
	private downloadToken = 0;
	/** The DE1 this flash is for (set at start, or from a restored marker). */
	private machine: string | null = null;

	constructor(private readonly deps: FirmwareUpdateDeps) {
		this.artifacts = pinnedFirmware();
		this.store = deps.store === undefined ? defaultStore() : deps.store;
		this.driver = new FlashDriver(deps.core, deps.io, {
			onStatus: (s) => {
				const wasHeld = this.status?.locksWrites ?? false;
				this.status = s;
				this.persist(s);
				// The flow stays on screen for as long as the flash holds the
				// DE1: it's the only place to watch, wait or Exit.
				if (s?.locksWrites && this.step === 'closed') this.step = 'flash';
				if (wasHeld && !(s?.locksWrites ?? false)) deps.onRelease?.();
			},
			onLog: deps.log,
			keepAlive: deps.keepAlive,
			now: deps.now,
			transportLabel: deps.transportLabel
		});
	}

	/** The artifact picked in the "choose" step. */
	get selected(): FirmwareArtifact | null {
		return this.artifacts.find((a) => a.id === this.selectedId) ?? null;
	}

	/** Whether a transfer is running (the flow must not close). */
	get running(): boolean {
		return this.status !== null && RUNNING.has(this.status.phase);
	}

	/** Why a new flash can't start in this browser, or null when it can. */
	get unavailable(): string | null {
		return this.deps.unavailableReason?.() ?? null;
	}

	/** Whether the flash holds the DE1 link (the shell skips its sweeps). */
	get holdsLink(): boolean {
		return this.status?.locksWrites ?? false;
	}

	private wallNow(): number {
		return this.deps.wallNow?.() ?? Date.now();
	}

	/** Keep the persisted marker in step with the core. */
	private persist(s: FlashStatus | null): void {
		if (this.store === null) return;
		try {
			const marker = markerFor(s, this.machine, this.wallNow());
			if (marker !== null) this.store.setItem(AWAITING_KEY, JSON.stringify(marker));
			else this.store.removeItem(AWAITING_KEY);
		} catch {
			// Storage blocked: the in-memory lock still holds for this session.
		}
	}

	/**
	 * After a reload: bring back the lock the flash held (a power-cycle wait,
	 * or an erase that may still be running) and show it at once (audit r2
	 * M-B). Already connected: read the build now.
	 */
	async restore(): Promise<void> {
		let marker: FlashMarker | null = null;
		try {
			const raw = this.store?.getItem(AWAITING_KEY) ?? null;
			marker = raw === null ? null : (JSON.parse(raw) as FlashMarker);
		} catch {
			marker = null;
		}
		if (marker === null || typeof marker.build !== 'number') return;
		this.machine = marker.machine ?? null;
		let status: FlashStatus | null;
		if (typeof marker.eraseHoldUntil === 'number') {
			const left = marker.eraseHoldUntil - this.wallNow();
			if (!Number.isFinite(left) || left <= 0) {
				this.persist(null);
				return;
			}
			// A wall clock that jumped back can't stretch the hold past what
			// an erase can take (audit r3 LOW-1; the core clamps too).
			status = await this.driver.restoreEraseHold(marker.build, Math.min(left, ERASE_HOLD_MAX_MS));
		} else if (
			typeof marker.verifyHoldUntil === 'number' &&
			Number.isFinite(marker.verifyHoldUntil - this.wallNow()) &&
			marker.verifyHoldUntil - this.wallNow() > 0
		) {
			// The DE1 may still be checking the image: wait out the rest of the
			// window before asking for the power-cycle (report 06 #7).
			const left = marker.verifyHoldUntil - this.wallNow();
			status = await this.driver.restoreVerifying(marker.build, Math.min(left, VERIFY_HOLD_MAX_MS));
		} else {
			status = await this.driver.restoreAwaiting(marker.build, marker.unconfirmed === true);
		}
		if (status === null) return;
		// The serial identifies the DE1 across browser restarts; the stored
		// device.id is from an earlier session and Chrome may have issued a new
		// one, so it is not bound (never "a different DE1" for that alone).
		await this.driver.bindMachine(typeof marker.serial === 'number' ? marker.serial : 0, '');
		this.deps.log(
			`Firmware: restored the lock for build ${marker.build} (${typeof marker.eraseHoldUntil === 'number' ? 'erase may still run' : status.phase === FlashPhase.Verifying ? 'may still be verifying' : 'waiting for a restart'}, DE1 ${marker.machine ?? 'unknown'})`
		);
		this.step = 'flash';
		if (this.deps.linkUp?.()) await this.onLinkUp(this.deps.machineId?.() ?? null);
	}

	/**
	 * The DE1 link came up — any path: auto-reconnect, a manual or launch
	 * connect, after a reload (audit r2 M-A). The core compares the DE1 with
	 * the one the flash is for on every link-up: a different DE1 is not read
	 * and can't satisfy the wait or release the lock (r3 LOW-2).
	 */
	async onLinkUp(connected: string | null): Promise<void> {
		if (this.status === null) return;
		await this.driver.linkRestored(connected ?? '');
	}


	/** Open the flow — straight to the flash view when one exists. */
	async open(): Promise<void> {
		const status = await this.driver.refresh();
		if (status !== null) {
			this.step = 'flash';
			return;
		}
		this.acknowledged = false;
		this.image = null;
		this.sourceProblem = null;
		this.manualNeeded = false;
		this.startProblem = null;
		this.confirmingCancel = false;
		this.confirmingExit = false;
		this.selectedId = this.defaultArtifact()?.id ?? null;
		this.step = 'warning';
	}

	/**
	 * Close the flow. It can't close while the flash holds the DE1 (a
	 * transfer, an erase that may still run, the power-cycle wait): the
	 * modal blocks the app until the DE1 restarts or the user Exits. A
	 * finished flash is forgotten.
	 */
	async close(): Promise<void> {
		if (this.running || this.holdsLink) return;
		this.confirmingCancel = false;
		this.confirmingExit = false;
		if (this.status !== null && !this.status.locksWrites) {
			await this.driver.dismiss();
		}
		this.step = 'closed';
	}

	acceptWarning(): void {
		if (this.acknowledged && this.unavailable === null) this.step = 'choose';
	}

	select(id: string): void {
		this.selectedId = id;
		this.image = null;
	}

	/** Back from the source step (a download in flight is then ignored). */
	backToChoose(): void {
		this.downloadToken++;
		this.downloading = false;
		this.step = 'choose';
	}

	/** Leave "choose": try the official download. */
	async fetchImage(): Promise<void> {
		const artifact = this.selected;
		if (artifact === null) return;
		const token = ++this.downloadToken;
		this.step = 'source';
		this.sourceProblem = null;
		this.manualNeeded = false;
		this.downloading = true;
		this.downloadProgress = 0;
		try {
			const result = await downloadOfficialImage(artifact, this.deps.fetchFn, (got, total) => {
				if (token === this.downloadToken) {
					this.downloadProgress = total > 0 ? Math.min(1, got / total) : 0;
				}
			});
			if (token !== this.downloadToken) return;
			if (result.kind === 'ok') {
				this.image = result.image;
				await this.toConfirm();
			} else if (result.kind === 'rejected') {
				this.sourceProblem = result.problem;
			} else {
				this.deps.log(`Firmware: direct download unavailable (${result.detail})`);
				this.manualNeeded = true;
			}
		} catch (error) {
			if (token === this.downloadToken) this.sourceProblem = describeError(error);
		} finally {
			if (token === this.downloadToken) this.downloading = false;
		}
	}

	/** The manual path: a file the user downloaded from Decent. */
	async useFile(file: Blob): Promise<void> {
		const artifact = this.selected;
		if (artifact === null) return;
		this.sourceProblem = null;
		if (file.size > artifact.byteLength * 2) {
			this.sourceProblem = 'This file is far too large to be DE1 firmware.';
			return;
		}
		const bytes = new Uint8Array(await file.arrayBuffer());
		const verdict = verifyImageFor(artifact, bytes);
		if (!verdict.ok) {
			this.sourceProblem = verdict.problem;
			return;
		}
		this.image = verdict.image;
		await this.toConfirm();
	}

	private async toConfirm(): Promise<void> {
		await this.refreshBlockers();
		this.step = 'confirm';
	}

	async refreshBlockers(): Promise<FlashBlocker[]> {
		this.blockers = await this.deps.core.firmwareFlashBlockers(await this.deps.conditions());
		return this.blockers;
	}

	/** Start (or retry) the flash with the verified image. */
	async startFlash(): Promise<void> {
		const image = this.image;
		if (image === null) return;
		this.startProblem = this.unavailable;
		if (this.startProblem !== null) {
			this.step = 'confirm';
			return;
		}
		if ((await this.refreshBlockers()).length > 0) {
			this.step = 'confirm';
			return;
		}
		this.machine = this.deps.machineId?.() ?? null;
		const error = await this.driver.start(image, await this.deps.conditions());
		if (error !== null) {
			if (error.type === 'Blocked') this.blockers = error.content.blockers;
			else this.startProblem = 'The firmware file failed verification.';
			this.step = 'confirm';
			return;
		}
		await this.driver.bindMachine(0, this.machine);
		this.step = 'flash';
	}

	/** Ask before cancelling (only while the core still allows it). */
	requestCancel(): void {
		if (this.status?.canCancel) this.confirmingCancel = true;
	}

	keepUpdating(): void {
		this.confirmingCancel = false;
	}

	async confirmCancel(): Promise<void> {
		this.confirmingCancel = false;
		await this.driver.cancel();
	}

	/** Ask before exiting the power-cycle step without a confirmed build. */
	requestExit(): void {
		if (this.status?.canExit) this.confirmingExit = true;
	}

	keepWaiting(): void {
		this.confirmingExit = false;
	}

	async confirmExit(): Promise<void> {
		this.confirmingExit = false;
		if (await this.driver.exit()) this.step = 'closed';
	}

	/** After a failure: back to the confirm step with the same image. */
	async retry(): Promise<void> {
		if (this.status?.locksWrites) return;
		await this.driver.dismiss();
		if (this.image === null) {
			this.step = 'choose';
			return;
		}
		await this.toConfirm();
	}

	/** Stop the clock (page teardown); the core keeps the flash. */
	dispose(): void {
		this.driver.dispose();
	}

	/** The newest pinned build above the installed one, else the newest. */
	private defaultArtifact(): FirmwareArtifact | null {
		const installed = this.deps.installedBuild();
		const byBuild = [...this.artifacts].sort((a, b) => b.build - a.build);
		return (
			(installed !== null ? byBuild.find((a) => a.build > installed) : undefined) ??
			byBuild[0] ??
			null
		);
	}
}
