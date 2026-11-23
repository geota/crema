/**
 * `#lib/grinder/follow.svelte.js` — follow mode as Brew shows it (spec
 * `.scratch/grinder-ble/SPEC.md` §5, owner decisions 2–4): Brew's grind tracks
 * the connected grinder's collar. The rules are the core's
 * (`CremaCore::grinder_follow`, `Event::GrinderFollowChanged`,
 * `Event::FollowGrind`); this holds what it last said, for the Grind cell, the
 * header lines and the bag and grinder picks.
 *
 * - `view`: the core's `GrinderFollowView` (following, waiting, paused, stale,
 *   the "Use ‹grinder›" suggestion).
 * - `followedGrinderId`: the record whose grind the collar sets now
 *   (`CremaCore::followed_grinder_id`), which a bag pick and a grinder switch
 *   pass so they leave Brew's grind to the collar (§5.3).
 * - `paused`: the user's "Pause following" for this session (decision 3),
 *   kept here because the core's view says Paused only while the record is
 *   active.
 * - `staleSince`: when the linked grinder dropped ("last seen 12:03").
 *
 * In memory only: a reload starts following again, as the core does.
 */
import { FollowStatus, type GrinderFollowView } from '#lib/core/crema-core.js';

/** Follow mode off: Brew's grind is the user's. */
export const FOLLOW_OFF: GrinderFollowView = { status: FollowStatus.Off };

export class GrinderFollowStore {
	#view = $state.raw<GrinderFollowView>(FOLLOW_OFF);
	#followed = $state.raw<string | null>(null);
	#paused = $state(false);
	#staleSince = $state.raw<number | null>(null);

	/** What the core last said about follow mode. */
	get view(): GrinderFollowView {
		return this.#view;
	}

	/** The record whose grind the collar sets now, or `null`. */
	get followedGrinderId(): string | null {
		return this.#followed;
	}

	/** The user paused following for this session. */
	get paused(): boolean {
		return this.#paused;
	}

	/** Unix ms the linked grinder went stale, or `null` while it isn't. */
	get staleSince(): number | null {
		return this.#staleSince;
	}

	/** Brew's grind follows the collar now: the stepper gives way to the grinder's value. */
	get following(): boolean {
		const s = this.#view.status;
		return s === FollowStatus.Following || s === FollowStatus.Waiting;
	}

	/** The core's new view, and the followed record it implies now. */
	update(view: GrinderFollowView, followedGrinderId: string | null, nowMs = Date.now()): void {
		if (view.status === FollowStatus.Stale) {
			if (this.#view.status !== FollowStatus.Stale) this.#staleSince = nowMs;
		} else this.#staleSince = null;
		this.#view = view;
		this.#followed = followedGrinderId;
	}

	/** The followed record changed with no new view (a pause while another grinder is active). */
	setFollowed(followedGrinderId: string | null): void {
		this.#followed = followedGrinderId;
	}

	setPaused(paused: boolean): void {
		this.#paused = paused;
	}

	/** Back to off (tests, a beta turned off). */
	reset(): void {
		this.#view = FOLLOW_OFF;
		this.#followed = null;
		this.#paused = false;
		this.#staleSince = null;
	}
}

let store: GrinderFollowStore | null = null;

/** The app's one follow store. */
export function getGrinderFollow(): GrinderFollowStore {
	return (store ??= new GrinderFollowStore());
}

/** What the Grind cell and the header lines show about follow mode. */
export interface FollowDisplay {
	/** The collar sets Brew's grind: the stepper is hidden. */
	readonly following: boolean;
	/** The grinder's value to show while following ("480"), the live one while the collar moves; `null` before a value. */
	readonly value: string | null;
	/** The collar is moving: the value is the live one, greyed. */
	readonly moving: boolean;
	/** "from grinder", "from grinder · 700 rpm" while following; else `null`. */
	readonly source: string | null;
	/** The label-row note: "Following paused", "Motto 80 offline · last seen 12:03"; else `null`. */
	readonly note: string | null;
	/** "Use ‹grinder›": the linked record isn't the active one (decision 4); else `null`. */
	readonly suggest: { readonly grinderId: string; readonly name: string } | null;
	/** Paused while the linked record is active: offer Resume. */
	readonly canResume: boolean;
}

/** {@link FollowDisplay} with what the Grind cell's buttons do. */
export interface FollowCell extends FollowDisplay {
	/** "Pause following" (decision 3). */
	readonly onPause: () => void;
	/** Resume following. */
	readonly onResume: () => void;
	/** "Use ‹grinder›": make the suggested record Brew's grinder. */
	readonly onUse: (grinderId: string) => void;
	/** Open Devices → Grinder. */
	readonly onDevices: () => void;
}

/** `HH:MM` in the viewer's clock. */
function clock(ms: number): string {
	return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/**
 * The follow view in words. `liveValue` is the grinder's latest reading's dial
 * (shown greyed while the collar moves); `variableSpeed` adds the speed;
 * `nameOf` names a grinder record (or `null` when it isn't a live record).
 */
export function followDisplay(input: {
	readonly view: GrinderFollowView;
	readonly staleSince: number | null;
	readonly liveValue: string | null;
	readonly variableSpeed: boolean;
	readonly nameOf: (grinderId: string) => string | null;
}): FollowDisplay {
	const { view } = input;
	const following = view.status === FollowStatus.Following || view.status === FollowStatus.Waiting;
	const moving = following && view.moving === true && input.liveValue !== null;
	const value = moving ? input.liveValue : (view.grind ?? input.liveValue);
	const rpm = input.variableSpeed && view.grind_rpm != null ? ` · ${view.grind_rpm} rpm` : '';
	const name = view.grinder_id ? input.nameOf(view.grinder_id) : null;
	let note: string | null = null;
	if (view.status === FollowStatus.Paused) note = 'Following paused';
	else if (view.status === FollowStatus.Stale) {
		const who = name ?? 'Grinder';
		note = input.staleSince != null ? `${who} offline · last seen ${clock(input.staleSince)}` : `${who} offline`;
	}
	return {
		following,
		value: following ? (value ?? null) : null,
		moving,
		source: following ? `from grinder${rpm}` : null,
		note,
		suggest:
			view.status === FollowStatus.Suggest && view.grinder_id && name
				? { grinderId: view.grinder_id, name }
				: null,
		canResume: view.status === FollowStatus.Paused
	};
}
