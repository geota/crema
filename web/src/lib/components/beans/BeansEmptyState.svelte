<script lang="ts">
	import ListChecksIcon from 'phosphor-svelte/lib/ListChecksIcon';
	import PlusCircleIcon from 'phosphor-svelte/lib/PlusCircleIcon';
	import UploadSimpleIcon from 'phosphor-svelte/lib/UploadSimpleIcon';
	/**
	 * `BeansEmptyState` — first-run state for `/beans`. Three CTA cards:
	 * Quick add, Full editor, Import. The first two go to the new-bean
	 * flow; Import opens the Beanconqueror dialog.
	 */
	let {
		onQuickAdd,
		onFullEditor,
		onImport
	}: {
		onQuickAdd: () => void;
		onFullEditor: () => void;
		onImport: () => void;
	} = $props();
</script>

<div class="bn-empty">
	<div class="bn-empty-mark">
		<svg viewBox="0 0 80 80" width="64" height="64" aria-hidden="true">
			<ellipse
				cx="40"
				cy="42"
				rx="22"
				ry="28"
				fill="none"
				stroke="var(--copper-500)"
				stroke-width="1.5"
			/>
			<path
				d="M40 14 C 32 26, 32 58, 40 70"
				stroke="var(--copper-500)"
				stroke-width="1.5"
				fill="none"
			/>
		</svg>
	</div>
	<div class="bn-empty-title">No beans tracked yet</div>
	<div class="bn-empty-sub">
		Build your library so every shot knows what bag it came from.
		Freshness, profile recommendations, and reorder reminders follow.
	</div>
	<div class="bn-empty-actions">
		<button class="bn-empty-card" onclick={onQuickAdd}>
			<PlusCircleIcon weight="duotone" aria-hidden="true" />
			<div class="bn-empty-card-l">
				<div class="bn-empty-card-title">Quick add</div>
				<div class="bn-empty-card-sub">Name, roaster, roast date — about 10 seconds.</div>
			</div>
		</button>
		<button class="bn-empty-card" onclick={onFullEditor}>
			<ListChecksIcon weight="duotone" aria-hidden="true" />
			<div class="bn-empty-card-l">
				<div class="bn-empty-card-title">Full editor</div>
				<div class="bn-empty-card-sub">All fields — origin, processing, tasting notes.</div>
			</div>
		</button>
		<button class="bn-empty-card" onclick={onImport}>
			<UploadSimpleIcon weight="duotone" aria-hidden="true" />
			<div class="bn-empty-card-l">
				<div class="bn-empty-card-title">Import from Beanconqueror</div>
				<div class="bn-empty-card-sub">Drop a <code>.zip</code> export — roasters, bags, dates.</div>
			</div>
		</button>
	</div>
</div>

<style>
	.bn-empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		padding: 64px 24px;
		gap: 6px;
	}
	.bn-empty-mark {
		opacity: 0.85;
	}
	.bn-empty-title {
		font-family: var(--font-serif);
		font-size: 24px;
		font-weight: 500;
		color: var(--fg-1);
		letter-spacing: -0.01em;
		margin-top: 8px;
	}
	.bn-empty-sub {
		font-family: var(--font-sans);
		font-size: 13px;
		color: rgba(var(--tint-rgb), 0.55);
		max-width: 460px;
		line-height: 1.55;
		margin-bottom: 14px;
	}
	.bn-empty-actions {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 220px));
		gap: 10px;
		justify-content: center;
		margin-top: 8px;
	}
	@media (max-width: 760px) {
		.bn-empty-actions {
			grid-template-columns: minmax(0, 320px);
		}
	}
	.bn-empty-card {
		display: flex;
		align-items: center;
		gap: 10px;
		text-align: left;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-md);
		padding: 14px;
		cursor: pointer;
		color: var(--fg-1);
		font: inherit;
		transition: all var(--dur-1) var(--ease);
	}
	.bn-empty-card:hover {
		background: rgba(var(--tint-rgb), 0.07);
		border-color: rgba(var(--tint-rgb), 0.16);
	}
	.bn-empty-card :global(svg) {
		font-size: 26px;
		color: var(--copper-400);
		flex-shrink: 0;
	}
	.bn-empty-card-l {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.bn-empty-card-title {
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.bn-empty-card-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		line-height: 1.45;
	}
	.bn-empty-card-sub code {
		font-family: var(--font-mono);
		font-size: 10.5px;
		padding: 1px 4px;
		background: rgba(var(--tint-rgb), 0.06);
		border-radius: 4px;
	}
</style>
