<script lang="ts">
	/**
	 * `PhoneTabBar` — the phone-width primary nav (≤ 600 px), replacing the
	 * 72 px rail: Brew · Scale · Profiles · Beans · History, the order of
	 * Android's `CremaPhoneDestinations`. Plain page links with `aria-current`,
	 * not a `tablist`: these navigate, they don't switch in-page panels.
	 *
	 * Rendered on every width and hidden above 600 px by CSS, so the rail is
	 * the only Primary nav in the a11y tree there.
	 */
	import Icon from '#lib/icons/Icon.svelte';
	import { page } from '$app/state';
	import { PHONE_TABS, tabIsActive } from './nav-items';
</script>

<nav class="ptab" aria-label="Primary">
	{#each PHONE_TABS as t (t.id)}
		{@const active = tabIsActive(t, page.url.pathname)}
		<a
			class={['ptab-item', { 'is-active': active }]}
			href={t.href}
			aria-current={active ? 'page' : undefined}
		>
			<span class="ptab-ind">
				<!-- Brew stays outlined when active; the others fill (Android). -->
				<Icon
					cls={(active && t.id !== 'brew' ? 'ph-fill' : 'ph-duotone') + ' ph-' + t.icon}
					aria-hidden="true"
				/>
			</span>
			<span class="ptab-label">{t.label}</span>
		</a>
	{/each}
</nav>

<style>
	.ptab {
		display: none;
	}
	@media (max-width: 600px) {
		.ptab {
			position: fixed;
			left: 0;
			right: 0;
			bottom: 0;
			z-index: 50;
			display: grid;
			grid-template-columns: repeat(5, minmax(0, 1fr));
			height: var(--ptab-clear);
			padding: 0 env(safe-area-inset-right, 0px) env(safe-area-inset-bottom, 0px)
				env(safe-area-inset-left, 0px);
			background: var(--bg-page);
			border-top: 1px solid rgba(var(--tint-rgb), 0.06);
		}
	}
	.ptab-item {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 3px;
		min-width: 0;
		text-decoration: none;
		color: var(--fg-muted);
		font-family: var(--font-sans);
		font-size: 11px;
		letter-spacing: 0.02em;
		-webkit-tap-highlight-color: transparent;
		transition: color var(--dur-1) var(--ease);
	}
	.ptab-ind {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 56px;
		height: 30px;
		border-radius: var(--radius-pill);
		transition: background var(--dur-1) var(--ease);
	}
	.ptab-ind :global(svg) {
		font-size: 22px;
	}
	.ptab-item.is-active {
		color: var(--fg-1);
	}
	.ptab-item.is-active .ptab-ind {
		background: rgba(var(--copper-rgb), 0.14);
		color: var(--fg-accent);
	}
	.ptab-item:focus-visible {
		outline: none;
	}
	.ptab-item:focus-visible .ptab-ind {
		box-shadow: var(--shadow-focus);
	}
	.ptab-label {
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	@media (max-width: 340px) {
		.ptab-ind {
			width: 48px;
		}
		.ptab-item {
			font-size: 10.5px;
		}
	}
</style>
