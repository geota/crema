<script lang="ts">
	import XIcon from 'phosphor-svelte/lib/XIcon';
	/**
	 * `BeanPhotoViewer` — the bag photo at full size (issue 61).
	 *
	 * Until now a bag photo only ever existed as a 96 px hero or a 44 px tile
	 * avatar, which is not enough to read a bag label — the most common reason
	 * to have photographed it. This is a plain lightbox: scrim, the image at up
	 * to `90vw × 85vh`, dismissed by clicking anywhere, pressing Escape, or the
	 * close button.
	 *
	 * No zoom controls: the image is already presented at the largest size the
	 * viewport allows, and desktop browsers zoom natively. The Android viewer
	 * adds pinch-zoom because a phone viewport cannot show a label legibly at
	 * fit-to-screen.
	 */
	import BeanImage from './BeanImage.svelte';

	let {
		imageRef,
		caption,
		onClose
	}: {
		/** The bean's `imageRef`. */
		imageRef: string | null;
		/** Bag name, announced as the image's alt text. */
		caption: string;
		onClose: () => void;
	} = $props();

	function onKey(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			onClose();
		}
	}
</script>

<svelte:window onkeydown={onKey} />

<!-- svelte-ignore a11y_click_events_have_key_events -->
<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div
	class="bpv"
	role="dialog"
	aria-modal="true"
	aria-label="{caption} photo"
	tabindex="-1"
	onclick={onClose}
>
	<button class="bpv-close" onclick={onClose} aria-label="Close photo">
		<XIcon aria-hidden="true" />
	</button>
	<BeanImage ref={imageRef} className="bpv-img" alt="{caption} bag photo" />
	<div class="bpv-caption">{caption}</div>
</div>

<style>
	.bpv {
		position: fixed;
		inset: 0;
		z-index: 90; /* above the drawer (69) and its scrim (68) */
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 14px;
		background: rgba(0, 0, 0, 0.86);
		cursor: zoom-out;
		padding: 24px;
	}
	:global(.bpv-img) {
		max-width: 90vw;
		max-height: 85vh;
		object-fit: contain;
		border-radius: var(--radius-md);
		box-shadow: 0 24px 64px rgba(0, 0, 0, 0.5);
	}
	.bpv-caption {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(255, 255, 255, 0.7);
		text-align: center;
	}
	.bpv-close {
		position: absolute;
		top: 16px;
		right: 16px;
		width: 40px;
		height: 40px;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		border: 0;
		border-radius: 50%;
		background: rgba(255, 255, 255, 0.12);
		color: #fff;
		font-size: 18px;
		cursor: pointer;
	}
	.bpv-close:hover {
		background: rgba(255, 255, 255, 0.22);
	}
</style>
