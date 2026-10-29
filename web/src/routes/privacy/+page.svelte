<script lang="ts">
	/**
	 * `/privacy` — Crema's Privacy Policy.
	 *
	 * Public, directly linkable for app-store submission (Google Play
	 * Console requires a privacy-policy URL accessible without
	 * authentication). Standalone layout — see the per-route
	 * `+layout@.svelte`.
	 *
	 * Shared by both shells: the web PWA links here in-app, and the Android
	 * tablet/phone About screen opens this same hosted page.
	 */
	import pkg from '../../../package.json';
	const version = pkg.version;
	const lastUpdated = '2026-10-28';
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
			<strong>Crema is a local-first application.</strong> Your settings,
			profiles, shot history, bean records, bean photos and Bluetooth-capture
			data are stored on your own device: in your browser's IndexedDB and
			localStorage on the web, or in app-private storage on Android. Crema
			does not operate any servers, has no Crema account and does not collect
			your data.
		</p>
		<p>
			Some features talk to third-party services directly from your device.
			Most only run when you turn them on, but a few happen automatically:
			loading the web app and its fonts, looking up roaster logos, and
			checking Decent's stolen-machine list. Section&nbsp;3 lists every one,
			what it sends and when.
		</p>
	</section>

	<section>
		<h2>1. Data We Collect</h2>
		<p>
			<strong>None.</strong> Crema has no analytics, no telemetry, no
			crash-reporting service, no advertising SDKs, and no remote
			logging. No data from your device is sent to the Crema project.
		</p>
	</section>

	<section>
		<h2>2. Data Stored On Your Device</h2>
		<p>The following data is kept locally on the device you use Crema on:</p>
		<ul>
			<li>
				<strong>Preferences</strong>: display units, theme, brew defaults,
				webhook settings (localStorage keys starting with <code>crema.</code>
				on the web).
			</li>
			<li><strong>Custom profiles</strong> you create or import.</li>
			<li>
				<strong>Shot history</strong> with telemetry curves (summaries in
				localStorage, telemetry in IndexedDB on the web).
			</li>
			<li><strong>Bean and roaster library</strong> entries, brew recipes and maintenance counters.</li>
			<li>
				<strong>Bean bag photos</strong>: optional images you capture with
				the camera or pick from your photo library and attach to a bean
				(IndexedDB on the web; app-private storage on Android). They leave
				your device only inside a backup you make.
			</li>
			<li>
				<strong>Bluetooth-traffic captures</strong>: on the web, the raw
				machine notifications of recent shots, used to recover a shot's
				telemetry if the page is reloaded mid-shot (IndexedDB). On Android,
				a recording of each Bluetooth session with the machine and scale,
				used to diagnose and replay problems; it is kept in the app's own
				folder on shared storage (<code>Android/data</code>), limited to the
				10 most recent sessions and to the last 14 days, and is never sent
				anywhere.
			</li>
			<li>
				<strong>Sign-in tokens</strong> for the services you connect: the
				visualizer.coffee tokens, the Decent account token, and the Google
				Drive token. On Android they are encrypted with a key held in the
				Android Keystore. On the web the Visualizer and Decent tokens are
				encrypted with a key kept in the browser's IndexedDB; this keeps them
				out of a copy of localStorage alone, but not out of a full copy of the
				browser profile. The web Drive token lives only in session storage and
				is gone when you close the tab. Your Decent password is never stored.
			</li>
		</ul>
		<p>
			You can erase this data at any time; see Section&nbsp;7. Data already
			sent to a third-party service (Section&nbsp;3) stays there until you
			delete it on that service.
		</p>
	</section>

	<section>
		<h2>3. Third-Party Services</h2>
		<p>
			Crema contacts the services below directly from your device. Crema does
			not proxy these requests and never sees them. Each service receives
			your IP address and normal browser or HTTP request information, and has
			its own privacy policy.
		</p>

		<h3>3.1 Automatic</h3>
		<ul>
			<li>
				<strong>Cloudflare Pages (web).</strong> The web app is served from
				Cloudflare's network, which handles your request for the app's files.
				Cloudflare's <a href="https://www.cloudflare.com/privacypolicy/" target="_blank" rel="noreferrer noopener">privacy policy</a>
				applies.
			</li>
			<li>
				<strong>Google Fonts (web).</strong> Each time the web app starts
				without a cached copy, your browser loads the font stylesheet from
				<code>fonts.googleapis.com</code> and font files from
				<code>fonts.gstatic.com</code>. Google receives your IP address and
				browser details; no Crema data is sent.
			</li>
			<li>
				<strong>Google favicon service (web).</strong> When a roaster has a
				website but no logo, the web app shows that site's icon by requesting
				it from Google (<code>t0.gstatic.com/faviconV2</code>). This happens
				automatically whenever such a roaster is displayed, and sends Google
				the roaster's website address (the host name, for example
				<code>onyxcoffeelab.com</code>) and your IP address.
			</li>
			<li>
				<strong>Roaster and bean images.</strong> A logo or image address
				stored with a roaster or bean (typed by you, imported, or pulled from
				visualizer.coffee) is loaded from the site that hosts it, which sees
				your IP address.
			</li>
			<li>
				<strong>Decent's stolen-machine list.</strong> When a DE1 connects,
				Crema downloads Decent's public list of stolen machine serial numbers
				from GitHub (<code>raw.githubusercontent.com</code>) at most once a
				day, and checks your machine against it on your device. Your serial
				number is not sent.
			</li>
		</ul>

		<h3>3.2 decentespresso.com: Decent account and shot upload</h3>
		<p>
			If you link your Decent account (Settings &rarr; Sharing), Crema sends
			your account email and password to <code>decentespresso.com</code> once,
			to obtain an account token; the password is not stored. Crema then uses
			the email and token to read the machine serial numbers registered to
			your account and to upload shots.
		</p>
		<ul>
			<li>
				<strong>Automatic upload once linked.</strong> Linking turns on
				"upload each shot". From then on every finished shot is uploaded as
				it completes, until you switch the toggle off or unlink. You can also
				upload earlier shots on request.
			</li>
			<li>
				<strong>What a shot upload contains:</strong> the shot's time and a
				Crema shot id; the telemetry curves (pressure, flow, mix, group and
				steam temperatures, their targets, the profile step, the machine
				state, scale weight and weight flow); the profile (its name and
				steps); target and actual dose and yield; TDS and extraction yield;
				your rating and tasting notes; the bean, roaster, roast date and
				level; grinder and setting; barista and drinker names if you entered
				them; your DE1's serial number, model and firmware version; and the
				Crema version. Nothing else is sent.
			</li>
			<li>
				<strong>Unlinking</strong> forgets the token on this device. Decent
				cannot revoke a token for one device; if a device is lost, change your
				Decent password. Uploaded shots stay in your Decent account until you
				delete them there.
			</li>
		</ul>

		<h3>3.3 visualizer.coffee</h3>
		<p>
			An optional service for sharing and comparing espresso shots. When you
			sign in (Settings &rarr; Sharing), Crema uses OAuth 2.0 with PKCE to
			obtain access tokens, stored on your device as described in
			Section&nbsp;2. Crema then sends shot telemetry, bean and roaster
			records, and, if you choose, the profile and your tasting notes to
			<a href="https://visualizer.coffee" target="_blank" rel="noreferrer noopener">visualizer.coffee</a>, either when you
			sync or automatically if auto-upload is on. Uploads use the privacy
			level you pick (public, unlisted or private). Disconnecting revokes the
			tokens; uploaded shots stay on visualizer.coffee until you delete them
			there.
		</p>

		<h3>3.4 User-Supplied Webhooks</h3>
		<p>
			If you enable webhooks in Settings &rarr; Advanced and supply a URL,
			Crema sends HTTP POST requests directly from your browser to that URL
			for the events you choose. A shot event carries the profile name and
			shot metrics; connection events carry your DE1's or scale's device name
			and identifier. Restoring a backup never sets a webhook without asking
			you first.
		</p>

		<h3>3.5 ipinfo.io: Country Lookup (web)</h3>
		<p>
			When you change the mains heater voltage or AC frequency for the
			first time, Crema performs a single IP-based country lookup against
			<a href="https://ipinfo.io" target="_blank" rel="noreferrer noopener">ipinfo.io</a> to warn you about regional
			mismatches (e.g.&nbsp;120&thinsp;V vs 230&thinsp;V). The result is
			cached locally. Lookup failures are silent and do not affect
			functionality.
		</p>

		<h3>3.6 Firmware Downloads and Update Checks</h3>
		<ul>
			<li>
				<strong>DE1 firmware.</strong> When you start a firmware update,
				Crema downloads the official image from Decent's update server
				(<code>fast.decentespresso.com</code>). On the web your browser may
				download it for you to pick instead.
			</li>
			<li>
				<strong>App updates (Android).</strong> When you tap "Check for
				updates", or once a day if you turn on automatic checks, the app asks
				GitHub (<code>api.github.com</code>) for the latest Crema release.
			</li>
		</ul>

		<h3>3.7 Google Drive: Optional Backup &amp; Restore</h3>
		<p>
			Crema offers an optional Google Drive integration so you can back up and
			restore your local data: app preferences, custom profiles, shot history
			(including telemetry curves), bean and roaster library (including any
			bean photos), recipes and maintenance state. Nothing is written to your
			Drive unless you connect it and start a backup or turn on scheduled
			backups.
		</p>
		<ul>
			<li>
				<strong>Authentication</strong> uses Google's OAuth 2.0. On the web,
				the sign-in loads Google's Identity Services script from
				<code>accounts.google.com</code> and Crema receives a short-lived
				access token, kept only for the browser session. On Android, Crema
				also receives a refresh token, stored encrypted on the device.
				Tokens are never sent anywhere except Google.
			</li>
			<li>
				<strong>Scope</strong> is limited to
				<code>https://www.googleapis.com/auth/drive.file</code>:
				"See, edit, create, and delete only the specific Google Drive
				files you use with this app." Crema cannot list, read, or modify any
				file on your Drive that you did not create or select through Crema.
			</li>
			<li>
				<strong>Payload</strong> consists solely of your own Crema data
				exported as a backup archive (the same format the local export
				buttons produce).
			</li>
			<li>
				<strong>Revoking access</strong>: disconnecting in Settings &rarr;
				Sharing (or erasing Crema's data) asks Google to revoke Crema's
				access, on the web and on Android. If that request cannot reach
				Google, for example offline, revoke the application at
				<a href="https://myaccount.google.com/permissions" target="_blank" rel="noreferrer noopener">myaccount.google.com/permissions</a>.
				Revoking does not delete backup files already uploaded; remove those
				from your Drive.
			</li>
			<li>
				<strong>Google's own privacy policy</strong> at
				<a href="https://policies.google.com/privacy" target="_blank" rel="noreferrer noopener">policies.google.com/privacy</a>
				applies to any data processed by Google Drive itself.
			</li>
		</ul>
		<p>
			Crema's use and transfer of information received from Google APIs
			adheres to the <a href="https://developers.google.com/terms/api-services-user-data-policy" target="_blank" rel="noreferrer noopener">Google API Services User Data Policy</a>,
			including the Limited Use requirements.
		</p>
	</section>

	<section>
		<h2>4. Device Permissions</h2>
		<p>
			Crema requests the following device permissions only to provide
			features you invoke. Everything they produce is processed locally and
			leaves your device only through a service you connect (Section&nbsp;3)
			or a backup you make. You can grant or revoke each
			one at any time in your browser or Android system settings.
		</p>
		<ul>
			<li>
				<strong>Bluetooth</strong> — to discover and pair a DE1 espresso
				machine and a compatible scale and subscribe to their Bluetooth
				GATT characteristics. Bluetooth payloads (state, telemetry, weight
				readings) are processed locally and stored as part of your shot
				history. On Android the scan is declared
				<code>neverForLocation</code>; Crema does not use Bluetooth to
				derive your physical location. Denying Bluetooth disables machine
				control.
			</li>
			<li>
				<strong>Camera</strong> (Android) — used only when you choose to
				take a photo of a coffee bag. The captured image is written to
				app-private storage and attached to that bean; it is uploaded only
				inside a backup you make.
			</li>
			<li>
				<strong>Photos / media</strong> — used only when you pick an
				existing image for a bean through the system photo picker. Crema
				receives only the single image you select; it does not browse or
				index your library.
			</li>
		</ul>
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
			Because Crema stores your data on your own device, you have full
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
				<strong>Erasure</strong>: on the web, Settings &rarr; Advanced &rarr;
				Factory reset deletes every Crema database and <code>crema.</code>
				storage key in this browser, including the sign-in tokens, after
				revoking the visualizer.coffee and Google Drive access. On Android,
				Settings &rarr; Erase all data deletes your beans, photos, shots,
				recipes, custom profiles, preferences, maintenance counters and
				Bluetooth captures, and the Visualizer, Decent and Google Drive
				sign-ins together with their sync settings and activity logs, with
				no backup copy left behind; it revokes the visualizer.coffee and
				Google Drive access too. Clearing the
				site's data in your browser, or uninstalling the Android app, also
				removes everything. Backup files you saved, and data already sent to
				a service in Section&nbsp;3, are not affected.
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
		<p>
			Crema was built with heavy use of AI / large-language-model
			(LLM)-assisted development — including Anthropic's Claude (via Claude
			Code) for a substantial portion of its code and design. This concerns
			how the software was <em>written</em>, not how it <em>runs</em>: no AI
			or LLM service is involved in operating Crema or processing your data,
			and the local-first, no-collection handling described above is unchanged.
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
