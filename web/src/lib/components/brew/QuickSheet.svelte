<script lang="ts">
	/**
	 * `QuickSheet` — the docked Quick Sheet, variant G, ported from the
	 * `variant === 'g'` path of `WebQDashV2` in `web-dashboard-v2.jsx`.
	 *
	 * Header (serif title, profile pill, Save / Reset / Close), the
	 * `FavoritesStrip`, the one-row six-card body (Dose|Grind · Yield · Brew
	 * Temp · Steam · Hot Water · Pre-Infuse|Flush), and the footer (the two
	 * mini-toggles plus the big copper Start / Stop button).
	 *
	 * The whole brew-CONTROL surface is **UI-only** in this porting step — the
	 * core treats the DE1 as read-only. Every action that would reach the
	 * machine is marked `// TODO: wire to DE1 control`.
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import type { FavoriteProfile } from './favorites';
	import FavoritesStrip from './FavoritesStrip.svelte';
	import DoseGrindStepper from './DoseGrindStepper.svelte';
	import YieldRatioStepper from './YieldRatioStepper.svelte';
	import BrewTempStepper from './BrewTempStepper.svelte';
	import SteamStepper from './SteamStepper.svelte';
	import WaterStepper from './WaterStepper.svelte';
	import PreinfFlushStepper from './PreinfFlushStepper.svelte';

	let {
		params,
		profileName,
		favorite,
		running,
		onSelectFavorite,
		onToggleRun
	}: {
		/** The shared Quick Sheet parameter store. */
		params: BrewParamState;
		/** The active profile's display name (for the header pill). */
		profileName: string;
		/** The selected favorite's id. */
		favorite: string;
		/** Whether an extraction is in progress — flips the big button to danger-red. */
		running: boolean;
		/** Called when a favorite chip is picked. */
		onSelectFavorite: (profile: FavoriteProfile) => void;
		/** Called when the Start / Stop button is pressed. */
		onToggleRun: () => void;
	} = $props();

	const p = $derived(params.current);
</script>

<!-- The scrim sits behind the docked sheet; both are always `is-open` here
     since the Quick Sheet is permanently docked in variant G. -->
<div class="qsheet-scrim is-open"></div>
<div class="qsheet is-v2 is-open is-onerow">
	<div class="qsheet-v2-head">
		<div class="qsheet-v2-title-block">
			<div class="qsheet-v2-title">Quick Controls</div>
			<div class="qsheet-v2-profile">
				<i class="ph-fill ph-star" style="color:var(--copper-400);font-size:12px" aria-hidden="true"
				></i>
				<span>Profile · </span><strong>{profileName}</strong>
			</div>
		</div>
		<div class="qsheet-v2-actions">
			<!-- TODO: wire to DE1 control — saving a preset needs the profile model. -->
			<button class="qsheet-cta">
				<i class="ph ph-bookmark-simple" aria-hidden="true"></i> Save preset
			</button>
			<button class="qsheet-cta" onclick={() => params.reset()}>
				<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i> Reset
			</button>
			<!-- The sheet is permanently docked in variant G — Close is decorative. -->
			<button class="qsheet-cta">
				<i class="ph ph-x" aria-hidden="true"></i> Close
			</button>
		</div>
	</div>

	<FavoritesStrip {favorite} onSelect={onSelectFavorite} />

	<div class="qsheet-g-grid is-six">
		<DoseGrindStepper {params} />
		<YieldRatioStepper {params} />
		<BrewTempStepper {params} />
		<SteamStepper {params} />
		<WaterStepper {params} />
		<PreinfFlushStepper {params} />
	</div>

	<div class="qsheet-foot">
		<div class="qsheet-toggles">
			<label class="qsheet-tog">
				<!-- TODO: wire to DE1 control — stop-on-weight is local UI state today. -->
				<button
					class="qmini-tog"
					class:on={p.stopOnWeight}
					onclick={() => params.set('stopOnWeight', !p.stopOnWeight)}
					aria-pressed={p.stopOnWeight}
					aria-label="Stop on weight"
				></button>
				<span>Stop on weight</span>
			</label>
			<label class="qsheet-tog">
				<!-- TODO: wire to DE1 control — auto-tare is local UI state today. -->
				<button
					class="qmini-tog"
					class:on={p.autoTare}
					onclick={() => params.set('autoTare', !p.autoTare)}
					aria-pressed={p.autoTare}
					aria-label="Auto-tare"
				></button>
				<span>Auto-tare</span>
			</label>
		</div>
		<!-- TODO: wire to DE1 control — starting / stopping a shot is a net-new
		     feature; today this only flips the local `running` flag. -->
		<button class="crema-bigbtn" class:running onclick={onToggleRun}>
			<i class={'ph-fill ph-' + (running ? 'stop' : 'play')} aria-hidden="true"></i>
			<span>{running ? 'Stop extraction' : 'Start extraction'}</span>
		</button>
	</div>
</div>
