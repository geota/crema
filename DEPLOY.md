# Deployment — Cloudflare Pages

Crema's web shell deploys to **Cloudflare Pages** as a static PWA. Production lives at **<https://crema.maceiras.dev>**.

## One-time setup (Cloudflare Pages dashboard)

1. **Create the Pages project**
   - Cloudflare Dashboard → Workers & Pages → Create application → **Pages** → **Connect to Git**
   - Select GitHub → `geota/crema` → branch `main`
   - Project name: **`crema`** (must match `name` in `wrangler.jsonc`)

2. **Build configuration**
   | Setting | Value |
   |---|---|
   | Framework preset | None |
   | Build command | `cd web && pnpm install && pnpm build` |
   | Build output directory | `web/build` |
   | Root directory | *(leave blank — repo root)* |

   The build pulls Rust + wasm-pack via the Cloudflare build image; `pnpm build` runs `pnpm wasm && vite build`, so the Rust core's WebAssembly bundle is produced + bundled in one shot.

3. **Environment variables** (Settings → Environment variables → Production)

   | Variable | Value | Why |
   |---|---|---|
   | `VITE_VISUALIZER_CLIENT_ID` | *(the production Doorkeeper Client UID from <https://visualizer.coffee/oauth/applications>)* | Required for Visualizer OAuth. Vite bakes this into the bundle at build time. |
   | `NODE_VERSION` | `20` | Pin Node so the build env matches local dev. |
   | `PNPM_VERSION` | `9` | Pin pnpm. |

   > Keep the production Client UID **out of git**. It only lives in the CF dashboard. The `web/.env.example` template carries an empty placeholder.

4. **Custom domain**
   - Pages project → Custom domains → Set up a custom domain → `crema.maceiras.dev`
   - Add the CNAME record CF prompts for in your DNS provider (Cloudflare DNS if the zone is here; external otherwise)
   - HTTPS auto-provisions via CF's universal SSL

5. **Verify the first deploy**
   - Push a commit to `main` → CF Pages auto-builds
   - The Pages dashboard's deployment log shows the build output
   - First build takes ~3-5 min (Rust + wasm-pack); subsequent builds are faster
   - Visit `https://crema.maceiras.dev` — Visualizer "Sign in" should redirect to the OAuth flow without an env-var error

## Manual deploy (hotfix path)

If you need to push an artifact without going through GitHub:

```bash
cd web
VITE_VISUALIZER_CLIENT_ID=<prod-uid> pnpm build
cd ..
npx wrangler pages deploy web/build --project-name crema --branch main
```

This bypasses the CF-side build entirely — useful for ad-hoc deploys when CI is broken.

## Local dev

Production deploys use the env var above. Local dev reads `web/.env.local` (gitignored):

```bash
cp web/.env.example web/.env.local
$EDITOR web/.env.local      # paste the DEV Client UID (separate from prod)
cd web && pnpm dev
```

> **Use separate Doorkeeper applications for dev and prod.** The prod app must NOT include `https://localhost:5173/...` in its redirect URI whitelist — that opens an attack surface where a malicious page on localhost can phish prod-app tokens. Register two apps at <https://visualizer.coffee/oauth/applications> with appropriate redirect URIs each.

## Files involved

| Path | Purpose |
|---|---|
| `wrangler.jsonc` | CF Pages project config (name + output dir + compat date). Read by `wrangler pages deploy`. |
| `web/static/_headers` | Cache + security headers (CSP, COOP/COEP, etc.) — copied verbatim into `web/build/` by adapter-static. CF Pages applies them per-request. |
| `web/svelte.config.js` | `adapter-static` configuration. |
| `web/.env.example` | Template for local dev's `.env.local`. |
| `web/.env.local` | **Local-only.** Gitignored. Holds your dev Client UID. |

## CI and branch protection

CI quality gates live in `.github/workflows/ci.yml` (Rust core + SvelteKit web shell, run in parallel on every PR + push to `main`). CI does **not** deploy — Cloudflare Pages auto-deploys from `main` independently. See the workflow file for the full step list.

Branch-protection rules are not in YAML; they're configured in GitHub Settings → Branches. Recommended rules for `main`:

- **Require status checks**: `rust (core)` and `web (svelte)` (both jobs from `ci.yml`).
- **Require branches to be up to date before merging.**
- **Require linear history** (no merge commits via UI — squash or rebase only).
- **Disable force-push and deletion** of `main`.
- **Allow administrators to bypass**: enabled, for emergencies.

PR titles are checked by `pr-title.yml` (loose Conventional Commits: `feat|fix|chore|docs|refactor|test|ci`, optional scope, then `: description`). It's a separate workflow, so you can promote it to a required status check independently of the build gates.
