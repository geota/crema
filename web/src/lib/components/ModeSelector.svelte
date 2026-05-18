<script lang="ts">
	import type { ModeInfo } from '$lib/core';

	/**
	 * A row of buttons, one per scale-reported display mode. The web mirror of
	 * the Android shell's `ModeSelector`.
	 *
	 * Two-way: `selectedMode` tracks the scale's live active mode, so the
	 * matching button is highlighted (filled `btn-primary`) while the others
	 * stay outlined. A tap still issues the write; the live stream confirms it.
	 */
	let {
		modes,
		selectedMode,
		enabled,
		onSetMode
	}: {
		modes: ModeInfo[];
		selectedMode: number | null;
		enabled: boolean;
		onSetMode: (id: number) => void;
	} = $props();
</script>

<div class="flex flex-col gap-1">
	<span class="text-base-content/50 text-xs">Mode</span>
	<div class="flex gap-2">
		{#each modes as mode (mode.id)}
			<button
				type="button"
				class="btn btn-sm grow {mode.id === selectedMode ? 'btn-primary' : 'btn-outline'}"
				disabled={!enabled}
				onclick={() => onSetMode(mode.id)}
			>
				{mode.name}
			</button>
		{/each}
	</div>
</div>
