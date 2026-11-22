<script lang="ts">
	/**
	 * `BagGrindsEditor` — the bag editor's "Grind per grinder" block (#488,
	 * "Bag grinds"; boards WideBagGrind, PhoneBagGrind, PhoneBagGrindAdd).
	 *
	 * One row per saved grind: ☆/★ ("this bag's grinder", at most one), the
	 * grinder's name, a stepper on that grinder's scale (tap the value to type
	 * it) and 🗑. Values are strings, never clamped: one outside the scale
	 * shows as it is, with a warning. A row whose grinder was removed from
	 * Devices (archived or unknown) is dimmed and read-only, and can still be
	 * removed. The Add box is the shared type-or-pick box.
	 *
	 * The block edits the editor's draft only (`onChange`); the editor's Save
	 * writes the rows through the core three-way merge, and Cancel drops them.
	 * Picking a grinder in the Add box does create or restore its record on
	 * Devices at once (`takeGrinderChoice`).
	 */
	import MinusIcon from 'phosphor-svelte/lib/MinusIcon';
	import PlusIcon from 'phosphor-svelte/lib/PlusIcon';
	import StarIcon from 'phosphor-svelte/lib/StarIcon';
	import TrashIcon from 'phosphor-svelte/lib/TrashIcon';
	import { getBeanStore } from '#lib/bean/index.js';
	import {
		DEFAULT_GRIND_SCALE,
		NO_GRINDER_ID,
		formatGrind,
		grindNumber,
		grindPlaceholder,
		grindText,
		isLiveGrinder,
		validScale,
		type BagGrind,
		type GrinderChoice,
		type GrindScale
	} from '#lib/bean/grinder.js';
	import { getHistoryReads, lastGrinds } from '#lib/history/reads.svelte.js';
	import GrinderTypeOrPick from '#lib/components/grinders/GrinderTypeOrPick.svelte';

	let {
		grinds,
		baseGrinds,
		beanId,
		toFix = [],
		onChange
	}: {
		/** The draft's rows. */
		grinds: readonly BagGrind[];
		/** The rows the editor opened on: what "Changed from" measures from. */
		baseGrinds: readonly BagGrind[];
		/** The bag's id: the Add box seeds a new row from its newest shot. */
		beanId: string;
		/** The grinder ids Save refused (an empty grind), shown until filled. */
		toFix?: readonly string[];
		onChange: (next: BagGrind[]) => void;
	} = $props();

	const uid = $props.id();
	const library = getBeanStore();

	type Row = {
		row: BagGrind;
		key: string;
		name: string;
		scale: GrindScale;
		/** On Devices (or the "no grinder" slot): editable. */
		live: boolean;
		/** Can carry the star: a grinder on Devices. */
		starrable: boolean;
		base: BagGrind | null;
		changed: boolean;
		outOfRange: boolean;
		needsGrind: boolean;
	};

	const rows = $derived.by<Row[]>(() =>
		grinds.map((row) => {
			const none = row.grinderId === NO_GRINDER_ID;
			const grinder = none ? null : library.getGrinder(row.grinderId);
			const live = none || (grinder !== null && isLiveGrinder(grinder));
			const scale = grinder && validScale(grinder.scale) ? grinder.scale : DEFAULT_GRIND_SCALE;
			const base = baseGrinds.find((b) => b.grinderId === row.grinderId) ?? null;
			const n = grindNumber(row.grind);
			return {
				row,
				key: `g:${row.grinderId}`,
				name: none ? 'No grinder' : (live ? grinder?.name : null) || row.grinderName || 'Unknown grinder',
				scale,
				live,
				starrable: live && !none,
				base,
				changed: base !== null && base.grind.trim() !== row.grind.trim(),
				outOfRange: n !== null && (n < scale.min || n > scale.max),
				needsGrind: row.grind.trim() === '' && toFix.includes(row.grinderId)
			};
		})
	);

	function starPrefix(r: Row): string {
		return r.row.starred ? "This bag's grinder · " : '';
	}

	function rangeText(scale: GrindScale): string {
		return `${scale.min}–${scale.max}${scale.unit ? ` ${scale.unit}` : ''}`;
	}

	function update(grinderId: string, p: Partial<BagGrind>): void {
		onChange(grinds.map((r) => (r.grinderId === grinderId ? { ...r, ...p } : r)));
	}

	/** A new grind on a row: the name copy is refreshed from Devices with it. */
	function setGrind(r: Row, grind: string): void {
		if (grind === r.row.grind) return;
		const g = r.row.grinderId === NO_GRINDER_ID ? null : library.getGrinder(r.row.grinderId);
		update(r.row.grinderId, { grind, grinderName: g?.name ?? r.row.grinderName });
	}

	/** Starring one unstars the others; the starred one toggled off leaves none. */
	function toggleStar(r: Row): void {
		const on = !r.row.starred;
		onChange(grinds.map((x) => ({ ...x, starred: on && x.grinderId === r.row.grinderId })));
	}

	function remove(r: Row): void {
		onChange(grinds.filter((x) => x.grinderId !== r.row.grinderId));
	}

	/**
	 * ± moves by the scale's step. It never moves a value further out of the
	 * scale, and stops at its ends; a value already outside moves back toward
	 * it. An empty grind starts at the scale's placeholder.
	 */
	function step(r: Row, dir: 1 | -1): void {
		const { min, max, step } = r.scale;
		const n = grindNumber(r.row.grind);
		if (n === null) {
			if (r.row.grind.trim() === '') setGrind(r, grindText(grindPlaceholder(r.scale)));
			return;
		}
		if ((dir > 0 && n >= max) || (dir < 0 && n <= min)) return;
		let next = Number((n + dir * step).toFixed(4));
		if (n >= min && n <= max) next = Math.min(max, Math.max(min, next));
		setGrind(r, grindText(next));
	}

	/** Steppers can't move a typed value that isn't a number ("6 + a tooth"). */
	function canStep(r: Row): boolean {
		return r.row.grind.trim() === '' || grindNumber(r.row.grind) !== null;
	}

	// ── Tap the value to type it ──────────────────────────────────────────
	let editingId = $state<string | null>(null);
	let draft = $state('');

	function beginEdit(r: Row): void {
		draft = r.row.grind;
		editingId = r.row.grinderId;
	}

	function focusSelect(el: HTMLInputElement): void {
		el.focus();
		el.select();
	}

	function commitEdit(r: Row): void {
		if (editingId !== r.row.grinderId) return;
		editingId = null;
		setGrind(r, draft.trim());
	}

	function onEditKey(e: KeyboardEvent, r: Row): void {
		if (e.key === 'Enter') {
			e.preventDefault();
			commitEdit(r);
		} else if (e.key === 'Escape') {
			e.preventDefault();
			editingId = null;
		}
	}

	// ── Add a grinder ─────────────────────────────────────────────────────
	const onBag = $derived(grinds.map((g) => g.grinderId));

	function onPick(choice: GrinderChoice): void {
		const g = library.takeGrinderChoice(choice);
		if (!g || grinds.some((r) => r.grinderId === g.id)) return;
		onChange([...grinds, { grinderId: g.id, grinderName: g.name, grind: '', starred: false }]);
		void seedFromLastShot(g.id, g.name);
	}

	/** A new row's grind starts as the newest shot on this bag with that grinder, else empty. */
	async function seedFromLastShot(grinderId: string, name: string): Promise<void> {
		let found: string | null = null;
		try {
			const key = name.trim().toLowerCase();
			const last = await getHistoryReads().answer(lastGrinds(beanId));
			const hit =
				last.find((l) => l.grinderId === grinderId) ??
				last.find((l) => l.grinderId === undefined && l.grinder.trim().toLowerCase() === key);
			found = hit?.grind?.trim() || null;
		} catch {
			return;
		}
		if (found === null) return;
		// Only into the row as it was added: still there, still empty.
		const row = grinds.find((r) => r.grinderId === grinderId);
		if (!row || row.grind.trim() !== '') return;
		update(grinderId, { grind: found });
	}
