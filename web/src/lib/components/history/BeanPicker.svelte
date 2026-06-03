<script lang="ts">
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import MagnifyingGlassIcon from 'phosphor-svelte/lib/MagnifyingGlassIcon';
	import XCircleIcon from 'phosphor-svelte/lib/XCircleIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
	/**
	 * `BeanPicker` — a small modal that lists every bag in the bean
	 * library and lets the user pick one to retroactively bind to a
	 * shot (or clear the current binding). Used by the shot-detail
	 * panel's "Change bean" / "Assign bean" affordance.
	 *
	 * Visual choice (per the task spec): a centered modal with a
	 * scrollable list, distinct from the brew page's inline bean
	 * card — the use case is retroactive binding, not active brew
	 * context. The data flow + selection model still match
	 * (`getBeanStore().getBean(id)` + the shared
	 * `snapshotFromBean` helper at the call site).
	 *
	 * Sort: most-recently-updated bag first — same default the brew
	 * page's library page uses for the bags tab.
	 */
	import { getBeanStore, daysOffRoast, type Bean, type Roaster } from '$lib/bean';

	let {
		currentBeanId,
		onPick,
		onClear,
		onClose
	}: {
		/** The shot's current bean FK, for the "currently bound" highlight. */
		currentBeanId: string | null;
		/** User picked a bean — caller resolves the live row + snapshots. */
		onPick: (bean: Bean, roaster: Roaster | null) => void;
		/** User chose to clear the shot's bean binding. */
		onClear: () => void;
		/** Close the modal without changing anything. */
		onClose: () => void;
	} = $props();

	const library = getBeanStore();

	/** Search query — case-insensitive substring over name + roaster. */
	let query = $state('');

	/**
	 * The bags to display: tombstoned / archived bags filtered out, then
	 * searched, then sorted by `updatedAt` desc. Archived bags would
	 * confuse the rebind UX — they're "this bag is done"; if the user
	 * wants to bind a finished bag, they can unarchive it first.
	 */
	const visible = $derived.by(() => {
		const q = query.trim().toLowerCase();
		return library.beans
			.filter((b) => b.deletedAt == null && b.archivedAt == null)
			.filter((b) => {
				if (q === '') return true;
				const name = b.name.toLowerCase();
				const r = b.roasterId
					? (library.getRoaster(b.roasterId)?.name ?? '').toLowerCase()
					: '';
				return name.includes(q) || r.includes(q);
			})
			.slice()
			.sort((a, b) => b.updatedAt - a.updatedAt);
	});

	function pick(bean: Bean): void {
		const roaster = bean.roasterId ? library.getRoaster(bean.roasterId) : null;
		onPick(bean, roaster);
	}

	function onBackdrop(e: MouseEvent): void {
		if (e.target === e.currentTarget) onClose();
	}

	function onKey(e: KeyboardEvent): void {
		if (e.key === 'Escape') onClose();
	}

	function beanSubtitle(b: Bean): string {
		const parts: string[] = [];
		const r = b.roasterId ? library.getRoaster(b.roasterId) : null;
		if (r?.name) parts.push(r.name);
		const days = daysOffRoast(b.roastedOn);
		if (days != null) parts.push(`${days}d off roast`);
		return parts.join(' · ');
	}
</script>

<svelte:window onkeydown={onKey} />

<div
	class="bp-backdrop"
	role="dialog"
	aria-modal="true"
	aria-label="Pick a bean for this shot"
	onclick={onBackdrop}
	onkeydown={onKey}
	tabindex="-1"
