<script lang="ts">
	/**
	 * About section — app identity, version, and the project links.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real** — the app name and version are read from `package.json` (Vite
	 * resolves the JSON import at build time), so the version line is genuine
	 * and updates with the package. The project links point at real URLs.
	 */
	import pkg from '../../../../../package.json';

	/** The app version from `package.json` — genuine, not a placeholder. */
	const version = pkg.version;
	/** The app name from `package.json`. */
	const name = pkg.name;
</script>

<div class="st-shead">
	<div class="st-shead-title">About</div>
	<div class="st-shead-sub">
		Crema is built with care for the moments before the first sip.
	</div>
</div>

<div class="st-about">
	<div class="st-about-mark">
		<img
			class="cside-mark-glyph"
			src="/bean-mark.svg"
			alt="Crema"
			width="56"
			height="56"
		/>
	</div>
	<div class="st-about-meta">
		<div class="st-about-line"><strong>Crema</strong></div>
		<div class="st-about-line">{name} · v{version}</div>
		<div class="st-about-line">
			For the Decent DE1 family. Local-only — no Crema account required. Not
			affiliated with Decent Espresso.
		</div>
	</div>
</div>

<div class="st-group">
	<div class="st-group-rows">
		<div class="st-row">
			<div class="st-row-text">
				<div class="st-row-title">Project repository</div>
			</div>
			<div class="st-row-control">
				<a
					class="st-btn st-btn-secondary"
					href="https://github.com/decentespresso"
					target="_blank"
					rel="noreferrer noopener"
				>
					<i class="ph ph-arrow-square-out" aria-hidden="true"></i><span>Open</span>
				</a>
			</div>
		</div>
		<div class="st-row">
			<div class="st-row-text">
				<div class="st-row-title">Decent Espresso</div>
			</div>
			<div class="st-row-control">
				<a
					class="st-btn st-btn-secondary"
					href="https://decentespresso.com"
					target="_blank"
					rel="noreferrer noopener"
				>
					<i class="ph ph-arrow-square-out" aria-hidden="true"></i><span>Visit</span>
				</a>
			</div>
		</div>
		<div class="st-row">
			<div class="st-row-text">
				<div class="st-row-title">Visualizer</div>
				<div class="st-row-sub">The community shot-sharing service.</div>
			</div>
			<div class="st-row-control">
				<a
					class="st-btn st-btn-secondary"
					href="https://visualizer.coffee"
					target="_blank"
					rel="noreferrer noopener"
				>
					<i class="ph ph-arrow-square-out" aria-hidden="true"></i><span>Visit</span>
				</a>
			</div>
		</div>
	</div>
</div>
