<script lang="ts">
	/**
	 * `FirmwareUpdateFlow` — Settings → Machine → "Update firmware…" (alpha).
	 *
	 * Steps: alpha warning (click-through) → choose a build → download and
	 * verify against the core's pinned manifest (or, when the browser blocks
	 * Decent's server, download it from Decent and choose the file) → type
	 * UPDATE → flash with phase, percent, ETA and Cancel → switch the DE1 off
	 * and on → the read-back result, or a failure with Retry.
	 *
	 * All state lives in `FirmwareUpdateController`; the flash itself is the
	 * core's state machine. Styling mirrors `MainsConfirmModal` (the `fu-`
	 * prefix), deliberately plain until a design pass.
	 */
	import Icon from '$lib/icons/Icon.svelte';
	import { FlashPhase } from '$lib/core';
	import type { FirmwareUpdateController } from '$lib/firmware/firmware-update.svelte';
	import {
		ALPHA_POINTS,
		STILL_SAFE,
		blockerText,
		channelLabel,
		etaText,
		failureText,
		phaseLabel
	} from '$lib/firmware/flash-copy';

	let { flow, installedBuild }: { flow: FirmwareUpdateController; installedBuild: number | null } =
		$props();

	const EXPECTED_TOKEN = 'UPDATE';
	let typed = $state('');
	const typedMatches = $derived(typed.trim().toUpperCase() === EXPECTED_TOKEN);

	const status = $derived(flow.status);
	const selected = $derived(flow.selected);
	const running = $derived(flow.running);

	function relation(build: number): string {
		if (installedBuild === null) return '';
		if (build > installedBuild) return 'Newer than installed';
		if (build === installedBuild) return 'Reinstalls the installed build';
		return 'Replaces the installed build';
	}

	function minutes(chunks: number): string {
		// ~30 ms per acknowledged write (Decenza's Android measurement);
		// Web Bluetooth adds a little per write, so state a range.
		const lo = Math.round((chunks * 0.03) / 60);
		return `${lo}–${lo + 10} min`;
	}

	function onKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape' && !running) {
			e.stopPropagation();
			void flow.close();
		}
	}

	function onFile(e: Event) {
		const input = e.currentTarget as HTMLInputElement;
		const file = input.files?.[0];
		if (file) void flow.useFile(file);
		input.value = '';
	}
</script>

<svelte:document onkeydown={onKeydown} />

