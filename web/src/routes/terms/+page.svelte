<script lang="ts">
	/**
	 * `/terms` — Crema's Terms of Service.
	 *
	 * Public, directly linkable for app-store submission (Google Play
	 * Console requires a TOS URL accessible without authentication). Kept
	 * outside the main sidebar/Bluetooth-gate layout so reviewers on any
	 * browser see it. Plain CSS so no design tokens are required.
	 */
	import pkg from '../../../package.json';
	const version = pkg.version;
	const lastUpdated = '2026-05-25';
</script>

<main class="legal">
	<header class="legal-head">
		<a class="legal-back" href="/">&larr; Crema</a>
		<h1>Terms of Service</h1>
		<p class="legal-meta">Effective {lastUpdated} &middot; Crema v{version}</p>
	</header>

	<section>
		<h2>1. Acceptance of Terms</h2>
		<p>
			By accessing or using Crema (the "Service" — a web application and
			Android client for the Decent Espresso DE1 machine, available at
			<a href="https://crema.maceiras.dev">crema.maceiras.dev</a>), you agree
			to these Terms of Service. If you do not agree, do not use the
			Service.
		</p>
	</section>

	<section>
		<h2>2. License</h2>
		<p>
			Crema is open-source software released under the
			<a
				href="https://www.gnu.org/licenses/gpl-3.0.html"
				target="_blank"
				rel="noreferrer noopener">GNU General Public License v3.0 (GPL-3.0)</a
			>. The source is available at
			<a
				href="https://github.com/geota/crema"
				target="_blank"
				rel="noreferrer noopener">github.com/geota/crema</a
			>. You may use, modify, and redistribute the Service in accordance
			with that license. Crema is not affiliated with Decent Espresso Inc.
		</p>
	</section>

	<section>
		<h2>3. Hardware Risk Disclaimer</h2>
		<p>
			Crema communicates with espresso-machine hardware over Bluetooth
			Low Energy. <strong>Improper use can damage or brick your DE1.</strong>
			Specifically:
		</p>
		<ul>
			<li>
				Writing an incorrect mains heater voltage (e.g.&nbsp;120&thinsp;V on a
				230&thinsp;V outlet, or vice versa) can permanently damage the
				heater element.
			</li>
			<li>
				Writing incorrect calibration values can produce unsafe pressure,
				flow, or temperature behaviour.
			</li>
			<li>
				Firmware updates carry the standard risks of any embedded-device
				flashing operation.
			</li>
		</ul>
		<p>
			You are responsible for verifying every change before committing it.
			Crema's type-to-confirm dialogs exist for this reason. <strong
				>If you are unsure what a setting does, leave it alone.</strong
			>
		</p>
	</section>

	<section>
		<h2>4. No Warranty</h2>
		<p>
			THE SERVICE IS PROVIDED <strong>"AS IS"</strong>, WITHOUT WARRANTY OF
			ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
			WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND
			NONINFRINGEMENT. The authors and copyright holders make no warranty
			that the Service will be uninterrupted, error-free, or free of harmful
			components.
		</p>
	</section>

	<section>
		<h2>5. Limitation of Liability</h2>
		<p>
			IN NO EVENT SHALL THE AUTHORS, CONTRIBUTORS, OR COPYRIGHT HOLDERS BE
			LIABLE FOR ANY CLAIM, DAMAGES (INCLUDING DAMAGE TO HARDWARE, LOST
			DATA, OR ECONOMIC LOSS), OR OTHER LIABILITY, WHETHER IN AN ACTION OF
			CONTRACT, TORT, OR OTHERWISE, ARISING FROM, OUT OF, OR IN CONNECTION
			WITH THE SERVICE OR ITS USE.
		</p>
	</section>

	<section>
		<h2>6. User Responsibilities</h2>
		<p>You agree to:</p>
		<ul>
			<li>
				Verify that any voltage, calibration, or firmware setting you apply
				matches your hardware before confirming.
			</li>
			<li>
				Maintain your own backups of profiles, shot history, and bean
				records. Crema stores data locally on your device; clearing your
				browser data or factory-resetting the app erases this data
				permanently.
			</li>
			<li>
				Use the Service only for lawful purposes consistent with the GPL-3.0
				license.
			</li>
		</ul>
	</section>

	<section>
		<h2>7. Third-Party Services</h2>
		<p>
			Crema can optionally integrate with third-party services that you
			explicitly enable:
		</p>
		<ul>
			<li>
				<strong>visualizer.coffee</strong> — for syncing shot history and
				bean records. Subject to that service's own terms and privacy
				policy.
			</li>
			<li>
				<strong>User-supplied webhook URLs</strong> — outbound POST
				requests to addresses you configure.
			</li>
			<li>
				<strong>ipinfo.io</strong> — a one-time IP-based country lookup,
				cached locally, used to surface a mismatch warning when you set the
				mains voltage. Optional; failures are silent.
			</li>
		</ul>
		<p>
			None of these are required to use Crema. Crema operates entirely
			locally by default.
		</p>
	</section>

	<section>
		<h2>8. Changes to These Terms</h2>
		<p>
			These Terms may be updated from time to time. The "Effective" date at
			the top of this page indicates the date of the most recent change.
			Material changes will be noted in the project release notes
			(<a
				href="https://github.com/geota/crema/releases"
				target="_blank"
				rel="noreferrer noopener">github.com/geota/crema/releases</a
			>).
		</p>
	</section>

	<section>
		<h2>9. Governing Law</h2>
		<p>
			These Terms are governed by the laws of the jurisdiction in which
			the project maintainer resides, without regard to its conflict-of-law
			provisions. Open-source contributions are governed by the GPL-3.0
			license linked in Section&nbsp;2.
		</p>
	</section>

	<section>
		<h2>10. Contact</h2>
		<p>
			Questions about these Terms can be raised as a GitHub issue at
			<a
				href="https://github.com/geota/crema/issues"
				target="_blank"
				rel="noreferrer noopener">github.com/geota/crema/issues</a
			>.
		</p>
	</section>

	<footer class="legal-foot">
		<p>
			<a href="/privacy">Privacy Policy</a> &middot;
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
