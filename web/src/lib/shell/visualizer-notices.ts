/**
 * `#lib/shell/visualizer-notices` — one toast as Visualizer starts needing the
 * user. The sync config holds the lasting state and Settings shows it; these say
 * so once, on the way in, because the work that found out runs in the background.
 */
import { toast } from '#lib/components/shared/toast.svelte.js';
import { onSyncConfigChange, readSyncConfig } from '#lib/visualizer/sync-config.js';

/** The notice and banner when the server ends the session. */
export const VISUALIZER_RECONNECT_LINE =
	'Visualizer ended this sign-in. Sign in again to keep syncing. Nothing on this device is lost.';

/** Start watching; returns the unsubscribe. */
export function installVisualizerNotices(): () => void {
	let { needsReconnect, accountChangeAsk } = readSyncConfig();
	return onSyncConfigChange((config) => {
		if (config.needsReconnect && !needsReconnect) toast.error(VISUALIZER_RECONNECT_LINE);
		if (config.accountChangeAsk && !accountChangeAsk) {
			toast.info('This is a different Visualizer account. Nothing uploads until you choose in Settings → Sharing.');
		}
		({ needsReconnect, accountChangeAsk } = config);
	});
}
