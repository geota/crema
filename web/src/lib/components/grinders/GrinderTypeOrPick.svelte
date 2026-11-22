<script lang="ts">
	/**
	 * `GrinderTypeOrPick` — "Add a grinder: type or pick" (#488, boards
	 * WideBagGrind, PhoneBagGrindAdd). One box for the bag editor, Devices →
	 * Grinder "Add grinder" and the Brew picker.
	 *
	 * Matches come from the core (`match_grinders`, bean search's typo-tolerant
	 * matcher): your grinders not in `excludeIds`, then the well-known
	 * catalogue, then your archived grinders. The last option is always
	 * "+ Create “<typed>”", highlighted when nothing matches. Enter takes the
	 * highlighted row (the top match). The box only answers the choice; the
	 * parent takes it (`BeanLibraryStore.takeGrinderChoice`).
	 */
	import MagnifyingGlassIcon from 'phosphor-svelte/lib/MagnifyingGlassIcon';
	import { matchGrinders, type Grinder, type GrinderChoice, type GrinderMatch } from '#lib/bean/grinder.js';

	let {
		grinders,
		excludeIds = [],
		placeholder = 'Add a grinder: type or pick',
		autofocus = false,
		onPick
	}: {
		/** Every grinder record (archived included: the core lists them last). */
		grinders: readonly Grinder[];
		/** Owned grinders to leave out (already on this bag). */
		excludeIds?: readonly string[];
		placeholder?: string;
		autofocus?: boolean;
		onPick: (choice: GrinderChoice) => void;
	} = $props();

	const uid = $props.id();
	let text = $state('');
	let open = $state(false);
	let active = $state(0);
	let inputEl = $state<HTMLInputElement | null>(null);

	$effect(() => {
		if (autofocus) inputEl?.focus();
	});

	const MAX = 8;
	const matches = $derived.by<GrinderMatch[]>(() => {
		try {
			return matchGrinders(text, grinders, excludeIds).slice(0, MAX);
		} catch {
			return [];
		}
	});
	/** The typed name, when there is one: "+ Create “…”" is offered last. */
	const typed = $derived(text.trim());
	type Row = { key: string; choice: GrinderChoice; title: string; sub: string; group: string };
	const rows = $derived.by<Row[]>(() => {
		const out: Row[] = matches.map((m) => ({
			key: `${m.kind}:${m.id}`,
			choice: { kind: m.kind, id: m.id, name: m.name },
			title: m.name,
			sub: subFor(m),
			group: m.kind === 'owned' ? 'Your grinders' : m.kind === 'catalogue' ? 'Well-known grinders' : 'Archived'
		}));
		if (typed) {
			out.push({
				key: 'create',
				choice: { kind: 'create', name: typed },
				title: `+ Create “${typed}”`,
				sub: 'A new grinder with the default scale (0–20, step 0.1); adjust it on Devices',
				group: ''
			});
		}
		return out;
	});

	function subFor(m: GrinderMatch): string {
		if (m.kind === 'owned') {
			const g = grinders.find((x) => x.id === m.id);
			const s = g?.scale;
			return s ? `${s.min}–${s.max}${s.unit ? ' ' + s.unit : ''}` : '';
		}
		if (m.kind === 'catalogue') return 'Adds it to your grinders, with its scale';
		return 'Removed from Devices: picking it brings it back';
	}

	$effect(() => {
		// A new query highlights the top match (or Create when nothing matches).
		void text;
		active = 0;
	});

	function pick(row: Row): void {
		onPick(row.choice);
		text = '';
		open = false;
	}

	function onKey(e: KeyboardEvent): void {
		if (e.key === 'ArrowDown') {
			e.preventDefault();
			open = true;
			if (rows.length) active = (active + 1) % rows.length;
		} else if (e.key === 'ArrowUp') {
			e.preventDefault();
			if (rows.length) active = (active - 1 + rows.length) % rows.length;
		} else if (e.key === 'Enter') {
			const row = rows[active];
			if (row) {
				e.preventDefault();
				pick(row);
			}
		} else if (e.key === 'Escape') {
			open = false;
		}
	}
</script>