<div class="fu-scrim" role="presentation">
	<div class="fu-panel" role="dialog" aria-modal="true" aria-labelledby="fu-title" tabindex="-1">
		<div class="fu-head">
			<div class="t-eyebrow">Firmware · alpha</div>
			<h2 id="fu-title">
				{#if flow.step === 'flash' && status}{phaseLabel(status.phase)}{:else}Update DE1 firmware{/if}
			</h2>
		</div>

		{#if flow.step === 'warning'}
			<div class="fu-banner fu-banner-warn" role="alert">
				<Icon cls="ph ph-warning-circle" aria-hidden="true" />
				<ul class="fu-points">
					{#each ALPHA_POINTS as point (point)}<li>{point}</li>{/each}
				</ul>
			</div>
			<label class="fu-check">
				<input type="checkbox" bind:checked={flow.acknowledged} />
				<span>I understand</span>
			</label>
			<div class="fu-actions">
				<button type="button" class="st-btn st-btn-secondary" onclick={() => flow.close()}>
					Cancel
				</button>
				<button
					type="button"
					class="st-btn st-btn-primary"
					disabled={!flow.acknowledged}
					onclick={() => flow.acceptWarning()}>Continue</button
				>
			</div>
		{:else if flow.step === 'choose'}
			<div class="fu-body">
				<div class="fu-sub">
					Installed: {installedBuild === null ? 'not read yet' : `build ${installedBuild}`}
				</div>
				<div class="fu-choices" role="radiogroup" aria-label="Firmware build">
					{#each flow.artifacts as a (a.id)}
						<label class="fu-choice" class:is-chosen={flow.selectedId === a.id}>
							<input
								type="radio"
								name="fw-build"
								checked={flow.selectedId === a.id}
								onchange={() => flow.select(a.id)}
							/>
							<span class="fu-choice-body">
								<span class="fu-choice-title">Build {a.build} · {channelLabel(a.channel)}</span>
								<span class="fu-choice-rel">{relation(a.build)}</span>
								<span class="fu-choice-notes">{a.releaseNotes}</span>
							</span>
						</label>
					{/each}
				</div>
			</div>
			<div class="fu-actions">
				<button type="button" class="st-btn st-btn-secondary" onclick={() => flow.close()}>
					Cancel
				</button>
				<button
					type="button"
					class="st-btn st-btn-primary"
					disabled={selected === null}
					onclick={() => flow.fetchImage()}>Download and verify</button
				>
			</div>
		{:else if flow.step === 'source' && selected}
			<div class="fu-body">
				{#if flow.downloading}
					<div class="fu-sub">Downloading build {selected.build} from Decent…</div>
					<progress max="1" value={flow.downloadProgress}></progress>
				{:else if flow.manualNeeded}
					<p class="fu-text">
						Your browser can't fetch Decent's file directly. Download it from Decent, then choose
						the downloaded file (<code>bootfwupdate.dat</code>). Crema checks it byte for byte
						before using it.
					</p>
					<div class="fu-row-actions">
						<a
							class="st-btn st-btn-secondary"
							href={selected.urls[0]}
							target="_blank"
							rel="noopener noreferrer"
							download>Download from Decent</a
						>
						<label class="st-btn st-btn-primary fu-file">
							Choose the downloaded file
							<input type="file" accept=".dat,application/octet-stream" onchange={onFile} />
						</label>
					</div>
				{/if}
				{#if flow.sourceProblem}
					<div class="fu-banner fu-banner-warn" role="alert">
						<Icon cls="ph ph-warning-circle" aria-hidden="true" /><span>{flow.sourceProblem}</span>
					</div>
				{/if}
			</div>
			<div class="fu-actions">
				<button type="button" class="st-btn st-btn-secondary" onclick={() => (flow.step = 'choose')}>
					Back
				</button>
			</div>
		{:else if flow.step === 'confirm' && selected}
			<div class="fu-body">
				<div class="fu-rows">
					<div class="fu-row">
						<span class="fu-row-label">Installed</span>
						<span class="fu-row-value">{installedBuild ?? '—'}</span>
					</div>
					<div class="fu-row">
						<span class="fu-row-label">Installing</span>
						<span class="fu-row-value fu-row-value-chosen"
							>{selected.build} · {channelLabel(selected.channel)}</span
						>
					</div>
					<div class="fu-row">
						<span class="fu-row-label">Verified</span>
						<span class="fu-row-value">SHA-256 {selected.sha256.slice(0, 12)}…</span>
					</div>
					<div class="fu-row">
						<span class="fu-row-label">Takes about</span>
						<span class="fu-row-value">{minutes(selected.chunkCount)}</span>
					</div>
				</div>
				{#if flow.blockers.length > 0}
					<div class="fu-banner fu-banner-warn" role="alert">
						<Icon cls="ph ph-warning-circle" aria-hidden="true" />
						<ul class="fu-points">
							{#each flow.blockers as b (b.type)}<li>{blockerText(b)}</li>{/each}
						</ul>
					</div>
				{/if}
				{#if flow.startProblem}
					<div class="fu-banner fu-banner-warn" role="alert">{flow.startProblem}</div>
				{/if}
				<label class="fu-field">
					<span class="fu-field-label">Type <code>{EXPECTED_TOKEN}</code> to start</span>
					<input
						type="text"
						autocomplete="off"
						autocapitalize="characters"
						spellcheck="false"
						bind:value={typed}
						placeholder={EXPECTED_TOKEN}
						aria-label="Type the confirmation word"
					/>
				</label>
			</div>
			<div class="fu-actions">
				{#if flow.blockers.length > 0}
					<button type="button" class="st-btn st-btn-secondary" onclick={() => flow.refreshBlockers()}>
						Check again
					</button>
				{/if}
				<button type="button" class="st-btn st-btn-secondary" onclick={() => flow.close()}>
					Cancel
				</button>
				<button
					type="button"
					class="st-btn st-btn-danger"
					disabled={!typedMatches || flow.blockers.length > 0}
					onclick={() => flow.startFlash()}>Update firmware</button
				>
			</div>
		{:else if flow.step === 'flash' && status}
			<div class="fu-body">
				{#if running}
					<progress max="100" value={status.percent}></progress>
					<div class="fu-progress-meta">
						<span>{status.percent.toFixed(0)} %</span>
						<span>{etaText(status)}</span>
					</div>
					{#if status.phase === FlashPhase.Uploading}
						<div class="fu-sub">
							{status.chunksAcked.toLocaleString()} of {status.chunksTotal.toLocaleString()} blocks ·
							build {status.targetBuild}
						</div>
					{/if}
					<div class="fu-banner fu-banner-info" role="status">
						Keep this tab open and in front, this device charging, and the DE1 nearby. If the
						update stops, the DE1 keeps its current firmware.
					</div>
				{:else if status.phase === FlashPhase.AwaitingPowerCycle}
					<p class="fu-text">
						{status.verifyUnconfirmed
							? 'The connection dropped while the DE1 was checking the firmware. Switching it off and on shows which build it runs.'
							: `The DE1 checked build ${status.targetBuild} and will run it after a restart.`}
					</p>
					<ol class="fu-steps">
						<li>Switch the DE1 off with the switch on the back.</li>
						<li>Wait 10 seconds.</li>
						<li>Switch it on. Crema reconnects and reads the build.</li>
					</ol>
					{#if status.restartedOnBuild !== null && status.restartedOnBuild !== undefined}
						<div class="fu-banner fu-banner-warn" role="alert">
							The DE1 restarted but still reports build {status.restartedOnBuild}. Switch it off and
							on once more. If it still reports {status.restartedOnBuild}, retry the update.
						</div>
					{:else if status.machineRestarted}
						<div class="fu-sub">Waiting for the DE1 to reconnect…</div>
					{/if}
				{:else if status.phase === FlashPhase.Succeeded}
					<div class="fu-banner fu-banner-ok" role="status">
						<Icon cls="ph ph-check-circle" aria-hidden="true" />
						<span>Your DE1 now runs build {status.targetBuild}.</span>
					</div>
				{:else}
					{#if status.failure}<p class="fu-text">{failureText(status.failure)}</p>{/if}
					<div class="fu-banner fu-banner-info" role="status">{STILL_SAFE}</div>
				{/if}
			</div>
			<div class="fu-actions">
				{#if running}
					<button
						type="button"
						class="st-btn st-btn-secondary"
						disabled={!status.canCancel}
						onclick={() => flow.cancel()}
						>{status.canCancel ? 'Cancel' : 'Finishing…'}</button
					>
				{:else if status.phase === FlashPhase.Failed || status.phase === FlashPhase.Cancelled}
					<button type="button" class="st-btn st-btn-secondary" onclick={() => flow.close()}>
						Close
					</button>
					<button type="button" class="st-btn st-btn-primary" onclick={() => flow.retry()}>
						Retry
					</button>
				{:else}
					<button type="button" class="st-btn st-btn-primary" onclick={() => flow.close()}>
						{status.phase === FlashPhase.Succeeded ? 'Done' : 'Close'}
					</button>
				{/if}
			</div>
		{/if}
	</div>
</div>

<style>
	.fu-scrim {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.6);
		backdrop-filter: blur(4px);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 1000;
		padding: 16px;
	}
	.fu-panel {
		background: var(--bg-surface);
		color: var(--fg-1);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 12px;
		box-shadow:
			0 24px 48px rgba(0, 0, 0, 0.4),
			0 4px 12px rgba(0, 0, 0, 0.25);
		max-width: 520px;
		width: 100%;
		max-height: calc(100dvh - 32px);
		overflow-y: auto;
		padding: 24px;
		display: flex;
		flex-direction: column;
		gap: 16px;
		outline: none;
	}
	.fu-head h2 {
		margin: 4px 0 0 0;
		font-size: 18px;
		font-weight: 600;
	}
	.fu-banner {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		padding: 12px 14px;
		border-radius: 8px;
		font-size: 13px;
		line-height: 1.45;
	}
	.fu-banner :global(svg) {
		flex-shrink: 0;
		font-size: 18px;
		margin-top: 1px;
	}
	.fu-banner-warn {
		background: rgba(var(--warning-rgb), 0.12);
		border: 1px solid rgba(var(--warning-rgb), 0.35);
		color: var(--warning);
	}
	.fu-banner-info {
		background: rgba(var(--tint-rgb), 0.05);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		color: var(--fg-2, var(--fg-1));
	}
	.fu-banner-ok {
		background: rgba(var(--tint-rgb), 0.06);
		border: 1px solid var(--copper-400);
		color: var(--fg-1);
	}
	.fu-points,
	.fu-steps {
		margin: 0;
		padding-left: 18px;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.fu-points {
		list-style: disc;
	}
	.fu-steps {
		list-style: decimal;
		font-size: 14px;
	}
	.fu-check input,
	.fu-choice input {
		accent-color: var(--copper-400);
	}
	.fu-check {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 14px;
		font-weight: 600;
	}
	.fu-body {
		display: flex;
		flex-direction: column;
		gap: 14px;
	}
	.fu-sub {
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.65);
	}
	.fu-text {
		margin: 0;
		font-size: 14px;
		line-height: 1.45;
	}
	.fu-text code,
	.fu-field-label code {
		font-family: var(--font-mono);
		font-size: 13px;
		padding: 1px 6px;
		background: rgba(var(--tint-rgb), 0.1);
		border-radius: 4px;
	}
	.fu-choices {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.fu-choice {
		display: flex;
		gap: 10px;
		align-items: flex-start;
		padding: 10px 12px;
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 8px;
		cursor: pointer;
	}
	.fu-choice.is-chosen {
		border-color: var(--copper-400);
		background: rgba(var(--tint-rgb), 0.04);
	}
	.fu-choice-body {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.fu-choice-title {
		font-weight: 600;
		font-size: 14px;
	}
	.fu-choice-rel {
		font-size: 12px;
		color: var(--copper-400);
	}
	.fu-choice-notes {
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.7);
		line-height: 1.4;
	}
	.fu-rows {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding: 12px 14px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: 8px;
	}
	.fu-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 16px;
	}
	.fu-row-label {
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.65);
	}
	.fu-row-value {
		font-family: var(--font-mono);
		font-size: 13px;
		font-variant-numeric: tabular-nums;
	}
	.fu-row-value-chosen {
		font-weight: 700;
	}
	.fu-field {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.fu-field-label {
		font-size: 13px;
		font-weight: 600;
	}
	.fu-field input {
		margin-top: 6px;
		border: 1px solid rgba(var(--tint-rgb), 0.25);
		border-radius: 8px;
		padding: 8px 12px;
		background: rgba(var(--tint-rgb), 0.04);
		color: var(--fg-1);
		font-family: var(--font-mono);
		font-size: 15px;
		outline: none;
	}
	progress {
		appearance: none;
		width: 100%;
		height: 10px;
		border: none;
		border-radius: 5px;
		overflow: hidden;
		background: rgba(var(--tint-rgb), 0.12);
	}
	progress::-webkit-progress-bar {
		background: rgba(var(--tint-rgb), 0.12);
	}
	progress::-webkit-progress-value {
		background: var(--copper-400);
	}
	progress::-moz-progress-bar {
		background: var(--copper-400);
	}
	.fu-progress-meta {
		display: flex;
		justify-content: space-between;
		font-size: 13px;
		font-variant-numeric: tabular-nums;
	}
	.fu-row-actions {
		display: flex;
		gap: 8px;
		flex-wrap: wrap;
	}
	.fu-file {
		position: relative;
		overflow: hidden;
	}
	.fu-file input {
		position: absolute;
		inset: 0;
		opacity: 0;
		cursor: pointer;
	}
	.fu-actions {
		display: flex;
		gap: 8px;
		justify-content: flex-end;
		flex-wrap: wrap;
	}
</style>
