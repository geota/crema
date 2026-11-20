/**
 * `#lib/ble/scale-flash-pause.js` — scale Bluetooth stands down while a DE1
 * firmware flash runs (port of Decenza 270b89df,
 * `BLEManager::setFirmwareUpdateActive`, `blemanager.cpp:185-213`; Android's
 * `ScaleFlashPause.kt` is the twin).
 *
 * The flash is 15–25 minutes of acknowledged writes, one at a time, and the
 * connection interval is its speed limit; a scale streaming weight at
 * ~10 Hz, its advertisement watch and its reconnect attempts share that
 * radio. So while the flash runs:
 *  - the launch reconnect's scale watch is cancelled and the scale link
 *    dropped without reconnecting (the remembered scale is kept);
 *  - every scale connect (a tap, the launch reconnect, a visibility kick) is
 *    held, not made.
 * When the flash ends — succeeded, failed or cancelled — the scale comes
 * back if it was linked, connecting, or asked for during the flash.
 *
 * "Runs" is the core's flash state as the driver reports it (its keep-alive:
 * preparing, erasing, uploading, verifying), so a flash restored after a
 * reload (an erase or verify window) pauses the scale too, and lets go when
 * it ends. Nothing is persisted: a reload outside a running flash starts
 * unpaused.
 */

export interface ScaleFlashPausePort {
	/** A scale is linked, connecting or reconnecting (not ended by the user). */
	scaleWanted(): boolean;
	/** Drop the scale link and its watch, with no reconnect; keep the remembered scale. */
	standDown(): void;
	/** Connect the scale again (the one dropped, else the remembered one). */
	bringBack(): void;
	log(line: string): void;
}

export class ScaleFlashPause {
	#paused = false;
	/** Bring the scale back when the flash ends. */
	#bringBack = false;

	constructor(private readonly port: ScaleFlashPausePort) {}

	/** A flash is running: scale connects are held. */
	get paused(): boolean {
		return this.#paused;
	}

	/** The flash started (`active`) or ended; repeats are ignored (the driver sets it per status). */
	onFlashActive(active: boolean): void {
		if (active === this.#paused) return;
		this.#paused = active;
		if (active) {
			this.#bringBack = this.port.scaleWanted();
			this.port.standDown();
			this.port.log('Scale: Bluetooth paused for the firmware update');
			return;
		}
		const restore = this.#bringBack;
		this.#bringBack = false;
		if (restore) {
			this.port.log('Scale: firmware update over — reconnecting');
			this.port.bringBack();
		}
	}

	/**
	 * A scale connect was asked for. While paused it is held for the end of
	 * the flash and this answers true (the caller does nothing now).
	 */
	holdConnect(): boolean {
		if (!this.#paused) return false;
		if (!this.#bringBack) this.port.log('Scale: connect held until the firmware update ends');
		this.#bringBack = true;
		return true;
	}
}
