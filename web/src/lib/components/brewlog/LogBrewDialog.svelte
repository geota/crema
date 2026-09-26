<script lang="ts">
	/**
	 * `LogBrewDialog` — the Brew Log entry form (issue #10). A centered
	 * modal in the `BeanQuickAdd` family: method chips first (they
	 * re-template the form), the active bag pre-selected with its
	 * remaining grams, mono numerals, a live ratio readout, and the
	 * journal fields collapsed behind one disclosure.
	 *
	 * Saving builds a telemetry-less `StoredShot` via
	 * `HistoryStore.addManualBrew` and debits the bean exactly like a
	 * live shot (`debitBean` → bag-empty prompt). A guided session's
	 * summary arrives through `prefill` with the weight series attached.
	 */
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
	import { getBeanStore, type Bean, type Roaster } from '$lib/bean';
	import {
		BREW_METHOD_PRESETS,
		INLINE_PRESET_COUNT,
		isEspressoMethod,
		lastUsedMethod,
		methodLabel,
		normalizeMethod,
		presetFor,
		rememberMethod,
		type LogBrewPrefill
	} from '$lib/brew/methods';
	import QuickStepper from '$lib/components/brew/QuickStepper.svelte';
	import BeanPicker from '$lib/components/history/BeanPicker.svelte';
	import StarRating from '$lib/components/common/StarRating.svelte';
	import { toast } from '$lib/components/shared/toast.svelte';
	import { promptBagEmpty } from '$lib/bean/bag-empty-prompt';
	import { getHistoryStore } from '$lib/history/store.svelte';
	import { methodOf, snapshotFromBean, type StoredShot } from '$lib/history/model';
	import { formatRatio } from '$lib/utils/ratio';
	import MethodMark from './MethodMark.svelte';

	let {
		onClose,
		onSaved,
		prefill
	}: {
		onClose: () => void;
		/** Successful save fired with the persisted row. */
		onSaved?: (shot: StoredShot) => void;
		/** Seed values — "Log again", bean-drawer entry, guided summary. */
		prefill?: LogBrewPrefill;
	} = $props();

	const library = getBeanStore();
	const history = getHistoryStore();

	// The dialog is created fresh per open (the host `{#if}`s it), so the
	// prefill is genuinely a one-shot seed — capturing its initial value
	// is the intent, not a reactivity bug.
	// svelte-ignore state_referenced_locally
	const seed = prefill;

	/** The newest brew matching `method` — same bag first, then any. */
	function lastBrewOf(method: string, beanId: string | null): StoredShot | undefined {
		const matches = (s: StoredShot) => (methodOf(s) ?? 'espresso') === method;
		if (beanId) {
			const scoped = history.all.find((s) => matches(s) && s.bean?.beanId === beanId);
			if (scoped) return scoped;
		}
		return history.all.find(matches);
	}

	// ── Form state ────────────────────────────────────────────────────
	let method = $state(seed?.method ?? lastUsedMethod());
	let customMethod = $state('');
	let showAllMethods = $state(
		seed?.method != null && !BREW_METHOD_PRESETS.slice(0, INLINE_PRESET_COUNT).some((p) => p.id === seed.method)
	);
	let beanId = $state<string | null>(seed?.beanId ?? library.activeBeanId);
	let pickingBean = $state(false);
	let dose = $state(0);
	let water = $state(0); // water-in for filter methods, yield-out for espresso
	let grind = $state<number | null>(null);
	let temp = $state<number | null>(null);
	let timeStr = $state('');
	let whenLocal = $state(toLocalInput(seed?.completedAt ?? Date.now()));
	let rating = $state(seed?.rating ?? 0);
	let notes = $state(seed?.notes ?? '');
	let nextPlan = $state('');
	let journalOpen = $state(!!(seed?.rating || seed?.notes));
	let attempted = $state(false);
	/** Fields the user touched this session — method changes don't re-seed them. */
	const dirty = new Set<string>();

	const isCustom = $derived(method === 'other');
	const espresso = $derived(isEspressoMethod(isCustom ? customMethod : method));
	const bean = $derived(beanId ? library.getBean(beanId) : null);
	const roaster = $derived.by<Roaster | null>(() => {
		const rid = bean?.roasterId;
		return rid ? library.getRoaster(rid) : null;
	});
	const doseMissing = $derived(bean != null && !(dose > 0));
	const overdraws = $derived(
		bean != null && dose > 0 && bean.remaining > 0 && dose > bean.remaining + 0.05
	);
	const ratio = $derived(formatRatio(dose > 0 ? dose : null, water > 0 ? water : null));

	// Seed the numeric fields for the method at open (later re-seeds run
	// from the chip handler with the freshly-picked id).
	// svelte-ignore state_referenced_locally
	seedFor(method);

	function seedFor(m: string): void {
		const id = m === 'other' ? '' : m;
		const last = id ? lastBrewOf(id, beanId) : undefined;
		const preset = presetFor(id);
		const esp = isEspressoMethod(id);
		if (!dirty.has('dose')) {
			dose = seed?.dose ?? last?.metadata.dose ?? preset?.seedDose ?? 15;
		}
		if (!dirty.has('water')) {
			const lastWater = esp ? last?.metadata.yieldOut : (last?.metadata.waterG ?? last?.metadata.yieldOut);
			water =
				(esp ? seed?.yieldOut : seed?.waterG) ??
				lastWater ??
				(esp ? (preset?.seedYield ?? 36) : (preset?.seedWater ?? 250));
		}
		if (!dirty.has('grind')) {
			const lastGrind = last?.metadata.grinderSetting ?? bean?.grinderSetting;
			const fromPrefill = seed?.grinderSetting;
			const raw = fromPrefill ?? lastGrind;
			const parsed = raw != null ? Number.parseFloat(raw) : NaN;
			grind = Number.isFinite(parsed) ? parsed : null;
		}
		if (!dirty.has('temp')) {
			temp = seed?.tempC ?? last?.brewTempTarget ?? preset?.seedTemp ?? null;
		}
		if (!dirty.has('time')) {
			const ms = seed?.durationMs ?? last?.record.duration ?? 0;
			timeStr = ms > 0 ? formatDuration(ms) : '';
		}
	}

	function pickMethod(id: string): void {
		method = id;
		seedFor(id);
	}

	function toLocalInput(ms: number): string {
		const d = new Date(ms);
		const pad = (n: number) => String(n).padStart(2, '0');
		return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
	}

	function formatDuration(ms: number): string {
		const total = Math.round(ms / 1000);
		const m = Math.floor(total / 60);
		const s = total % 60;
		return `${m}:${String(s).padStart(2, '0')}`;
	}

	/** Parse "m:ss" or bare seconds into ms; `null` for empty/garbage. */
	function parseDuration(raw: string): number | null {
		const t = raw.trim();
		if (!t) return null;
		const m = /^(\d+):([0-5]?\d)$/.exec(t);
		if (m) return (Number(m[1]) * 60 + Number(m[2])) * 1000;
		const secs = Number(t);
		return Number.isFinite(secs) && secs > 0 ? Math.round(secs * 1000) : null;
	}

	function save(): void {
		attempted = true;
		const storedMethod = isCustom ? normalizeMethod(customMethod) : method;
		if (!storedMethod) return;
		if (doseMissing) return;
		const completedAt = new Date(whenLocal).getTime() || Date.now();
		const record = history.addManualBrew({
			method: storedMethod,
			completedAt,
			bean: snapshotFromBean(bean, roaster),
			dose: dose > 0 ? dose : null,
			waterG: !espresso && water > 0 ? water : null,
			yieldOut: espresso && water > 0 ? water : (seed?.yieldOut ?? null),
			grinderSetting:
				grind != null && grind > 0
					? Number.isInteger(grind)
						? String(grind)
						: grind.toFixed(1)
					: null,
			brewTempC: temp != null && temp > 0 ? temp : null,
			durationMs: parseDuration(timeStr),
			rating: rating > 0 ? rating : null,
			notes: notes.trim() ? notes.trim() : null,
			nextPlan: nextPlan.trim() ? nextPlan.trim() : null,
			recipeName: seed?.recipeName ?? null,
			brewSeries: seed?.brewSeries ?? null
		});
		if (bean && dose > 0) {
			const emptied = library.debitBean(bean.id, dose);
			if (emptied) void promptBagEmpty(bean.id);
		}
		rememberMethod(storedMethod);
		toast.success('Brew logged');
		onSaved?.(record);
		onClose();
	}

	function onKey(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			onClose();
		}
	}
