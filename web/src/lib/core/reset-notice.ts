/**
 * `#lib/core/reset-notice.js` — the shell's two engine notices as one system:
 * the "engine restarted, reload" offer after a wasm core reset
 * ({@link CoreRecoveryWatch}), and the "engine stopped, reload" notice once
 * the bridge has stopped for good (`onCoreStopped`).
 *
 * Reset notice. A reload drops the DE1 and scale links and the live
 * recording, so its Reload goes through the same idle gate as the
 * service-worker update (`canReloadNow`): tapped while a shot, steam, a guided
 * brew or a firmware transfer is running, it withdraws the notice, says why,
 * and offers it again once the machine is parked (final review 03 P3-2, 07 P2
 * #7). A second reset while the notice is up or parked does not stack another
 * one; once the user closes it with ×, the next reset shows it again (round-2
 * verification V3 #3).
 *
 * Stop notice. Nothing works on a stopped core, so it replaces the reset
 * notice (withdrawn, and never offered again this page), and its Reload is
 * not gated: there is no recording or live control left to protect, and a
 * reload is the only way out (V3 #7, V2 P3-1). At most one of the two is up.
 */

export interface ResetNoticeDeps {
	/** Whether a reload would interrupt nothing right now. */
	readonly canReload: () => boolean;
	/**
	 * Show the pinned reset notice; `apply` is its Reload, `closed` runs once
	 * when the toast is gone however it went (×, Reload, withdrawn). Returns
	 * how to withdraw it.
	 */
	readonly show: (apply: () => void, closed: () => void) => () => void;
	/** Show the pinned stop notice; `reload` is its (ungated) Reload. */
	readonly showStopped: (reload: () => void) => void;
	/** Reload was tapped while busy: the notice comes back once idle. */
	readonly onDeferred: () => void;
	/** `location.reload` (injected for tests). */
	readonly reload: () => void;
}

export class CoreResetNotice {
	/** How to withdraw the reset notice while it is up; null when it is not. */
	#withdraw: (() => void) | null = null;
	/** A busy tap withdrew the notice; offer it again once idle. */
	#parked = false;
	/** The core stopped: the stop notice is the only one from now on. */
	#stopped = false;

	constructor(private readonly deps: ResetNoticeDeps) {}

	/** The core reset (again): show the notice unless it is already up or parked. */
	onReset(): void {
		if (this.#stopped || this.#withdraw !== null || this.#parked) return;
		this.#offer();
	}

	/** The machine / brew state changed: a parked notice comes back when idle. */
	onStateChange(): void {
		if (this.#stopped || !this.#parked || !this.deps.canReload()) return;
		this.#parked = false;
		this.#offer();
	}

	/** The core stopped: withdraw the reset notice and pin the stop notice once. */
	onCoreStopped(): void {
		if (this.#stopped) return;
		this.#stopped = true;
		this.#parked = false;
		const withdraw = this.#withdraw;
		this.#withdraw = null;
		withdraw?.();
		this.deps.showStopped(() => this.deps.reload());
	}

	#offer(): void {
		const withdraw = this.deps.show(
			() => this.#apply(),
			() => {
				// Closed with × (or gone any other way): a later reset shows it again.
				if (this.#withdraw === withdraw) this.#withdraw = null;
			}
		);
		this.#withdraw = withdraw;
	}

	#apply(): void {
		// The notice is pinned: a shot may have started since it appeared.
		if (!this.deps.canReload()) {
			const withdraw = this.#withdraw;
			this.#withdraw = null;
			withdraw?.();
			this.#parked = true;
			this.deps.onDeferred();
			return;
		}
		this.#withdraw = null;
		this.deps.reload();
	}
}
