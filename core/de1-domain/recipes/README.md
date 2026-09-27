# Built-in brew recipes

`builtin.json` holds Crema's built-in guided-brew recipes: a JSON array of
`BrewRecipe` (the `de1-domain` model, camelCase wire form). It is embedded
into the `de1-domain` crate at compile time (`include_str!`) and exposed by
the [`brew_builtin`](../src/brew_builtin.rs) module — the same approach as the
built-in DE1 profiles in [`../profiles`](../profiles/README.md).

Each recipe is a real, published recipe, credited to its author with a link
to the primary source (`credit`, `sourceUrl`). The numbers are the sources'
own. Crema is not affiliated with, or endorsed by, any of these authors or
companies; the credit line is attribution only.

| id | Credit | Source |
| --- | --- | --- |
| `builtin:hoffmann-1-cup-v60` | James Hoffmann — A Better 1 Cup V60 Technique (2022) | <https://www.youtube.com/watch?v=1oB1oDrDkHM> |
| `builtin:hoffmann-ultimate-v60` | James Hoffmann — The Ultimate V60 Technique (2019) | <https://www.youtube.com/watch?v=AI4ynXzkSQo> |
| `builtin:kasuya-4-6` | Tetsu Kasuya — 4:6 Method (2016 World Brewers Cup Champion) | <https://hario.co.uk/blogs/hario-ambassadors/hario-v60-recipe-interview-with-hario-ambassador-tetsu-kasuya> |
| `builtin:hoffmann-ultimate-aeropress` | James Hoffmann — The Ultimate AeroPress Technique (2021) | <https://www.youtube.com/watch?v=j6VlT_jUVPc> |
| `builtin:aeropress-official` | AeroPress Inc. — official brewing instructions | <https://aeropress.com/pages/how-to-use> |
| `builtin:merikanto-wac-2021` | Tuomas Merikanto — 2021 World AeroPress Champion | <https://aeropress.com/pages/wac-recipes> |
| `builtin:hoffmann-ultimate-french-press` | James Hoffmann — The Ultimate French Press Technique (2016) | <https://www.youtube.com/watch?v=st571DYYTR8> |
| `builtin:hoffmann-ultimate-clever` | James Hoffmann — The Ultimate Clever Dripper Technique (2020) | <https://www.youtube.com/watch?v=RpOdennxP24> |
| `builtin:stumptown-chemex` | Stumptown Coffee Roasters — Chemex Brew Guide | <https://www.stumptowncoffee.com/pages/brew-guide-chemex> |
| `builtin:stumptown-kalita-wave` | Stumptown Coffee Roasters — Kalita Wave Brew Guide | <https://www.stumptowncoffee.com/pages/brew-guide-kalita-wave> |
| `builtin:hoffmann-cold-brew` | James Hoffmann — Everything I Learned About Cold Brew Coffee (2025) | <https://www.youtube.com/watch?v=AB0QLjroFss> |
| `builtin:hoffmann-moka` | Adapted from James Hoffmann — The Ultimate Moka Pot Technique (2022) | <https://www.youtube.com/watch?v=BfDLoIvb0w4> |
| `builtin:hario-syphon` | Hario — Syphon Brew Guide | <https://www.hario.co.uk/pages/brew-guides-syphon> |

## Rules

- Ids are **stable**: shells persist them as per-method default pointers.
  Renaming or removing one needs a migration.
- Steps follow the `BrewStep` shape; swirls, stirs, "break the crust" and
  "flip" are `stir` steps with the action in `label`. Water targets are
  cumulative and rise; the last pour equals `waterG`. The `brew_builtin`
  tests enforce this shape.
- Built-ins are read-only. The shells never persist or back them up; editing
  goes through a copy (`duplicate_recipe`), credited "Adapted from …".
- `createdAt` / `updatedAt` are `0`: the catalogue has no edit history.
