# 33 — Bean library redesign

**Branch:** `bean-redesign`
**Source design:** `/Users/adrianmaceiras/code/crema-beans-svelte-export/`
**Lands:** the `/beans` library + roaster directory + full editor route.

## Summary

The bean library on `/beans` is rebuilt on top of the Claude-designed
canvas exported as `crema-beans-svelte-export/`. The visual structure of
the **library tile**, **drawer**, **full editor page**, **quick-add
popover**, **import modal** and **roast slider** are adopted; everything
else is translated to Crema's existing primitives so the page stops
diverging from the rest of the app.

## What was adopted

| Component | From design | Notes |
|---|---|---|
| `RoastSlider.svelte` | one-for-one | The *single* design element kept verbatim. 1-10 track + gradient fill + knob + 5-band labels. Translated colours from `--copper-300..500` to Crema's identical tokens. |
| `BeanTile.svelte` | one-for-one | Replaces the old `BeanCard.svelte`. Avatar block (left) with deterministic roaster mark + tone; favourite star **on the avatar, top-left**; frozen snowflake on the avatar; body with roaster eyebrow / serif bean name / origin row / pill row / stats row / burn-down. Click anywhere → opens the drawer. |
| `BeanDrawer.svelte` | one-for-one | Right-side detail panel. Hero + 4-cell status strip (off-roast / opened / remaining / shots) + collapsible groups (Identity / Dates / Bag & grinder / Origin / Tasting / Buy again / Shots) + footer actions. |
| `BeansEmptyState.svelte` | one-for-one | First-run state with three CTA cards (Quick add / Full editor / Import). Replaced the old single-message empty block. |
| `/beans/+page.svelte` | layout adopted | Header + tabs (Bags / Roasters), chip-rail filters (Status / Roast / Tags), sort dropdown, **split "Add bean" button** with caret menu (Quick add / Full editor / Import). Grid sectioned by Active / Frozen / Archived. |
| `BeanQuickAdd.svelte` | adopted | 10-second popover wired to `library.quickAdd(...)`. "Save & make active" sets the active bean and seeds remaining=bag size. "Open full editor" navigates to `/beans/new`. |
| `BeanEditPage.svelte` (route-level) | shape adopted | Left rail (photo placeholder + TOC) + right form with 8 numbered blocks. Each block uses Crema primitives — `QStepper`, `StToggle`, native `<select>`/`<input>`, `RoasterAutocomplete`, `TagInput` — except the roast slider which uses the design's component. Mounted at `/beans/new` and `/beans/[id]/edit`. |
| Roasters tab | layout adopted | Sort row + region chip-rail + grid of roaster cards with deterministic mark + tone. Includes a duplicate-merge banner that detects roaster name duplicates (stopword-stripped) and offers a 1-click merge. |
| `BeanImportDialog.svelte` | kept | The existing dialog handles Beanconqueror `.zip` imports with the lossy-pick model. Wired to both Import buttons in the redesigned page. Not visually re-shaped this pass — the existing dialog already does the modal-with-stats layout. Future polish work to bring it fully in-line with the design's `BeansImportModal.svelte` is captured in §Followups. |

### Tags — added end-to-end

The design's README called out that tags were deliberately left out. We
added them throughout:

- **Tile** — up to 3 tag chips render after the Roast / Blend / Decaf
  pills, with a `+N` overflow chip if there are more.
- **Drawer** — Identity group renders the full tag list.
- **Full editor** — Block 07 wraps the existing `$lib/components/profiles/TagInput.svelte`.
- **Library chip-rail** — every tag in use across the library appears
  as a filter chip with a count badge. AND-semantics — selecting two
  tags returns beans that have both.

### Favourite star — moved

The design overlays the favourite star on the **top-left of the
avatar**, replacing the bag-label spot on the upper-right of the old
card. We adopted that pattern exactly; the old card's top-right star is
gone.

### Actions — moved to the drawer

The old card had a full-width "Set active" button + icon row (Edit /
Archive / Delete) at the bottom. The design has none of that — the
tile is for browsing, the drawer is for acting. We followed:

- **Set active** lives in the drawer footer (primary copper button).
- **Edit** lives in the drawer footer (and the head's pencil icon).
  Opens the full editor route, not an inline drawer.
- **Archive / Restore** lives in the drawer footer.
- **Delete** lives in the drawer footer.
- The brew strip activation flow is unchanged — `library.setActiveBean(id)`.

### Bag-remaining concept — re-introduced

Per the brief, the `remainingG` value is restored to first-class status:

- The **tile's burn-down bar** at the bottom edge shows
  `remaining / bag size` with a copper-gradient fill.
- The **drawer status strip** shows `Remaining: NNg of MMg · ~K shots`.
- The **full editor** has an explicit Remaining stepper next to the Bag
  size stepper.
- When the user changes `bagSizeG` (either in Quick Add or the editor),
  `remainingG` is silently re-seeded to match — so a fresh bag starts
  full. The original "Refill" button is dropped; this auto-seed plus
  shot-level auto-debit cover the same workflow.

## What was translated to Crema patterns

The design's standalone CSS has its own tokens (`--espresso-950` /
`--paper-50` / `--copper-300..600` / `--font-serif` etc.). Crema already
has tokens with **the same names** for the copper ramp and the semantic
colours, and equivalent tokens for surfaces (Crema uses `--bg-page` /
`--bg-surface` / `--tint-rgb`). The translation rules applied:

