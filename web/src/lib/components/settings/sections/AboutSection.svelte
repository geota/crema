<script lang="ts">
	/**
	 * About section — app identity, the open-source libraries Crema is built
	 * on, and the project's terms of use.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real** — the app name and version are read from `package.json` (Vite
	 * resolves the JSON import at build time), so the version line is genuine
	 * and updates with the package. The project links point at real URLs.
	 */
	import pkg from '../../../../../package.json';

	const version = pkg.version;
	const name = pkg.name;

	/**
	 * Open-source libraries Crema ships with. Curated rather than auto-derived
	 * from `package.json` so the list stays short, sorted by visibility in
	 * the user's experience, and can credit Rust-side crates alongside JS.
	 */
	const libraries: { name: string; url: string; desc: string }[] = [
		{ name: 'Svelte', url: 'https://svelte.dev', desc: 'UI runtime + reactivity (v5 runes).' },
		{ name: 'SvelteKit', url: 'https://kit.svelte.dev', desc: 'App framework, routing, and static build.' },
		{ name: 'Vite', url: 'https://vite.dev', desc: 'Dev server and bundler.' },
		{ name: 'Tailwind CSS', url: 'https://tailwindcss.com', desc: 'Utility CSS foundation.' },
		{ name: 'bits-ui', url: 'https://www.bits-ui.com/', desc: 'Headless Svelte primitives.' },
		{ name: 'Phosphor Icons', url: 'https://phosphoricons.com/', desc: 'The icon set used throughout the app.' },
		{ name: 'uPlot', url: 'https://github.com/leeoniya/uPlot', desc: 'Fast canvas charts for the live shot telemetry.' },
		{ name: 'fflate', url: 'https://github.com/101arrowz/fflate', desc: 'zip / gzip decoding for Beanconqueror imports.' },
		{ name: 'Vite PWA', url: 'https://vite-pwa-org.netlify.app/', desc: 'Service worker + offline install.' },
		{ name: 'Rust', url: 'https://www.rust-lang.org/', desc: 'Powers the core, compiled to WebAssembly.' },
		{ name: 'wasm-bindgen', url: 'https://github.com/wasm-bindgen/wasm-bindgen', desc: 'Rust ↔ JavaScript glue.' },
		{ name: 'serde', url: 'https://serde.rs/', desc: 'Serialization for shot exports and persisted state.' },
		{ name: 'thiserror', url: 'https://github.com/dtolnay/thiserror', desc: 'Typed errors across the core.' },
		{ name: 'Decent de1app (TCL)', url: 'https://github.com/decentespresso/de1app', desc: 'Reference for the BLE protocol and MMR register map.' },
		{ name: 'reaprime', url: 'https://github.com/reaprime/reaprime', desc: 'Open Dart/Flutter DE1 client whose REST schema informs ours.' }
	];
</script>

<div class="st-shead">
	<div class="t-page-title st-shead-title">About</div>
	<div class="st-shead-sub">
		Crema is built with care for the moments before the first sip.
	</div>
</div>

<div class="st-about">
	<div class="st-about-mark">
		<!-- Matches the sidebar's brand glyph (CremaSidebar.svelte:86) — same
		     `.cside-mark-glyph` class, just larger via the size override in
		     `routes/settings/+page.svelte`. -->
		<div class="cside-mark-glyph" aria-label="Crema">C</div>
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

<!-- ── About ─────────────────────────────────────────────────────────── -->
<div class="st-group">
	<div class="st-group-head">
		<div class="st-group-title">About</div>
		<div class="st-group-sub">
			A modern web client for the Decent DE1, built on the same BLE protocol
			the legacy de1app and reaprime talk. Every preference, every shot,
			every bean entry lives in your browser — IndexedDB and localStorage on
			this device. Nothing leaves Crema unless you turn on Visualizer sync
			(Sharing settings) or upload a shot manually.
		</div>
	</div>
	<div class="st-group-rows">
		<div class="st-row">
			<div class="st-row-text">
				<div class="st-row-title">Project repository</div>
				<div class="st-row-sub">Source, issues, and release notes.</div>
			</div>
			<div class="st-row-control">
				<a
					class="st-btn st-btn-secondary"
					href="https://github.com/geota/crema"
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
				<div class="st-row-sub">The folks who make the DE1.</div>
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
				<div class="st-row-sub">Optional cloud sync for shots and beans.</div>
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

<!-- ── Also by the author ────────────────────────────────────────────── -->
<div class="st-group">
	<div class="st-group-head">
		<div class="st-group-title">Also by the author</div>
		<div class="st-group-sub">Other projects in the same spirit — local-first, free, no account.</div>
	</div>
	<div class="st-group-rows">
		<div class="st-row">
			<div class="st-row-text">
				<div class="st-row-title">pdf.maceiras.dev</div>
				<div class="st-row-sub">A browser-based PDF toolkit. Files never leave your device.</div>
			</div>
			<div class="st-row-control">
				<a
					class="st-btn st-btn-secondary"
					href="https://pdf.maceiras.dev"
					target="_blank"
					rel="noreferrer noopener"
				>
					<i class="ph ph-arrow-square-out" aria-hidden="true"></i><span>Visit</span>
				</a>
			</div>
		</div>
	</div>
</div>