>
	<div class="bp-modal">
		<div class="bp-head">
			<div>
				<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Library</div>
				<div class="bp-title">Choose a bean</div>
			</div>
			<button class="bp-close" onclick={onClose} aria-label="Close">
				<XIcon aria-hidden="true" />
			</button>
		</div>

		<div class="bp-search">
			<MagnifyingGlassIcon aria-hidden="true" />
			<input
				bind:value={query}
				placeholder="Search bag or roaster…"
				autocomplete="off"
			/>
		</div>

		<ul class="bp-list">
			{#each visible as b (b.id)}
				<li>
					<button
						class="bp-row"
						class:is-current={b.id === currentBeanId}
						onclick={() => pick(b)}
					>
						<span class="bp-row-l">
							<span class="bp-row-name">{b.name || 'Untitled bag'}</span>
							<span class="bp-row-sub">{beanSubtitle(b)}</span>
						</span>
						{#if b.id === currentBeanId}
							<CheckIcon class="bp-row-current" aria-hidden="true" />
						{/if}
					</button>
				</li>
			{/each}
			{#if visible.length === 0}
				<li class="bp-empty">
					{query
						? 'No bags match — try a different search.'
						: 'No bags in the library yet. Add one on the /beans page.'}
				</li>
			{/if}
		</ul>

		<div class="bp-foot">
			{#if currentBeanId}
				<button class="bp-clear" onclick={onClear}>
					<XCircleIcon aria-hidden="true" /> Clear binding
				</button>
			{/if}
			<button class="bp-cancel" onclick={onClose}>Cancel</button>
		</div>
	</div>
</div>

<style>
	.bp-backdrop {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.55);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 100;
		padding: 20px;
	}
	.bp-modal {
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-lg, 14px);
		width: min(520px, 100%);
		max-height: 80vh;
		display: flex;
		flex-direction: column;
		gap: 14px;
		padding: 20px;
		box-shadow: var(--shadow-lg);
	}
	.bp-head {
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
	}
	.bp-title {
		font-family: var(--font-serif);
		font-size: 20px;
		color: var(--fg-1);
		margin-top: 2px;
	}
	.bp-close {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.5);
		cursor: pointer;
		font-size: 18px;
		padding: 4px;
	}
	.bp-close:hover {
		color: var(--fg-1);
	}
	.bp-search {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 12px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-pill);
	}
	.bp-search:focus-within {
		border-color: rgba(var(--tint-rgb), 0.25);
	}
	.bp-search :global(svg) {
		color: rgba(var(--tint-rgb), 0.45);
		font-size: 13px;
	}
	.bp-search input {
		flex: 1 1 auto;
		min-width: 0;
		background: transparent;
		border: 0;
		outline: 0;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 2px 0;
	}
	.bp-list {
		list-style: none;
		padding: 0;
		margin: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
		overflow-y: auto;
		flex: 1 1 auto;
		min-height: 0;
	}
	.bp-row {
		display: flex;
		width: 100%;
		justify-content: space-between;
		align-items: center;
		gap: 10px;
		padding: 10px 12px;
		background: transparent;
		border: 0;
		border-radius: var(--radius-sm);
		text-align: left;
		cursor: pointer;
		color: var(--fg-1);
		transition: background var(--dur-1) var(--ease);
	}
	.bp-row:hover {
		background: rgba(var(--tint-rgb), 0.05);
	}
	.bp-row.is-current {
		background: rgba(193, 116, 75, 0.08);
		border: 1px solid rgba(193, 116, 75, 0.3);
		padding: 9px 11px; /* compensate for the 1px border */
	}
	.bp-row-l {
		display: flex;
		flex-direction: column;
		gap: 2px;
		flex: 1 1 auto;
		min-width: 0;
	}
	.bp-row-name {
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.bp-row-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	:global(.bp-row-current) {
		color: var(--copper-400);
		font-size: 14px;
	}
	.bp-empty {
		padding: 24px 12px;
		text-align: center;
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.45);
	}
	.bp-foot {
		display: flex;
		justify-content: flex-end;
		align-items: center;
		gap: 10px;
		padding-top: 8px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.06);
	}
	.bp-clear {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		font-family: var(--font-sans);
		font-size: 12px;
		cursor: pointer;
		margin-right: auto;
		padding: 6px 10px;
		border-radius: var(--radius-sm);
	}
	.bp-clear:hover {
		color: var(--danger);
		background: rgba(var(--danger-rgb), 0.08);
	}
	.bp-cancel {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 6px 14px;
		border-radius: var(--radius-pill);
		cursor: pointer;
	}
	.bp-cancel:hover {
		background: rgba(var(--tint-rgb), 0.07);
	}
</style>
