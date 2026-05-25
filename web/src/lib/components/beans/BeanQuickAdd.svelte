<script lang="ts">
	/**
	 * `BeanQuickAdd` — the 10-second new-bag popover. Mounts as a modal
	 * over the library; the user types Name + Roaster + Roasted-on +
	 * Bag size, and on Save the store gets a `quickAdd(name, roaster,
	 * roastedOn)` call. The new bean is also flipped to **active** so
	 * the brew page picks it up immediately.
	 *
	 * For everything else the design's full editor (`/beans/[id]/edit`)
	 * is one click away via the `Open full editor` ghost button.
	 */
	import { tick } from 'svelte';
	import {
		getBeanStore,
		daysOffRoast,
		type Bean,
		type Roaster
	} from '$lib/bean';
	import QStepper from '$lib/components/brew/QStepper.svelte';
	import RoasterAutocomplete from './RoasterAutocomplete.svelte';

	let {
		onClose,
		onOpenFull,
		onCreated
	}: {
		onClose: () => void;
		/** User picked "Open full editor" — parent navigates to the editor route. */
		onOpenFull: (draft: Partial<Bean>) => void;
		/** Successful Save fired with the persisted bean (already activated). */
		onCreated?: (bean: Bean) => void;
	} = $props();

	const library = getBeanStore();

	// ── Form state ────────────────────────────────────────────────────
	let name = $state('');
	let roasterName = $state('');
	let resolvedRoaster = $state<Roaster | null>(null);
	let roastedOn = $state<string>(new Date().toISOString().slice(0, 10));
	let bagSizeG = $state<number>(250);
	let attempted = $state(false);
	let nameInputEl = $state<HTMLInputElement | null>(null);

	const isNameMissing = $derived(!name.trim());
	const isRoasterMissing = $derived(!roasterName.trim());
	const roastedOnHint = $derived.by<string>(() => {
		const d = daysOffRoast(roastedOn);
		if (d == null) return '';
		if (d === 0) return 'today';
		if (d === 1) return '1d ago';
		return `${d}d ago`;
	});

	const BAG_PRESETS = [200, 250, 340, 454] as const;

	async function attemptSave(activate: boolean): Promise<void> {
		attempted = true;
		await tick();
		if (isNameMissing) {
			nameInputEl?.focus();
			return;
		}
		if (isRoasterMissing) return;
		// `quickAdd` resolves/creates the roaster, persists the bean,
		// activates it. We then patch a few extra fields the design's
		// quick-add captures that `quickAdd` doesn't take as args.
		const created = library.quickAdd(name.trim(), roasterName.trim(), roastedOn);
		library.updateBean(created.id, {
			bagSizeG,
			remainingG: bagSizeG
		});
		// Optionally clear the active pointer if the user opted out of
		// "make active". The default is to make active; the secondary
		// ghost option doesn't activate.
		if (!activate) {
			// The active id is set on quickAdd — only flip it off when
			// the user explicitly chose not to activate. (We don't expose
			// that path in the v1 UI but the indirection is here for
			// future polish.)
			library.setActiveBean(null);
		}
		const persisted = library.getBean(created.id);
		if (persisted) onCreated?.(persisted);
		onClose();
	}

	function gotoFullEditor(): void {
		onOpenFull({
			name: name.trim(),
			roasterId: resolvedRoaster?.id ?? null,
			roastedOn,
			bagSizeG,
			remainingG: bagSizeG
		});
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
	class="bn-qadd-scrim"
	onclick={onClose}
	onkeydown={onKey}
	role="button"
	tabindex="-1"
	aria-label="Close quick add"
></div>

<div
	class="bn-qadd"
	role="dialog"
	aria-modal="true"
	aria-labelledby="bn-qadd-title"
	tabindex="-1"
	onkeydown={onKey}
>
	<header class="bn-qadd-head">
		<div>
			<div class="t-eyebrow">Quick add</div>
			<h2 class="bn-qadd-title" id="bn-qadd-title">New bag</h2>
			<div class="bn-qadd-sub">
				Just the essentials. Refine the rest from the bean's drawer.
			</div>
		</div>
		<button class="bn-qadd-x" onclick={onClose} aria-label="Close">
			<i class="ph ph-x" aria-hidden="true"></i>
		</button>
	</header>

	<div class="bn-qadd-body">
		<label class="bn-fld">
			<span class="bn-fld-label">Name <span class="bn-fld-req">*</span></span>
			<input
				bind:this={nameInputEl}
				class="bn-fld-input"
				class:is-invalid={attempted && isNameMissing}
				bind:value={name}
				placeholder="e.g. Geisha Esmeralda Lot 3"
			/>
		</label>

		<div class="bn-fld">
			<span class="bn-fld-label">Roaster <span class="bn-fld-req">*</span></span>
			<RoasterAutocomplete
				value={roasterName}
				resolved={resolvedRoaster}
				roasters={library.roasters}
				placeholder="e.g. Onyx Coffee Lab"
				invalid={attempted && isRoasterMissing}
				onChange={(v) => (roasterName = v)}
				onResolve={(r) => {
					resolvedRoaster = r;
					if (r) roasterName = r.name;
				}}
			/>
		</div>

		<div class="bn-fld bn-fld-row">
			<span class="bn-fld-label">Roasted on</span>
			<div class="bn-fld-date">
				<input class="bn-fld-input" type="date" bind:value={roastedOn} />
				{#if roastedOnHint}
					<span class="bn-fld-date-hint">{roastedOnHint}</span>
				{/if}
			</div>
		</div>

		<div class="bn-fld bn-fld-row">
			<span class="bn-fld-label">Bag size</span>
			<div class="bn-fld-bag">
				<QStepper
					label=""
					value={bagSizeG}
					unit="g"
					min={0}
					max={5000}
					step={50}
					onChange={(n) => (bagSizeG = n)}
				/>
				<div class="bn-fld-bag-presets">
					{#each BAG_PRESETS as g (g)}
						<button
							type="button"
							class="bn-preset"
							class:is-on={bagSizeG === g}
							onclick={() => (bagSizeG = g)}
						>
							{g}<em>g</em>
						</button>
					{/each}
				</div>
			</div>
		</div>

		<div class="bn-qadd-hint">
			<i class="ph ph-info" aria-hidden="true"></i>
			<span>
				Tip — open the full editor to add origin, processing, tasting notes
				and grinder setting.
			</span>
		</div>
	</div>

	<footer class="bn-qadd-foot">
		<button class="bn-btn bn-btn-ghost" onclick={gotoFullEditor}>
			<i class="ph ph-arrow-square-out" aria-hidden="true"></i> Open full editor
		</button>
		<button class="bn-btn bn-btn-primary" onclick={() => attemptSave(true)}>
			<i class="ph ph-coffee" aria-hidden="true"></i>
			Save &amp; make active
		</button>
	</footer>
</div>

<style>
	.bn-qadd-scrim {
		position: fixed;
		inset: 0;
		background: rgba(var(--scrim-rgb, 0, 0, 0), 0.55);
		z-index: 70;
	}
	.bn-qadd {
		position: fixed;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		width: min(520px, calc(100vw - 32px));
		max-height: calc(100vh - 64px);
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: var(--radius-lg);
		z-index: 71;
		display: flex;
		flex-direction: column;
		overflow: hidden;
		box-shadow: var(--shadow-lg);
	}
	.bn-qadd-head {
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		gap: 16px;
		padding: 20px 22px 14px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.bn-qadd-title {
		font-family: var(--font-serif);
		font-size: 22px;
		font-weight: 500;
		margin: 4px 0 4px;
		color: var(--fg-1);
		letter-spacing: -0.01em;
	}
	.bn-qadd-sub {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
		max-width: 360px;
	}
	.bn-qadd-x {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		font-size: 16px;
		padding: 4px;
		cursor: pointer;
		border-radius: var(--radius-sm);
	}
	.bn-qadd-x:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}
	.bn-qadd-body {
		padding: 20px 22px 18px;
		display: flex;
		flex-direction: column;
		gap: 14px;
		overflow-y: auto;
	}
	.bn-fld {
		display: flex;
		flex-direction: column;
		gap: 5px;
	}
	.bn-fld-row {
		flex-direction: row;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
	}
	.bn-fld-row .bn-fld-label {
		flex: 0 0 auto;
	}
	.bn-fld-label {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.bn-fld-req {
		color: var(--copper-400);
	}
	.bn-fld-input {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 8px 10px;
		outline: 0;
		color-scheme: dark;
		width: 100%;
		box-sizing: border-box;
	}
	.bn-fld-input:focus {
		border-color: var(--copper-400);
	}
	.bn-fld-input.is-invalid {
		border-color: var(--danger);
	}
	.bn-fld-date {
		display: inline-flex;
		align-items: center;
		gap: 8px;
	}
	.bn-fld-date input {
		width: 160px;
	}
	.bn-fld-date-hint {
		font-family: var(--font-mono);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.bn-fld-bag {
		display: flex;
		flex-direction: column;
		gap: 8px;
		/* Stretch so the stepper and the preset row share one column
		   width — they visually align as one block. (Was `flex-end`,
		   which left the stepper at its intrinsic width and made the
		   preset row look orphaned underneath.) */
		align-items: stretch;
		/* A modest min-width keeps the preset row from collapsing into
		   two lines on narrow viewports. */
		min-width: 260px;
	}
	.bn-fld-bag-presets {
		display: flex;
		justify-content: space-between;
		gap: 4px;
		width: 100%;
	}
	.bn-fld-bag-presets .bn-preset {
		flex: 1;
		text-align: center;
	}
	/* Keep the stepper at the column width on click — the inline
	   number editor inherits `width: 100%` from the QStepper's
	   `.qcs-num-input`, which inside a `flex: 1` `.qcs-val` cell does
	   not change the row width. Explicit here so a future tweak to
	   the stepper internals can't widen the row on focus. */
	.bn-fld-bag :global(.qcs) {
		width: 100%;
	}
	.bn-fld-bag :global(.qcs-row) {
		width: 100%;
	}
	.bn-preset {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 10px;
		padding: 3px 8px;
		border-radius: var(--radius-pill);
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.bn-preset em {
		font-style: normal;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.bn-preset:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.bn-preset.is-on {
		background: rgba(var(--copper-rgb), 0.16);
		border-color: var(--copper-400);
		color: var(--copper-300);
	}
	.bn-preset.is-on em {
		color: var(--copper-400);
	}
	.bn-qadd-hint {
		display: inline-flex;
		align-items: flex-start;
		gap: 8px;
		font-family: var(--font-sans);
		font-size: 11.5px;
		color: rgba(var(--tint-rgb), 0.6);
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		padding: 8px 10px;
		border-radius: var(--radius-sm);
		line-height: 1.4;
	}
	.bn-qadd-hint i {
		font-size: 13px;
		color: var(--copper-400);
		margin-top: 1px;
	}
	.bn-qadd-foot {
		display: flex;
		gap: 8px;
		justify-content: space-between;
		padding: 14px 22px 18px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.bn-btn {
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
	.bn-btn-ghost {
		background: rgba(var(--tint-rgb), 0.04);
		border-color: rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
	}
	.bn-btn-ghost:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.bn-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.bn-btn-primary:hover {
		background: var(--copper-600);
	}
</style>
