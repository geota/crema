/**
 * Drives `installServiceWorker` against a fake `ServiceWorkerContainer`: the
 * registration call, the offer when a new build is waiting, the parked offer
 * that fires once the shell reports an idle machine, and the apply path
 * (SKIP_WAITING, then one reload on controllerchange).
 */
import { describe, expect, it, vi } from 'vitest';
import { installServiceWorker, SW_SCOPE, SW_URL } from './register.js';

class FakeWorker extends EventTarget {
	state: ServiceWorkerState = 'installing';
	readonly posted: unknown[] = [];
	postMessage(m: unknown): void {
		this.posted.push(m);
	}
	setState(s: ServiceWorkerState): void {
		this.state = s;
		this.dispatchEvent(new Event('statechange'));
	}
}

class FakeRegistration extends EventTarget {
	waiting: FakeWorker | null = null;
	installing: FakeWorker | null = null;
	readonly update = vi.fn(async () => {});
	/** Simulate the browser finding a new build. */
	foundUpdate(): FakeWorker {
		const w = new FakeWorker();
		this.installing = w;
		this.dispatchEvent(new Event('updatefound'));
		return w;
	}
}

class FakeContainer extends EventTarget {
	controller: object | null = {};
	readonly registration = new FakeRegistration();
	readonly register = vi.fn(async (url: string, opts: { scope: string }) => {
		this.calls.push([url, opts.scope]);
		return this.registration as unknown as ServiceWorkerRegistration;
	});
	readonly calls: [string, string][] = [];
}

