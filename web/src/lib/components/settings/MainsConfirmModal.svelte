<script lang="ts">
	/**
	 * `MainsConfirmModal` — the type-to-confirm gate the user must pass
	 * before committing the mains heater voltage (`kind="voltage"`) or the
	 * AC mains frequency override (`kind="hz"`).
	 *
	 * ## Why a modal?
	 *
	 * Heater voltage is **hardware-damaging if mis-set** — writing 120 V
	 * when the wall outlet is 240 V will fry the heater. The legacy de1app
	 * uses a confirm-then-set dance in the Tcl settings page; reaprime
	 * clamps the REST value to `[120, 230]` but offers no UX guard. Crema does both: the Rust core clamps (last-line guard) and
	 * this modal asks the user to literally type the value (front-line
	 * guard) so a stray tap or autocomplete cannot commit the change.
	 *
	 * Hz is much safer — wrong Hz only mis-calibrates the AC-period
	 * integrator (~5% volume drift). We still gate it behind the same UX
	 * for symmetry: the user expects the two "mains" settings to behave
	 * the same.
	 *
	 * ## Country hint
	 *
	 * On mount we lazily call `detectCountry()` (IP → ISO-3166 via ipinfo,
	 * cached in localStorage). If the country has a single known standard
	 * and that standard differs from the value the user is trying to set,
	 * we surface an extra-loud red banner. Detection failures are silent;
	 * the type-to-confirm requirement is unconditional.
	 *
	 * ## Pattern source
	 *
	 * Type-to-confirm is patterned after GitHub's "delete repository" UX:
	 * the user types the repo name verbatim; the destructive button stays
	 * disabled until the input matches.
	 */
	import { onMount } from 'svelte';
	import { detectCountry } from '$lib/utils/country-detect';
	import { POWER_STANDARDS, countryName } from '$lib/utils/power-standards';

	let {
		kind,
		chosen,
		current,
		onConfirm,
		onCancel
	}: {
		/** Which mains setting the user is changing. */
		kind: 'voltage' | 'hz';
		/** The value the user wants to apply (raw integer). */
		chosen: number;
		/** The value the firmware currently holds (raw integer; `0` = unset). */
		current: number;
		/** Called once the user types the chosen value and clicks Confirm. */
		onConfirm: () => void;
		/** Called on scrim click, Escape, or the Cancel button. */
		onCancel: () => void;
	} = $props();

	const unit = $derived(kind === 'voltage' ? 'V' : ' Hz');
	const expectedLabel = $derived(`${chosen}${unit}`);
	const currentLabel = $derived(
		current === 0 ? '— (unset)' : `${current}${unit}`
	);
	const heading = $derived(
		kind === 'voltage' ? 'Confirm machine voltage' : 'Confirm AC mains frequency'
	);

	/** Damage / impact copy that's always shown, regardless of country mismatch. */
	const damageCopy = $derived(
		kind === 'voltage'
			? 'Wrong voltage on your mains can permanently damage your heater. Verify your wall outlet matches the value below before proceeding.'
			: 'Wrong AC frequency mis-calibrates the volume integrator (no hardware damage, but shot volumes drift up to 5 %). Verify your mains frequency matches the value below.'
	);

	// ---- Country detection (lazy on mount) -------------------------------
	let country = $state<string | null>(null);
	let detectionDone = $state(false);

	onMount(async () => {
		country = await detectCountry();
		detectionDone = true;
	});

	const entry = $derived(country ? POWER_STANDARDS[country] : undefined);
	const expectedFromCountry = $derived(
		entry ? (kind === 'voltage' ? entry.voltage : entry.hz) : undefined
	);
	const hasMismatch = $derived(
		expectedFromCountry != null && expectedFromCountry !== chosen
	);
	/**
	 * "In-country variation" — the country exists in the table but the
	 * field the user is editing was deliberately omitted (BO/BR/GY for
	 * voltage, BR/GY/JP for Hz). Suppress the mismatch banner and show a
	 * gentler note instead.
	 */
	const inCountryVariation = $derived(
		country != null && entry != null && expectedFromCountry == null
	);

	// ---- Type-to-confirm input -------------------------------------------
	let typed = $state('');
	const expectedTyped = $derived(String(chosen));
	const typedMatches = $derived(typed.trim() === expectedTyped);

	function onKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			e.stopPropagation();
			onCancel();
		}
	}

	function onScrimClick() {
		onCancel();
	}

	function onPanelClick(e: MouseEvent) {
		e.stopPropagation();
	}

	function tryConfirm() {
		if (typedMatches) onConfirm();
	}
</script>

<svelte:document onkeydown={onKeydown} />

<div
	class="mc-scrim"
	role="presentation"
	onclick={onScrimClick}
	onkeydown={(e) => {
		if (e.key === 'Enter' || e.key === ' ') onScrimClick();
	}}
