<script lang="ts">
	/**
	 * `/privacy` — Crema's Privacy Policy.
	 *
	 * Public, directly linkable for app-store submission (Google Play
	 * Console requires a privacy-policy URL accessible without
	 * authentication). Standalone layout — see the per-route
	 * `+layout@.svelte`.
	 */
	import pkg from '../../../package.json';
	const version = pkg.version;
	const lastUpdated = '2026-05-25';
</script>

<main class="legal">
	<header class="legal-head">
		<a class="legal-back" href="/">&larr; Crema</a>
		<h1>Privacy Policy</h1>
		<p class="legal-meta">Effective {lastUpdated} &middot; Crema v{version}</p>
	</header>

	<section>
		<h2>Summary</h2>
		<p>
			<strong>Crema is a local-first application.</strong> All settings,
			profiles, shot history, bean records, and Bluetooth-capture data
			are stored on your own device, in your browser's IndexedDB and
			localStorage (or, on Android, in app-private storage). Crema does
			not operate any servers and does not collect, transmit, or aggregate
			user data unless you explicitly enable an opt-in third-party
			integration described below.
		</p>
	</section>

	<section>
		<h2>1. Data We Collect</h2>
		<p>
			<strong>None.</strong> Crema has no analytics, no telemetry, no
			crash-reporting service, no advertising SDKs, and no remote
			logging. We do not receive any data from your device by default.
		</p>
	</section>

	<section>
		<h2>2. Data Stored On Your Device</h2>
		<p>The following data is kept locally on the device you use Crema on:</p>
		<ul>
			<li>
				<strong>Preferences</strong> — display units, theme, brew defaults
				(localStorage key prefix <code>crema.</code>).
			</li>
			<li>
				<strong>Custom profiles</strong> you create or import (localStorage).
			</li>
			<li>
				<strong>Shot history</strong> with telemetry curves (IndexedDB).
			</li>
			<li>
				<strong>Bean and roaster library</strong> entries (localStorage).
			</li>
			<li>
				<strong>Rolling Bluetooth-traffic capture</strong> — the last few
				minutes of BLE notifications, used to recover a shot's telemetry
				if the page is reloaded mid-shot (IndexedDB).
			</li>
			<li>
				<strong>Visualizer access tokens</strong>, if you sign in to that
				service (localStorage, rotated automatically).
			</li>
		</ul>
		<p>
			This data never leaves your device. You can erase all of it at any
			time via <em>Settings → Advanced → Factory reset</em> or by clearing
			your browser's site data for the Crema origin.
		</p>
	</section>

	<section>
		<h2>3. Third-Party Services (Opt-In)</h2>
		<p>
			Crema can connect to the following third-party services if and only
			if you enable them. None is required for normal operation.
		</p>

		<h3>3.1 visualizer.coffee</h3>
		<p>
			An optional cloud service for sharing and comparing espresso shots.
			When you sign in (Settings → Sharing), Crema uses OAuth 2.0 with PKCE
			to obtain an access token. The token is stored in localStorage on
			your device. Subsequently, when you trigger a sync (manually or via
			the auto-upload toggle), Crema sends shot telemetry, bean metadata,
			and optionally profile JSON to <a
				href="https://visualizer.coffee"
				target="_blank"
				rel="noreferrer noopener">visualizer.coffee</a
			>. That service has its own privacy policy. You can disconnect at any
			time from Settings → Sharing.
		</p>

		<h3>3.2 User-Supplied Webhooks</h3>
		<p>
			If you enable webhooks in Settings → Advanced and supply a URL,
			Crema sends outbound HTTP POST requests directly from your browser
			to that URL on the events you configure (shot completion, machine
			error, etc.). Crema does not proxy these requests; they go straight
			from your device to your endpoint.
		</p>

		<h3>3.3 ipinfo.io — Country Lookup</h3>
		<p>
			When you change the mains heater voltage or AC frequency for the
			first time, Crema performs a single IP-based country lookup against
			<a href="https://ipinfo.io" target="_blank" rel="noreferrer noopener"
				>ipinfo.io</a
			> to warn you about regional mismatches (e.g.&nbsp;120&thinsp;V vs
			230&thinsp;V). The result is cached locally; we do not retransmit it.
			Lookup failures are silent and do not affect functionality.
		</p>

		<h3>3.4 Google Drive — Optional Backup &amp; Restore</h3>
		<p>
			Crema offers an optional Google Drive integration so you can
			back up and restore your local data: app preferences, custom
			profiles, shot history (including telemetry curves), bean and
			roaster library, and maintenance state. This is strictly
			user-initiated: nothing is written to your Drive unless you
			sign in and explicitly trigger a backup or enable scheduled
			backups in Settings.
		</p>
		<ul>
			<li>
				<strong>Authentication</strong> uses Google's OAuth 2.0 flow.
				Crema receives a short-lived access token (and, with your
				consent, a refresh token) which is stored on your device only.
				Tokens are never transmitted to any Crema server (there is no
				Crema server).
			</li>
			<li>
				<strong>Scope</strong> is limited to
				<code>https://www.googleapis.com/auth/drive.file</code> —
				"See, edit, create, and delete only the specific Google Drive
				files you use with this app." Crema cannot list, read, or
				modify any file on your Drive that you did not create or
				select through Crema itself. You retain visibility and
				control of these files from the regular Drive UI.
			</li>
			<li>
				<strong>Payload</strong> consists solely of your own Crema data
				exported as JSON / JSONL files (the same format the local
				export buttons produce). No analytics, no device identifiers,
				no metrics — just the data you already see in the app.
			</li>
			<li>
				<strong>Restore</strong> downloads those files from your
				Drive back onto a new device or browser, replacing the local
				records you select.
			</li>
			<li>
				<strong>Revoking access</strong> — sign out from Settings →
				Sharing, or revoke the application from your Google account
				at
				<a
					href="https://myaccount.google.com/permissions"
					target="_blank"
					rel="noreferrer noopener">myaccount.google.com/permissions</a
				>. Revoking does not delete previously uploaded backup files;
				you can remove those directly from your Drive or let Crema
				delete them from within the app.
			</li>
			<li>
				<strong>Google's own privacy policy</strong> at
				<a
					href="https://policies.google.com/privacy"
					target="_blank"
					rel="noreferrer noopener">policies.google.com/privacy</a
				> applies to any data processed by Google Drive itself.
			</li>
		</ul>
		<p>
			Crema's use and transfer of information received from Google APIs
			adheres to the <a
				href="https://developers.google.com/terms/api-services-user-data-policy"
				target="_blank"
				rel="noreferrer noopener">Google API Services User Data Policy</a
			>, including the Limited Use requirements.
		</p>
	</section>

	<section>
		<h2>4. Bluetooth Device Data</h2>
		<p>
			When you pair a DE1 espresso machine or a compatible scale, Crema
			subscribes to Bluetooth GATT characteristics on those devices.
			Bluetooth payloads (state, telemetry, weight readings) are processed
			locally and stored as part of your shot history. They are not
			transmitted off-device by Crema. The OS-level Bluetooth permission
			prompt is governed by your browser or Android system.
		</p>
	</section>

	<section>
		<h2>5. Cookies and Tracking</h2>
		<p>
			Crema sets no cookies and does not use any tracking technology.
		</p>
	</section>

	<section>
		<h2>6. Children's Privacy</h2>
		<p>
			Crema is a tool for espresso enthusiasts and is not directed at
			children. We do not knowingly collect data from anyone, including
			minors (see Section&nbsp;1).
		</p>
	</section>

	<section>
		<h2>7. Your Rights</h2>
		<p>
			Because Crema stores all data on your own device, you have full
			control:
		</p>
		<ul>
			<li>
				<strong>Access</strong> — every record is visible in the app
				surfaces (History, Beans, Profiles, Settings).
			</li>
			<li>
				<strong>Export</strong> — shot history can be downloaded as
				community-v2 JSON; profiles can be exported per file or in
				bulk.
			</li>
			<li>
				<strong>Erasure</strong> — Settings → Advanced → Factory reset
				wipes every <code>crema.*</code> key from localStorage and IndexedDB
				on your device.
			</li>
		</ul>
	</section>

	<section>
		<h2>8. Open Source</h2>
		<p>
			Crema is open-source software (GPL-3.0). Its full source code is
			available at
			<a
				href="https://github.com/geota/crema"
				target="_blank"
				rel="noreferrer noopener">github.com/geota/crema</a
			>. You can audit exactly what the application does, build your own
			copy from source, or host it under your own domain.
		</p>
	</section>

	<section>
		<h2>9. Changes to This Policy</h2>
		<p>
			This policy may be updated as the application evolves. Material
			changes will be noted in the project release notes
			(<a
				href="https://github.com/geota/crema/releases"
				target="_blank"
				rel="noreferrer noopener">github.com/geota/crema/releases</a
			>). The "Effective" date at the top of this page reflects the most
			recent change.
		</p>
	</section>

	<section>
		<h2>10. Contact</h2>
		<p>
			Privacy questions can be raised as a GitHub issue at
			<a
				href="https://github.com/geota/crema/issues"
				target="_blank"
				rel="noreferrer noopener">github.com/geota/crema/issues</a
			>.
		</p>
	</section>

	<footer class="legal-foot">
		<p>
			<a href="/terms">Terms of Service</a> &middot;
			<a href="/">Back to Crema</a>
		</p>
	</footer>
