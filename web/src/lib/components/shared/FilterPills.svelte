<script lang="ts">
	/**
	 * `FilterPills` — a horizontal row of selectable filter pills, the shared
	 * surface for filter-chip rails across the app.
	 *
	 * Visual is 1:1 with the `/profiles` library `.pp-tag` rail (see
	 * `styles/profiles-page.css`): no border in the resting state, a soft
	 * tinted fill when active, and a tabular `.pp-tag-count` badge that flips
	 * to the copper accent when its parent pill is active.
	 *
	 * Pills can be grouped by callers — pass `pills` as a flat list and use
	 * `divider: true` or `groupLabel: '…'` entries to render the existing
	 * `.pp-tag-divider` / `.pp-tag-grouplabel` separators verbatim.
	 */
	interface FilterPillItem {
		/** Stable key (also passed to `onclick`). */
		id: string;
		label?: string;
		count?: number;
		selected?: boolean;
		/** Optional Phosphor icon name (with the `ph ph-` prefix preserved). */
		icon?: string;
		/** Optional inline-style override applied to the leading icon. */
		iconStyle?: string;
		/** Render this entry as a vertical divider rather than a button. */
		divider?: boolean;
		/** Render this entry as an all-caps group label rather than a button. */
		groupLabel?: string;
		/** Tag-style copper accent (used for user-defined tags). */
		custom?: boolean;
		/** Optional tooltip text. */
		title?: string;
	}

	let {
		pills,
		onclick
	}: {
		pills: FilterPillItem[];
		onclick: (id: string) => void;
	} = $props();
</script>

<div class="pp-tags">
	{#each pills as p (p.id)}
		{#if p.divider}
			<span class="pp-tag-divider"></span>
		{:else if p.groupLabel}
			<span class="pp-tag-grouplabel">{p.groupLabel}</span>
		{:else}
			<button
				type="button"
				class="pp-tag"
				class:pp-tag-custom={p.custom}
				class:is-active={p.selected}
				title={p.title}
				onclick={() => onclick(p.id)}
			>
				{#if p.icon}
					<i class={p.icon} style={p.iconStyle} aria-hidden="true"></i>
				{/if}
				{#if p.label}<span>{p.label}</span>{/if}
				{#if p.count != null}<span class="pp-tag-count">{p.count}</span>{/if}
			</button>
		{/if}
	{/each}
</div>

<!-- `.pp-tag*` rules live in `styles/profiles-page.css` so this component
     stays purely structural; no local styles needed. -->
