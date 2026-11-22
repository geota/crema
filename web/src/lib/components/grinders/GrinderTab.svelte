<script lang="ts">
	/**
	 * `GrinderTab` — Devices → Grinder (#488, boards WideDevicesGrinder,
	 * WideDevicesAutoSave, PhoneDevicesGrinder).
	 *
	 * The owned grinders (the active one badged "In use"), "+ Add grinder"
	 * through the shared type-or-pick box, and for the selected grinder: its
	 * grind scale (Lowest / Highest / Step / Unit, saved on change through the
	 * core's merge, a bad scale refused inline), Reset to preset, a preview of
	 * a value as Brew shows it, the grind each bag saved for it, Rename,
	 * Change preset and Remove (which archives it: bags and shots keep it).
	 * The archived grinders are listed with Restore, and the per-device
	 * "Save each shot's grind to its bag" setting sits under the list.
	 *
	 * Selecting a grinder here shows its detail; it does not change the
	 * grinder Brew uses (that is Brew's picker).
	 */
	import type { Snippet } from 'svelte';
	import PlusIcon from 'phosphor-svelte/lib/PlusIcon';
	import StarIcon from 'phosphor-svelte/lib/StarIcon';
	import { getBeanStore } from '#lib/bean/store.svelte.js';
	import { formatGrind, grindStartFromBlank, grindText, stepText as scaleStepText, type GrinderChoice } from '#lib/bean/grinder.js';
	import { getSettingsStore } from '#lib/settings/index.js';
	import { phoneLayout } from '#lib/shell/phone.svelte.js';
	import GrinderTypeOrPick from './GrinderTypeOrPick.svelte';
	import {
		parseScaleDraft,
		presetChoices,
		presetLabel,
		presetOf,
		presetScaleOf,
		rangeNotSet,
		sameScale,
		savedOnBags,
		scaleSummary,
		stepFieldText
	} from './grinder-devices.js';

	let { tabs }: { /** The Devices tabs, drawn under the header. */ tabs?: Snippet } = $props();

	const lib = getBeanStore();
	const settings = getSettingsStore();
	const uid = $props.id();

	const owned = $derived(lib.grinders);
	const archived = $derived(lib.archivedGrinders);
	const activeId = $derived(lib.activeGrinder?.id ?? null);

	/** The grinder whose detail is shown: the picked one, else the active one, else the first. */
	let pickedId = $state<string | null>(null);
	const selected = $derived(
		owned.find((g) => g.id === pickedId) ?? owned.find((g) => g.id === activeId) ?? owned[0] ?? null
	);

	// The scale fields: the selected grinder's stored scale, until typed over.
	// A save (or another grinder) brings them back to what is stored.
	let minText = $derived(selected ? String(selected.scale.min) : '');
	let maxText = $derived(selected ? String(selected.scale.max) : '');
	let stepText = $derived(selected ? stepFieldText(selected.scale) : '');
	let unitText = $derived(selected ? selected.scale.unit : '');
	let scaleError = $derived.by<string | null>(() => {
		void selected?.id;
		return null;
	});

	const preset = $derived(selected ? presetOf(selected) : null);
	const presetScale = $derived(selected ? presetScaleOf(selected) : null);
	const atPreset = $derived(!!selected && !!presetScale && sameScale(selected.scale, presetScale));
	const notSet = $derived(!!selected && rangeNotSet(selected));
	const preview = $derived(
		selected ? formatGrind(grindText(grindStartFromBlank(selected.scale), selected.scale), selected.scale) : ''
	);
	const bags = $derived(selected ? savedOnBags(lib.beans, selected.id) : []);
	const autoSave = $derived(settings.current.grindAutoSave === true);

	let adding = $state(false);
	let renaming = $state(false);
	let nameText = $state('');
	let nameError = $state<string | null>(null);
	let choosingPreset = $state(false);
	let presetPick = $state('');

	function closeEditors(): void {
		renaming = false;
		nameError = null;
		choosingPreset = false;
	}

	function pick(id: string): void {
		pickedId = id;
		closeEditors();
	}

	function onAdd(choice: GrinderChoice): void {
		const g = lib.takeGrinderChoice(choice);
		adding = false;
		if (g) pick(g.id);
	}

	/** Save the typed scale, or say why it is refused. */
	function commitScale(): void {
		if (!selected) return;
		const { scale, error } = parseScaleDraft({ min: minText, max: maxText, step: stepText, unit: unitText });
		scaleError = error;
		if (error || sameScale(scale, selected.scale)) return;
		lib.saveGrinderEdit(selected, { ...selected, scale });
	}

	function resetToPreset(): void {
		if (!selected || !presetScale) return;
		scaleError = null;
		lib.saveGrinderEdit(selected, { ...selected, scale: { ...presetScale } });
	}

	function startRename(): void {
		if (!selected) return;
		closeEditors();
		nameText = selected.name;
		renaming = true;
	}

	function saveName(): void {
		if (!selected) return;
		const name = nameText.trim();
		if (!name) {
			nameError = 'Enter a name.';
			return;
		}
		if (name !== selected.name) lib.saveGrinderEdit(selected, { ...selected, name });
		renaming = false;
		nameError = null;
	}

	function startPreset(): void {
		if (!selected) return;
		closeEditors();
		presetPick = selected.presetId ?? '';
		choosingPreset = true;
	}

	function applyPreset(): void {
		if (!selected) return;
		const entry = presetChoices().find((c) => c.id === presetPick);
		if (!entry) return;
		scaleError = null;
		lib.saveGrinderEdit(selected, {
			...selected,
			presetId: entry.id,
			scale: entry.scale ? { ...entry.scale } : selected.scale
		});
		choosingPreset = false;
	}

	function remove(): void {
		if (!selected) return;
		const id = selected.id;
		closeEditors();
		lib.archiveGrinder(id);
		if (pickedId === id) pickedId = null;
	}

	function restore(id: string): void {
		const g = lib.restoreGrinder(id);
		if (g) pick(g.id);
	}