</script>

<div
	class="bl-scrim"
	onclick={onClose}
	onkeydown={onKey}
	role="button"
	tabindex="-1"
	aria-label="Close log brew"
></div>

<div
	class="bl-dialog"
	role="dialog"
	aria-modal="true"
	aria-labelledby="bl-title"
	tabindex="-1"
	onkeydown={onKey}
>
	<header class="bl-head">
		<div>
			<div class="t-eyebrow">Journal</div>
			<h2 class="bl-title" id="bl-title">Log a brew</h2>
		</div>
		<button class="bl-x" onclick={onClose} aria-label="Close">
			<XIcon aria-hidden="true" />
		</button>
	</header>

	<div class="bl-body">
		<div class="bl-chips" role="radiogroup" aria-label="Brew method">
			{#each BREW_METHOD_PRESETS.slice(0, showAllMethods ? undefined : INLINE_PRESET_COUNT) as p (p.id)}
				<button
					type="button"
					class="bl-chip"
					class:is-on={method === p.id}
					role="radio"
					aria-checked={method === p.id}
					onclick={() => pickMethod(p.id)}
				>
					<MethodMark method={p.id} size={13} />
					{p.label}
				</button>
			{/each}
			{#if showAllMethods}
				<button
					type="button"
					class="bl-chip"
					class:is-on={isCustom}
					role="radio"
					aria-checked={isCustom}
					onclick={() => pickMethod('other')}
				>
					Other…
				</button>
			{:else}
				<button
					type="button"
					class="bl-chip bl-chip-more"
					onclick={() => (showAllMethods = true)}
				>
					More ▾
				</button>
			{/if}
		</div>
		{#if isCustom}
			<input
				class="bl-input"
				bind:value={customMethod}
				placeholder="e.g. Karlsbad Kanne"
				class:is-invalid={attempted && !normalizeMethod(customMethod)}
				aria-label="Custom method name"
			/>
		{/if}

		<button type="button" class="bl-bean" onclick={() => (pickingBean = true)}>
			<div class="bl-bean-main">
				<span class="bl-label">Bean</span>
				{#if bean}
					<span class="bl-bean-name">
						{roaster ? `${roaster.name} · ` : ''}{bean.name}
					</span>
				{:else}
					<span class="bl-bean-name bl-bean-none">No bean — inventory untouched</span>
				{/if}
			</div>
			<span class="bl-bean-side">
				{#if bean}
					<span class="bl-bean-left">{Math.max(0, Math.round(bean.remaining))} g left</span>
				{/if}
				<span aria-hidden="true">▾</span>
			</span>
		</button>

		<div class="bl-grid">
			<div class="bl-cell" class:is-invalid={attempted && doseMissing}>
				<span class="bl-label">Dose</span>
				<QuickStepper
					label=""
					value={dose}
					unit="g"
					min={0}
					max={200}
					step={0.5}
					onChange={(n) => {
						dirty.add('dose');
						dose = n;
					}}
				/>
			</div>
			<div class="bl-cell">
				<span class="bl-label">{espresso ? 'Yield' : 'Water'}</span>
				<QuickStepper
					label=""
					value={water}
					unit="g"
					min={0}
					max={2000}
					step={espresso ? 1 : 10}
					onChange={(n) => {
						dirty.add('water');
						water = n;
					}}
				/>
			</div>
			<div class="bl-cell">
				<span class="bl-label">Grind</span>
				<QuickStepper
					label=""
					value={grind ?? 0}
					unit=""
					min={0}
					max={200}
					step={0.1}
					onChange={(n) => {
						dirty.add('grind');
						grind = n > 0 ? n : null;
					}}
				/>
			</div>
			<div class="bl-cell">
				<span class="bl-label">Temp</span>
				<QuickStepper
					label=""
					value={temp ?? 0}
					unit="°C"
					min={0}
					max={100}
					step={1}
					onChange={(n) => {
						dirty.add('temp');
						temp = n > 0 ? n : null;
					}}
				/>
			</div>
			<label class="bl-cell">
				<span class="bl-label">Brew time</span>
				<input
					class="bl-input bl-input-mono"
					bind:value={timeStr}
					oninput={() => dirty.add('time')}
					placeholder="m:ss"
				/>
			</label>
			<label class="bl-cell">
				<span class="bl-label">When</span>
				<input class="bl-input" type="datetime-local" bind:value={whenLocal} />
			</label>
		</div>

		{#if overdraws}
			<div class="bl-warn">
				More than the {Math.max(0, Math.round(bean?.remaining ?? 0))} g left in this bag —
				saving floors the bag at zero.
			</div>
		{/if}

		<div class="bl-journal-row">
			<button
				type="button"
				class="bl-journal-toggle"
				aria-expanded={journalOpen}
				onclick={() => (journalOpen = !journalOpen)}
			>
				{journalOpen ? '▾' : '▸'} Rating, notes &amp; next time
			</button>
			<span class="bl-ratio">ratio {ratio}</span>
		</div>

		{#if journalOpen}
			<div class="bl-journal">
				<div class="bl-cell">
					<span class="bl-label">Rating</span>
					<StarRating {rating} interactive onRate={(r) => (rating = r)} />
				</div>
				<label class="bl-cell">
					<span class="bl-label">Tasting notes</span>
					<textarea class="bl-input bl-area" bind:value={notes} rows="2"></textarea>
				</label>
				<label class="bl-cell">
					<span class="bl-label">Next time</span>
					<textarea
						class="bl-input bl-area"
						bind:value={nextPlan}
						rows="2"
						placeholder="e.g. grind 1 finer, bloom 45 s"
					></textarea>
				</label>
			</div>
		{/if}
	</div>

	<footer class="bl-foot">
		<button class="bl-btn bl-btn-ghost" onclick={onClose}>Cancel</button>
		<button class="bl-btn bl-btn-primary" onclick={save}>
			<CheckIcon aria-hidden="true" /> Save brew
		</button>
	</footer>
</div>

{#if pickingBean}
	<BeanPicker
		currentBeanId={beanId}
		onPick={(b: Bean) => {
			beanId = b.id;
			pickingBean = false;
		}}
		onClear={() => {
			beanId = null;
			pickingBean = false;
		}}
		onClose={() => (pickingBean = false)}
	/>
{/if}

<style>
	.bl-scrim {
		position: fixed;
		inset: 0;
		background: rgba(var(--scrim-rgb, 0, 0, 0), 0.55);
		z-index: 70;
	}
	.bl-dialog {
		position: fixed;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		/* The BeanQuickAdd family: max 520px, the body scrolls inside, the
		   header + Save footer never leave the dialog (issue #10). */
		width: min(520px, calc(100vw - 32px));
		max-height: calc(100dvh - 64px);
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: var(--radius-lg);
		z-index: 71;
		display: flex;
		flex-direction: column;
		overflow: hidden;
		box-shadow: var(--shadow-lg);
	}
	.bl-head {
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		gap: 16px;
		padding: 20px 22px 12px;
	}
	.bl-title {
		font-family: var(--font-serif);
		font-size: 22px;
		font-weight: 500;
		margin: 4px 0 0;
		color: var(--fg-1);
		letter-spacing: -0.01em;
	}
	.bl-x {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		font-size: 16px;
		padding: 4px;
		cursor: pointer;
		border-radius: var(--radius-sm);
	}
	.bl-x:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}
	.bl-head {
		flex-shrink: 0;
	}
	.bl-body {
		padding: 4px 22px 16px;
		display: flex;
		flex-direction: column;
		gap: 12px;
		flex: 1 1 auto;
		min-height: 0;
		overflow-y: auto;
		overscroll-behavior: contain;
	}
	.bl-chips {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.bl-chip {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		border-radius: var(--radius-pill);
		padding: 6px 12px;
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		background: transparent;
		color: var(--fg-2, var(--fg-1));
		font-family: var(--font-sans);
		font-size: 12.5px;
		font-weight: 500;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.bl-chip:hover {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.bl-chip.is-on {
		background: var(--copper-500);
		border-color: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.bl-chip-more {
		color: rgba(var(--tint-rgb), 0.6);
	}
	.bl-bean {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		padding: 8px 12px;
		cursor: pointer;
		text-align: left;
		color: var(--fg-1);
	}
	.bl-bean:hover {
		border-color: var(--copper-400);
	}
	.bl-bean-main {
		display: flex;
		flex-direction: column;
		gap: 3px;
		min-width: 0;
	}
	.bl-bean-name {
		font-family: var(--font-sans);
		font-size: 13.5px;
		font-weight: 600;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.bl-bean-none {
		font-weight: 400;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.bl-bean-side {
		display: flex;
		align-items: center;
		gap: 8px;
		color: rgba(var(--tint-rgb), 0.55);
		font-size: 12px;
		flex: none;
	}
	.bl-bean-left {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 11.5px;
	}
	.bl-grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 10px;
	}
	.bl-cell {
		display: flex;
		flex-direction: column;
		gap: 5px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-sm);
		padding: 8px 10px;
		min-width: 0;
	}
	.bl-cell.is-invalid {
		border-color: var(--danger);
	}
	.bl-cell :global(.qcs) {
		width: 100%;
	}
	.bl-label {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.bl-input {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 8px 10px;
		outline: 0;
		width: 100%;
		box-sizing: border-box;
	}
	.bl-input:focus {
		border-color: var(--copper-400);
	}
	.bl-input.is-invalid {
		border-color: var(--danger);
	}
	.bl-input-mono {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
	}
	.bl-area {
		resize: vertical;
		min-height: 40px;
	}
	.bl-warn {
		font-family: var(--font-sans);
		font-size: 11.5px;
		color: var(--warning);
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-sm);
		padding: 7px 10px;
		line-height: 1.4;
	}
	.bl-journal-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
	}
	.bl-journal-toggle {
		background: transparent;
		border: 0;
		padding: 2px 0;
		color: rgba(var(--tint-rgb), 0.6);
		font-family: var(--font-sans);
		font-size: 12.5px;
		cursor: pointer;
	}
	.bl-journal-toggle:hover {
		color: var(--fg-1);
	}
	.bl-ratio {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 12px;
		color: var(--copper-400);
	}
	.bl-journal {
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.bl-foot {
		flex-shrink: 0;
		display: flex;
		gap: 8px;
		justify-content: flex-end;
		padding: 12px 22px 18px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.08);
		background: var(--bg-page);
	}
	/* Short viewports (a laptop with the dock up, a landscape tablet): use the
	   height, tighten the chrome, and pin Save as a sticky footer over the
	   scrolling body. */
	@media (max-height: 700px) {
		.bl-dialog {
			max-height: calc(100dvh - 24px);
		}
		.bl-head {
			padding: 12px 22px 8px;
		}
		.bl-foot {
			position: sticky;
			bottom: 0;
			padding: 10px 22px 12px;
		}
	}
	.bl-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 9px 16px;
		border-radius: var(--radius-pill);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		border: 1px solid transparent;
		transition: all var(--dur-1) var(--ease);
	}
	.bl-btn-ghost {
		background: rgba(var(--tint-rgb), 0.04);
		border-color: rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
	}
	.bl-btn-ghost:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.bl-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.bl-btn-primary:hover {
		background: var(--copper-600);
	}
</style>
