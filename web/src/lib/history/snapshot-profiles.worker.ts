/**
 * `#lib/history/snapshot-profiles.worker.js` — reads the daily copy's
 * profiles off the main thread (#421): each ask's ids' details, answered with
 * only their profiles. Read only. Its connection never creates the database:
 * a missing one is lost, and the ask is answered with an error, so the page
 * reads that batch itself.
 */
import { HistoryDb } from './history-db';
import { answerProfiles, type ProfileAnswer, type ProfileAsk } from './snapshot-profiles';

const db = new HistoryDb({ expectExisting: () => true });
const scope = self as unknown as {
	onmessage: ((e: MessageEvent<ProfileAsk>) => void) | null;
	postMessage(m: ProfileAnswer): void;
};
scope.onmessage = (e) => {
	void answerProfiles(db, e.data).then((a) => scope.postMessage(a));
};