| Design token | Crema token (used) |
|---|---|
| `--espresso-950` / `--espresso-900` | `var(--bg-page)` / `var(--bg-surface)` (theme-aware) |
| `--paper-50` | `var(--bg-page)` |
| `--ink-50` / `--ink-100` | `var(--fg-1)` |
| `--copper-300..600` | `var(--copper-300..600)` (identical names, kept as-is) |
| `--font-serif`/`--font-sans`/`--font-mono` | Same Crema tokens. |
| `--success` / `--danger` / `--warning` / `--info` | Same Crema tokens (PR #40 landed these). |
| `--track-allcaps` | Same Crema token. |
| `--radius-pill` / `--radius-lg` / `--radius-md` / `--radius-sm` | Same Crema tokens. |
| `--dur-1` / `--dur-2` / `--ease` | Same Crema tokens. |

We did **not** import the design's `beans-page.css` — the styles are
inlined in each `<style>` block of the new components, so every rule is
scoped and goes through Crema's tokens. This was the brief's explicit
direction.

### Control primitives swapped

The design's `bn-fld-stepper` / `bn-fld-combo` / `bn-fld-input` are
hand-rolled; we used Crema's existing primitives instead so the
keyboard, click-to-type, focus, and unit-aware behaviour come for free:

| Design control | Crema replacement |
|---|---|
| `bn-fld-stepper` (− / value / +) | `QStepper` for grams (bag size, remaining, dose); `StStepper` could replace it on smaller surfaces. |
| `bn-fld-combo` (input + chev) | `RoasterAutocomplete` for the roaster field; native `<input>` for grinder; segmented chips for the Mix field. |
| `Toggle.svelte` (design's trivial one) | `StToggle`. |
| `bn-fld` (label + input) | A row helper class inlined in each component. The design's vertical-stack form is preserved on the full editor; the drawer uses a fixed key/value grid. |
| `Rating.svelte` (design's read-only 5-star) | Replaced with inline rendered `<i>` elements driven by `bean.rating`. Avoids dragging the design's component in just for star icons. |
| `FormBlock.svelte` / `FormRow.svelte` | Inlined as `be-block` / `be-frow` classes on `BeanEditPage` — same visual shape, but Svelte-scoped instead of a global stylesheet. |

## What was deliberately not done

- **Photo OCR / capture.** The full editor's left rail has a photo
  drop placeholder + caption but no actual file handling yet — the
  whole bag-photo feature is on the roadmap (`Bean.imageRef` already
  exists in the schema). Surfacing the placeholder keeps the visual
  hierarchy of the editor right and signals the feature is coming.
- **Beanconqueror import modal — visual refresh.** The existing
  `BeanImportDialog.svelte` does the work (parse `.zip` with `fflate`,
  preview, commit). The design's `BeansImportModal.svelte` has a
  prettier counts strip + "Skipping" grid we did not port this pass —
  the existing dialog is wired correctly and ships the same lossy-pick
  logic. Cosmetic upgrade is a future task.
- **Beanconqueror sort dropdown for roasters tab.** The roasters tab
  has Bag count / Name / Recent inline sort options instead of the
  popover dropdown. Less moving parts, same coverage.
- **Schema extensions.** None were needed — `core/de1-domain/src/bean.rs`
  already has every field the design references (`frozen_on`,
  `defrosted_on`, `rating: u8`, nested `origin: BeanOrigin`,
  `roast_level: 1..10`, `tags`). The brief asked us to verify before
  extending; verification confirmed nothing was missing.

## File map

```
core/de1-domain/src/bean.rs                                # unchanged (verified)

web/src/lib/bean/
  model.ts                  # + roastBand5() + bagState() helpers
  index.ts                  # + the above exports + roasterMarkTone
  roaster-mark.ts           # NEW — deterministic mark + tone from roaster name

web/src/lib/components/beans/
  BeanCard.svelte           # DELETED
  BeanEditor.svelte         # DELETED
  BeanTile.svelte           # NEW — replaces BeanCard
  BeanDrawer.svelte         # NEW — read-only detail panel
  BeanEditPage.svelte       # NEW — full editor (page-level)
  BeanQuickAdd.svelte       # NEW — 10-second popover
  BeansEmptyState.svelte    # NEW — first-run state
  RoastSlider.svelte        # NEW — 1-10 slider with band labels
  RoasterAutocomplete.svelte# kept as-is, reused in Quick Add + editor
  BeanImportDialog.svelte   # kept as-is, wired to the new Import buttons
  BeanStrip.svelte          # kept as-is (brew page)

web/src/routes/beans/
  +page.svelte              # rewritten — tabs / chip-rail / split add / drawer
  new/+page.svelte          # NEW — mounts BeanEditPage in isNew mode
  [id]/edit/+page.svelte    # NEW — mounts BeanEditPage on a live bean

docs/33-bean-library-redesign.md  # this doc
```

## Tests + gates

| Gate | Result |
|---|---|
| `cargo test --workspace` | ✅ all suites green (181 + 1 + 231 + 28 + 109 + 160 + 34 + 2 + 1 + doc-tests = …) |
| `pnpm check` | ✅ 0 errors, 0 warnings, 491 files |
| `pnpm build` | ✅ (see commit log) |
| `cargo clippy --workspace --all-targets -- -D warnings` | ✅ |

## Followups

- Visual refresh of `BeanImportDialog.svelte` to mirror the design's
  `BeansImportModal.svelte` (the counts strip + "Skipping" grid).
- Photo capture + OCR on the editor's left rail placeholder.
- Sort dropdown on the Roasters tab (the design's `SortDropdown.svelte`
  rather than the inline sort buttons used here).
- Best-shot / per-bean profile recommendation card on the drawer's
  Shots group (the old `BeanCard.svelte` had this and it was lost in
  the rewrite — should come back as a small "Tip" pill above the
  shot list).
- "Reorder a past bag" + "Scan label photo" entries in the split-add
  menu (rendered but unwired in this pass).
