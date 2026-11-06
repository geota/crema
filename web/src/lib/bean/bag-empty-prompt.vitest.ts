import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getActiveDialog } from '#lib/components/shared/confirm-dialog.svelte.js';
import { coerceBean } from './model';
import { getBeanStore } from './store.svelte';

vi.mock('$app/navigation', () => ({ goto: vi.fn() }));
vi.mock('$app/paths', () => ({ resolve: (p: string) => p }));

const { closeBagEmptyPrompt, promptBagEmpty } = await import('./bag-empty-prompt');

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	getBeanStore().clearAll();
	getBeanStore().upsertBean({ ...coerceBean({ id: 'a', name: 'Gesha' })!, bagSize: 250 });
});

describe('closeBagEmptyPrompt', () => {
	it('closes the question and asks nothing else', async () => {
		const done = promptBagEmpty('a');
		expect(getActiveDialog()?.options.title).toBe('Bag empty');
		closeBagEmptyPrompt();
		await done;
		expect(getActiveDialog()).toBeNull();
	});

	it('leaves the bag unarchived', async () => {
		const done = promptBagEmpty('a');
		closeBagEmptyPrompt();
		await done;
		expect(getBeanStore().getBean('a')?.archivedAt).toBeNull();
	});

	it('leaves another dialog alone', async () => {
		const { confirmDialog } = await import('#lib/components/shared/confirm-dialog.svelte.js');
		getBeanStore().updateBean('a', { rating: 4 });
		const done = promptBagEmpty('a');
		const other = confirmDialog({ title: 'Other', message: 'x' });
		await done;
		closeBagEmptyPrompt();
		expect(getActiveDialog()?.options.title).toBe('Other');
		getActiveDialog()?.resolve(false);
		await other;
	});
});
