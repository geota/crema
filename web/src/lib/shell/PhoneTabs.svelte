<script lang="ts">
	/** `PhoneTabs` — underlined two-way tabs (Android `PhoneTabs`), as Beans' Bags / Roasters. */
	let {
		options,
		value,
		label,
		onchange
	}: {
		options: readonly { id: string; label: string }[];
		value: string;
		label: string;
		onchange: (id: string) => void;
	} = $props();
</script>

<div class="ptabs" role="group" aria-label={label}>
	{#each options as o (o.id)}
		<button
			type="button"
			class={['ptabs-item', { 'is-on': o.id === value }]}
			aria-pressed={o.id === value}
			onclick={() => onchange(o.id)}
		>
			<span>{o.label}</span>
			<i aria-hidden="true"></i>
		</button>
	{/each}
</div>

<style>
	.ptabs {
		display: flex;
		padding: 0 var(--page-pad-x);
	}
	.ptabs-item {
		flex: 1 1 0;
		display: flex;
		flex-direction: column;
		align-items: center;
		padding: 0;
		border: 0;
		background: transparent;
		color: var(--fg-3);
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 500;
		cursor: pointer;
	}
	.ptabs-item span {
		display: flex;
		align-items: center;
		height: 41px;
	}
	.ptabs-item i {
		display: block;
		width: 60%;
		height: 3px;
		border-radius: 3px 3px 0 0;
	}
	.ptabs-item.is-on {
		color: var(--fg-accent);
	}
	.ptabs-item.is-on i {
		background: var(--fg-accent);
	}
	.ptabs-item:focus-visible {
		outline: none;
		border-radius: 8px;
		box-shadow: var(--shadow-focus);
	}
</style>
