<script lang="ts">
	/**
	 * `RoasterAutocomplete` — a typeahead input over the existing roaster
	 * directory.
	 *
	 * Two-track state: `value` is the in-progress *string* the user is typing
	 * (free-form, debounced into a filter); `resolved` is the {@link Roaster}
	 * the user has actually picked from the dropdown (or `null` if the typed
	 * name doesn't match an existing roaster yet).
	 *
	 * Importantly, this component does NOT create roasters as the user types
	 * — that bug pollutes the directory with `O`, `On`, `Ony`, `Onyx`, …
	 * partial rows. Creation is the parent's job, deferred to Save time:
	 * the parent checks for an exact-name match in the directory; if none,
	 * it calls `library.ensureRoaster(value)` once.
	 *
	 * Keyboard nav: ↓ / ↑ move the active match, Enter selects it, Esc
	 * closes the popover. Mouse hover updates the active match too.
	 *
	 * Mirrors the brief's spec in §7 of the bean-editor-polish task.
	 */
	import type { Roaster } from '$lib/bean';

	let {
		value,
		resolved,
		roasters,
		placeholder = '',
		onChange,
		onResolve,
		onCommitTyped
	}: {
		/** The current free-text input. */
		value: string;
		/** The Roaster the user has selected (or `null` if not yet picked). */
		resolved: Roaster | null;
		/** The full roaster directory to search against. */
		roasters: Roaster[];
		/** Input placeholder text. */
		placeholder?: string;
		/** Fires on every keystroke with the new free-text value. */
		onChange: (next: string) => void;
		/**
		 * Fires when the user picks a match (click or Enter). Receives the
		 * full Roaster row. Parents typically set both `value` (to the row's
		 * name) and `resolved` (to the row) from this.
		 *
		 * Also fires with `null` when the user clears the input — so the
		 * parent can clear any stored resolved Roaster.
		 */
		onResolve: (roaster: Roaster | null) => void;
		/**
		 * Optional. Fires when the input loses focus AND the user typed a
		 * value but didn't pick a row. The parent decides whether to create
		 * a fresh roaster from the typed string. Used by the BeanEditor's
		 * live-edit mode to commit "I typed `Onyx`, blurred, didn't click"
		 * to the store.
		 */
		onCommitTyped?: (typed: string) => void;
	} = $props();

	let focused = $state(false);
	let activeIdx = $state(0);
	let inputEl = $state<HTMLInputElement | null>(null);

	/**
	 * Case-insensitive prefix + substring match on the typed value. We use
	 * substring rather than strict prefix so "onyx" still matches "Coffee
	 * Onyx Lab" — names don't always start with the roastery word. The
	 * active record (if it's in the list) is hidden from suggestions so
	 * the user doesn't see their own pick echoed back.
	 *
	 * Pure read-only over the props — safe to be a `$derived`.
	 */
	const matches = $derived.by<Roaster[]>(() => {
		const needle = value.trim().toLowerCase();
		if (!needle) return [];
		const out: Roaster[] = [];
		for (const r of roasters) {
			if (resolved?.id === r.id) continue;
			if (r.name.toLowerCase().includes(needle)) out.push(r);
			if (out.length >= 5) break;
		}
		return out;
	});

	/** Whether there is an exact-name match in the directory for the input. */
	const exactMatch = $derived.by<Roaster | null>(() => {
		const needle = value.trim().toLowerCase();
		if (!needle) return null;
		return roasters.find((r) => r.name.trim().toLowerCase() === needle) ?? null;
	});

	/** Pick a row from the suggestion list. */
	function pick(r: Roaster): void {
		onChange(r.name);
		onResolve(r);
		focused = false;
		activeIdx = 0;
		// blur so the popover hides
		inputEl?.blur();
	}

	function onInput(e: Event): void {
		const next = (e.currentTarget as HTMLInputElement).value;
		onChange(next);
		// Empty input → clear any resolved roaster too. Otherwise, if the
		// typed value no longer matches the resolved row, drop the resolve
		// (parent will re-resolve via `exactMatch` at save time, or treat
		// as a new roaster).
		const trimmed = next.trim();
		if (!trimmed) {
			onResolve(null);
		} else if (resolved && resolved.name.trim().toLowerCase() !== trimmed.toLowerCase()) {
			onResolve(null);
		}
		activeIdx = 0;
	}

	function onKeyDown(e: KeyboardEvent): void {
		if (!focused || matches.length === 0) return;
		if (e.key === 'ArrowDown') {
			e.preventDefault();
			activeIdx = (activeIdx + 1) % matches.length;
		} else if (e.key === 'ArrowUp') {
			e.preventDefault();
			activeIdx = (activeIdx - 1 + matches.length) % matches.length;
		} else if (e.key === 'Enter') {
			const r = matches[activeIdx];
			if (r) {
				e.preventDefault();
				pick(r);
			}
		} else if (e.key === 'Escape') {
			e.preventDefault();
			focused = false;
		}
	}

	const showPopover = $derived(focused && matches.length > 0);

	/**
	 * Resolve the input to a roaster (existing or fresh) — called by the
	 * parent at save time. Returns `null` if the input is blank. If an
	 * exact case-insensitive match exists, returns it; otherwise this
	 * function takes no action — the parent decides whether to create a
	 * fresh roaster via the store. Exposed via the {@link resolveAtSave}
	 * helper rather than a `$bind:` to keep the component declarative.
	 */
	export function exactMatchAtSave(): Roaster | null {
		return exactMatch;
	}
