/**
 * `$lib/firmware/firmware-update` — the state behind Settings → Machine →
 * "Update firmware…": alpha warning → choose a build → download and verify →
 * type UPDATE → flash (progress, confirmed cancel) → power-cycle → result /
 * retry.
 *
 * The flash itself is the core's state machine driven by {@link FlashDriver};
 * this class owns only the flow's steps, the image the user picked, and the
 * persisted "waiting for the DE1's power-cycle" marker that survives a page
 * reload (audit M3).
 */

import type {
	CremaCore,
	FirmwareArtifact,
	FlashBlocker,
	FlashShellConditions,
	FlashStatus
} from '$lib/core';
import { FlashPhase } from '$lib/core';
import { describeError } from '$lib/utils/error';
import { FlashDriver, type FlashCore, type FlashIo, type FlashKeepAlive } from './flash-driver';
import { downloadOfficialImage, pinnedFirmware, verifyImageFor } from './source';

/** The visible step of the flow. */
export type FlowStep = 'closed' | 'warning' | 'choose' | 'source' | 'confirm' | 'flash';

/** localStorage key of the power-cycle marker. */
export const AWAITING_KEY = 'crema.firmware.awaitingPowerCycle.v1';

interface AwaitingMarker {
	build: number;
	unconfirmed: boolean;
}

/** Minimal storage surface (localStorage in the app, a map in tests). */
export interface MarkerStore {
	getItem(key: string): string | null;
	setItem(key: string, value: string): void;
	removeItem(key: string): void;
}

export interface FirmwareUpdateDeps {
	core: FlashCore & Pick<CremaCore, 'firmwareFlashBlockers' | 'firmwareFlashRestoreAwaiting'>;
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

	constructor(private readonly deps: FirmwareUpdateDeps) {
		this.artifacts = pinnedFirmware();
		this.store = deps.store === undefined ? defaultStore() : deps.store;
		this.driver = new FlashDriver(deps.core, deps.io, {
			onStatus: (s) => {
				const wasHeld = this.status?.locksWrites ?? false;
				this.status = s;
				this.persist(s);
				if (wasHeld && !(s?.locksWrites ?? false)) deps.onRelease?.();
			},
			onLog: deps.log,
			keepAlive: deps.keepAlive,
			now: deps.now
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

	/** Whether the flash holds the DE1 link (the shell skips its sweeps). */
	get holdsLink(): boolean {
		return this.status?.locksWrites ?? false;
	}

	/** Keep the power-cycle marker in step with the core. */
	private persist(s: FlashStatus | null): void {
		if (this.store === null) return;
		try {
			if (s?.phase === FlashPhase.AwaitingPowerCycle) {
				const marker: AwaitingMarker = { build: s.targetBuild, unconfirmed: s.verifyUnconfirmed };
				this.store.setItem(AWAITING_KEY, JSON.stringify(marker));
			} else if (s === null || s.phase === FlashPhase.Succeeded) {
				this.store.removeItem(AWAITING_KEY);
			}
		} catch {
			// Storage blocked: the in-memory lock still holds for this session.
		}
	}

	/**
	 * After a reload: re-create a flash that was waiting for the DE1's
	 * power-cycle, so the lock and that step come back.
	 */
	async restore(): Promise<void> {
		let marker: AwaitingMarker | null = null;
		try {
			const raw = this.store?.getItem(AWAITING_KEY) ?? null;
			marker = raw === null ? null : (JSON.parse(raw) as AwaitingMarker);
		} catch {
			marker = null;
		}
		if (marker === null || typeof marker.build !== 'number') return;
		const step = await this.deps.core.firmwareFlashRestoreAwaiting(
			marker.build,
			marker.unconfirmed === true,
			this.deps.now?.() ?? performance.now()
		);
		if (step.status) {
			this.deps.log(`Firmware: still waiting for the DE1 to restart on build ${marker.build}`);
			await this.driver.refresh();
		}
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
	 * Close the flow. While a transfer runs it can't close. On the power-cycle
	 * step it only hides (the lock stays; reopening returns there). A finished
	 * flash is forgotten.
	 */
	async close(): Promise<void> {
		if (this.running) return;
		this.confirmingCancel = false;
		this.confirmingExit = false;
		if (this.status !== null && !this.status.locksWrites) {
			await this.driver.dismiss();
		}
		this.step = 'closed';
	}

	acceptWarning(): void {
		if (this.acknowledged) this.step = 'choose';
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
		this.startProblem = null;
		if ((await this.refreshBlockers()).length > 0) {
			this.step = 'confirm';
			return;
		}
		const error = await this.driver.start(image, await this.deps.conditions());
		if (error !== null) {
			if (error.type === 'Blocked') this.blockers = error.content.blockers;
			else this.startProblem = 'The firmware file failed verification.';
			this.step = 'confirm';
			return;
		}
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