</main>

<style>
	:global(body) {
		background: #fdfaf6;
		color: #1f1a14;
		margin: 0;
		font-family:
			ui-sans-serif,
			-apple-system,
			BlinkMacSystemFont,
			'Segoe UI',
			Roboto,
			sans-serif;
		line-height: 1.55;
	}
	.legal {
		max-width: 720px;
		margin: 0 auto;
		padding: 48px 24px 96px;
	}
	.legal-head {
		margin-bottom: 32px;
	}
	.legal-back {
		display: inline-block;
		font-size: 14px;
		color: #6a5c45;
		text-decoration: none;
		margin-bottom: 18px;
	}
	.legal-back:hover {
		color: #b07b32;
	}
	.legal h1 {
		font-family:
			'Newsreader',
			ui-serif,
			Georgia,
			serif;
		font-size: 36px;
		margin: 0 0 8px;
		letter-spacing: -0.01em;
	}
	.legal-meta {
		margin: 0;
		font-size: 13px;
		color: #6a5c45;
	}
	.legal h2 {
		font-size: 18px;
		margin: 32px 0 10px;
		letter-spacing: -0.01em;
	}
	.legal h3 {
		font-size: 15px;
		margin: 18px 0 6px;
	}
	.legal p,
	.legal ul {
		font-size: 15px;
		margin: 0 0 12px;
	}
	.legal ul {
		padding-left: 22px;
	}
	.legal li {
		margin: 4px 0;
	}
	.legal code {
		font-family: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, monospace;
		font-size: 13px;
		background: #f0e7d4;
		padding: 1px 5px;
		border-radius: 3px;
	}
	.legal a {
		color: #b07b32;
		text-decoration: underline;
	}
	.legal a:hover {
		color: #8a5e22;
	}
	.legal-foot {
		margin-top: 56px;
		padding-top: 20px;
		border-top: 1px solid #e9dfca;
		font-size: 13px;
		color: #6a5c45;
	}
	.legal-foot a {
		color: #6a5c45;
	}
	.legal-foot a:hover {
		color: #b07b32;
	}
</style>