<div class="gtp">
	<div class="gtp-box" class:is-open={open}>
		<MagnifyingGlassIcon aria-hidden="true" />
		<input
			bind:this={inputEl}
			class="gtp-input"
			type="text"
			role="combobox"
			aria-expanded={open && rows.length > 0}
			aria-controls="{uid}-list"
			aria-activedescendant={open && rows[active] ? `${uid}-opt-${active}` : undefined}
			aria-label={placeholder}
			{placeholder}
			bind:value={text}
			onfocus={() => (open = true)}
			oninput={() => (open = true)}
			onblur={() => setTimeout(() => (open = false), 120)}
			onkeydown={onKey}
			autocomplete="off"
			spellcheck="false"
		/>
	</div>
	{#if open && rows.length > 0}
		<div class="gtp-list" id="{uid}-list" role="listbox" aria-label="Grinder matches">
			{#if matches.length === 0 && typed}
				<div class="gtp-none">No match in your grinders or the well-known list.</div>
			{/if}
			{#each rows as row, i (row.key)}
				{#if row.group && (i === 0 || rows[i - 1].group !== row.group)}
					<div class="gtp-group">{row.group}</div>
				{/if}
				<div
					id="{uid}-opt-{i}"
					role="option"
					tabindex="-1"
					aria-selected={i === active}
					class="gtp-opt"
					class:is-active={i === active}
					class:is-create={row.choice.kind === 'create'}
					onmousedown={(e) => {
						e.preventDefault();
						pick(row);
					}}
					onmouseenter={() => (active = i)}
				>
					<span class="gtp-text">
						<span class="gtp-title">{row.title}</span>
						{#if row.sub}<span class="gtp-sub">{row.sub}</span>{/if}
					</span>
					{#if i === active}<kbd class="gtp-key" aria-hidden="true">↵</kbd>{/if}
				</div>
			{/each}
		</div>
	{/if}
</div>

<style>
	.gtp {
		position: relative;
		width: 100%;
	}
	.gtp-box {
		display: flex;
		align-items: center;
		gap: 10px;
		min-height: 44px;
		box-sizing: border-box;
		padding: 0 12px;
		border: 1px solid var(--field-border);
		border-radius: var(--radius-sm, 8px);
		background: rgba(var(--tint-rgb), 0.04);
		color: var(--fg-muted);
	}
	.gtp-box.is-open,
	.gtp-box:focus-within {
		border-color: var(--copper-400);
	}
	.gtp-input {
		flex: 1;
		min-width: 0;
		border: 0;
		outline: 0;
		background: transparent;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 14px;
		padding: 10px 0;
	}
	.gtp-list {
		position: absolute;
		top: calc(100% + 4px);
		left: 0;
		right: 0;
		z-index: 70;
		max-height: 320px;
		overflow-y: auto;
		background: var(--bg-card, rgba(20, 20, 22, 0.98));
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: 12px;
		box-shadow: 0 16px 40px rgba(0, 0, 0, 0.5);
	}
	.gtp-group {
		padding: 9px 14px 3px;
		font-size: 11px;
		font-weight: 500;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--fg-2);
	}
	.gtp-none {
		padding: 12px 14px;
		font-size: 13px;
		color: var(--fg-muted);
	}
	.gtp-opt {
		display: flex;
		align-items: center;
		gap: 10px;
		min-height: 46px;
		padding: 6px 14px;
		box-sizing: border-box;
		cursor: pointer;
	}
	.gtp-opt.is-create {
		border-top: 1px solid rgba(var(--tint-rgb), 0.1);
	}
	.gtp-opt.is-active {
		background: rgba(193, 124, 79, 0.12);
	}
	.gtp-text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 1px;
	}
	.gtp-title {
		font-size: 14px;
		color: var(--fg-1);
	}
	.gtp-opt.is-create .gtp-title {
		color: var(--fg-accent);
	}
	.gtp-sub {
		font-size: 12px;
		color: var(--fg-muted);
	}
	.gtp-key {
		font-family: var(--font-mono);
		font-size: 12px;
		color: var(--fg-2);
		border: 1px solid rgba(var(--tint-rgb), 0.2);
		border-radius: 4px;
		padding: 1px 6px;
	}
</style>
