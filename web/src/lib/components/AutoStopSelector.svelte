<script lang="ts">
	/**
	 * A two-option selector for the scale's auto-stop mode — Flow-Stop (`id 0`)
	 * or Cup-Removal (`id 1`). The web mirror of the Android shell's
	 * `AutoStopSelector`.
	 *
	 * Two-way: the scale echoes its live auto-stop mode in every weight
	 * notification, so `selectedMode` tracks that real value and the matching
	 * button is highlighted (filled) while the other stays outlined.
	 */
	let {
		selectedMode,
		enabled,
		onSetMode
	}: {
		selectedMode: number | null;
		enabled: boolean;
		onSetMode: (id: number) => void;
	} = $props();

	// id -> label; the order matches the core's AutoStopMode discriminants.
	const options: { id: number; label: string }[] = [
		{ id: 0, label: 'Flow-Stop' },
		{ id: 1, label: 'Cup-Removal' }
	];
</script>

<div class="flex flex-col gap-1">
	<span class="text-base-content/50 text-xs">Auto-stop</span>
	<div class="flex gap-2">
		{#each options as option (option.id)}
			<button
				type="button"
				class="btn btn-sm grow {option.id === selectedMode ? 'btn-primary' : 'btn-outline'}"
				disabled={!enabled}
				onclick={() => onSetMode(option.id)}
			>
				{option.label}
			</button>
		{/each}
	</div>
</div>