<!-- ── Open source libraries ─────────────────────────────────────────── -->
<div class="st-group">
	<div class="st-group-head">
		<div class="st-group-title">Open source libraries</div>
		<div class="st-group-sub">
			Crema stands on the shoulders of these projects. Each link opens the
			project's home; please consider starring or sponsoring the ones you
			recognise.
		</div>
	</div>
	<ul class="ab-libs">
		{#each libraries as lib (lib.name)}
			<li class="ab-lib">
				<a
					class="ab-lib-name"
					href={lib.url}
					target="_blank"
					rel="noreferrer noopener"
				>
					{lib.name}
					<i class="ph ph-arrow-square-out" aria-hidden="true"></i>
				</a>
				<span class="ab-lib-desc">{lib.desc}</span>
			</li>
		{/each}
	</ul>
</div>

<!-- ── Terms of use ──────────────────────────────────────────────────── -->
<div class="st-group">
	<div class="st-group-head">
		<div class="st-group-title">Terms of use</div>
		<div class="st-group-sub">
			The short version: Crema is best-effort hobbyist software with no
			warranty. Please read this before relying on it.
		</div>
	</div>
	<div class="ab-terms">
		<div class="ab-terms-block">
			<div class="ab-terms-h">Best effort, no warranty</div>
			<p>
				Crema is provided <strong>as-is, with no warranty</strong> of any
				kind, express or implied. It is a community project, not a
				commercial product, and there is no support contract or
				service-level guarantee.
			</p>
		</div>
		<div class="ab-terms-block">
			<div class="ab-terms-h">Not every feature has been tested end-to-end</div>
			<p>
				Crema controls real hardware over Bluetooth. Many controls are
				validated against captured BLE traces, but not every combination
				of firmware version, profile, and DE1 model has been exercised on
				live machines. Treat new releases like a fresh roast — sip it
				before you brew the whole bag.
			</p>
		</div>
		<div class="ab-terms-block ab-terms-warn">
			<i class="ph ph-warning-octagon" aria-hidden="true"></i>
			<div>
				<div class="ab-terms-h">Some features can damage or brick your DE1</div>
				<p>
					Writes to the heater voltage, calibration values, factory
					reset, and any future firmware-update flow can leave your DE1
					in a state that requires service or factory recovery. The
					type-to-confirm gates exist for a reason — read them. If you
					are not sure what a setting does, leave it alone.
				</p>
			</div>
		</div>
		<div class="ab-terms-block">
			<div class="ab-terms-h">Your data stays on this device</div>
			<p>
				Settings, profiles, shot history, and bean entries are stored in
				your browser's <strong>IndexedDB</strong> and
				<strong>localStorage</strong> on this device. Nothing is sent to
				Crema servers (there are none). The only outbound traffic is the
				services you explicitly enable — Visualizer sync, webhooks, and
				the country lookup used by the mains-voltage confirm dialog.
			</p>
		</div>
		<div class="ab-terms-block">
			<div class="ab-terms-h">By using Crema you accept these terms</div>
			<p>
				You are responsible for your machine, your shots, and your
				calibration. Crema's authors and contributors are not liable for
				damage to hardware, lost data, or anything else that may arise
				from using this software.
			</p>
		</div>
	</div>
</div>

<style>
	/* ── Open-source libraries list ──────────────────────────────────────
	   A compact two-column grid of library entries inside the group's
	   bordered card. Each entry is a link (name) followed by a one-line
	   description, separated by a thin column on wider viewports. */
	.ab-libs {
		list-style: none;
		margin: 0;
		padding: 14px 16px;
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 10px 24px;
		align-items: start;
	}
	@media (max-width: 720px) {
		.ab-libs {
			grid-template-columns: 1fr;
		}
	}
	.ab-lib {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.ab-lib-name {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		color: var(--fg-1);
		text-decoration: none;
	}
	.ab-lib-name i {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.ab-lib-name:hover {
		color: var(--copper-400);
	}
	.ab-lib-name:hover i {
		color: var(--copper-400);
	}
	.ab-lib-desc {
		font-size: 11px;
		line-height: 1.45;
		color: rgba(var(--tint-rgb), 0.6);
	}

	/* ── Terms of use blocks ────────────────────────────────────────────
	   Stacked plain-prose blocks. The "may brick your DE1" block uses the
	   same red-orange treatment as the mains / calibration warn banners
	   so danger reads consistently across the app. */
	.ab-terms {
		display: flex;
		flex-direction: column;
		gap: 14px;
		padding: 14px 16px;
	}
	.ab-terms-block {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.ab-terms-h {
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.ab-terms p {
		margin: 0;
		font-size: 12px;
		line-height: 1.55;
		color: rgba(var(--tint-rgb), 0.7);
	}
	.ab-terms p strong {
		color: var(--fg-1);
		font-weight: 600;
	}
	.ab-terms-warn {
		flex-direction: row;
		align-items: flex-start;
		gap: 12px;
		padding: 12px 14px;
		border-radius: 8px;
		background: rgba(220, 80, 0, 0.12);
		border: 1px solid rgba(220, 80, 0, 0.35);
	}
	.ab-terms-warn i {
		flex-shrink: 0;
		font-size: 18px;
		margin-top: 1px;
		color: #c64500;
	}
	.ab-terms-warn .ab-terms-h {
		color: #c64500;
	}
	.ab-terms-warn p {
		color: #8a3500;
	}
</style>
