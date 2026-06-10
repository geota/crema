<script lang="ts">
	/**
	 * `ConfirmDialog` — the single host for {@link confirmDialog} /
	 * {@link promptDialog} (FD4). Mounted once in the root layout; renders the
	 * active request as an in-app modal patterned on `MainsConfirmModal` (scrim,
	 * `role="dialog"`, `aria-modal`, Escape / scrim cancel, the shared
	 * `st-btn` / `st-btn-danger` button classes). For a prompt it shows a text
	 * input (auto-focused, Enter confirms).
	 */
	import { getActiveDialog, resolveActive } from './confirm-dialog.svelte';

	const dialog = $derived(getActiveDialog());

	let typed = $state('');
	let inputEl = $state<HTMLInputElement | null>(null);

	/** The confirm gate: prompts are always submittable; a `requireTyped`
	 *  confirm arms only on an exact (trimmed) match. */
	const confirmArmed = $derived.by(() => {
		const d = dialog;
		if (!d || d.kind === 'prompt') return true;
		const expected = d.options.requireTyped;
		return expected == null || typed.trim() === expected;
	});

	// Seed (prompt) / clear the input each time a dialog opens, and focus the
	// prompt input so the user can type immediately.
	$effect(() => {
		const d = dialog;
		if (!d) return;
		typed = d.kind === 'prompt' ? (d.options.initialValue ?? '') : '';
		if (d.kind === 'prompt' || d.options.requireTyped != null) {
			// Focus after the input renders.
			queueMicrotask(() => inputEl?.focus());
		}
	});

	function confirm(): void {
		const d = dialog;
		if (!d || !confirmArmed) return;
		resolveActive(d.kind === 'prompt' ? typed : true);
	}
	function cancel(): void {
		resolveActive(dialog?.kind === 'prompt' ? null : false);
	}

	function onKeydown(e: KeyboardEvent): void {
		// Only intercept Escape while a dialog is open, so the document-level
		// handler never swallows Escape for the rest of the app.
		if (!dialog) return;
		if (e.key === 'Escape') {
			e.stopPropagation();
			cancel();
		}
	}
</script>

<svelte:document onkeydown={onKeydown} />

{#if dialog}
	<div
		class="cd-scrim"
		role="presentation"
		onclick={cancel}
		onkeydown={(e) => {
			if (e.key === 'Enter' || e.key === ' ') cancel();
		}}
	>
		<div
			class="cd-panel"
			role="dialog"
			aria-modal="true"
			aria-label={dialog.options.title ?? dialog.options.message}
			tabindex="-1"
			onclick={(e) => e.stopPropagation()}
			onkeydown={(e) => e.stopPropagation()}
		>
			{#if dialog.options.title}
				<h2 class="cd-title">{dialog.options.title}</h2>
			{/if}
			<p class="cd-message">{dialog.options.message}</p>

			{#if dialog.kind === 'prompt' || dialog.options.requireTyped != null}
				<input
					bind:this={inputEl}
					bind:value={typed}
					class="cd-input"
					type="text"
					autocomplete="off"
					placeholder={dialog.kind === 'prompt'
						? (dialog.options.placeholder ?? '')
						: `Type \u201c${dialog.options.requireTyped}\u201d to confirm`}
					aria-label={dialog.options.message}
					onkeydown={(e) => {
						if (e.key === 'Enter') {
							e.preventDefault();
							confirm();
						}
					}}
				/>
			{/if}

			<div class="cd-actions">
				<button type="button" class="st-btn st-btn-secondary" onclick={cancel}>
					{dialog.options.cancelLabel ?? 'Cancel'}
				</button>
				<button
					type="button"
					class={['st-btn', dialog.options.danger ? 'st-btn-danger' : 'st-btn-primary']}
					disabled={!confirmArmed}
					onclick={confirm}
				>
					{dialog.options.confirmLabel ?? (dialog.kind === 'prompt' ? 'OK' : 'Confirm')}
				</button>
			</div>
		</div>
	</div>
{/if}

<style>
	.cd-scrim {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.6);
		backdrop-filter: blur(4px);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 1050;
		padding: 24px;
	}
	.cd-panel {
		background: var(--bg-surface);
		color: var(--fg-1);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 12px;
		box-shadow:
			0 24px 48px rgba(0, 0, 0, 0.4),
			0 4px 12px rgba(0, 0, 0, 0.25);
		max-width: 440px;
		width: 100%;
		padding: 24px;
		display: flex;
		flex-direction: column;
		gap: 14px;
		outline: none;
	}
	.cd-title {
		margin: 0;
		font-size: 18px;
		font-weight: 600;
	}
	.cd-message {
		margin: 0;
		font-size: 14px;
		line-height: 1.5;
		color: var(--fg-1);
		white-space: pre-line;
	}
	.cd-input {
		border: 1px solid rgba(var(--tint-rgb), 0.25);
		border-radius: 8px;
		padding: 9px 12px;
		background: rgba(var(--tint-rgb), 0.04);
		color: var(--fg-1);
		font-size: 15px;
		outline: none;
	}
	.cd-input:focus-visible {
		border-color: rgba(var(--tint-rgb), 0.6);
		background: rgba(var(--tint-rgb), 0.06);
	}
	.cd-actions {
		display: flex;
		gap: 8px;
		justify-content: flex-end;
		margin-top: 2px;
	}
</style>
