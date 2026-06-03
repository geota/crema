<script lang="ts">
	import XIcon from 'phosphor-svelte/lib/XIcon';
	/**
	 * `ShotImportDialog` — drop or pick legacy `.shot` / `.shot.json`
	 * files to import into the shot library. Mirrors the BeanImportDialog
	 * shell (modal overlay, header, drop zone) so /beans and /history
	 * share one drag-drop pattern.
	 *
	 * Calls back through `onImport` with the file list — the caller owns
	 * the actual ingestion via `CremaApp.importShotFile` and surfaces the
	 * result banner on /history.
	 */
	import FileDropZone from '$lib/components/shared/FileDropZone.svelte';

	let {
		onClose,
		onImport,
		importing = false
	}: {
		onClose: () => void;
		onImport: (files: File[]) => Promise<void>;
		importing?: boolean;
	} = $props();
</script>

<!-- Backdrop — click to close. -->
<div
	class="sid-backdrop"
	role="button"
	tabindex="0"
	onclick={onClose}
	onkeydown={(e) => {
		if (e.key === 'Escape') onClose();
	}}
	aria-label="Close import"
></div>

<div class="sid-modal" role="dialog" aria-labelledby="sid-title">
	<header class="sid-head">
		<h2 class="sid-title" id="sid-title">Import shot files</h2>
		<button class="sid-close" onclick={onClose} aria-label="Close">
			<XIcon aria-hidden="true" />
		</button>
	</header>

	<div class="sid-drop-wrap">
		<FileDropZone
			icon="ph-duotone ph-file-arrow-down"
			title="Drop legacy .shot or .shot.json files"
			accept=".shot,.json,.shot.json"
			disabled={importing}
			pickLabel={importing ? 'Importing…' : 'Choose files…'}
			onFiles={async (files) => {
				await onImport(files);
				if (!importing) onClose();
			}}
		>
			{#snippet subtitle()}
				Multi-select supported — drop a whole <code>history/</code> folder of de1app
				shots in one go. Each parsed shot is prepended to the local history;
				duplicates by filename + timestamp are skipped.
			{/snippet}
		</FileDropZone>
	</div>
</div>

<style>
	.sid-backdrop {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.45);
		backdrop-filter: blur(2px);
		z-index: 200;
	}
	.sid-modal {
		position: fixed;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		z-index: 201;
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-lg, 14px);
		box-shadow: var(--shadow-lg);
		width: min(560px, 90vw);
		max-height: 85vh;
		display: flex;
		flex-direction: column;
		overflow: hidden;
	}
	.sid-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 14px 18px 12px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.06);
	}
	.sid-title {
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 600;
		margin: 0;
		color: var(--fg-1);
	}
	.sid-close {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		cursor: pointer;
		padding: 4px;
	}
	.sid-drop-wrap {
		margin: 20px;
	}
</style>
