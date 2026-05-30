<script lang="ts">
	/**
	 * `RoasterCard` — one roastery row in the `/beans` Roasters directory.
	 *
	 * Built on top of {@link LibraryCardShell}, so the grid template, the
	 * action-row layout, and the focus / hover affordances match the bean
	 * tile next door. The shell pins the action row to the bottom of the
	 * card (was floating up the middle in the previous implementation)
	 * and lets metrics absorb extra vertical space so a row of cards
	 * shares a baseline.
	 *
	 * Roaster cards skip the favourite star (no "favourite roaster"
	 * concept) by passing `favourite={null}` to the shell.
	 *
	 * Per-card affordances:
	 *   • Logo or deterministic mark in the avatar slot.
	 *   • Name + optional dup badge in the head slot.
	 *   • Location · website · bag count in the metrics slot.
	 *   • Optional un-merge (visible on canonical-tagged dupes) + the
	 *     standard duplicate / edit / delete trio in the actions slot.
	 */
	import { roasterMarkTone, type Roaster } from '$lib/bean';
	import LibraryCardShell from '$lib/components/shared/LibraryCardShell.svelte';
	import RoasterDeleteSplit from './RoasterDeleteSplit.svelte';

	let {
		roaster,
		count,
		dupOf,
		onOpen,
		onEdit,
		onDuplicate,
		onUnmerge
	}: {
		roaster: Roaster;
		count: number;
		/** Non-null when this roaster row is tagged as a duplicate of
		 *  another. Drives the inline badge + the un-merge button. */
		dupOf: Roaster | null;
		/** Card click — opens the editor (mirrors the bean tile). */
		onOpen: (id: string) => void;
		onEdit: (id: string) => void;
		onDuplicate: (id: string) => void;
		onUnmerge: (id: string) => void;
	} = $props();

	const mt = $derived(roasterMarkTone(roaster));

	const websiteDomain = $derived.by<string | null>(() => {
		if (!roaster.website) return null;
		try {
			return new URL(roaster.website).hostname.replace(/^www\./, '');
		} catch {
			return roaster.website;
		}
	});

	function onCardClick(): void {
		// Card click opens the editor — mirrors BeanTile's "click anywhere
		// outside the action row" behaviour.
		onEdit(roaster.id);
	}
	function onEditClick(e: MouseEvent): void {
		e.stopPropagation();
		onEdit(roaster.id);
	}
	function onDuplicateClick(e: MouseEvent): void {
		e.stopPropagation();
		onDuplicate(roaster.id);
	}
	function onUnmergeClick(e: MouseEvent): void {
		e.stopPropagation();
		onUnmerge(roaster.id);
	}
</script>

<LibraryCardShell
	cardKind="roaster"
	favourite={null}
	{onCardClick}
	ariaLabel={`Open ${roaster.name}`}
	isArchived={dupOf != null}
	minHeight={180}
