# Deployment — Cloudflare Pages

Crema's web shell deploys to **Cloudflare Pages** as a static PWA. Production lives at **<https://crema.maceiras.dev>**.

> **Deploys happen only when a version tag (`v*.*.*`) is pushed.** Pushing or merging to `main` does **not** deploy. The Pages project `crema` is a *Direct Upload* project with no Git connection (`wrangler pages project list` shows "Git Provider: No"), so the only way a build reaches it is `wrangler pages deploy`: from the release workflow on a tag, or by hand (below).

## How a deploy happens: tag a release

Cut a release with `scripts/cut-release.sh` (two steps; see the script header for details):

```bash
scripts/cut-release.sh 0.1.0 -m "Short user-facing note."   # 1. opens the release-notes PR
# review and merge that PR, then:
scripts/cut-release.sh --tag 0.1.0                           # 2. tags main and pushes v0.1.0
```

The tag push runs `.github/workflows/release.yml`. Its `web (pwa)` job:

1. Stamps the version from the tag into `web/package.json`. The About panel, the backup header and Decent uploads report it, and the committed value isn't bumped reliably.
2. Builds with `pnpm build` (`pnpm wasm` then `vite build`), with `VITE_VISUALIZER_CLIENT_ID` from the repo secrets.
3. Runs `wrangler pages deploy web/build --project-name crema --branch main`. The `main` branch is production in the Pages project.

The same tag also builds the signed Android APK/AAB and publishes the GitHub Release.

**"Run workflow" (manual dispatch) builds the artifacts but does not deploy.** The deploy step runs only for `refs/tags/*`.

### GitHub secrets the web deploy needs

Settings → Secrets and variables → Actions:

| Secret | Value | Why |
|---|---|---|
| `VITE_VISUALIZER_CLIENT_ID` | the production Doorkeeper Client UID from <https://visualizer.coffee/oauth/applications> | Required for Visualizer OAuth. Vite bakes it into the bundle at build time. |
| `CLOUDFLARE_API_TOKEN` | API token with **Pages: Edit** | Used by `wrangler pages deploy`. Without it, the deploy step is skipped silently. |
| `CLOUDFLARE_ACCOUNT_ID` | the Cloudflare account id | Used by `wrangler pages deploy`. |

> Keep the production Client UID **out of git**. The `web/.env.example` template carries an empty placeholder. (It's a public OAuth client id and ends up in the shipped JavaScript, but it's per-environment, so it isn't committed.)

## Manual deploy (untagged / hotfix)

To put the current `main` live without cutting a release, build and deploy it yourself from a clean checkout of `origin/main`:

```bash
npx wrangler login                      # once; needs Pages access to the account
git fetch origin
git worktree add --detach /tmp/crema-deploy origin/main
cd /tmp/crema-deploy/web
npm pkg set version="0.0.9+$(git rev-parse --short=8 HEAD)"   # last release + commit
pnpm install --frozen-lockfile
VITE_VISUALIZER_CLIENT_ID=<prod-uid> pnpm build
cd ..
npx wrangler pages deploy web/build --project-name crema --branch main \
  --commit-hash "$(git rev-parse HEAD)" --commit-dirty=true
cd - && git worktree remove --force /tmp/crema-deploy
```

- **Stamp the version** so the deployed app doesn't report the stale committed one. Use the last release plus the commit (`0.0.9+e0145ede`), and replace `0.0.9` with the latest tag.
- **Don't build without `VITE_VISUALIZER_CLIENT_ID`**: "Sign in with Visualizer" breaks on production.
- Check the result with `npx wrangler pages deployment list --project-name crema --environment production`. Installed copies of the PWA pick up the new service worker the next time they're opened.

## One-time setup (already done)

For reference, if the project ever has to be recreated:

1. **Pages project**: Workers & Pages → Create → Pages → **Direct Upload** (or let the first `wrangler pages deploy --project-name crema` create it). Name it **`crema`**, matching `name` in `wrangler.jsonc`. Don't connect Git, so deploys stay tied to release tags.
2. **Custom domain**: Pages project → Custom domains → `crema.maceiras.dev`. Add the CNAME record Cloudflare prompts for; HTTPS provisions automatically.
3. **GitHub secrets**: the three in the table above.

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
| `.github/workflows/release.yml` | The only automated deploy: the `web (pwa)` job, on `v*.*.*` tags. |
| `scripts/cut-release.sh` | Release notes PR, then the tag that triggers `release.yml`. |
| `wrangler.jsonc` | CF Pages project config (name + output dir + compat date). Read by `wrangler pages deploy`. |
| `web/static/_headers` | Cache + security headers (CSP, COOP/COEP, etc.) — copied verbatim into `web/build/` by adapter-static. CF Pages applies them per-request. |
| `web/vite.config.ts` | SvelteKit config (the `sveltekit()` plugin: `adapter-static`, the CSP) and the PWA plugin. SvelteKit 3 has no `svelte.config.js`. |
| `web/.env.example` | Template for local dev's `.env.local`. |
| `web/.env.local` | **Local-only.** Gitignored. Holds your dev Client UID. |

## CI and branch protection

CI quality gates live in `.github/workflows/ci.yml` (Rust core + SvelteKit web shell, run in parallel on every PR + push to `main`). CI does **not** deploy, and neither does merging to `main`: the web deploys only from `release.yml` on a version tag (see above). See the workflow file for the full step list.

Branch-protection rules are not in YAML; they're configured in GitHub Settings → Branches. Recommended rules for `main`:

- **Require status checks**: `rust (core)` and `web (svelte)` (both jobs from `ci.yml`).
- **Require branches to be up to date before merging.**
- **Require linear history** (no merge commits via UI — squash or rebase only).
- **Disable force-push and deletion** of `main`.
- **Allow administrators to bypass**: enabled, for emergencies.

PR titles are checked by `pr-title.yml` (loose Conventional Commits: `feat|fix|chore|docs|refactor|test|ci`, optional scope, then `: description`). It's a separate workflow, so you can promote it to a required status check independently of the build gates.
