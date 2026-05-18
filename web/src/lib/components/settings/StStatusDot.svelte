<script lang="ts">
	/**
	 * `StStatusDot` — a small status dot + label, green when `ok`, muted grey
	 * otherwise. Ported from the design's `StStatusDot`.
	 */
	let { ok, label }: { ok: boolean; label: string } = $props();
</script>

<span class="st-statusdot">
	<span class="st-statusdot-d" class:off={!ok}></span>
	<span>{label}</span>
</span>