function setup(canReload = () => true) {
	const container = new FakeContainer();
	const withdrawn: number[] = [];
	let offers = 0;
	const offer = vi.fn<(apply: () => void) => () => void>(() => {
		const n = ++offers;
		return () => withdrawn.push(n);
	});
	const onDeferred = vi.fn();
	const reload = vi.fn();
	const doc = { addEventListener: vi.fn(), removeEventListener: vi.fn(), visibilityState: 'visible' as const };
	const handle = installServiceWorker({
		canReload,
		offer,
		onDeferred,
		reload,
		container: container as unknown as ServiceWorkerContainer,
		doc,
		setInterval: vi.fn(() => 1 as unknown as ReturnType<typeof setInterval>) as unknown as typeof setInterval,
		clearInterval: vi.fn() as unknown as typeof clearInterval
	});
	return { container, offer, onDeferred, withdrawn, reload, doc, handle };
}

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('installServiceWorker', () => {
	it('registers /sw.js at the root scope', async () => {
		const { container } = setup();
		await tick();
		expect(container.calls).toEqual([[SW_URL, SW_SCOPE]]);
	});

	it('offers a reload when a new build finishes installing behind a live controller', async () => {
		const { container, offer } = setup();
		await tick();
		const w = container.registration.foundUpdate();
		w.setState('installed');
		expect(offer).toHaveBeenCalledTimes(1);
	});

	it('does not offer on the very first install (no controller yet)', async () => {
		const { container, offer } = setup();
		container.controller = null;
		await tick();
		container.registration.foundUpdate().setState('installed');
		expect(offer).not.toHaveBeenCalled();
	});

	it('parks the offer while the machine is busy and makes it once idle', async () => {
		let idle = false;
		const { container, offer, handle } = setup(() => idle);
		await tick();
		container.registration.foundUpdate().setState('installed');
		expect(offer).not.toHaveBeenCalled();
		handle.onStateChange(); // still busy
		expect(offer).not.toHaveBeenCalled();
		idle = true;
		handle.onStateChange();
		expect(offer).toHaveBeenCalledTimes(1);
		handle.onStateChange(); // never twice for the same worker
		expect(offer).toHaveBeenCalledTimes(1);
	});

	it('applying tells the waiting worker to take over and reloads once on controllerchange', async () => {
		const { container, offer, reload } = setup();
		await tick();
		const w = container.registration.foundUpdate();
		w.setState('installed');
		const apply = offer.mock.calls[0][0];
		apply();
		expect(w.posted).toEqual([{ type: 'SKIP_WAITING' }]);
		expect(reload).not.toHaveBeenCalled();
		container.dispatchEvent(new Event('controllerchange'));
		container.dispatchEvent(new Event('controllerchange'));
		expect(reload).toHaveBeenCalledTimes(1);
	});

	it('offers a build that was already waiting when the tab opened', async () => {
		const { container, offer } = setup();
		const w = new FakeWorker();
		w.state = 'installed';
		container.registration.waiting = w;
		await tick();
		expect(offer).toHaveBeenCalledTimes(1);
	});

	it('re-checks the gate when Reload is tapped: busy re-parks and offers again once idle (cohort review 06 #6)', async () => {
		let idle = true;
		const { container, offer, onDeferred, reload, handle } = setup(() => idle);
		await tick();
		const w = container.registration.foundUpdate();
		w.setState('installed');
		expect(offer).toHaveBeenCalledTimes(1);
		idle = false; // a shot started after the toast appeared
		offer.mock.calls[0][0]();
		expect(w.posted).toEqual([]);
		expect(onDeferred).toHaveBeenCalledTimes(1);
		container.dispatchEvent(new Event('controllerchange'));
		expect(reload).not.toHaveBeenCalled();
		handle.onStateChange(); // still busy
		expect(offer).toHaveBeenCalledTimes(1);
		idle = true;
		handle.onStateChange();
		expect(offer).toHaveBeenCalledTimes(2);
		offer.mock.calls[1][0]();
		expect(w.posted).toEqual([{ type: 'SKIP_WAITING' }]);
	});

	it('a newer build supersedes the offered one: its offer replaces the old, and Reload targets it (cohort review 06 #5)', async () => {
		const { container, offer, withdrawn, reload } = setup();
		await tick();
		const a = container.registration.foundUpdate();
		a.setState('installed');
		container.registration.waiting = a;
		expect(offer).toHaveBeenCalledTimes(1);
		// Hourly check finds build B; A goes redundant.
		const b = container.registration.foundUpdate();
		a.setState('redundant');
		b.setState('installed');
		container.registration.waiting = b;
		expect(offer).toHaveBeenCalledTimes(2);
		expect(withdrawn).toEqual([1]);
		// Even the stale first toast's Reload reaches the worker waiting now.
		offer.mock.calls[0][0]();
		expect(a.posted).toEqual([]);
		expect(b.posted).toEqual([{ type: 'SKIP_WAITING' }]);
		container.dispatchEvent(new Event('controllerchange'));
		expect(reload).toHaveBeenCalledTimes(1);
	});

	it('reloads straight away when nothing is waiting any more (another tab applied it)', async () => {
		const { container, offer, reload } = setup();
		await tick();
		const w = container.registration.foundUpdate();
		w.setState('installed');
		w.setState('activated');
		container.registration.waiting = null;
		offer.mock.calls[0][0]();
		expect(w.posted).toEqual([]);
		expect(reload).toHaveBeenCalledTimes(1);
	});

	it('a busy Reload with no worker waiting any more is offered again once idle (final review 03 P3-3)', async () => {
		let idle = true;
		const { container, offer, onDeferred, reload, handle } = setup(() => idle);
		await tick();
		const w = container.registration.foundUpdate();
		w.setState('installed');
		expect(offer).toHaveBeenCalledTimes(1);
		// Another tab applied the update: nothing waits any more.
		w.setState('activated');
		container.registration.waiting = null;
		idle = false;
		offer.mock.calls[0][0]();
		expect(reload).not.toHaveBeenCalled();
		expect(onDeferred).toHaveBeenCalledTimes(1);
		handle.onStateChange(); // still busy
		expect(offer).toHaveBeenCalledTimes(1);
		idle = true;
		handle.onStateChange();
		expect(offer).toHaveBeenCalledTimes(2);
		// Only once: a later state change does not stack another offer.
		handle.onStateChange();
		expect(offer).toHaveBeenCalledTimes(2);
		offer.mock.calls[1][0]();
		expect(reload).toHaveBeenCalledTimes(1);
	});

	it('checks for a new build when the tab becomes visible and stops on dispose', async () => {
		const { container, doc, handle } = setup();
		await tick();
		const onVis = doc.addEventListener.mock.calls.find((c) => c[0] === 'visibilitychange')?.[1] as () => void;
		onVis();
		expect(container.registration.update).toHaveBeenCalledTimes(1);
		handle.dispose();
		expect(doc.removeEventListener).toHaveBeenCalledWith('visibilitychange', onVis);
	});
});
