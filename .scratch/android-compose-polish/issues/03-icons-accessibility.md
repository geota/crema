# Real Phosphor icons + accessibility (compose-expert review)

Status: ready-for-agent

- **Real Phosphor icons.** `PhIcon` (CremaComponents.kt) is still a placeholder
  tinted box → icons are missing app-wide — the single most visible design-fidelity
  gap. Bind real glyphs: convert the web's icon subset (`web/src/lib/icons/*.woff2`)
  to a `.ttf` in `res/font` and map glyph name → codepoint, OR adopt a Phosphor
  Compose vector library. Screen code already references exact Phosphor names
  ("scales", "gear-six", "coffee-bean", …).

- **Accessibility.** There is **no `contentDescription` / `Modifier.semantics`
  anywhere** in the app. Icon-only controls — `PhIcon`, the rail connection pips,
  `CremaIconButton` — announce nothing to TalkBack. Add `contentDescription` (give
  `PhIcon` a `contentDescription: String?` param), label icon-buttons, and verify
  48dp touch targets. Pairs naturally with the icon binding above.
