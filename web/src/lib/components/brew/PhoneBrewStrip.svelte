<script lang="ts" module>
	import type { ProfileSegment } from '#lib/profiles/index.js';

	export interface StripProfileRow {
		id: string;
		name: string;
		/** `1:1.9 · 18.0 g · 90.0 °C`. */
		meta: string;
		segments: readonly ProfileSegment[];
	}

	export interface StripBeanRow {
		id: string;
		/** `Roaster · Bean`. */
		name: string;
		/** `64d off roast · 169.0 g left`. */
		meta: string;
		mark: string;
		tone: string;
	}
</script>

<script lang="ts">
	/**
	 * `PhoneBrewStrip` — the one tappable profile + bean strip on the phone Brew
	 * screen, and the swap dropdown anchored under it (Android `ProfileStrip` +
	 * `SwapDropdown`): the loaded profile and bag lead, each section links to
	 * its library. Presentational; `BrewDashboard` owns the data and handlers.
	 */
	import ArrowRightIcon from 'phosphor-svelte/lib/ArrowRightIcon';
	import ArrowsClockwiseIcon from 'phosphor-svelte/lib/ArrowsClockwiseIcon';
	import CaretDownIcon from 'phosphor-svelte/lib/CaretDownIcon';
	import CheckCircleIcon from 'phosphor-svelte/lib/CheckCircleIcon';
	import { restoreFocus, trapTab } from '#lib/a11y/focus.js';
	import ProfileSpark from './ProfileSpark.svelte';

	let {
		title,
		beanLine,
		specLine = null,
		freshLabel = null,
		freshColor = 'rgba(var(--tint-rgb), 0.4)',
		syncLabel = null,
		profiles,
		beans,
		activeProfileId = null,
		activeBeanId = null,
		onSelectProfile,
		onSelectBean,
		onAllProfiles,
		onAllBeans
	}: {
		title: string;
		/** `Roaster · Bean`, or the empty-state line. */
		beanLine: string;
		/** `Grind 5.9 · Niche`, fainter, on its own row. */
		specLine?: string | null;
		freshLabel?: string | null;
		freshColor?: string;
		/** "Uploading… 3/9" while a profile goes to the DE1. */
		syncLabel?: string | null;
		profiles: readonly StripProfileRow[];
		beans: readonly StripBeanRow[];
		activeProfileId?: string | null;
		activeBeanId?: string | null;
		onSelectProfile: (id: string) => void;
		onSelectBean: (id: string) => void;
		onAllProfiles: () => void;
		onAllBeans: () => void;
	} = $props();

	let open = $state(false);
	let panelEl = $state<HTMLDivElement>();

	$effect(() => {
		if (!open) return;
		const opener = document.activeElement;
		panelEl?.focus({ preventScroll: true });
		return () => {
			const active = document.activeElement;
			if (active == null || active === document.body) restoreFocus(opener);
		};
	});

	function pick(select: (id: string) => void, id: string): void {
		open = false;
		select(id);
	}

	function onKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			e.stopPropagation();
			open = false;
			return;
		}
		if (panelEl) trapTab(e, panelEl);
	}
</script>

