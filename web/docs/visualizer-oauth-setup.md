# Visualizer OAuth setup

Crema authenticates with [visualizer.coffee](https://visualizer.coffee) via
**OAuth 2.0 Authorization Code + PKCE** (RFC 7636). Visualizer runs the
[Doorkeeper](https://github.com/doorkeeper-gem/doorkeeper) gem, which supports
PKCE for **public clients** — clients that can't keep a secret, like a static
PWA shipped to every browser.

This document walks through:

1. Registering a Doorkeeper application on Visualizer.
2. Whitelisting Crema's redirect URI(s).
3. Wiring the `Client UID` into the Crema build via env var.
4. Scopes Crema requests and what they unlock.
5. How sign-out and token refresh behave.
6. Security trade-offs (token storage, XSS surface).

---

## 1. Register a Doorkeeper application on Visualizer

1. Sign in to https://visualizer.coffee.
2. Open https://visualizer.coffee/oauth/applications and click **New
   Application**.
3. Fill the form:

   | Field             | Value                                                     |
   | ----------------- | --------------------------------------------------------- |
   | **Name**          | `Crema (dev)` or `Crema` for production                   |
   | **Confidential**  | **No** — Crema is a static PWA, can't keep a secret       |
   | **Redirect URI**  | one per line, see §2                                      |
   | **Scopes**        | leave default (`read upload write`) — Crema asks for all  |

4. Save. Doorkeeper prints a **Client UID** (the value Crema needs) and a
   Client Secret (Crema does **not** use the secret — public client + PKCE).

> Repeat the process once per environment (one app for local dev, one for
> production). Keeping them separate means an accidental secret leak from
> dev never touches prod, and each app's redirect-URI list stays tight.

## 2. Whitelist redirect URIs

Crema derives the redirect URI from `window.location.origin` at runtime, so
the path is always `/auth/visualizer/callback`. List each origin you sign
in from:

```
http://localhost:5173/auth/visualizer/callback
https://crema.coffee/auth/visualizer/callback
https://<your-cf-pages-preview>.pages.dev/auth/visualizer/callback
```

Doorkeeper requires an exact match including scheme, host, and port — wildcard
patterns are not supported. Add each origin as a separate line in the
**Redirect URI** field.

## 3. Configure Crema with the Client UID

Copy the env-var template and paste the UID:

```sh
cp web/.env.example web/.env.local
$EDITOR web/.env.local
# VITE_VISUALIZER_CLIENT_ID=<paste your Client UID here>
```

Vite reads `.env.local` automatically; `pnpm dev` and `pnpm build` will pick
it up. The `.env.local` file is git-ignored.

> **Note** Vite only exposes variables prefixed `VITE_` to the client
> bundle. The Client UID is intentionally public — Doorkeeper's public-client
> + PKCE protocol does not consider it a secret.

If the env var is missing, Crema renders the bean-sync card with a
"OAuth client not configured" message and a link back to this doc — the
build still ships, just without sign-in.

## 4. Scopes

Crema requests three scopes per the Visualizer OpenAPI spec (v1.8.2):

| Scope    | What it unlocks                                                     |
| -------- | ------------------------------------------------------------------- |
| `read`   | Read account metadata, shots, roasters, coffee bags                 |
| `upload` | Upload shot files (lower-risk ingestion scope)                      |
| `write`  | Update/delete shots, manage roasters/bags (**Premium-only writes**) |

Visualizer's bag and roaster *writes* are paywalled behind a €5/mo Premium
subscription. On the free tier the bean-sync still works one-way (pulls
remote data into Crema) — the sync UI detects the 403 on the first push
attempt and downshifts to "Connected (read-only — free tier)".

## 5. Sign-out and token refresh

* **Refresh** — access tokens are short-lived (Doorkeeper default: 2 h).
  Crema's token-store proactively refreshes when there's < 5 minutes left
  on the clock, using the rotating refresh token Doorkeeper returns. If
  the refresh call fails (revoked, expired, server error), Crema clears
  the local tokens and prompts re-sign-in.
* **Disconnect** — the "Disconnect" button calls Doorkeeper's
  `/oauth/revoke` endpoint with the current access token, then clears
  the local token blob. The revoke is best-effort: if it fails locally
  we still log the user out client-side.

## 6. Security trade-offs

**Token storage** — Crema stores the access + refresh tokens in
`localStorage` under `crema.visualizer.tokens.v1`. A Service Worker token
broker (the alternative pattern that keeps tokens out of JS reach) was
deliberately deferred for v1; the threat model for coffee data is
low-value, short-lived access tokens + refresh-token rotation keep the
blast radius of an XSS to *hours*, not weeks, and Crema's tight CSP
keeps the XSS surface itself small. If data sensitivity ever increases
(e.g. payments or PII), revisit and move tokens behind a SW broker.

**PKCE** — every sign-in generates a fresh 64-character code verifier in
`sessionStorage` (cleared on tab close), so an authorization code
intercepted in transit is useless without the verifier from this tab.

**State** — every sign-in generates a fresh 16-byte CSRF state string,
also in `sessionStorage`. The callback page rejects mismatched state.

**Client ID is public** — Doorkeeper's PKCE flow treats the client ID as
non-secret. Anyone can read it out of the bundled JS; only the redirect-
URI whitelist and PKCE binding stop a third-party app from pretending to
be Crema.
