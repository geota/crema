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

<!-- ── Legal ─────────────────────────────────────────────────────────── -->
<div class="st-group">
	<div class="st-group-head">
		<div class="st-group-title">Legal</div>
		<div class="st-group-sub">
			Crema is best-effort hobbyist software with no warranty. The full
			Terms of Service and Privacy Policy are public pages.
		</div>
	</div>
	<div class="st-group-rows">
		<div class="st-row">
			<div class="st-row-text">
				<div class="st-row-title">Terms of Service</div>
				<div class="st-row-sub">
					License, hardware-risk disclaimer, limitation of liability.
				</div>
			</div>
			<div class="st-row-control">
				<a
					class="st-btn st-btn-secondary"
					href="/terms"
					target="_blank"
					rel="noreferrer noopener"
				>
					<i class="ph ph-arrow-square-out" aria-hidden="true"></i><span>Read</span>
				</a>
			</div>
		</div>
		<div class="st-row">
			<div class="st-row-text">
				<div class="st-row-title">Privacy Policy</div>
				<div class="st-row-sub">
					Crema is local-only. No analytics, no cloud, no tracking — just
					what you opt in to.
				</div>
			</div>
			<div class="st-row-control">
				<a
					class="st-btn st-btn-secondary"
					href="/privacy"
					target="_blank"
					rel="noreferrer noopener"
				>
					<i class="ph ph-arrow-square-out" aria-hidden="true"></i><span>Read</span>
				</a>
			</div>
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

</style>
