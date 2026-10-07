<script lang="ts">
	/**
	 * `ReconnectStrip` — a slim, app-wide notice while the DE1 or the scale is
	 * auto-reconnecting, with a "Retry now" button per device. Shown on every
	 * route and at every width (it sits at the top of the content column, so
	 * the phone layout gets it too); hidden the moment both links are back.
	 *
	 * "Retry now" cuts the transport's backoff / 60 s lurk wait short and
	 * resets it to the fast burst (`CremaApp.retryReconnect`) — the same kick
	 * the tab gets when it comes back into view.
	 */
	import Icon from '$lib/icons/Icon.svelte';
	import type { CremaApp } from '$lib/state';

	let { app }: { app: CremaApp | null } = $props();

	const snapshot = $derived(app?.state.current ?? null);
	const devices = $derived(
		snapshot === null
			? []
			: [
					...(snapshot.de1State === 'reconnecting' ? [{ id: 'de1' as const, name: 'DE1' }] : []),
					...(snapshot.scaleState === 'reconnecting'
						? [{ id: 'scale' as const, name: 'Scale' }]
						: [])
				]
	);
</script>

{#if devices.length > 0}
	<div class="rc-strip" role="status" aria-live="polite">
		{#each devices as d (d.id)}
			<div class="rc-row">
				<Icon cls="ph ph-bluetooth" aria-hidden="true" />
				<span class="rc-label">{d.name} reconnecting…</span>
				<button type="button" class="rc-retry" onclick={() => app?.retryReconnect(d.id)}>
					<Icon cls="ph ph-arrows-clockwise" aria-hidden="true" />
					<span>Retry now</span>
				</button>
			</div>
		{/each}
	</div>
{/if}

<style>
	.rc-strip {
		position: sticky;
		top: 0;
		z-index: 30;
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding: 8px 16px;
		background: var(--bg-surface-2);
		border-bottom: 1px solid var(--hairline);
	}
	.rc-row {
		display: flex;
		align-items: center;
		gap: 10px;
		min-width: 0;
		color: var(--fg-2);
		font-family: var(--font-sans);
		font-size: 13px;
	}
	.rc-label {
		flex: 1 1 auto;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.rc-retry {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		flex: 0 0 auto;
		min-height: 32px;
		padding: 4px 12px;
		border: 1px solid var(--hairline-strong);
		border-radius: 999px;
		background: var(--bg-surface);
		color: var(--fg-1);
		font: inherit;
		font-weight: 600;
		cursor: pointer;
	}
	.rc-retry:hover {
		border-color: var(--copper-400);
	}
	.rc-retry:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
</style>
