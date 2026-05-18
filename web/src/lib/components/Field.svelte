<script lang="ts">
	/**
	 * A read-only labelled field: a small label above a monospace value. The
	 * web mirror of the Android shell's `Field` composable. `null` renders as
	 * an em dash, so callers can pass an optional value straight through.
	 */
	let { label, value }: { label: string; value: string | null } = $props();
</script>

<div class="flex flex-col gap-0.5">
	<span class="text-base-content/50 text-xs">{label}</span>
	<span class="font-mono text-sm">{value ?? '—'}</span>
</div>
