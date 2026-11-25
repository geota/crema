<script lang="ts">
	/**
	 * `ConnectedGrinderCard` — Devices → Grinder's "Connected grinder" card
	 * (spec `.scratch/grinder-ble/SPEC.md` §2.8, §6.1, §7). Marked Beta: no
	 * one has run it against a real Motto 80 yet.
	 *
	 * Connect (the browser's chooser), the link's status, the live readout
	 * (grind, speed, feed, state, humidity, lifetime grinds, firmware), which
	 * owned grinder it is ("Linked to", or "Which grinder is this?", or "Add
	 * … to your grinders"), Disconnect and Forget. Readings only: nothing here
	 * writes to the grinder.
	 */
	import type { CremaApp } from '#lib/state/index.js';
	import { NO_GRINDER } from '#lib/state/ui-state.svelte.js';
	import { getBeanStore } from '#lib/bean/store.svelte.js';
	import { getGrinderLinkStore, resolveGrinderLink } from '#lib/grinder/connected-link.svelte.js';
	import { hasRememberedDevice } from '#lib/ble/launch-reconnect.js';
	import {
		catalogueFor,
		grinderProblemText,
		grinderReadout,
		grinderStatusLine,
		lastRunText
	} from './connected-grinder.js';

	let { app }: { /** The orchestrator, or null while the core loads. */ app: CremaApp | null } =
		$props();

	const uid = $props.id();
	const lib = getBeanStore();
	const links = getGrinderLinkStore();

	const view = $derived(app?.state.current.grinder ?? NO_GRINDER);
	const entry = $derived(catalogueFor(view.model));
	const status = $derived(grinderStatusLine(view));
	const problem = $derived(grinderProblemText(view.problem, entry));
	const readout = $derived(grinderReadout(view.reading, view.capabilities, view.info));
	const lastRun = $derived(lastRunText(view, view.capabilities));
	/** The reading is from before a drop: shown, but not live. */
	const stale = $derived(view.reading !== null && view.state !== 'ready');

	const busy = $derived(
		view.state === 'connecting' ||
			view.state === 'subscribing' ||
			view.state === 'ready' ||
			view.state === 'reconnecting'
	);
	// Re-read when the link state moves: a connect remembers it, Forget drops it.
	const remembered = $derived(view.state !== null && hasRememberedDevice('grinder'));

	const link = $derived(
		view.deviceId && view.model
			? resolveGrinderLink({
					deviceId: view.deviceId,
					model: view.model,
					grinders: lib.allGrinders,
					links: links.links
				})
			: null
	);
	const linkedId = $derived(link?.kind === 'linked' ? link.grinder.id : '');

	function onLinkPick(e: Event & { currentTarget: HTMLSelectElement }): void {
		const deviceId = view.deviceId;
		if (!deviceId) return;
		const id = e.currentTarget.value;
		if (id) links.link(deviceId, id);
		else links.unlink(deviceId);
	}

	function addAndLink(): void {
		const deviceId = view.deviceId;
		if (!deviceId || !view.model) return;
		const g = lib.addGrinder('', view.model);
		if (g) links.link(deviceId, g.id);
	}

	function restoreLinked(): void {
		if (link?.kind === 'archived') lib.restoreGrinder(link.grinder.id);
	}
</script>

