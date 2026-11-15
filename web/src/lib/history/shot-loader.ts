/**
 * `#lib/history/shot-loader.js` — whole shots for a view that shows one at a
 * time (#310 P4w, SPEC §4).
 *
 * A history row carries no samples, so the detail loads its shot with
 * `history.loadShot`. A load can end after the user has moved on: a slow read
 * for shot A landing after shot B was picked must not put A's curve on B's
 * page. So only the latest request's answer is given; an earlier one ends as
 * `stale`, and the view ignores it.
 */
import type { StoredShot } from './model';

/** How one {@link LatestShotLoader.request} ended. */
export type ShotLoad =
	| { readonly kind: 'loaded'; readonly id: string; readonly shot: StoredShot | undefined }
	| { readonly kind: 'failed'; readonly id: string; readonly error: unknown }
	| { readonly kind: 'stale'; readonly id: string };

/** Loads whole shots, answering only the latest request. */
export class LatestShotLoader {
	private seq = 0;

	constructor(private readonly load: (id: string) => Promise<StoredShot | undefined>) {}

	/** Load `id`. A request made after this one, or {@link cancel}, makes this one `stale`. */
	async request(id: string): Promise<ShotLoad> {
		const mine = ++this.seq;
		try {
			const shot = await this.load(id);
			if (mine !== this.seq) return { kind: 'stale', id };
			// A shot that isn't the one asked for is never given as it.
			return { kind: 'loaded', id, shot: shot && shot.id === id ? shot : undefined };
		} catch (error) {
			if (mine !== this.seq) return { kind: 'stale', id };
			return { kind: 'failed', id, error };
		}
	}

	/** Every request so far ends `stale`: the view has gone, or moved on. */
	cancel(): void {
		this.seq++;
	}
}
