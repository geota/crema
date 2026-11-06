<script lang="ts">
	/**
	 * `PhoneModeBanner` — the running service mode on the phone Brew screen
	 * (Android `ModeStatusBanner`): its name, live counter and temperature, a
	 * bar against the target when the mode has one, and Stop.
	 */
	let {
		label,
		meta,
		color,
		pct,
		onStop
	}: {
		label: string;
		/** `3.2 / 12 s · 148.0 °C`. */
		meta: string;
		/** The mode's colour (CSS value). */
		color: string;
		/** Progress 0–100; a pour with no time target stays at 0. */
		pct: number;
		/** Same action as tapping the active mode chip. */
		onStop: () => void;
	} = $props();
</script>

<section class="pmode" style:--mode={color} aria-label="{label} running">
	<div class="pmode-top">
		<span class="pmode-dot"></span>
		<span class="pmode-label">{label}</span>
		<span class="pmode-meta">{meta}</span>
		<button type="button" class="pmode-stop" onclick={onStop}>Stop</button>
	</div>
	<div class="pmode-bar"><span style:width="{pct}%"></span></div>
</section>

<style>
	.pmode {
		flex: none;
		display: flex;
		flex-direction: column;
		gap: 8px;
		padding: 10px 14px;
		border-radius: 14px;
		background: color-mix(in srgb, var(--mode) 16%, var(--bg-surface));
	}
	.pmode-top {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.pmode-dot {
		flex: none;
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--mode);
	}
	.pmode-label {
		flex: 1 1 auto;
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 600;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.pmode-meta {
		flex: none;
		order: 1;
		font-family: var(--font-mono);
		font-size: 13px;
		font-variant-numeric: tabular-nums;
		font-weight: 500;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.pmode-stop {
		position: relative;
		order: 2;
		flex: none;
		padding: 5px 12px;
		border: 0;
		border-radius: var(--radius-pill);
		background: rgba(var(--tint-rgb), 0.12);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 500;
		cursor: pointer;
	}
	.pmode-stop::before {
		content: '';
		position: absolute;
		inset: -8px -4px;
	}
	/* The counter and temperature don't fit beside the label: they take a row. */
	@media (max-width: 389px) {
		.pmode-top {
			flex-wrap: wrap;
		}
		.pmode-stop {
			order: 0;
		}
		.pmode-meta {
			flex-basis: 100%;
		}
	}
	.pmode-stop:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.pmode-bar {
		height: 5px;
		overflow: hidden;
		border-radius: var(--radius-pill);
		background: rgba(var(--tint-rgb), 0.1);
	}
	.pmode-bar span {
		display: block;
		height: 100%;
		border-radius: inherit;
		background: var(--mode);
	}
</style>