</script>

<div class="bgg" role="group" aria-labelledby="{uid}-title">
	<div class="bgg-head">
		<span class="bgg-title" id="{uid}-title">Grind per grinder</span>
		<span class="bgg-note">Saved when you tap Save</span>
	</div>

	{#each rows as r (r.key)}
		{@const subId = `${uid}-${r.key}-sub`}
		<div
			class={['bgg-row', { 'is-starred': r.row.starred && r.live, 'is-gone': !r.live, 'is-invalid': r.needsGrind }]}
		>
			{#if r.starrable}
				<button
					type="button"
					class={['bgg-icon', 'bgg-star', { 'is-on': r.row.starred }]}
					aria-pressed={r.row.starred}
					aria-label="{r.name}: this bag's grinder"
					title={r.row.starred ? "This bag's grinder" : "Make this the bag's grinder"}
					onclick={() => toggleStar(r)}
				>
					<StarIcon weight={r.row.starred ? 'fill' : 'regular'} aria-hidden="true" />
				</button>
			{:else}
				<span class="bgg-icon bgg-spacer" aria-hidden="true"></span>
			{/if}

			<span class="bgg-text">
				<span class="bgg-name">{r.name}</span>
				<span class="bgg-sub" id={subId}>
					{#if !r.live}
						<span>No longer on Devices · kept until you remove it</span>
					{:else}
						{#if r.needsGrind}
							<span class="bgg-err" role="alert">Enter a grind or remove this row</span>
						{:else if r.changed && r.base}
							<span class="bgg-changed">{starPrefix(r)}Changed from {formatGrind(r.base.grind, r.scale)} · saved when you tap Save</span>
						{:else if r.base === null}
							<span class="bgg-changed">{starPrefix(r)}Added · saved when you tap Save</span>
						{:else if r.row.starred}
							<span>This bag's grinder</span>
						{/if}
						{#if r.outOfRange}
							<span class="bgg-warn">Outside this grinder's scale ({rangeText(r.scale)})</span>
						{/if}
					{/if}
				</span>
			</span>

			{#if r.live}
				<div class={['qcs-row', 'bgg-stepper', { 'is-changed': r.changed || r.base === null }]}>
					<button
						type="button"
						class="qcs-btn"
						aria-label="Decrease {r.name} grind"
						disabled={!canStep(r)}
						onclick={() => step(r, -1)}
					>
						<MinusIcon aria-hidden="true" />
					</button>
					<div class="qcs-val">
						{#if editingId === r.row.grinderId}
							<input
								{@attach focusSelect}
								class="qcs-num qcs-num-input bgg-num"
								type="text"
								inputmode="decimal"
								aria-label="{r.name} grind"
								aria-describedby={subId}
								bind:value={draft}
								onblur={() => commitEdit(r)}
								onkeydown={(e) => onEditKey(e, r)}
							/>
						{:else}
							<button
								type="button"
								class={['qcs-num', 'qcs-num-btn', 'bgg-num', { 'is-warn': r.outOfRange, 'is-empty': r.row.grind.trim() === '' }]}
								aria-label="{r.name} grind: {formatGrind(r.row.grind, r.scale)}, edit"
								aria-describedby={subId}
								onclick={() => beginEdit(r)}
							>
								{r.row.grind.trim() === '' ? '—' : r.row.grind.trim()}
							</button>
						{/if}
						{#if r.scale.unit}<span class="qcs-unit">{r.scale.unit}</span>{/if}
					</div>
					<button
						type="button"
						class="qcs-btn"
						aria-label="Increase {r.name} grind"
						disabled={!canStep(r)}
						onclick={() => step(r, 1)}
					>
						<PlusIcon aria-hidden="true" />
					</button>
				</div>
			{:else}
				<div class="bgg-readonly" aria-label="{r.name} grind: {formatGrind(r.row.grind, r.scale)}">
					{formatGrind(r.row.grind, r.scale)}
				</div>
			{/if}

			<button
				type="button"
				class="bgg-icon bgg-remove"
				aria-label="Remove {r.name} from this bag"
				title="Remove from this bag"
				onclick={() => remove(r)}
			>
				<TrashIcon aria-hidden="true" />
			</button>
		</div>
	{/each}

	<div class="bgg-add">
		<GrinderTypeOrPick grinders={library.allGrinders} excludeIds={onBag} {onPick} />
	</div>
</div>

<style>
	.bgg {
		display: flex;
		flex-direction: column;
		gap: 6px;
		min-width: 0;
	}
	.bgg-head {
		display: flex;
		align-items: baseline;
		gap: 12px;
		min-height: 22px;
	}
	.bgg-title {
		flex: 1;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: var(--fg-1);
	}
	.bgg-note {
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--fg-3);
	}
	.bgg-row {
		display: flex;
		align-items: center;
		gap: 10px;
		min-height: 56px;
		box-sizing: border-box;
		padding: 4px 6px;
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-md, 10px);
	}
	.bgg-row.is-starred {
		border-color: rgba(var(--copper-rgb), 0.45);
		background: rgba(var(--copper-rgb), 0.06);
	}
	.bgg-row.is-gone {
		border-style: dashed;
		border-color: rgba(var(--tint-rgb), 0.12);
	}
	.bgg-row.is-invalid {
		border-color: var(--danger);
	}
	.bgg-icon {
		flex: 0 0 44px;
		width: 44px;
		height: 44px;
		display: flex;
		align-items: center;
		justify-content: center;
		border: 0;
		border-radius: var(--radius-sm, 8px);
		background: transparent;
		color: var(--fg-3);
		font-size: 18px;
		padding: 0;
	}
	button.bgg-icon {
		cursor: pointer;
	}
	button.bgg-icon:hover {
		background: rgba(var(--tint-rgb), 0.06);
		color: var(--fg-1);
	}
	.bgg-star.is-on {
		color: var(--fg-accent);
	}
	.bgg-text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.bgg-name {
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 500;
		color: var(--fg-1);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.is-gone .bgg-name {
		font-weight: 400;
		color: var(--fg-3);
	}
	.bgg-sub {
		display: flex;
		flex-direction: column;
		gap: 1px;
		font-family: var(--font-sans);
		font-size: 11px;
		line-height: 1.4;
		color: var(--fg-3);
	}
	.bgg-changed {
		color: var(--fg-accent);
	}
	.bgg-warn {
		color: var(--warning);
	}
	.bgg-err {
		color: var(--danger);
	}
	.bgg-stepper {
		flex: 0 0 220px;
		min-height: 44px;
		box-sizing: border-box;
	}
	.bgg-stepper.is-changed {
		box-shadow: inset 0 0 0 1px rgba(var(--copper-rgb), 0.6);
	}
	.bgg-stepper .qcs-btn {
		/* 44 px touch targets (the row's own 36 px buttons sit in 4 px padding). */
		min-height: 36px;
	}
	.bgg-stepper .qcs-btn:disabled {
		opacity: 0.4;
		cursor: default;
	}
	.bgg-stepper .qcs-val {
		align-items: center;
		min-width: 0;
	}
	.bgg-num {
		font-size: 18px;
		min-height: 36px;
		min-width: 44px;
	}
	.bgg-num.is-warn {
		color: var(--warning);
	}
	.bgg-num.is-empty {
		color: var(--fg-3);
	}
	.bgg-readonly {
		flex: 0 0 220px;
		text-align: center;
		font-family: var(--font-mono);
		font-size: 16px;
		color: var(--fg-3);
	}
	.bgg-add {
		margin-top: 8px;
	}
	@media (max-width: 680px) {
		.bgg-row {
			flex-wrap: wrap;
			row-gap: 6px;
		}
		.bgg-text {
			flex: 1 1 calc(100% - 120px);
		}
		.bgg-stepper,
		.bgg-readonly {
			order: 4;
			flex: 1 1 100%;
		}
	}
</style>
