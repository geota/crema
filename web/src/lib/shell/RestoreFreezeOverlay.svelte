<script lang="ts">
	/**
	 * `RestoreFreezeOverlay` — covers the app while a restore runs in another
	 * tab. This tab's writes are being dropped, so nothing may look saved; it
	 * reloads by itself when the restore is over. A shot finished here that
	 * couldn't be kept for the reload is offered as a file first.
	 */
	import { downloadBlob } from '#lib/utils/download.js';
	import { freezeOverlay } from '#lib/restore/freeze-overlay.svelte.js';

	let { onSaved, reloadNow = () => location.reload() }: { onSaved: () => void; reloadNow?: () => void } =
		$props();

	function download(): void {
		const shot = freezeOverlay.unsaved;
		if (!shot) return;
		downloadBlob('crema-shot.json', new Blob([JSON.stringify(shot, null, 2)], { type: 'application/json' }));
		freezeOverlay.saved();
		onSaved();
	}
</script>

{#if freezeOverlay.shown}
	<div class="rf-overlay" role="alertdialog" aria-modal="true" aria-labelledby="rf-title">
		<div class="rf-card">
			<p id="rf-title" class="rf-title">A restore is running in another tab.</p>
			{#if freezeOverlay.unsaved}
				<p class="rf-body">
					A shot finished here, and this tab couldn't keep it for the reload. Download it before
					this tab reloads.
				</p>
				<button type="button" class="rf-button" onclick={download}>Download this shot</button>
			{:else}
				<p class="rf-body">This tab will reload when it's done.</p>
				{#if freezeOverlay.waitedLong}
					<p class="rf-body">
						It's waiting for the machine to finish. If nothing is running on it, reload now.
					</p>
					<button type="button" class="rf-button" onclick={reloadNow}>Reload now</button>
				{/if}
			{/if}
		</div>
	</div>
{/if}

<style>
	.rf-overlay {
		position: fixed;
		inset: 0;
		z-index: 2000;
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 24px;
		background: color-mix(in srgb, var(--bg-base, #000) 80%, transparent);
	}
	.rf-card {
		max-width: 420px;
		padding: 20px 24px;
		border: 1px solid var(--hairline-strong);
		border-radius: 12px;
		background: var(--bg-surface);
		color: var(--fg-1);
		font-family: var(--font-sans);
	}
	.rf-title {
		margin: 0 0 8px;
		font-weight: 600;
	}
	.rf-body {
		margin: 0;
		color: var(--fg-2);
	}
	.rf-button {
		margin-top: 16px;
		min-height: 40px;
		padding: 6px 16px;
		border: 1px solid var(--hairline-strong);
		border-radius: 999px;
		background: var(--bg-surface-2);
		color: var(--fg-1);
		font: inherit;
		font-weight: 600;
		cursor: pointer;
	}
	.rf-button:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
</style>