<div class="pstrip-wrap">
	<button
		type="button"
		class="pstrip"
		aria-haspopup="dialog"
		aria-expanded={open}
		aria-label="Change profile or bean. {title}, {beanLine}"
		onclick={() => (open = true)}
	>
		<span class="pstrip-text">
			<span class="pstrip-top">
				<span class="pstrip-title">{title}</span>
				{#if syncLabel}
					<span class="pstrip-sync"><ArrowsClockwiseIcon aria-hidden="true" />{syncLabel}</span>
				{:else if freshLabel}
					<span class="pstrip-fresh" style:color={freshColor}>
						<span class="pstrip-pip"></span>{freshLabel}
					</span>
				{/if}
			</span>
			<span class="pstrip-line">{beanLine}</span>
			{#if specLine}<span class="pstrip-line is-spec">{specLine}</span>{/if}
		</span>
		<CaretDownIcon aria-hidden="true" />
	</button>

	{#if open}
		<button
			type="button"
			class="pstrip-scrim"
			tabindex="-1"
			aria-hidden="true"
			onclick={() => (open = false)}
		></button>
		<div
			bind:this={panelEl}
			class="pstrip-dd"
			role="dialog"
			aria-label="Choose profile and bean"
			tabindex="-1"
			onkeydown={onKeydown}
		>
			<div class="pdd-head">
				<h2 class="pdd-title">Profile</h2>
				<button type="button" class="pdd-link" onclick={() => pick(onAllProfiles, '')}>
					All profiles <ArrowRightIcon aria-hidden="true" />
				</button>
			</div>
			{#each profiles as p (p.id)}
				{@const active = p.id === activeProfileId}
				<button
					type="button"
					class={['pdd-row', { 'is-active': active }]}
					aria-pressed={active}
					onclick={() => pick(onSelectProfile, p.id)}
				>
					<span class="pdd-spark"><ProfileSpark segments={p.segments} {active} /></span>
					<span class="pdd-text">
						<span class="pdd-name">{p.name}</span>
						<span class="pdd-meta">{p.meta}</span>
					</span>
					{#if active}<CheckCircleIcon aria-hidden="true" />{/if}
				</button>
			{/each}

			<div class="pdd-rule" aria-hidden="true"></div>

			<div class="pdd-head">
				<h2 class="pdd-title">Bean</h2>
				<button type="button" class="pdd-link" onclick={() => pick(onAllBeans, '')}>
					All beans <ArrowRightIcon aria-hidden="true" />
				</button>
			</div>
			{#each beans as b (b.id)}
				{@const active = b.id === activeBeanId}
				<button
					type="button"
					class={['pdd-row', { 'is-active': active }]}
					aria-pressed={active}
					onclick={() => pick(onSelectBean, b.id)}
				>
					<span class="pdd-av" style:background={b.tone}>{b.mark}</span>
					<span class="pdd-text">
						<span class="pdd-name">{b.name}</span>
						<span class="pdd-meta">{b.meta}</span>
					</span>
					{#if active}<CheckCircleIcon aria-hidden="true" />{/if}
				</button>
			{:else}
				<p class="pdd-empty">No beans yet — add a bag in Beans.</p>
			{/each}
		</div>
	{/if}
</div>

<style>
	.pstrip-wrap {
		position: relative;
		flex: none;
	}
	.pstrip {
		display: flex;
		align-items: center;
		gap: 10px;
		width: 100%;
		padding: 10px 14px;
		border: 0;
		border-radius: var(--radius-md);
		background: var(--bg-surface);
		color: var(--fg-3);
		font: inherit;
		text-align: left;
		cursor: pointer;
	}
	.pstrip > :global(svg) {
		flex: none;
		font-size: 18px;
	}
	.pstrip:focus-visible,
	.pdd-row:focus-visible,
	.pdd-link:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.pstrip-text {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.pstrip-top {
		display: flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
	}
	.pstrip-title {
		flex: 1 1 auto;
		min-width: 0;
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 17px;
		font-weight: 500;
		line-height: 1.3;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.pstrip-fresh,
	.pstrip-sync {
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
		white-space: nowrap;
	}
	.pstrip-sync {
		color: var(--fg-accent);
	}
	.pstrip-pip {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: currentColor;
	}
	.pstrip-line {
		overflow: hidden;
		font-family: var(--font-mono);
		font-size: 12.5px;
		line-height: 1.35;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.pstrip-line.is-spec {
		color: var(--fg-muted);
	}

	.pstrip-scrim {
		position: fixed;
		inset: 0;
		z-index: 40;
		border: 0;
		padding: 0;
		background: transparent;
	}
	.pstrip-dd {
		position: absolute;
		top: calc(100% + 8px);
		left: 0;
		right: 0;
		z-index: 41;
		max-height: min(560px, calc(100dvh - var(--ptop-clear) - var(--ptab-clear) - 92px));
		overflow-y: auto;
		overscroll-behavior: contain;
		padding: 8px;
		border-radius: 20px;
		background: var(--bg-surface-2);
		box-shadow: var(--shadow-lg);
		outline: none;
	}
	.pdd-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 8px 8px 6px;
	}
	.pdd-title {
		margin: 0;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 600;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	.pdd-link {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		min-height: 44px;
		margin: -12px -4px -12px 0;
		padding: 0 4px;
		border: 0;
		border-radius: 6px;
		background: transparent;
		color: var(--fg-accent);
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 500;
		cursor: pointer;
	}
	.pdd-row {
		display: flex;
		align-items: center;
		gap: 11px;
		width: 100%;
		min-height: 44px;
		padding: 6px 8px;
		border: 0;
		border-radius: 12px;
		background: transparent;
		color: var(--fg-1);
		font: inherit;
		text-align: left;
		cursor: pointer;
	}
	.pdd-row.is-active {
		background: rgba(var(--copper-rgb), 0.18);
	}
	.pdd-row > :global(svg) {
		flex: none;
		font-size: 18px;
		color: var(--fg-accent);
	}
	.pdd-spark {
		flex: none;
		width: 44px;
		height: 26px;
		padding: 3px;
		border-radius: 7px;
		background: var(--bg-page);
	}
	.pdd-av {
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 30px;
		height: 30px;
		border-radius: 8px;
		color: #1f140b;
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
	}
	.pdd-text {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.pdd-name {
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 500;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.pdd-meta {
		overflow: hidden;
		font-family: var(--font-mono);
		font-size: 11px;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.pdd-rule {
		height: 1px;
		margin: 6px 8px;
		background: rgba(var(--tint-rgb), 0.12);
	}
	.pdd-empty {
		margin: 0;
		padding: 8px 10px;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-3);
	}
</style>