</script>

<div class={['gdv', { 'is-phone': phoneLayout.current }]}>
	{#if !phoneLayout.current}
		<div class="gdv-head">
			<div class="gdv-head-l">
				<span class="t-eyebrow">Devices</span>
				<h1 class="t-page-title gdv-title">{selected?.name ?? 'Grinders'}</h1>
				{#if selected}
					<span class="gdv-head-sub">
						{scaleSummary(selected.scale)}{selected.id === activeId ? ' · in use on Brew' : ''}
					</span>
				{/if}
			</div>
			<button type="button" class="gdv-add" aria-expanded={adding} onclick={() => (adding = !adding)}>
				<PlusIcon aria-hidden="true" />
				<span>Add grinder</span>
			</button>
		</div>
	{/if}

	{@render tabs?.()}

	<div class="gdv-body">
		<section class="gdv-card gdv-list" aria-labelledby="{uid}-list">
			<h2 class="gdv-h2" id="{uid}-list">Your grinders</h2>
			{#if adding}
				<GrinderTypeOrPick
					grinders={lib.allGrinders}
					excludeIds={owned.map((g) => g.id)}
					autofocus
					onPick={onAdd}
				/>
			{/if}
			{#each owned as g (g.id)}
				<button
					type="button"
					class={['gdv-item', { 'is-on': g.id === selected?.id }]}
					aria-pressed={g.id === selected?.id}
					onclick={() => pick(g.id)}
				>
					<span class="gdv-item-text">
						<span class="gdv-item-name">{g.name}</span>
						<span class="gdv-item-sub">{scaleSummary(g.scale)}</span>
					</span>
					{#if g.id === activeId}<span class="gdv-badge">In use</span>{/if}
				</button>
			{:else}
				<p class="gdv-note">No grinders yet. Add the one you use, and Brew shows its scale.</p>
			{/each}
			{#if phoneLayout.current}
				<button type="button" class="gdv-add-row" aria-expanded={adding} onclick={() => (adding = !adding)}>
					<PlusIcon aria-hidden="true" />
					<span>Add grinder</span>
				</button>
			{/if}

			<div class="gdv-auto">
				<span class="gdv-auto-text">
					<span class="gdv-auto-title" id="{uid}-auto">Save each shot's grind to its bag</span>
					<span class="gdv-auto-help" id="{uid}-auto-help">
						Off: grinds are saved only when you tap Save. On: every kept shot also saves its grind for
						that bag and grinder.
					</span>
				</span>
				<button
					type="button"
					role="switch"
					class="gdv-switch"
					aria-checked={autoSave}
					aria-labelledby="{uid}-auto"
					aria-describedby="{uid}-auto-help"
					onclick={() => settings.set('grindAutoSave', !autoSave)}
				>
					<span class={['qmini-tog', { on: autoSave }]} aria-hidden="true"></span>
				</button>
			</div>

			{#if archived.length > 0}
				<div class="gdv-archived">
					<h3 class="gdv-h3">Archived</h3>
					{#each archived as g (g.id)}
						<div class="gdv-arch-row">
							<span class="gdv-arch-name">{g.name}</span>
							<button
								type="button"
								class="gdv-btn"
								aria-label="Restore {g.name}"
								onclick={() => restore(g.id)}
							>
								Restore
							</button>
						</div>
					{/each}
				</div>
			{/if}
		</section>

		{#if selected}
			<section class="gdv-card gdv-detail" aria-label="{selected.name} settings">
				{#if phoneLayout.current}
					<h2 class="gdv-h2">{selected.name}</h2>
				{/if}
				<div class="gdv-scale-head">
					<h3 class="gdv-h3 gdv-grow">Grind scale</h3>
					{#if preset && presetScale}
						<span class="gdv-muted">
							{atPreset ? presetLabel(preset) : `Changed from the ${preset.name} preset`}
						</span>
						<button type="button" class="gdv-link" onclick={resetToPreset}>Reset to preset</button>
					{/if}
				</div>
				{#if notSet}
					<p class="gdv-warn">Range not set: check your grinder's dial</p>
				{/if}
				<div class="gdv-fields">
					<label class="gdv-field">
						<span>Lowest</span>
						<input
							type="text"
							inputmode="decimal"
							bind:value={minText}
							onchange={commitScale}
							aria-invalid={scaleError ? true : undefined}
							aria-describedby={scaleError ? `${uid}-scale-err` : undefined}
						/>
					</label>
					<label class="gdv-field">
						<span>Highest</span>
						<input
							type="text"
							inputmode="decimal"
							bind:value={maxText}
							onchange={commitScale}
							aria-invalid={scaleError ? true : undefined}
							aria-describedby={scaleError ? `${uid}-scale-err` : undefined}
						/>
					</label>
					<label class="gdv-field">
						<span>Step</span>
						<input
							type="text"
							inputmode="decimal"
							bind:value={stepText}
							onchange={commitScale}
							aria-invalid={scaleError ? true : undefined}
							aria-describedby={scaleError ? `${uid}-scale-err` : undefined}
						/>
					</label>
					<label class="gdv-field">
						<span>Unit</span>
						<input type="text" placeholder="none" bind:value={unitText} onchange={commitScale} />
					</label>
				</div>
				<p class="gdv-error" id="{uid}-scale-err" role="alert">{scaleError ?? ''}</p>

				<div class="gdv-panels">
					<div class="gdv-panel">
						<span class="t-eyebrow">On the Brew screen</span>
						<span class="gdv-preview">{preview}</span>
						<span class="gdv-muted">Steps of {scaleStepText(selected.scale)}</span>
					</div>
					<div class="gdv-panel">
						<span class="t-eyebrow">Saved on bags</span>
						{#each bags as b (b.beanId)}
							<div class="gdv-bag">
								<span class="gdv-bag-name">
									{b.bagName}
									{#if b.starred}<span class="gdv-star" role="img" aria-label="starred"><StarIcon weight="fill" aria-hidden="true" /></span>{/if}
								</span>
								<span class="gdv-mono">{formatGrind(b.grind, selected.scale)}</span>
							</div>
						{:else}
							<span class="gdv-muted">No bag has a grind saved for this grinder yet.</span>
						{/each}
					</div>
				</div>

				{#if renaming}
					<div class="gdv-inline">
						<label class="gdv-field gdv-grow">
							<span>Name</span>
							<input
								type="text"
								bind:value={nameText}
								aria-invalid={nameError ? true : undefined}
								aria-describedby={nameError ? `${uid}-name-err` : undefined}
								onkeydown={(e) => {
									if (e.key === 'Enter') saveName();
									else if (e.key === 'Escape') closeEditors();
								}}
							/>
						</label>
						<button type="button" class="gdv-btn gdv-btn-primary" onclick={saveName}>Save name</button>
						<button type="button" class="gdv-btn" onclick={closeEditors}>Cancel</button>
					</div>
					{#if nameError}<p class="gdv-error" id="{uid}-name-err" role="alert">{nameError}</p>{/if}
				{/if}

				{#if choosingPreset}
					<div class="gdv-inline">
						<label class="gdv-field gdv-grow">
							<span>Preset</span>
							<select bind:value={presetPick}>
								<option value="" disabled>Pick a grinder</option>
								{#each presetChoices() as c (c.id)}
									<option value={c.id}>
										{c.name} · {c.scale ? scaleSummary(c.scale) : 'range not set'}
									</option>
								{/each}
							</select>
						</label>
						<button
							type="button"
							class="gdv-btn gdv-btn-primary"
							disabled={!presetPick}
							onclick={applyPreset}
						>
							Use preset
						</button>
						<button type="button" class="gdv-btn" onclick={closeEditors}>Cancel</button>
					</div>
				{/if}

				<div class="gdv-actions">
					<button type="button" class="gdv-btn" onclick={startRename}>Rename</button>
					<button type="button" class="gdv-btn" onclick={startPreset}>Change preset</button>
					<span class="gdv-grow"></span>
					<button type="button" class="gdv-btn gdv-btn-quiet" onclick={remove}>Remove grinder</button>
				</div>
			</section>
		{/if}
	</div>
</div>

<style>
	.gdv {
		display: flex;
		flex-direction: column;
		gap: 20px;
	}
	.gdv-head {
		display: flex;
		align-items: flex-start;
		gap: 20px;
	}
	.gdv-head-l {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.gdv-head-l .t-eyebrow {
		color: var(--fg-3);
	}
	.gdv-title {
		margin: 0;
	}
	.gdv-head-sub {
		font-size: 14px;
		color: var(--fg-3);
	}
	.gdv-add,
	.gdv-add-row {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 8px;
		min-height: 44px;
		padding: 0 18px;
		border: 0;
		border-radius: var(--radius-pill);
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 600;
		cursor: pointer;
	}
	.gdv-add-row {
		background: transparent;
		color: var(--fg-accent);
		border: 1px dashed rgba(var(--tint-rgb), 0.2);
		border-radius: var(--radius-md, 12px);
	}
	.gdv-body {
		display: flex;
		gap: 24px;
		align-items: flex-start;
	}
	.gdv-card {
		background: var(--bg-surface);
		border: 1px solid var(--hairline);
		border-radius: var(--radius-lg, 16px);
		padding: 22px;
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		gap: 12px;
	}
	.gdv-list {
		width: 360px;
		flex-shrink: 0;
	}
	.gdv-detail {
		flex: 1;
		min-width: 0;
		gap: 16px;
	}
	.gdv-h2 {
		margin: 0 0 4px;
		font-family: var(--font-serif);
		font-weight: 400;
		font-size: 22px;
		color: var(--fg-1);
	}
	.gdv-h3 {
		margin: 0;
		font-family: var(--font-serif);
		font-weight: 400;
		font-size: 18px;
		color: var(--fg-1);
	}
	.gdv-grow {
		flex: 1;
		min-width: 0;
	}
	.gdv-item {
		display: flex;
		align-items: center;
		gap: 10px;
		min-height: 60px;
		padding: 10px 14px;
		border: 1px solid var(--hairline);
		border-radius: var(--radius-md, 12px);
		background: transparent;
		color: var(--fg-1);
		font-family: var(--font-sans);
		text-align: left;
		cursor: pointer;
	}
	.gdv-item.is-on {
		border-color: var(--copper-500);
		background: rgba(193, 124, 79, 0.1);
	}
	.gdv-item-text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 3px;
	}
	.gdv-item-name {
		font-size: 15px;
		font-weight: 500;
	}
	.gdv-item-sub,
	.gdv-muted,
	.gdv-note,
	.gdv-auto-help {
		font-size: 12px;
		line-height: 1.5;
		color: var(--fg-muted);
	}
	.gdv-note {
		margin: 0;
	}
	.gdv-badge {
		font-size: 11px;
		font-weight: 600;
		letter-spacing: 0.08em;
		text-transform: uppercase;
		color: var(--fg-on-accent);
		background: var(--copper-500);
		border-radius: var(--radius-pill);
		padding: 4px 9px;
	}
	.gdv-auto {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-top: 8px;
		padding-top: 14px;
		border-top: 1px solid var(--hairline);
	}
	.gdv-auto-text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.gdv-auto-title {
		font-size: 14px;
		font-weight: 500;
		color: var(--fg-1);
	}
	.gdv-switch {
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-width: 44px;
		min-height: 44px;
		padding: 0;
		border: 0;
		background: transparent;
		cursor: pointer;
	}
	.gdv-switch:focus-visible,
	.gdv-item:focus-visible,
	.gdv-btn:focus-visible,
	.gdv-link:focus-visible,
	.gdv-add:focus-visible,
	.gdv-add-row:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.gdv-archived {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding-top: 14px;
		border-top: 1px solid var(--hairline);
	}
	.gdv-arch-row {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.gdv-arch-name {
		flex: 1;
		min-width: 0;
		font-size: 14px;
		color: var(--fg-3);
	}
	.gdv-scale-head {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 12px;
	}
	.gdv-link {
		min-height: 44px;
		padding: 0 4px;
		border: 0;
		background: transparent;
		color: var(--fg-accent);
		font-family: var(--font-sans);
		font-size: 13px;
		cursor: pointer;
	}
	.gdv-warn {
		margin: 0;
		font-size: 13px;
		color: var(--warning);
	}
	.gdv-fields {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: 14px;
	}
	.gdv-field {
		display: flex;
		flex-direction: column;
		gap: 6px;
		font-size: 11px;
		font-weight: 500;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	.gdv-field input,
	.gdv-field select {
		min-height: 44px;
		box-sizing: border-box;
		border: 1px solid var(--field-border);
		border-radius: var(--radius-sm, 8px);
		background: var(--bg-sunken, var(--bg-page));
		padding: 0 12px;
		color: var(--fg-1);
		font-family: var(--font-mono);
		font-size: 16px;
		letter-spacing: 0;
		text-transform: none;
		width: 100%;
	}
	.gdv-field input:focus,
	.gdv-field select:focus {
		outline: none;
		border-color: var(--copper-400);
	}
	.gdv-field input[aria-invalid='true'] {
		border-color: var(--danger);
	}
	.gdv-error {
		margin: 0;
		min-height: 0;
		font-size: 13px;
		color: var(--danger);
	}
	.gdv-error:empty {
		display: none;
	}
	.gdv-panels {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 16px;
	}
	.gdv-panel {
		border-radius: var(--radius-md, 12px);
		background: var(--bg-sunken, var(--bg-page));
		padding: 16px 18px;
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.gdv-panel .t-eyebrow {
		color: var(--fg-3);
	}
	.gdv-preview {
		font-family: var(--font-mono);
		font-size: 36px;
		color: var(--fg-1);
	}
	.gdv-bag {
		display: flex;
		justify-content: space-between;
		gap: 12px;
		font-size: 14px;
		color: var(--fg-1);
	}
	.gdv-bag-name {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		min-width: 0;
	}
	.gdv-star {
		display: inline-flex;
		color: var(--fg-accent);
		font-size: 12px;
		flex: none;
	}
	.gdv-mono {
		font-family: var(--font-mono);
		white-space: nowrap;
	}
	.gdv-inline {
		display: flex;
		align-items: flex-end;
		flex-wrap: wrap;
		gap: 10px;
	}
	.gdv-actions {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 12px;
	}
	.gdv-btn {
		min-height: 44px;
		padding: 0 16px;
		border: 1px solid rgba(var(--tint-rgb), 0.18);
		border-radius: var(--radius-pill);
		background: transparent;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
	}
	.gdv-btn:disabled {
		opacity: 0.5;
		cursor: default;
	}
	.gdv-btn-primary {
		border-color: transparent;
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.gdv-btn-quiet {
		border-color: transparent;
		color: var(--fg-accent);
	}

	/* Phone: one column, the list then the detail (board PhoneDevicesGrinder). */
	.gdv.is-phone {
		gap: 16px;
	}
	.gdv.is-phone .gdv-body {
		flex-direction: column;
		align-items: stretch;
		gap: 16px;
		padding: 0 var(--page-pad-x);
	}
	.gdv.is-phone .gdv-list {
		width: auto;
	}
	.gdv.is-phone .gdv-card {
		padding: 16px;
	}
	.gdv.is-phone .gdv-fields {
		grid-template-columns: repeat(2, minmax(0, 1fr));
	}
	.gdv.is-phone .gdv-panels {
		grid-template-columns: 1fr;
	}
	@media (max-width: 900px) {
		.gdv:not(.is-phone) .gdv-body {
			flex-direction: column;
			align-items: stretch;
		}
		.gdv:not(.is-phone) .gdv-list {
			width: auto;
		}
	}
</style>