</script>

<div class="ra-wrap">
	<input
		bind:this={inputEl}
		class="be-input"
		type="text"
		{value}
		{placeholder}
		oninput={onInput}
		onfocus={() => (focused = true)}
		onblur={() => {
			// Defer so a click on a popover item can fire first.
			setTimeout(() => {
				focused = false;
				const trimmed = value.trim();
				// Fire only if the typed value differs from the resolved row's
				// name — otherwise we'd spam the parent with no-ops.
				if (
					trimmed &&
					(!resolved || resolved.name.trim().toLowerCase() !== trimmed.toLowerCase())
				) {
					onCommitTyped?.(trimmed);
				}
			}, 120);
		}}
		onkeydown={onKeyDown}
		autocomplete="off"
		spellcheck="false"
	/>
	{#if showPopover}
		<ul class="ra-popover" role="listbox">
			{#each matches as r, i (r.id)}
				<li>
					<button
						type="button"
						class="ra-item"
						class:is-active={i === activeIdx}
						onmousedown={(e) => {
							// mousedown fires before blur, so the popover stays alive
							e.preventDefault();
							pick(r);
						}}
						onmouseenter={() => (activeIdx = i)}
					>
						<i class="ph ph-buildings" aria-hidden="true"></i>
						<span>{r.name}</span>
					</button>
				</li>
			{/each}
			{#if value.trim() && !exactMatch}
				<li class="ra-hint">
					<i class="ph ph-plus" aria-hidden="true"></i>
					New roaster <strong>"{value.trim()}"</strong> will be created on save
				</li>
			{/if}
		</ul>
	{/if}
</div>

<style>
	.ra-wrap {
		position: relative;
		width: 100%;
	}
	.ra-popover {
		position: absolute;
		top: calc(100% + 4px);
		left: 0;
		right: 0;
		z-index: 70;
		list-style: none;
		padding: 4px;
		margin: 0;
		background: var(--bg-card, rgba(20, 20, 22, 0.98));
		backdrop-filter: blur(8px);
		border: 1px solid rgba(var(--tint-rgb), 0.16);
		border-radius: var(--radius-md, 8px);
		box-shadow: 0 12px 32px rgba(0, 0, 0, 0.5);
		max-height: 220px;
		overflow-y: auto;
	}
	.ra-item {
		display: flex;
		align-items: center;
		gap: 8px;
		width: 100%;
		background: transparent;
		border: 0;
		text-align: left;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 7px 10px;
		border-radius: var(--radius-sm, 6px);
		cursor: pointer;
	}
	.ra-item i {
		font-size: 13px;
		color: rgba(var(--tint-rgb), 0.45);
	}
	.ra-item.is-active {
		background: rgba(193, 124, 79, 0.16);
		color: var(--copper-300);
	}
	.ra-item.is-active i {
		color: var(--copper-300);
	}
	.ra-hint {
		display: flex;
		align-items: center;
		gap: 8px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
		padding: 7px 10px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.08);
		margin-top: 2px;
	}
	.ra-hint i {
		font-size: 12px;
	}
	.ra-hint strong {
		color: var(--copper-300);
		font-weight: 600;
	}
</style>
