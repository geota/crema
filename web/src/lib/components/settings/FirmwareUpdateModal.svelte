<script lang="ts">
	/**
	 * `FirmwareUpdateModal` — the type-to-confirm gate the user must pass
	 * before any firmware-update flow is allowed to run.
	 *
	 * ## Why a modal?
	 *
	 * Firmware flashing is **bricking-class destructive** — a mid-flash
	 * power loss, the wrong file picked, or a stray double-tap can leave
	 * the DE1 unbootable. The firmware-update entry point needs the same
	 * type-to-confirm gate the mains voltage / Hz writes use
	 * (`MainsConfirmModal`). This is that gate — the flow itself is
	 * tracked separately as #55 and is not implemented here.
	 *
	 * ## Confirmation token
	 *
	 * The user types the literal string `UPDATE`. Reasoning:
	 *
	 * - Short enough to type on a tablet.
	 * - Mixed-case-insensitive risk avoided by comparing case-insensitively
	 *   on the input but displaying uppercase (the obvious "I meant this").
	 * - Distinct from the build number — using the build would leak the
	 *   "should the user even update?" question into the token, and the
	 *   build is also shown directly above as the "from → to" pair.
	 * - The country-code idiom from `MainsConfirmModal` doesn't fit:
	 *   there's no location-derived "right answer" for a firmware bump.
	 *
	 * ## Pattern source
	 *
	 * Mirrors `MainsConfirmModal.svelte` exactly — same scrim, panel,
	 * banner, type-to-confirm input, and action row. CSS tokens are
	 * shared (`--bg-surface`, `--fg-1`, `--tint-rgb`, `--font-mono`); no
	 * new color tokens introduced.
	 */

	let {
		installedBuild,
		latestBuild,
		onConfirm,
		onCancel
	}: {
		/** The build the DE1 is currently running (e.g. `1352`); `undefined` if unread. */
		installedBuild: number | undefined;
		/** The build Crema would flash to (`undefined` if we don't know one). */
		latestBuild: number | undefined;
		/** Called once the user types `UPDATE` and clicks Apply. */
		onConfirm: () => void;
		/** Called on scrim click, Escape, or the Cancel button. */
		onCancel: () => void;
	} = $props();

	const installedLabel = $derived(
		installedBuild === undefined ? '— (unread)' : `v${installedBuild}`
	);
	const latestLabel = $derived(
		latestBuild === undefined ? '— (unknown)' : `v${latestBuild}`
	);

	const EXPECTED_TOKEN = 'UPDATE';
	let typed = $state('');
	const typedMatches = $derived(typed.trim().toUpperCase() === EXPECTED_TOKEN);

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
	class="fu-scrim"
	role="presentation"
	onclick={onScrimClick}
	onkeydown={(e) => {
		if (e.key === 'Enter' || e.key === ' ') onScrimClick();
	}}
>
	<div
		class="fu-panel"
		role="dialog"
		aria-modal="true"
		aria-labelledby="fu-title"
		tabindex="-1"
		onclick={onPanelClick}
		onkeydown={(e) => e.stopPropagation()}
	>
		<div class="fu-head">
			<div class="t-eyebrow">Service-grade</div>
			<h2 id="fu-title">Confirm firmware update</h2>
		</div>

		<!-- Always-shown loud red banner: damage / impact copy. -->
		<div class="fu-banner fu-banner-warn" role="alert">
			<i class="ph ph-warning-octagon" aria-hidden="true"></i>
			<span
				>Flashing the wrong firmware, or losing power mid-flash, can
				leave the DE1 unbootable. Make sure the machine is plugged in,
				idle, and that you have read the release notes before
				proceeding.</span
			>
		</div>

		<div class="fu-body">
			<div class="fu-rows">
				<div class="fu-row">
					<span class="fu-row-label">Currently installed</span>
					<span class="fu-row-value">{installedLabel}</span>
				</div>
				<div class="fu-row">
					<span class="fu-row-label">Updating to</span>
					<span class="fu-row-value fu-row-value-chosen">{latestLabel}</span>
				</div>
			</div>

			<label class="fu-field">
				<span class="fu-field-label">
					Type <code>{EXPECTED_TOKEN}</code> to confirm
				</span>
				<span class="fu-field-sub">
					This prevents a stray tap from starting the flash.
				</span>
				<input
					type="text"
					inputmode="text"
					autocomplete="off"
					autocorrect="off"
					autocapitalize="characters"
					spellcheck="false"
					bind:value={typed}
					onkeydown={(e) => {
						if (e.key === 'Enter' && typedMatches) {
							e.preventDefault();
							tryConfirm();
						}
					}}
					placeholder={EXPECTED_TOKEN}
					aria-label="Type the confirmation word"
				/>
			</label>
		</div>

		<div class="fu-actions">
			<button type="button" class="st-btn st-btn-secondary" onclick={onCancel}>
				Cancel
			</button>
			<button
				type="button"
				class="st-btn st-btn-danger"
				disabled={!typedMatches}
				onclick={tryConfirm}
			>
				Update firmware
			</button>
		</div>
	</div>
</div>

<style>
	/* CSS mirrors MainsConfirmModal exactly — same tokens, same spacing.
	   The class prefix is `fu-` (firmware-update) so the two modals can
	   coexist without selector collisions if both ever mount at once. */
	.fu-scrim {
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
	.fu-panel {
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
	.fu-head h2 {
		margin: 4px 0 0 0;
		font-size: 18px;
		font-weight: 600;
	}

	/* ---- Banners ---------------------------------------------------------- */

	.fu-banner {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		padding: 12px 14px;
		border-radius: 8px;
		font-size: 13px;
		line-height: 1.4;
	}
	.fu-banner i {
		flex-shrink: 0;
		font-size: 18px;
		margin-top: 1px;
	}
	.fu-banner-warn {
		background: rgba(var(--warning-rgb), 0.12);
		border: 1px solid rgba(var(--warning-rgb), 0.35);
		color: var(--warning);
	}

	/* ---- Body ------------------------------------------------------------- */

	.fu-body {
		display: flex;
		flex-direction: column;
		gap: 16px;
	}
	.fu-rows {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding: 12px 14px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: 8px;
	}
	.fu-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 16px;
	}
	.fu-row-label {
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.65);
	}
	.fu-row-value {
		font-family: var(--font-mono);
		font-size: 14px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
	}
	.fu-row-value-chosen {
		font-weight: 700;
		font-size: 16px;
	}

	/* ---- Field ------------------------------------------------------------ */

	.fu-field {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.fu-field-label {
		font-size: 13px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.fu-field-label code {
		font-family: var(--font-mono);
		font-size: 13px;
		font-weight: 700;
		padding: 1px 6px;
		background: rgba(var(--tint-rgb), 0.1);
		border-radius: 4px;
	}
	.fu-field-sub {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.6);
	}
	.fu-field input {
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
	.fu-field input:focus-visible {
		border-color: rgba(var(--tint-rgb), 0.6);
		background: rgba(var(--tint-rgb), 0.06);
	}

	/* ---- Actions ---------------------------------------------------------- */

	.fu-actions {
		display: flex;
		gap: 8px;
		justify-content: flex-end;
	}
</style>
