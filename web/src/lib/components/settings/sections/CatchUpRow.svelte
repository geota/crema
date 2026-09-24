<script lang="ts">
	/**
	 * The one-time catch-up offer — "Upload the N shots already on this
	 * device to X?" — shown inline the moment a destination becomes able to
	 * receive shots (sign-in, auto-upload on, push direction on). Presentational:
	 * the parent owns the offer, runs the upload and reports progress.
	 */
	import StButton from '../StButton.svelte';
	import StRow from '../StRow.svelte';

	let {
		destination,
		count,
		progress,
		onUpload,
		onDismiss
	}: {
		destination: 'Visualizer' | 'Decent';
		/** Shots the upload would send. */
		count: number;
		/** `{done, total}` while uploading, else `null`. */
		progress: { done: number; total: number } | null;
		onUpload: () => void;
		onDismiss: () => void;
	} = $props();

	const busy = $derived(progress !== null);
</script>

<StRow
	title={`Upload the ${count} shot${count === 1 ? '' : 's'} already on this device to ${destination}?`}
	sub={destination === 'Decent'
		? 'Older shots need the DE1 connected for its serial. You can also do this later from History.'
		: 'You can also do this later from History.'}
>
	{#snippet control()}
		<div class="cu-actions">
			<button type="button" class="st-btn st-btn-secondary" disabled={busy} onclick={onDismiss}>
				Not now
			</button>
			<StButton
				label={progress ? `${progress.done}/${progress.total}…` : 'Upload'}
				icon="cloud-arrow-up"
				variant="primary"
				disabled={busy}
				onClick={onUpload}
			/>
		</div>
	{/snippet}
</StRow>
