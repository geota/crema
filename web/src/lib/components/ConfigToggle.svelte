<script lang="ts">
	/**
	 * A labelled on/off toggle for a boolean scale setting (flow smoothing,
	 * anti-mistouch). The web mirror of the Android shell's `ConfigToggle`.
	 *
	 * Two-way: `checked` tracks the scale's live value and is updated
	 * optimistically by the orchestrator on a user change. The toggle disables
	 * when the scale is not READY.
	 */
	let {
		label,
		checked,
		enabled,
		onCheckedChange
	}: {
		label: string;
		checked: boolean;
		enabled: boolean;
		onCheckedChange: (next: boolean) => void;
	} = $props();
</script>

<label class="flex items-center gap-2">
	<span class="text-base-content/50 grow text-xs">{label}</span>
	<input
		type="checkbox"
		class="toggle toggle-sm"
		{checked}
		disabled={!enabled}
		onchange={(e) => onCheckedChange(e.currentTarget.checked)}
	/>
</label>
