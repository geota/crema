/**
 * `$lib/firmware/firmware-update` — the state behind Settings → Machine →
 * "Update firmware…": alpha warning → choose a build → download and verify →
 * type UPDATE → flash (progress, cancel) → power-cycle → result / retry.
 *
 * The flash itself is the core's state machine driven by {@link FlashDriver};
 * this class owns only the flow's steps and the image the user picked.
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
}

/** The phases in which the flow must stay open. */
const RUNNING: ReadonlySet<FlashPhase> = new Set([
	FlashPhase.Preparing,
	FlashPhase.Erasing,
	FlashPhase.Uploading,
	FlashPhase.Verifying
]);

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

	readonly artifacts: FirmwareArtifact[];
	private readonly driver: FlashDriver;

	constructor(private readonly deps: FirmwareUpdateDeps) {
		this.artifacts = pinnedFirmware();
		this.driver = new FlashDriver(deps.core, deps.io, {
			onStatus: (s) => {
				this.status = s;
			},
			onLog: deps.log,
			keepAlive: deps.keepAlive
		});
	}

	/** The artifact picked in the "choose" step. */
	get selected(): FirmwareArtifact | null {
		return this.artifacts.find((a) => a.id === this.selectedId) ?? null;
	}

	/** Whether a flash is running (the flow must not close). */
	get running(): boolean {
		return this.status !== null && RUNNING.has(this.status.phase);
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
		this.selectedId = this.defaultArtifact()?.id ?? null;
		this.step = 'warning';
	}

	/** Close the flow; a finished flash is forgotten. Ignored while running. */
	async close(): Promise<void> {
		if (this.running) return;
		if (this.status !== null && this.status.phase !== FlashPhase.AwaitingPowerCycle) {
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

	/** Leave "choose": try the official download. */
	async fetchImage(): Promise<void> {
		const artifact = this.selected;
		if (artifact === null) return;
		this.step = 'source';
		this.sourceProblem = null;
		this.manualNeeded = false;
		this.downloading = true;
		this.downloadProgress = 0;
		try {
			const result = await downloadOfficialImage(artifact, this.deps.fetchFn, (got, total) => {
				this.downloadProgress = total > 0 ? Math.min(1, got / total) : 0;
			});
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
			this.sourceProblem = describeError(error);
		} finally {
			this.downloading = false;
		}
	}

	/** The manual path: a file the user downloaded from Decent. */
	async useFile(file: Blob): Promise<void> {
		const artifact = this.selected;
		if (artifact === null) return;
		this.sourceProblem = null;
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

	async cancel(): Promise<void> {
		await this.driver.cancel();
	}

	/** After a failure: back to the confirm step with the same image. */
	async retry(): Promise<void> {
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
