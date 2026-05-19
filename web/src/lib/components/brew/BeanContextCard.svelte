<script lang="ts">
	/**
	 * `BeanContextCard` — the left-column "what you're pulling right now" card,
	 * ported from `BeanContextCard` in `web-dashboard-v2.jsx`. Reuses the
	 * `.crema-bean*` classes from `web-kit.css`.
	 *
	 * Wired to the active profile where the data exists: the bean line and the
	 * roast level come from the `lib/profiles` library store's active profile.
	 * The off-roast age, the precise roast date and the grinder model are not
	 * carried by the `CremaProfile` model, so they fall back to the design's
	 * representative placeholders — these stay static until the profile model
	 * grows those fields.
	 *
	 * Only mounted on the resting Brew dashboard (Quick Sheet closed).
	 */
	import type { CremaProfile } from '$lib/profiles';

	let {
		profile,
		grind
	}: {
		/** The active profile, or `undefined` when none is selected. */
		profile?: CremaProfile;
		/** The current grinder click setting from the Quick Sheet params. */
		grind: number;
	} = $props();

	/** The bean line — the active profile's bean, or a neutral fallback. */
	const beanName = $derived(profile?.bean?.trim() || 'No bean logged');
	/** The roast level, title-cased, or a dash when unknown. */
	const roastLabel = $derived(
		profile?.roast ? profile.roast[0].toUpperCase() + profile.roast.slice(1) : '—'
	);

	// Off-roast age, roast date and grinder are not in the profile model yet —
	// representative placeholders, kept static until those fields land.
	const daysOff = 7;
	const roastDate = 'May 11';
	const grinder = 'Niche Zero';
</script>

<div class="crema-target crema-bean">
	<div class="crema-bean-head">
		<div class="t-eyebrow">Bean</div>
		<div class="crema-bean-rest">
			<span class="crema-bean-rest-dot"></span>
			{daysOff}d off roast
		</div>
	</div>
	<div class="crema-bean-name">{beanName}</div>
	<div class="crema-bean-origin">{roastLabel} roast</div>
	<div class="crema-bean-grid">
		<div class="crema-bean-cell">
			<span class="t-eyebrow">Roasted</span>
			<span class="crema-bean-val">{roastDate}</span>
		</div>
		<div class="crema-bean-cell">
			<span class="t-eyebrow">Grind</span>
			<span class="crema-bean-val">{grind.toFixed(1)} <em>{grinder}</em></span>
		</div>
	</div>
</div>
