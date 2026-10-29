/**
 * `#lib/core/reset-notice.js` — the "engine restarted, reload" offer after a
 * wasm core reset ({@link CoreRecoveryWatch}), gated like the update offer.
 *
 * A reload drops the DE1 and scale links and the live recording, so the
 * notice's Reload goes through the same idle gate as the service-worker update
 * (`canReloadNow`): tapped while a shot, steam, a guided brew or a firmware
 * transfer is running, it withdraws the notice, says why, and offers it again
 * once the machine is parked (final review 03 P3-2, 07 P2 #7). A second reset
 * while the notice is up or parked does not stack another one.
 */

export interface ResetNoticeDeps {
	/** Whether a reload would interrupt nothing right now. */
	readonly canReload: () => boolean;
	/** Show the pinned notice; `apply` is its Reload. Returns how to withdraw it. */
	readonly show: (apply: () => void) => () => void;
	/** Reload was tapped while busy: the notice comes back once idle. */
	readonly onDeferred: () => void;
	/** `location.reload` (injected for tests). */
	readonly reload: () => void;
}

export class CoreResetNotice {
	#withdraw: (() => void) | null = null;
	/** A busy tap withdrew the notice; offer it again once idle. */
	#parked = false;

	constructor(private readonly deps: ResetNoticeDeps) {}

	/** The core reset (again): show the notice unless it is already up or parked. */
	onReset(): void {
		if (this.#withdraw !== null || this.#parked) return;
		this.#offer();
	}

	/** The machine / brew state changed: a parked notice comes back when idle. */
	onStateChange(): void {
		if (!this.#parked || !this.deps.canReload()) return;
		this.#parked = false;
		this.#offer();
	}

	#offer(): void {
		this.#withdraw = this.deps.show(() => this.#apply());
	}

	#apply(): void {
		// The notice is pinned: a shot may have started since it appeared.
		if (!this.deps.canReload()) {
			this.#withdraw?.();
			this.#withdraw = null;
			this.#parked = true;
			this.deps.onDeferred();
			return;
		}
		this.#withdraw = null;
		this.deps.reload();
	}
}