>
	<div
		class="mc-panel"
		role="dialog"
		aria-modal="true"
		aria-labelledby="mc-title"
		tabindex="-1"
		onclick={onPanelClick}
		onkeydown={(e) => e.stopPropagation()}
	>
		<div class="mc-head">
			<div class="t-eyebrow">{kind === 'voltage' ? 'Service-grade' : 'Mains'}</div>
			<h2 id="mc-title">{heading}</h2>
		</div>

		<!-- Always-shown loud red banner: damage / impact copy. -->
		<div class="mc-banner mc-banner-warn" role="alert">
			<i class="ph ph-warning-octagon" aria-hidden="true"></i>
			<span>{damageCopy}</span>
		</div>

		<!--
			Extra-loud banner: shown only when the country's standard
			differs from what the user is setting. Hidden during detection,
			when detection fails (country == null), or when the country has
			multiple in-use standards for this field.
		-->
		{#if hasMismatch && country}
			<div class="mc-banner mc-banner-danger" role="alert">
				<i class="ph ph-warning-circle" aria-hidden="true"></i>
				<span>
					Detected location: <strong>{countryName(country)}</strong> — expected
					<strong>{expectedFromCountry}{unit}</strong>. You are setting
					<strong>{chosen}{unit}</strong>.
				</span>
			</div>
		{:else if inCountryVariation}
			<div class="mc-banner mc-banner-info" role="status">
				<i class="ph ph-info" aria-hidden="true"></i>
				<span>
					Your region ({countryName(country!)}) uses more than one mains
					standard — please verify your outlet directly.
				</span>
			</div>
		{/if}

		<div class="mc-body">
			<div class="mc-rows">
				<div class="mc-row">
					<span class="mc-row-label">Currently set</span>
					<span class="mc-row-value">{currentLabel}</span>
				</div>
				<div class="mc-row">
					<span class="mc-row-label">Setting to</span>
					<span class="mc-row-value mc-row-value-chosen">{expectedLabel}</span>
				</div>
			</div>

			<label class="mc-field">
				<span class="mc-field-label">
					Type <code>{expectedTyped}</code> to confirm
				</span>
				<span class="mc-field-sub">
					This prevents a stray tap from committing the change.
				</span>
				<input
					type="text"
					inputmode="numeric"
					autocomplete="off"
					autocorrect="off"
					autocapitalize="off"
					spellcheck="false"
					bind:value={typed}
					onkeydown={(e) => {
						if (e.key === 'Enter' && typedMatches) {
							e.preventDefault();
							tryConfirm();
						}
					}}
					placeholder={expectedTyped}
					aria-label="Type the value to confirm"
				/>
			</label>
		</div>

		<div class="mc-actions">
			<button type="button" class="st-btn st-btn-secondary" onclick={onCancel}>
				Cancel
			</button>
			<button
				type="button"
				class={['st-btn', kind === 'voltage' ? 'st-btn-danger' : 'st-btn-primary']}
				disabled={!typedMatches}
				onclick={tryConfirm}
			>
				{kind === 'voltage' ? 'Apply voltage' : 'Apply frequency'}
			</button>
		</div>

		{#if !detectionDone}
			<div class="mc-detect-status" aria-live="polite">
				Checking your region…
			</div>
		{/if}
	</div>
</div>

<style>
	.mc-scrim {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.6);
		backdrop-filter: blur(4px);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 1000;
		padding: 24px;
	}
	.mc-panel {
		background: var(--bg-surface);
		color: var(--fg-1);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 12px;
		box-shadow:
			0 24px 48px rgba(0, 0, 0, 0.4),
			0 4px 12px rgba(0, 0, 0, 0.25);
		max-width: 480px;
		width: 100%;
		padding: 24px;
		display: flex;
		flex-direction: column;
		gap: 16px;
		outline: none;
	}
	.mc-head h2 {
		margin: 4px 0 0 0;
		font-size: 18px;
		font-weight: 600;
	}

	/* ---- Banners ---------------------------------------------------------- */

	.mc-banner {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		padding: 12px 14px;
		border-radius: 8px;
		font-size: 13px;
		line-height: 1.4;
	}
	.mc-banner i {
		flex-shrink: 0;
		font-size: 18px;
		margin-top: 1px;
	}
	.mc-banner-warn {
		background: rgba(220, 80, 0, 0.12);
		border: 1px solid rgba(220, 80, 0, 0.35);
		color: #c64500;
	}
	.mc-banner-danger {
		background: rgba(220, 30, 30, 0.16);
		border: 1px solid rgba(220, 30, 30, 0.5);
		color: #b80000;
		font-weight: 500;
	}
	.mc-banner-info {
		background: rgba(60, 120, 200, 0.1);
		border: 1px solid rgba(60, 120, 200, 0.3);
		color: var(--fg-1);
	}

	/* ---- Body ------------------------------------------------------------- */

	.mc-body {
		display: flex;
		flex-direction: column;
		gap: 16px;
	}
	.mc-rows {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding: 12px 14px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: 8px;
	}
	.mc-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 16px;
	}
	.mc-row-label {
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.65);
	}
	.mc-row-value {
		font-family: var(--font-mono);
		font-size: 14px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
	}
	.mc-row-value-chosen {
		font-weight: 700;
		font-size: 16px;
	}

	/* ---- Field ------------------------------------------------------------ */

	.mc-field {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.mc-field-label {
		font-size: 13px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.mc-field-label code {
		font-family: var(--font-mono);
		font-size: 13px;
		font-weight: 700;
		padding: 1px 6px;
		background: rgba(var(--tint-rgb), 0.1);
		border-radius: 4px;
	}
	.mc-field-sub {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.6);
	}
	.mc-field input {
		margin-top: 6px;
		border: 1px solid rgba(var(--tint-rgb), 0.25);
		border-radius: 8px;
		padding: 8px 12px;
		background: rgba(var(--tint-rgb), 0.04);
		color: var(--fg-1);
		font-family: var(--font-mono);
		font-size: 15px;
		font-variant-numeric: tabular-nums;
		outline: none;
	}
	.mc-field input:focus-visible {
		border-color: rgba(var(--tint-rgb), 0.6);
		background: rgba(var(--tint-rgb), 0.06);
	}

	/* ---- Actions ---------------------------------------------------------- */

	.mc-actions {
		display: flex;
		gap: 8px;
		justify-content: flex-end;
	}

	.mc-detect-status {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		text-align: right;
	}
</style>
