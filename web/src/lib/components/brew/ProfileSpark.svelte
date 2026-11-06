<script lang="ts">
	/**
	 * `ProfileSpark` — a tiny silhouette of a profile's segment plan for the
	 * phone picker rows (Android `MiniProfileSpark`): pressure phases solid
	 * sage, flow phases dashed blue, everything copper on the loaded profile.
	 */
	import type { ProfileSegment } from '#lib/profiles/index.js';

	let { segments, active = false }: { segments: readonly ProfileSegment[]; active?: boolean } =
		$props();

	const WIDTH = 38;
	const HEIGHT = 20;

	/** One SVG path per phase: a move to where the last ended, a ramp, a hold. */
	const phases = $derived.by(() => {
		const segs = segments.filter((s) => s.time > 0);
		const total = segs.reduce((sum, s) => sum + s.time, 0);
		if (total <= 0) return [];
		const peak = Math.max(1, ...segs.map((s) => s.target));
		const yOf = (v: number): number => (1 - v / peak) * HEIGHT * 0.9 + HEIGHT * 0.05;
		let t = 0;
		let lastY = HEIGHT * 0.95;
		return segs.map((s, index) => {
			const x0 = (t / total) * WIDTH;
			t += s.time;
			const x1 = (t / total) * WIDTH;
			const y = yOf(s.target);
			const ramp =
				s.ramp === 'fast'
					? `L${x0} ${y}`
					: `C${x0} ${lastY} ${x0} ${y} ${x0 + (x1 - x0) * 0.2} ${y}`;
			const d = `M${x0} ${lastY} ${ramp} L${x1} ${y}`;
			lastY = y;
			return { index, d, flow: s.mode === 'flow' };
		});
	});
</script>

<svg class="spark" viewBox="0 0 {WIDTH} {HEIGHT}" aria-hidden="true">
	{#each phases as phase (phase.index)}
		<path
			d={phase.d}
			class={{ 'is-flow': phase.flow, 'is-active': active }}
			fill="none"
			stroke-width="2"
			stroke-linecap="round"
			stroke-linejoin="round"
			stroke-dasharray={phase.flow ? '3 2.5' : undefined}
		/>
	{/each}
</svg>

<style>
	.spark {
		display: block;
		width: 100%;
		height: 100%;
		overflow: visible;
	}
	path {
		stroke: var(--tel-pressure);
	}
	path.is-flow {
		stroke: var(--tel-flow);
	}
	path.is-active {
		stroke: var(--fg-accent);
	}
</style>
