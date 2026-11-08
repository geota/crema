import { describe, expect, it } from 'vitest';
import { GattQueue } from './transport';
import { StopLane } from './stop-lane';
import { STOP_BYTE } from './de1';

/** A write that waits for `release`, recording its guard's verdict. */
function gate() {
	let release: () => void = () => {};
	const done = new Promise<void>((r) => (release = r));
	return { done, release: () => release() };
}

describe('GattQueue', () => {
	it('runs operations one at a time, in call order, and never inside the call', async () => {
		const queue = new GattQueue();
		const order: string[] = [];
		const a = queue.enqueue(async () => order.push('a'));
		const b = queue.enqueue(async () => order.push('b'));
		expect(order).toEqual([]);
		await Promise.all([a, b]);
		expect(order).toEqual(['a', 'b']);
	});

	it('puts a front operation ahead of every waiting one, not the one in flight', async () => {
		const queue = new GattQueue();
		const order: string[] = [];
		const hold = gate();
		const first = queue.enqueue(async () => {
			order.push('in flight');
			await hold.done;
		});
		await Promise.resolve();
		await Promise.resolve();
		const rest = [
			queue.enqueue(async () => order.push('queued 1')),
			queue.enqueue(async () => order.push('queued 2')),
			queue.enqueueFront(async () => order.push('stop'))
		];
		hold.release();
		await Promise.all([first, ...rest]);
		expect(order).toEqual(['in flight', 'stop', 'queued 1', 'queued 2']);
	});

	it('goes on after a failed or throwing operation', async () => {
		const queue = new GattQueue();
		const failed = queue.enqueue(async () => {
			throw new Error('gatt');
		});
		const threw = queue.enqueue((() => {
			throw new Error('sync');
		}) as () => Promise<void>);
		const after = queue.enqueue(async () => 'ok');
		await expect(failed).rejects.toThrow('gatt');
		await expect(threw).rejects.toThrow('sync');
		await expect(after).resolves.toBe('ok');
	});
});

describe('StopLane', () => {
	it('keeps one stop outstanding and drops a second', async () => {
		const log: string[] = [];
		const hold = gate();
		let writes = 0;
		const lane = new StopLane(async () => {
			writes += 1;
			await hold.done;
			return true;
		}, (l) => log.push(l));
		const first = lane.stop();
		await expect(lane.stop()).resolves.toBe(false);
		expect(writes).toBe(1);
		expect(log).toContain('Stop write already in flight — not stacking another');
		hold.release();
		await expect(first).resolves.toBe(true);
		const third = lane.stop();
		expect(writes).toBe(2);
		await third;
	});

	it('a cancel makes a queued stop refuse when it reaches the front', async () => {
		const queue = new GattQueue();
		const hold = gate();
		void queue.enqueue(() => hold.done);
		let sent = false;
		const lane = new StopLane(
			(guard) =>
				queue.enqueueFront(async () => {
					if (!guard()) return false;
					sent = true;
					return true;
				}),
			() => {}
		);
		const stop = lane.stop();
		lane.cancel();
		hold.release();
		await expect(stop).resolves.toBe(false);
		expect(sent).toBe(false);
	});

	it('writes the Idle byte', () => {
		expect(STOP_BYTE).toBe(0x02);
	});
});
