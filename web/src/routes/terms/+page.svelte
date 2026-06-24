<script lang="ts">
	/**
	 * `/terms` — Crema's Terms of Service.
	 *
	 * Public, directly linkable for app-store submission (Google Play
	 * Console requires a TOS URL accessible without authentication). Kept
	 * outside the main sidebar/Bluetooth-gate layout so reviewers on any
	 * browser see it. Plain CSS so no design tokens are required.
	 *
	 * Shared by both shells: the web PWA links here in-app, and the Android
	 * tablet/phone About screen opens this same hosted page.
	 */
	import pkg from '../../../package.json';
	const version = pkg.version;
	const lastUpdated = '2026-06-24';
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
			<a href="https://crema.maceiras.dev">crema.maceiras.dev</a> and on
			Google Play), you agree to these Terms of Service. If you do not
			agree, do not use the Service.
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
		<p>
			Crema is independent, best-effort hobbyist software provided free of
			charge. <strong>You use it entirely at your own risk.</strong> The
			author makes no commitment that Crema is safe, accurate, or fit to
			control your equipment, and <strong>accepts no liability for any
			damage to your DE1, grinder, scale, or other property, or for any
			injury or loss, however caused,</strong> arising from your use of
			Crema — including damage from a bug, an incorrect reading, or a
			setting you choose to apply. If you are not willing to accept this
			risk, do not use Crema to change machine settings.
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
			TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT SHALL
			THE AUTHORS, CONTRIBUTORS, OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
			CLAIM, DAMAGES (INCLUDING DAMAGE TO HARDWARE, LOST DATA, OR ECONOMIC
			LOSS), OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT, OR
			OTHERWISE, ARISING FROM, OUT OF, OR IN CONNECTION WITH THE SERVICE OR
			ITS USE.
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
				<strong>Google Drive</strong> — optional backup and restore of your
				own Crema data. Subject to Google's terms and privacy policy.
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
		<h2>8. Assumption of Risk &amp; Best-Effort Software</h2>
		<p>
			Crema is maintained on a best-effort basis by a single author as a
			free, open-source project. It is offered in the hope that it is
			useful, but with <strong>no service-level commitment, no guarantee of
			availability, and no obligation to provide support, updates, or
			fixes.</strong> Features may change or be removed between releases. By
			using Crema you accept these limitations and the hardware risks in
			Section&nbsp;3.
		</p>
	</section>

	<section>
		<h2>9. Google Play &amp; the Android App</h2>
		<p>
			The Crema Android app is distributed through Google Play. Your
			download and use of it are also subject to the
			<a
				href="https://play.google.com/about/play-terms/"
				target="_blank"
				rel="noreferrer noopener">Google Play Terms of Service</a
			> and the applicable Google Play policies, in addition to these Terms.
		</p>
		<ul>
			<li>
				Crema is an independent, open-source project. It is
				<strong>not created, sponsored, endorsed by, or affiliated with
				Google LLC or Decent Espresso Inc.</strong> Google is not a party
				to these Terms and bears no responsibility for the app or for
				providing support for it.
			</li>
			<li>
				The app requests device permissions you can grant or deny in
				Android settings: <strong>Bluetooth</strong> (to discover and
				communicate with your DE1 and scale) and <strong>camera / photo
				access</strong> (only if you choose to attach a photo to a bean).
				Denying Bluetooth disables machine control. How this data is
				handled is described in the <a href="/privacy">Privacy Policy</a>.
			</li>
			<li>
				The app contains no in-app purchases, no subscriptions, and no
				advertising.
			</li>
		</ul>
	</section>

	<section>
		<h2>10. Eligibility</h2>
		<p>
			You must be at least 13 years old, or the minimum age of digital
			consent in your jurisdiction, to use Crema. Crema is intended for use
			with consumer espresso equipment by the equipment's owner or an
			authorised user.
		</p>
	</section>

	<section>
		<h2>11. Acceptable Use</h2>
		<p>You agree not to use Crema to:</p>
		<ul>
			<li>violate any applicable law or regulation;</li>
			<li>
				access, scan, or interfere with any Bluetooth device, network, or
				system you do not own or are not authorised to control;
			</li>
			<li>
				remove, obscure, or misrepresent the GPL-3.0 license, copyright
				notices, or authorship.
			</li>
		</ul>
		<p>
			Beyond the rights granted to you by the GPL-3.0 license
			(Section&nbsp;2), no other rights are granted.
		</p>
	</section>

	<section>
		<h2>12. Indemnification</h2>
		<p>
			To the maximum extent permitted by law, you agree to indemnify and
			hold harmless the authors, contributors, and copyright holders from
			and against any claim, demand, loss, or expense (including reasonable
			legal fees) arising out of your use or misuse of Crema, your violation
			of these Terms, or your violation of any law or the rights of any
			third party.
		</p>
	</section>

	<section>
		<h2>13. Term &amp; Termination</h2>
		<p>
			These Terms apply for as long as you use Crema; you may stop using it
			at any time. The disclaimers, the limitation of liability, and the
			indemnification provisions survive any termination. Nothing in this
			section limits the rights granted to you in the source code under the
			GPL-3.0 license.
		</p>
	</section>

	<section>
		<h2>14. Changes to These Terms</h2>
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
		<h2>15. Governing Law &amp; Entire Agreement</h2>
		<p>
			These Terms are governed by the laws of the jurisdiction in which
			the project maintainer resides, without regard to its conflict-of-law
			provisions. Open-source contributions are governed by the GPL-3.0
			license linked in Section&nbsp;2.
		</p>
		<p>
			These Terms, together with the GPL-3.0 license, constitute the entire
			agreement between you and the author regarding Crema and supersede any
			prior understanding. If any provision is held unenforceable, the
			remaining provisions remain in full effect.
		</p>
	</section>

	<section>
		<h2>16. Contact</h2>
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
