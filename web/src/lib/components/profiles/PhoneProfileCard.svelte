<script lang="ts">
	/**
	 * `PhoneProfileCard` — a profile in the phone library (Android
	 * `PhoneProfileCard`): name and source, the Loaded badge and star, the curve,
	 * the roast pill and four figures, then Load on Brew, edit and ⋮.
	 *
	 * The same data and handlers as the wide `ProfileCard`; what the wide card
	 * keeps in its icon row is in the page's ⋮ sheet.
	 */
	import ArrowLineDownIcon from 'phosphor-svelte/lib/ArrowLineDownIcon';
	import CheckCircleIcon from 'phosphor-svelte/lib/CheckCircleIcon';
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import StarIcon from 'phosphor-svelte/lib/StarIcon';
	import { preinfusionSeconds, ratioLabel, type CremaProfile } from '#lib/profiles/index.js';
	import { stepCapNote } from '#lib/profiles/step-cap.js';
	import { convertTemp, convertWeight, getSettingsStore } from '#lib/settings/index.js';
	import PhoneCardActions from '#lib/shell/PhoneCardActions.svelte';
	import ProfilePreview from './ProfilePreview.svelte';

	let {
		profile,
		active = false,
		onLoad,
		onEdit,
		onTogglePin,
		onMenu
	}: {
		profile: CremaProfile;
		active?: boolean;
		onLoad: (id: string) => void;
		onEdit: (id: string) => void;
		onTogglePin: (id: string) => void;
		onMenu: (id: string) => void;
	} = $props();

	const settings = getSettingsStore();
	const name = $derived(profile.name || 'Untitled profile');
	const stepNote = $derived(stepCapNote(profile.segments.length));
	const preinf = $derived(Math.round(preinfusionSeconds(profile)));
	const dose = $derived(convertWeight(profile.dose, settings.current.weightUnit));
	const temp = $derived(convertTemp(profile.brewTemp, settings.current.tempUnit));
	const metrics = $derived([
		{ label: 'Ratio', value: ratioLabel(profile) },
		{ label: 'Dose', value: `${dose.value}${dose.unit}` },
		{ label: 'Temp', value: `${temp.value}${temp.unit}` },
		{ label: 'Pre', value: `${preinf}s` }
	]);
</script>

<article class={['ppc', { 'is-loaded': active }]} data-scroll-key={profile.id}>
	<div class="ppc-head">
		<div class="ppc-names">
			<div class="ppc-name">{name}</div>
			<div class={['ppc-sub', { 'is-warn': stepNote }]}>
				{stepNote ?? (profile.source === 'builtin' ? 'Built-in profile' : 'Custom profile')}
			</div>
		</div>
		{#if active}
			<span class="ppc-badge"><CheckIcon weight="bold" aria-hidden="true" /> Loaded</span>
		{/if}
		<button
			type="button"
			class={['ppc-star', { 'is-on': profile.pinned }]}
			aria-label={profile.pinned ? `Unpin ${name}` : `Pin ${name}`}
			aria-pressed={profile.pinned}
			onclick={() => onTogglePin(profile.id)}
		>
			<StarIcon weight={profile.pinned ? 'fill' : 'regular'} aria-hidden="true" />
		</button>
	</div>

	<div class="ppc-curve">
		<ProfilePreview segments={profile.segments} {preinf} {active} plan />
	</div>

	<div class="ppc-foot">
		{#if profile.roast != null}<span class="ppc-roast">{profile.roast}</span>{/if}
		<div class="ppc-metrics">
			{#each metrics as m (m.label)}
				<div class="ppc-metric">
					<span class="ppc-metric-v">{m.value}</span>
					<span class="ppc-metric-l">{m.label}</span>
				</div>
			{/each}
		</div>
	</div>

	<PhoneCardActions
		label={active ? 'Loaded on Brew' : 'Load on Brew'}
		icon={active ? CheckCircleIcon : ArrowLineDownIcon}
		on={active}
		{name}
		onprimary={() => !active && onLoad(profile.id)}
		onedit={() => onEdit(profile.id)}
		onmenu={() => onMenu(profile.id)}
	/>
</article>

<style>
	.ppc {
		display: flex;
		flex-direction: column;
		gap: 11px;
		padding: 14px 16px 12px;
		border-radius: 16px;
		background: var(--bg-surface);
	}
	.ppc.is-loaded {
		background: color-mix(in srgb, var(--copper-500) 8%, var(--bg-surface));
		box-shadow: inset 0 0 0 1.5px var(--copper-500);
	}
	.ppc-head {
		display: flex;
		align-items: flex-start;
		gap: 4px;
	}
	.ppc-names {
		flex: 1 1 auto;
		min-width: 0;
	}
	.ppc-name {
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 17px;
		font-weight: 500;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.ppc-sub {
		display: -webkit-box;
		margin-top: 2px;
		overflow: hidden;
		-webkit-box-orient: vertical;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-3);
	}
	.ppc-sub.is-warn {
		color: var(--danger);
	}
	.ppc-badge {
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 5px;
		height: 24px;
		margin-top: 2px;
		padding: 0 10px;
		border-radius: 999px;
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-family: var(--font-sans);
		font-size: 10.5px;
		font-weight: 600;
		letter-spacing: 0.04em;
		text-transform: uppercase;
	}
	.ppc-badge :global(svg) {
		font-size: 11px;
	}
	.ppc-star {
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 44px;
		height: 44px;
		margin: -8px -10px -8px 0;
		border: 0;
		border-radius: 50%;
		background: transparent;
		color: var(--fg-3);
		font-size: 20px;
		cursor: pointer;
	}
	.ppc-star.is-on {
		color: var(--fg-accent);
	}
	.ppc-star:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.ppc-curve {
		height: 64px;
		overflow: hidden;
		border-radius: 12px;
		background: var(--ph-lowest);
	}
	.ppc-foot {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
	}
	.ppc-roast {
		flex: none;
		display: inline-flex;
		align-items: center;
		height: 24px;
		padding: 0 10px;
		border-radius: 999px;
		background: rgba(var(--copper-rgb), 0.12);
		color: var(--fg-accent);
		font-family: var(--font-sans);
		font-size: 10.5px;
		font-weight: 600;
		letter-spacing: 0.03em;
		text-transform: uppercase;
	}
	.ppc-metrics {
		display: flex;
		gap: 14px;
		margin-left: auto;
		min-width: 0;
	}
	.ppc-metric {
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		gap: 1px;
	}
	.ppc-metric-v {
		font-family: var(--font-mono);
		font-size: 14px;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
		line-height: 15px;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.ppc-metric-l {
		font-family: var(--font-sans);
		font-size: 8px;
		font-weight: 600;
		letter-spacing: 0.06em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	@media (max-width: 359px) {
		.ppc-metrics {
			gap: 10px;
		}
		.ppc-metric-v {
			font-size: 12.5px;
		}
	}
</style>
