<script lang="ts">
	/** `PhoneSearch` — the 46 px search pill (Android `CremaPhoneSearch`). */
	import MagnifyingGlassIcon from 'phosphor-svelte/lib/MagnifyingGlassIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';

	let {
		value = $bindable(),
		placeholder,
		label,
		title
	}: { value: string; placeholder: string; label: string; title?: string } = $props();
</script>

<div class="psearch">
	<MagnifyingGlassIcon aria-hidden="true" />
	<input type="search" bind:value {placeholder} aria-label={label} {title} autocomplete="off" />
	{#if value}
		<button type="button" class="psearch-clear" aria-label="Clear search" onclick={() => (value = '')}>
			<XIcon aria-hidden="true" />
		</button>
	{/if}
</div>

<style>
	.psearch {
		display: flex;
		align-items: center;
		gap: 10px;
		height: 46px;
		padding: 0 4px 0 16px;
		border-radius: 999px;
		background: var(--bg-surface);
		color: var(--fg-3);
	}
	.psearch > :global(svg) {
		flex: none;
		font-size: 19px;
	}
	.psearch input {
		flex: 1 1 auto;
		min-width: 0;
		height: 44px;
		padding: 0;
		border: 0;
		outline: none;
		background: transparent;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 16px;
		-webkit-appearance: none;
		appearance: none;
	}
	.psearch input::-webkit-search-cancel-button {
		display: none;
	}
	.psearch input::placeholder {
		color: var(--fg-3);
		opacity: 1;
	}
	.psearch:focus-within {
		box-shadow: var(--shadow-focus);
	}
	.psearch-clear {
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 44px;
		height: 44px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		color: var(--fg-3);
		font-size: 16px;
		cursor: pointer;
	}
</style>
