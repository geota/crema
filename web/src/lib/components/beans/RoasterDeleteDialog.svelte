<script lang="ts">
	/**
	 * `RoasterDeleteDialog` — confirm dialog with two danger paths for
	 * deleting a roaster that has linked beans.
	 *
	 *  - **Detach beans** (default): library.deleteRoaster — strips
	 *    the roaster, sets bean.roasterId=null on every linked bean,
	 *    fires Visualizer DELETE on the roaster. Beans survive,
	 *    display as "No roaster" until re-linked.
	 *  - **Delete beans too**: library.deleteRoasterAndBeans —
	 *    cascades into the beans, soft-deleting each (so Visualizer
	 *    tombstones propagate). Destructive; high-value data goes.
	 *
	 * For roasters with zero linked beans we render a normal one-
	 * button confirm — there's nothing to cascade.
	 */
	import { getBeanStore } from '$lib/bean';

	let {
		roasterId,
		roasterName,
		linkedBeanCount,
		onClose,
		onDeleted
	}: {
		roasterId: string;
		roasterName: string;
		linkedBeanCount: number;
		onClose: () => void;
		onDeleted: () => void;
	} = $props();

	const library = getBeanStore();

	function detach(): void {
		library.deleteRoaster(roasterId);
		onDeleted();
	}
	function cascade(): void {
		library.deleteRoasterAndBeans(roasterId);
		onDeleted();
	}
</script>

<div
	class="rdd-scrim"
	onclick={onClose}
	onkeydown={(e) => e.key === 'Escape' && onClose()}
	role="button"
	tabindex="-1"
	aria-label="Close dialog"
></div>

<div class="rdd-modal" role="dialog" aria-labelledby="rdd-title" aria-describedby="rdd-body">
	<header class="rdd-head">
		<h2 class="rdd-title" id="rdd-title">Delete {roasterName || 'roaster'}?</h2>
		<button class="rdd-close" onclick={onClose} aria-label="Close">
			<i class="ph ph-x" aria-hidden="true"></i>
		</button>
	</header>

	<div class="rdd-body" id="rdd-body">
		{#if linkedBeanCount === 0}
			<p>No beans link to this roaster. Deleting is safe.</p>
		{:else}
			<p>
				<strong>{linkedBeanCount} bean{linkedBeanCount === 1 ? '' : 's'}</strong>
				link{linkedBeanCount === 1 ? 's' : ''} to this roaster.
			</p>
			<p class="rdd-help">
				Detach keeps the beans (they'll show as "No roaster" until you
				re-link them). Delete them too if they're junk you also want
				gone — that one can't be undone.
			</p>
		{/if}
	</div>

	<div class="rdd-foot">
		<button class="rdd-btn" onclick={onClose}>Cancel</button>
		{#if linkedBeanCount === 0}
			<button class="rdd-btn rdd-btn-danger" onclick={detach}>Delete roaster</button>
		{:else}
			<button class="rdd-btn rdd-btn-primary" onclick={detach}>
				Detach {linkedBeanCount} bean{linkedBeanCount === 1 ? '' : 's'} (keep them)
			</button>
			<button class="rdd-btn rdd-btn-danger" onclick={cascade}>
				Delete roaster + {linkedBeanCount} bean{linkedBeanCount === 1 ? '' : 's'}
			</button>
		{/if}
	</div>
</div>

<style>
	.rdd-scrim {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.5);
		z-index: 70;
	}
	.rdd-modal {
		position: fixed;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		width: min(520px, calc(100vw - 32px));
		max-height: calc(100vh - 64px);
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-lg, 14px);
		z-index: 71;
		display: flex;
		flex-direction: column;
		overflow: hidden;
	}
	.rdd-head {
		display: flex;
		justify-content: space-between;
		align-items: center;
		padding: 16px 20px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.rdd-title {
		font-family: var(--font-sans);
		font-size: 16px;
		font-weight: 600;
		margin: 0;
		color: var(--fg-1);
	}
	.rdd-close {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		cursor: pointer;
		padding: 4px;
	}
	.rdd-body {
		padding: 20px;
		font-family: var(--font-sans);
		font-size: 14px;
		color: var(--fg-1);
		line-height: 1.55;
	}
	.rdd-body p {
		margin: 0 0 12px;
	}
	.rdd-body p:last-child {
		margin-bottom: 0;
	}
	.rdd-help {
		font-size: 13px;
		color: rgba(var(--tint-rgb), 0.7);
	}
	.rdd-foot {
		display: flex;
		flex-wrap: wrap;
		justify-content: flex-end;
		gap: 8px;
		padding: 14px 20px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.rdd-btn {
		padding: 8px 14px;
		border-radius: var(--radius-md, 10px);
		border: 1px solid rgba(var(--tint-rgb), 0.15);
		background: transparent;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		cursor: pointer;
	}
	.rdd-btn:hover {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.rdd-btn-primary {
		background: var(--copper-400);
		border-color: var(--copper-400);
		color: #fff;
	}
	.rdd-btn-primary:hover {
		filter: brightness(0.96);
		background: var(--copper-400);
	}
	.rdd-btn-danger {
		background: transparent;
		border-color: var(--danger);
		color: var(--danger);
	}
	.rdd-btn-danger:hover {
		background: color-mix(in srgb, var(--danger) 12%, transparent);
	}
</style>