>
	{#snippet avatar()}
		<div class="rcd-mark" style="--tone: {mt.tone}">
			{#if roaster.imageUrl}
				<!-- Logo: 88×88 thumbnail. `object-fit: cover` keeps a
				     non-square logo from squashing; an `onerror` handler
				     swaps in the deterministic mark on a broken URL so the
				     card never shows a sad-cloud glyph. -->
				<img
					class="rcd-logo"
					src={roaster.imageUrl}
					alt=""
					onerror={(e) => {
						const img = e.currentTarget as HTMLImageElement;
						img.style.display = 'none';
						const mark = img.nextElementSibling as HTMLElement | null;
						if (mark) mark.style.display = 'flex';
					}}
				/>
				<span class="rcd-mono" style="display: none">{mt.mark}</span>
			{:else}
				<span class="rcd-mono">{mt.mark}</span>
			{/if}
		</div>
	{/snippet}

	{#snippet head()}
		<div class="rcd-name">
			{roaster.name}
			{#if dupOf}
				<span
					class="rcd-dup-badge"
					title="Tagged as a duplicate of {dupOf.name}"
				>
					duplicate of {dupOf.name}
				</span>
			{/if}
		</div>
		<div class="rcd-loc">
			{#if roaster.city && roaster.country}
				{roaster.city} · {roaster.country}
			{:else if roaster.city}
				{roaster.city}
			{:else if roaster.country}
				{roaster.country}
			{:else}
				&mdash;
			{/if}
		</div>
		<div class="rcd-meta">
			{#if websiteDomain && roaster.website}
				<a
					class="rcd-site"
					href={roaster.website}
					target="_blank"
					rel="noopener noreferrer"
					onclick={(e) => e.stopPropagation()}
					title={roaster.website}
				>
					<i class="ph ph-globe"></i>{websiteDomain}
				</a>
			{/if}
			<span class="rcd-count">
				{count} bag{count === 1 ? '' : 's'}
			</span>
		</div>
	{/snippet}

	{#snippet actions()}
		{#if dupOf}
			<!-- Un-merge — only visible on dupe-tagged rows. Clearing the
			     canonical pointer restores the row to the directory. -->
			<button
				type="button"
				class="lcs-action-icon"
				title="Un-merge (restore as standalone roaster)"
				aria-label="Un-merge"
				onclick={onUnmergeClick}
			>
				<i class="ph ph-link-break"></i>
			</button>
		{/if}
		<button
			type="button"
			class="lcs-action lcs-action-primary"
			onclick={onEditClick}
		>
			<i class="ph ph-pencil" aria-hidden="true"></i>
			<span>Edit</span>
		</button>
		<button
			type="button"
			class="lcs-action-icon"
			title="Duplicate"
			aria-label="Duplicate"
			onclick={onDuplicateClick}
		>
			<i class="ph ph-copy"></i>
		</button>
		<!-- Delete: compact danger split — primary detaches bags + deletes the
		     roaster locally; the caret menu adds cascade and Visualizer scopes. -->
		<RoasterDeleteSplit
			roasterId={roaster.id}
			roasterName={roaster.name}
			linkedBeanCount={count}
			size="sm"
		/>
	{/snippet}
</LibraryCardShell>

<style>
	/* Avatar — gradient block holds either a logo `<img>` or a fallback
	   monogram. Mirrors `.bn-tile-avatar` so the two cards share a
	   visual idiom; the size hugs the shell's 88×88 avatar slot. */
	.rcd-mark {
		position: relative;
		width: 88px;
		height: 88px;
		border-radius: var(--radius-md, 12px);
		background:
			linear-gradient(135deg, color-mix(in srgb, var(--tone) 88%, transparent), var(--tone)),
			var(--tone);
		display: inline-flex;
		align-items: center;
		justify-content: center;
		overflow: hidden;
		flex-shrink: 0;
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.1);
	}
	.rcd-logo {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}
	.rcd-mono {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 100%;
		height: 100%;
		color: rgba(255, 255, 255, 0.92);
		font-family: var(--font-serif);
		font-weight: 500;
		font-size: 32px;
		letter-spacing: -0.02em;
		text-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
	}

	/* Head slot — name + dup badge + location + meta row. */
	.rcd-name {
		font-family: var(--font-serif);
		font-size: 17px;
		font-weight: 500;
		color: var(--fg-1);
		letter-spacing: -0.01em;
		line-height: 1.2;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.rcd-dup-badge {
		display: inline-block;
		margin-left: 6px;
		padding: 1px 6px;
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: var(--warning);
		background: rgba(var(--warning-rgb), 0.12);
		border-radius: var(--radius-pill);
		vertical-align: middle;
	}
	.rcd-loc {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		margin-top: 2px;
	}
	.rcd-meta {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		margin-top: 4px;
		font-family: var(--font-sans);
		font-size: 10.5px;
		color: rgba(var(--tint-rgb), 0.5);
		align-items: center;
	}
	.rcd-site {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: rgba(var(--tint-rgb), 0.5);
		text-decoration: none;
	}
	.rcd-site:hover {
		color: var(--copper-400);
		text-decoration: underline;
	}
	.rcd-site i {
		font-size: 11px;
	}
	.rcd-count {
		font-family: var(--font-mono);
		color: var(--copper-400);
	}
</style>
