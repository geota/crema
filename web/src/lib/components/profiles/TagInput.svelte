<script lang="ts">
	/**
	 * `TagInput` — the custom-tag chip input, ported from `TagList` in
	 * `profile-edit-page.jsx`.
	 *
	 * Shows the current tags as removable chips and an "Add tag" affordance
	 * that becomes a text input with a `<datalist>` of suggestions (tags drawn
	 * from the rest of the library). Enter / blur commits, Escape cancels.
	 */
	let {
		tags,
		suggestions = [],
		onChange
	}: {
		/** The current tag list. */
		tags: string[];
		/** Suggested tags for the datalist — usually the library's other tags. */
		suggestions?: string[];
		/** Called with the next tag list whenever it changes. */
		onChange: (tags: string[]) => void;
	} = $props();

	/** Whether the add-input is showing. */
	let adding = $state(false);
	/** The in-progress tag text. */
	let draft = $state('');
	/** A unique datalist id, so multiple instances do not collide. */
	const listId = `tag-suggestions-${Math.random().toString(36).slice(2, 8)}`;

	/** Commit the draft as a new tag if it is non-empty and not a duplicate. */
	function commit(): void {
		const t = draft.trim();
		if (t && !tags.includes(t)) onChange([...tags, t]);
		draft = '';
		adding = false;
	}

	/** Remove a tag. */
	function remove(t: string): void {
		onChange(tags.filter((x) => x !== t));
	}

	/** Keydown on the add-input: Enter commits, Escape cancels. */
	function onKeydown(event: KeyboardEvent): void {
		if (event.key === 'Enter') {
			event.preventDefault();
			commit();
		} else if (event.key === 'Escape') {
			draft = '';
			adding = false;
		}
	}
</script>

<div class="pe-taglist">
	{#each tags as t (t)}
		<span class="pe-tagchip">
			{t}
			<button class="pe-tagchip-x" onclick={() => remove(t)} aria-label="Remove {t}">
				<i class="ph ph-x" aria-hidden="true"></i>
			</button>
		</span>
	{/each}
	{#if adding}
		<!-- svelte-ignore a11y_autofocus -->
		<input
			class="pe-tag-input"
			bind:value={draft}
			autofocus
			placeholder="New tag…"
			list={listId}
			onblur={commit}
			onkeydown={onKeydown}
		/>
	{:else}
		<button class="pe-tag-add" onclick={() => (adding = true)}>
			<i class="ph ph-plus" aria-hidden="true"></i> Add tag
		</button>
	{/if}
	<datalist id={listId}>
		{#each suggestions.filter((s) => !tags.includes(s)) as s (s)}
			<option value={s}></option>
		{/each}
	</datalist>
</div>

<style>
	.pe-taglist {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
		align-items: center;
	}
	.pe-tagchip {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 5px 4px 5px 10px;
		background: rgba(193, 116, 75, 0.1);
		border: 1px solid rgba(193, 116, 75, 0.35);
		border-radius: var(--radius-pill);
		color: var(--copper-400);
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
	}
	.pe-tagchip-x {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 16px;
		height: 16px;
		background: transparent;
		border: 0;
		color: var(--copper-400);
		border-radius: 50%;
		font-size: 10px;
		cursor: pointer;
		opacity: 0.65;
		transition:
			opacity var(--dur-1) var(--ease),
			background var(--dur-1) var(--ease);
	}
	.pe-tagchip-x:hover {
		opacity: 1;
		background: rgba(193, 116, 75, 0.18);
	}
	.pe-tag-add {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		background: transparent;
		border: 1px dashed rgba(var(--tint-rgb), 0.18);
		border-radius: var(--radius-pill);
		color: rgba(var(--tint-rgb), 0.55);
		font-family: var(--font-sans);
		font-size: 11px;
		padding: 5px 12px;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.pe-tag-add:hover {
		color: var(--fg-1);
		border-color: rgba(var(--tint-rgb), 0.4);
	}
	.pe-tag-add i {
		font-size: 12px;
	}
	.pe-tag-input {
		background: rgba(var(--tint-rgb), 0.05);
		border: 1px solid var(--copper-500);
		outline: 0;
		border-radius: var(--radius-pill);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 11px;
		padding: 5px 12px;
		width: 120px;
	}
</style>
