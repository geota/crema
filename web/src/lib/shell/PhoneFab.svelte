<script lang="ts">
	/**
	 * `PhoneFab` — the extended floating button (Android `CremaNewFab`): above
	 * the tab bar on the library screens, under the top bar and sheets.
	 */
	import PlusIcon from 'phosphor-svelte/lib/PlusIcon';

	let {
		label,
		title,
		haspopup,
		onclick
	}: { label: string; title?: string; haspopup?: 'dialog'; onclick: () => void } = $props();
</script>

<button type="button" class="pfab" {title} aria-haspopup={haspopup} {onclick}>
	<PlusIcon aria-hidden="true" />
	<span>{label}</span>
</button>

<style>
	.pfab {
		position: fixed;
		right: calc(16px + env(safe-area-inset-right, 0px));
		bottom: calc(var(--ptab-clear) + 16px);
		z-index: 40;
		display: inline-flex;
		align-items: center;
		gap: 12px;
		height: 56px;
		padding: 0 20px 0 16px;
		border: 0;
		border-radius: 16px;
		background: var(--ph-fab);
		color: var(--ph-on-fab);
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 500;
		box-shadow:
			0 6px 10px rgba(0, 0, 0, 0.28),
			0 2px 4px rgba(0, 0, 0, 0.2);
		cursor: pointer;
	}
	.pfab :global(svg) {
		font-size: 20px;
	}
	.pfab:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
</style>
