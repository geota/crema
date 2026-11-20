/**
 * The Brew status card (brew-messages PRD §1): machine faults have no ✕ and
 * announce as alerts; advisory cards are status regions; the maintenance
 * card's text block is one tap-through button carrying its link line.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount, type ComponentProps } from 'svelte';
import BrewStatusCard from './BrewStatusCard.svelte';

let cmp: ReturnType<typeof mount> | null = null;
afterEach(() => {
	if (cmp) unmount(cmp);
	cmp = null;
	document.body.innerHTML = '';
});

function render(props: ComponentProps<typeof BrewStatusCard>): HTMLElement {
	const target = document.createElement('div');
	document.body.appendChild(target);
	cmp = mount(BrewStatusCard, { target, props });
	flushSync();
	return target;
}

describe('BrewStatusCard', () => {
	it('a machine fault is an alert with no dismiss button', () => {
		const el = render({ title: 'Machine error', text: 'Flow too fast', variant: 'error' });
		const card = el.querySelector('.bsc')!;
		expect(card.getAttribute('role')).toBe('alert');
		expect(card.textContent).toContain('Machine error');
		expect(card.textContent).toContain('Flow too fast');
		expect(el.querySelector('button')).toBeNull();
	});

	it('maintenance: status role, one tap-through with the link, and a labelled ✕', () => {
		const onClick = vi.fn();
		const onDismiss = vi.fn();
		const el = render({
			title: 'Maintenance due',
			text: 'Filter due',
			variant: 'warning',
			onClick,
			linkLabel: 'Water & maintenance →',
			onDismiss
		});
		expect(el.querySelector('.bsc')!.getAttribute('role')).toBe('status');
		const body = el.querySelector<HTMLButtonElement>('.bsc-body-btn')!;
		expect(body.textContent).toContain('Water & maintenance →');
		body.click();
		expect(onClick).toHaveBeenCalledTimes(1);
		expect(onDismiss).not.toHaveBeenCalled();
		const close = el.querySelector<HTMLButtonElement>('.bsc-dismiss')!;
		expect(close.getAttribute('aria-label')).toBe('Dismiss Maintenance due');
		close.click();
		expect(onDismiss).toHaveBeenCalledTimes(1);
		expect(onClick).toHaveBeenCalledTimes(1);
	});

	it('renders the labelled action', () => {
		const run = vi.fn();
		const el = render({
			title: 'Bag empty',
			text: 'Kenya AA is empty',
			variant: 'warning',
			action: { label: 'Switch bag', onClick: run }
		});
		const btn = el.querySelector<HTMLButtonElement>('.bsc-action')!;
		expect(btn.textContent?.trim()).toBe('Switch bag');
		btn.click();
		expect(run).toHaveBeenCalledTimes(1);
	});
});
