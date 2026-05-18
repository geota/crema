<script lang="ts">
	import type { RangeCapability } from '$lib/core';

	/**
	 * A `−` / value / `+` stepper for a whole-number scale setting (beep volume,
	 * auto-standby timeout) over the scale-reported `[min, max]` range. The web
	 * mirror of the Android shell's `ConfigStepper`.
	 *
	 * The `−` / `+` buttons disable at the range ends, so `onSetValue` is only
	 * ever called with an in-range value. The whole control disables when the
	 * scale is not READY.
	 */
	let {
		label,
		value,
		range,
		enabled,
		onSetValue
	}: {
		label: string;
		value: number;
		range: RangeCapability;
		enabled: boolean;
		onSetValue: (next: number) => void;
	} = $props();
</script>

<div class="flex flex-col gap-1">
	<span class="text-base-content/50 text-xs">{label}</span>
	<div class="flex items-center gap-2">
		<button
			type="button"
			class="btn btn-sm btn-outline"
			disabled={!enabled || value <= range.min}
			onclick={() => onSetValue(value - 1)}
			aria-label="Decrease {label}"
		>
			−
		</button>
		<span class="grow text-center font-mono text-lg">{value}</span>
		<button
			type="button"
			class="btn btn-sm btn-outline"
			disabled={!enabled || value >= range.max}
			onclick={() => onSetValue(value + 1)}
			aria-label="Increase {label}"
		>
			+
		</button>
	</div>
</div>
