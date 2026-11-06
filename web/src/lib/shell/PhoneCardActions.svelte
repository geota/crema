<script lang="ts">
	/**
	 * `PhoneCardActions` — a library card's foot (Android's Load / Set active
	 * pill, plus the round edit and ⋮ buttons). The pill is copper while the
	 * card is the loaded one.
	 */
	import type { Component } from 'svelte';
	import DotsThreeVerticalIcon from 'phosphor-svelte/lib/DotsThreeVerticalIcon';
	import PencilSimpleIcon from 'phosphor-svelte/lib/PencilSimpleIcon';

	let {
		label,
		icon: LeadIcon,
		on = false,
		name,
		onprimary,
		onedit,
		onmenu
	}: {
		label: string;
		icon: Component;
		/** This card is the loaded profile or active bag. */
		on?: boolean;
		/** The card's name, for the round buttons' accessible names. */
		name: string;
		onprimary: () => void;
		onedit: () => void;
		onmenu: () => void;
	} = $props();
</script>

<div class="pcact">
	<button type="button" class={['pcact-main', { 'is-on': on }]} onclick={onprimary}>
		<LeadIcon aria-hidden="true" />
		<span>{label}</span>
	</button>
	<button type="button" class="pcact-round" aria-label="Edit {name}" onclick={onedit}>
		<PencilSimpleIcon aria-hidden="true" />
	</button>
	<button
		type="button"
		class="pcact-round"
		aria-label="More actions for {name}"
		aria-haspopup="dialog"
		onclick={onmenu}
	>
		<DotsThreeVerticalIcon weight="bold" aria-hidden="true" />
	</button>
</div>

<style>
	.pcact {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.pcact-main {
		flex: 1 1 auto;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 7px;
		min-width: 0;
		height: 44px;
		padding: 0 12px;
		border: 0;
		border-radius: 999px;
		background: var(--ph-tonal);
		color: var(--ph-on-tonal);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		white-space: nowrap;
		cursor: pointer;
	}
	.pcact-main.is-on {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.pcact-main :global(svg) {
		flex: none;
		font-size: 16px;
	}
	.pcact-round {
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 44px;
		height: 44px;
		border: 0;
		border-radius: 50%;
		background: var(--ph-highest);
		color: var(--fg-3);
		font-size: 18px;
		cursor: pointer;
	}
	.pcact-main:focus-visible,
	.pcact-round:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
</style>