<section class="cgc" aria-labelledby="{uid}-title">
	<div class="cgc-head">
		<h2 class="cgc-title" id="{uid}-title">Connected grinder</h2>
		<span class="cgc-beta">Beta</span>
		<span class="cgc-grow"></span>
		<span class={['cgc-status', { live: view.state === 'ready' }]} role="status">{status}</span>
	</div>
	<p class="cgc-muted">
		Not tested on a real grinder yet. Have a Motto 80? Please send a debug capture: pull a shot with
		it connected, then History → Download → Replayable capture.
	</p>

	{#if view.name}
		<p class="cgc-name">{entry?.name ?? view.name}<span class="cgc-muted"> · {view.name}</span></p>
	{/if}

	{#if problem}
		<p class="cgc-problem" role="alert">{problem}</p>
	{:else if view.state === 'idle' || view.state === 'disconnected'}
		<p class="cgc-muted">
			Turn the grinder on and close its maker's app (it takes one phone at a time), then connect.
		</p>
	{/if}

	{#if readout.length > 0}
		<dl class={['cgc-readout', { stale }]} aria-label="Grinder readings{stale ? ' (not live)' : ''}">
			{#each readout as row (row.label)}
				<div class="cgc-cell">
					<dt>{row.label}</dt>
					<dd>{row.value}</dd>
				</div>
			{/each}
		</dl>
	{/if}
	{#if lastRun}<p class="cgc-muted">{lastRun}</p>{/if}

	{#if link}
		<div class="cgc-link">
			{#if link.kind === 'archived'}
				<span class="cgc-grow">Linked to {link.grinder.name}, which is archived.</span>
				<button type="button" class="cgc-btn" onclick={restoreLinked}>Restore</button>
			{:else}
				<label class="cgc-field cgc-grow">
					<span>{link.kind === 'choose' ? 'Which grinder is this?' : 'Linked to'}</span>
					<select value={linkedId} onchange={onLinkPick}>
						<option value="">{link.kind === 'choose' ? 'Pick a grinder' : 'Not linked'}</option>
						{#each lib.grinders as g (g.id)}
							<option value={g.id}>{g.name}</option>
						{/each}
					</select>
				</label>
				{#if link.kind === 'add' && entry}
					<button type="button" class="cgc-btn" onclick={addAndLink}>
						Add {entry.name} to your grinders
					</button>
				{/if}
			{/if}
		</div>
	{/if}

	<div class="cgc-actions">
		{#if busy}
			{#if view.state === 'reconnecting'}
				<button type="button" class="cgc-btn" onclick={() => app?.retryReconnect('grinder')}>
					Retry now
				</button>
			{/if}
			<button type="button" class="cgc-btn" onclick={() => void app?.disconnectGrinder()}>
				Disconnect
			</button>
		{:else if view.problem?.kind === 'no-data'}
			<button
				type="button"
				class="cgc-btn cgc-btn-primary"
				disabled={!app}
				onclick={() => void app?.reconnectGrinder()}
			>
				Reconnect
			</button>
		{:else}
			<button
				type="button"
				class="cgc-btn cgc-btn-primary"
				disabled={!app}
				onclick={() => void app?.connectGrinder()}
			>
				{view.problem ? 'Try again' : 'Connect'}
			</button>
		{/if}
		<span class="cgc-grow"></span>
		{#if remembered || view.deviceId}
			<button type="button" class="cgc-btn cgc-btn-quiet" onclick={() => void app?.forgetGrinder()}>
				Forget
			</button>
		{/if}
	</div>
</section>

<style>
	.cgc {
		display: flex;
		flex-direction: column;
		gap: 12px;
	}
	.cgc-head {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 10px;
	}
	.cgc-title {
		margin: 0;
		font-family: var(--font-serif);
		font-weight: 400;
		font-size: 18px;
		color: var(--fg-1);
	}
	.cgc-beta {
		font-size: 11px;
		font-weight: 600;
		letter-spacing: 0.08em;
		text-transform: uppercase;
		color: var(--fg-accent);
		border: 1px solid currentColor;
		border-radius: var(--radius-pill);
		padding: 2px 8px;
	}
	.cgc-grow {
		flex: 1;
		min-width: 0;
	}
	.cgc-status {
		font-size: 13px;
		color: var(--fg-3);
	}
	.cgc-status.live {
		color: var(--fg-accent);
	}
	.cgc-name {
		margin: 0;
		font-size: 15px;
		font-weight: 500;
		color: var(--fg-1);
	}
	.cgc-muted {
		margin: 0;
		font-size: 12px;
		font-weight: 400;
		line-height: 1.5;
		color: var(--fg-muted);
	}
	.cgc-problem {
		margin: 0;
		font-size: 13px;
		line-height: 1.5;
		color: var(--warning);
	}
	.cgc-readout {
		margin: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
		gap: 10px;
	}
	.cgc-readout.stale {
		opacity: 0.55;
	}
	.cgc-cell {
		border-radius: var(--radius-md, 12px);
		background: var(--bg-sunken, var(--bg-page));
		padding: 10px 12px;
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
	}
	.cgc-cell dt {
		font-size: 11px;
		font-weight: 500;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	.cgc-cell dd {
		margin: 0;
		font-family: var(--font-mono);
		font-size: 16px;
		color: var(--fg-1);
		overflow-wrap: anywhere;
	}
	.cgc-link,
	.cgc-actions {
		display: flex;
		align-items: flex-end;
		flex-wrap: wrap;
		gap: 10px;
	}
	.cgc-actions {
		align-items: center;
	}
	.cgc-link > span {
		font-size: 14px;
		color: var(--fg-1);
		align-self: center;
	}
	.cgc-field {
		display: flex;
		flex-direction: column;
		gap: 6px;
		font-size: 11px;
		font-weight: 500;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	.cgc-field select {
		min-height: 44px;
		box-sizing: border-box;
		border: 1px solid var(--field-border);
		border-radius: var(--radius-sm, 8px);
		background: var(--bg-sunken, var(--bg-page));
		padding: 0 12px;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 15px;
		letter-spacing: 0;
		text-transform: none;
		width: 100%;
	}
	.cgc-field select:focus {
		outline: none;
		border-color: var(--copper-400);
	}
	.cgc-btn {
		min-height: 44px;
		padding: 0 16px;
		border: 1px solid rgba(var(--tint-rgb), 0.18);
		border-radius: var(--radius-pill);
		background: transparent;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
	}
	.cgc-btn:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.cgc-btn:disabled {
		opacity: 0.5;
		cursor: default;
	}
	.cgc-btn-primary {
		border-color: transparent;
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.cgc-btn-quiet {
		border-color: transparent;
		color: var(--fg-accent);
	}
</style>
