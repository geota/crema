/**
 * `#lib/history-source/port.js` — the SQLite source's port onto a stage-2
 * `HistoryDbClient` (#310 R1w). Reads and writes are the client's own; the
 * export walk's calls go through its once-only call, which is never resent,
 * since the walk's cursor and token live on the owner's connection.
 */
import type { HistoryDbClient } from '#lib/history-db/client.js';
import type { HistoryDbPort, WalkCall } from './sqlite';

/** What the port uses of a client. */
export type HistoryClientLike = Pick<HistoryDbClient, 'read' | 'write' | 'onChange' | 'status'> & {
	once<T = unknown>(name: WalkCall, arg?: unknown): Promise<T>;
};

export function clientPort(client: HistoryClientLike): HistoryDbPort {
	return {
		read: <T>(name: string, arg?: unknown) => client.read<T>(name as never, arg),
		once: <T>(name: WalkCall, arg?: unknown) => client.once<T>(name, arg),
		write: (w) => client.write(w),
		onChange: (f) => client.onChange(f),
		health: () => client.status.health
	};
}
