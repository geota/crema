<script lang="ts">
	/**
	 * `StButton` — the settings-kit button: secondary (default), primary, or
	 * danger, with an optional leading Phosphor icon. Ported from the design's
	 * `StButton` (`settings-page.jsx`).
	 */
	let {
		label,
		icon,
		variant = 'secondary',
		disabled = false,
		onClick
	}: {
		label: string;
		icon?: string;
		variant?: 'secondary' | 'primary' | 'danger';
		disabled?: boolean;
		onClick?: () => void;
	} = $props();
</script>

<button
	type="button"
	class={'st-btn st-btn-' + variant}
	{disabled}
	onclick={() => onClick?.()}
>
	{#if icon}<i class={'ph ph-' + icon} aria-hidden="true"></i>{/if}
	<span>{label}</span>
</button>
