<script lang="ts">
	/**
	 * `FileDropZone` — drag-drop file region with a hidden file-picker
	 * button. Hands the chosen / dropped files back to the caller as a
	 * `File[]` so the caller owns the routing (BC import wires bean +
	 * photo files; /history import wires `.shot` / `.shot.json` files).
	 *
	 * The dashed-border + icon + copy layout matches the original
	 * BeanImportDialog drop zone — extracted here so /history's Import
	 * gets the same affordance without duplicating styles.
	 */

	interface Props {
		/** Phosphor icon class — defaults to a generic file glyph. */
		icon?: string;
		/** Headline above the drop instructions. */
		title: string;
		/** Smaller helper line under the title — explains accepted formats. */
		subtitle: string;
		/** Native `accept` value on the file picker. */
		accept: string;
		/** Allow multi-select on the file picker. */
		multiple?: boolean;
		/** Disable picking / dropping while a previous batch is processing. */
		disabled?: boolean;
		/** Label inside the pick button — defaults to "Choose files…". */
		pickLabel?: string;
		/** Fired once with the files the user picked or dropped. */
		onFiles: (files: File[]) => void | Promise<void>;
	}

	let {
		icon = 'ph-duotone ph-file-arrow-up',
		title,
		subtitle,
		accept,
		multiple = true,
		disabled = false,
		pickLabel = 'Choose files…',
		onFiles
	}: Props = $props();

	let dragging = $state(false);

	async function onFileChosen(event: Event): Promise<void> {
		const input = event.currentTarget as HTMLInputElement;
		const files = input.files ? [...input.files] : [];
		input.value = '';
		if (files.length > 0) await onFiles(files);
	}

	async function onDrop(event: DragEvent): Promise<void> {
		event.preventDefault();
		dragging = false;
		if (disabled) return;
		const files = event.dataTransfer?.files ? [...event.dataTransfer.files] : [];
		if (files.length > 0) await onFiles(files);
	}

	function onDragOver(event: DragEvent): void {
		event.preventDefault();
		if (disabled) return;
		dragging = true;
	}

	function onDragLeave(): void {
		dragging = false;
	}
</script>

<div
	class="fdz"
	class:fdz-active={dragging}
	class:fdz-disabled={disabled}
	ondragover={onDragOver}
	ondragleave={onDragLeave}
	ondrop={onDrop}
	role="region"
	aria-label={title}
>
	<i class={icon} aria-hidden="true"></i>
	<div class="fdz-title">{title}</div>
	<div class="fdz-sub">{@html subtitle}</div>
	<label class="fdz-pick" class:is-disabled={disabled}>
		<input
			type="file"
			{accept}
			{multiple}
			{disabled}
			onchange={onFileChosen}
		/>
		<span>{pickLabel}</span>
	</label>
</div>

<style>
	.fdz {
		padding: 40px 24px;
		text-align: center;
		display: flex;
		flex-direction: column;
		gap: 12px;
		border: 2px dashed rgba(var(--tint-rgb), 0.16);
		border-radius: var(--radius-lg, 14px);
		transition: border-color var(--dur-1) var(--ease),
			background var(--dur-1) var(--ease);
	}
	.fdz-active {
		border-color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.05);
	}
	.fdz-disabled {
		opacity: 0.6;
		pointer-events: none;
	}
	.fdz > i {
		font-size: 48px;
		color: var(--copper-400);
	}
	.fdz-title {
		font-family: var(--font-sans);
		font-size: 15px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.fdz-sub {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
		max-width: 380px;
		margin: 0 auto;
		line-height: 1.5;
	}
	.fdz-sub :global(code) {
		font-family: var(--font-mono);
		font-size: 11px;
		padding: 1px 5px;
		background: rgba(var(--tint-rgb), 0.06);
		border-radius: 4px;
	}
	.fdz-pick {
		display: inline-flex;
		position: relative;
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		border-radius: var(--radius-pill);
		padding: 8px 18px;
		cursor: pointer;
		margin: 6px auto 0;
		max-width: 200px;
	}
	.fdz-pick.is-disabled {
		opacity: 0.6;
		cursor: not-allowed;
	}
	.fdz-pick input[type='file'] {
		position: absolute;
		inset: 0;
		opacity: 0;
		cursor: pointer;
	}
</style>
