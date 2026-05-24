<script lang="ts">
	/**
	 * `RoasterAvatar` — the single source of truth for "show this roaster
	 * as a small visual chip". Resolution order:
	 *
	 *   1. `roaster.imageUrl` (user-uploaded logo / Visualizer hero) — if
	 *      present, we always prefer it.
	 *   2. Google's `faviconV2` lookup against `roaster.website` (only
	 *      icons of the requested size — the endpoint 404s otherwise).
	 *   3. The deterministic two-letter mark + tone from
	 *      `roaster-mark.ts`.
	 *
	 * The `<img>`'s `onerror` is the fallback gate: if the favicon URL
	 * 404s (no icon of sufficient size on the roaster's site), we hide
	 * the image and let the mark render instead. There is no flicker —
	 * the mark is the always-mounted background of the avatar.
	 */
	import type { Roaster } from '$lib/bean';
	import { roasterMarkTone, roasterFaviconUrl } from '$lib/bean';

	let {
		roaster,
		size = 28
	}: {
		roaster: Roaster | null;
		/** CSS pixel diameter for the round avatar. */
		size?: number;
	} = $props();

	const { mark, tone } = $derived(roasterMarkTone(roaster));

	/**
	 * The image source we want to load. `imageUrl` wins; otherwise the
	 * Google favicon. We request the icon at 2× the rendered size for
	 * Retina sharpness — Google's `size` query is in CSS pixels of the
	 * source asset, not the rendered element.
	 */
	const imgSrc = $derived.by<string | null>(() => {
		if (!roaster) return null;
		if (roaster.imageUrl && roaster.imageUrl.trim() !== '') {
			return roaster.imageUrl;
		}
		return roasterFaviconUrl(roaster, size * 2);
	});

	/**
	 * Whether the resolved image actually loaded. We optimistically
	 * mount the `<img>` when `imgSrc` is set; `onerror` flips this back
	 * to `false` so the mark shows through. `onload` is also wired in
	 * case the image is decode-failed (rare).
	 */
	let imgOk = $state(true);
	$effect(() => {
		// Reset the "loaded" flag whenever the source URL changes — a
		// previously-failed roaster might have a working URL after the
		// user edits the website field.
		void imgSrc;
		imgOk = true;
	});
</script>

<span class="ra" style="--ra-size: {size}px; --ra-tone: {tone};">
	<span class="ra-mark" aria-hidden={imgOk && imgSrc ? 'true' : 'false'}>{mark}</span>
	{#if imgSrc && imgOk}
		<img
			class="ra-img"
			src={imgSrc}
			alt=""
			width={size}
			height={size}
			loading="lazy"
			referrerpolicy="no-referrer"
			onerror={() => (imgOk = false)}
		/>
	{/if}
</span>

<style>
	.ra {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: var(--ra-size);
		height: var(--ra-size);
		border-radius: 999px;
		background: var(--ra-tone);
		color: #fff;
		position: relative;
		overflow: hidden;
		flex-shrink: 0;
	}
	.ra-mark {
		font-family: var(--font-serif);
		font-weight: 600;
		font-size: calc(var(--ra-size) * 0.42);
		letter-spacing: -0.02em;
	}
	.ra-img {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		object-fit: cover;
		background: #fff;
	}
</style>
