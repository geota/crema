<script lang="ts">
	/**
	 * The one mark a brew method wears everywhere it appears — history
	 * rows, method chips, detail headers (issue #10). Phosphor icons,
	 * direct-imported (tree-shaken); unknown / free-text methods fall
	 * back to the coffee bean.
	 *
	 * Two shapes: `tile` (a 30px sunken tile, the row's sparkline-slot
	 * stand-in) and `inline` (bare glyph for chips and headers).
	 */
	import type { Component } from 'svelte';
	import Coffee from 'phosphor-svelte/lib/Coffee';
	import CoffeeBean from 'phosphor-svelte/lib/CoffeeBean';
	import Cylinder from 'phosphor-svelte/lib/Cylinder';
	import Drop from 'phosphor-svelte/lib/Drop';
	import Flask from 'phosphor-svelte/lib/Flask';
	import Funnel from 'phosphor-svelte/lib/Funnel';
	import FunnelSimple from 'phosphor-svelte/lib/FunnelSimple';
	import Hourglass from 'phosphor-svelte/lib/Hourglass';
	import Jar from 'phosphor-svelte/lib/Jar';
	import Snowflake from 'phosphor-svelte/lib/Snowflake';

	let {
		method = null,
		tile = false,
		size = 15
	}: {
		/** Normalized method string; `null` = machine espresso. */
		method?: string | null;
		/** Render as the 30px sunken row tile instead of a bare glyph. */
		tile?: boolean;
		/** Glyph size, px (bare form; the tile fixes its own). */
		size?: number;
	} = $props();

	const ICONS: Record<string, Component> = {
		espresso: Coffee,
		pourover: Funnel,
		aeropress: Cylinder,
		french_press: Jar,
		moka: Hourglass,
		cold_brew: Snowflake,
		drip: Drop,
		siphon: Flask,
		clever: FunnelSimple
	};

	let Icon = $derived(ICONS[method?.trim().toLowerCase() ?? 'espresso'] ?? CoffeeBean);
</script>

{#if tile}
	<span class="mark-tile" aria-hidden="true">
		<Icon size={15} weight="regular" />
	</span>
{:else}
	<Icon {size} weight="regular" aria-hidden="true" />
{/if}

<style>
	.mark-tile {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 30px;
		height: 30px;
		border-radius: var(--radius-sm, 8px);
		background: var(--bg-sunken, rgba(31, 24, 18, 0.05));
		border: 1px solid var(--hairline, rgba(31, 24, 18, 0.08));
		color: var(--fg-accent, #a55f2a);
		flex: none;
	}
</style>
