<script lang="ts">
	/**
	 * `StToggle` — the mini on/off switch used throughout Settings. Reuses the
	 * shared `.qmini-tog` styling (from `dashboard.css`), exactly as the design's
	 * `StToggle` does.
	 */
	let {
		on,
		onChange,
		disabled = false,
		label
	}: {
		on: boolean;
		onChange?: (next: boolean) => void;
		disabled?: boolean;
		label?: string;
	} = $props();
</script>

<button
	type="button"
	class="qmini-tog"
	class:on
	{disabled}
	aria-pressed={on}
	aria-label={label}
	onclick={() => onChange?.(!on)}
></button>
