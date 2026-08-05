//! Fuzzy, weighted search over the bean library — the one ranking
//! implementation every shell calls (issue 62).
//!
//! The bean libraries used to be searched with a hand-rolled
//! `name.contains(q) || roaster.contains(q) || …` chain, duplicated per
//! shell and per surface. That has three problems the user hit in practice:
//!
//! 1. **Coverage.** Processing, elevation, harvest, the free-form notes box,
//!    grinder, where the bag was bought — none of it was searchable, and
//!    Android could not search tags at all while web could.
//! 2. **Typos.** `yirgacheff`, `guatamala` and `geshia` all returned nothing.
//! 3. **Relevance.** A tasting-note hit sorted identically to a name hit,
//!    and nothing told the user *why* a bag was in the list.
//!
//! This module answers all three. [`search_beans`] scores every bag against
//! the query and returns the matches best-first, each carrying the fields it
//! matched on with the matched runs already split out ([`SearchSegment`]) so a
//! shell can highlight them without doing any string arithmetic of its own.
//!
//! **Why segments and not offsets.** Rust `char` indices, JS UTF-16 offsets
//! and Kotlin UTF-16 offsets disagree the moment a snippet contains an
//! astral-plane character, and silently produce off-by-N highlights rather
//! than an error. Handing back pre-split `(text, hit)` runs removes the whole
//! class of bug: each shell concatenates, it never indexes.
//!
//! The matcher is deliberately conservative about fuzz — see the gates in
//! [`token_score_for_text`]. A search that matches everything is the same as
//! no search at all.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::bean::{Bean, BeanMix, BeanRoastType, Roaster, roast_band};

// ───────────────────────────────────────────────────────────────────
// Wire types
// ───────────────────────────────────────────────────────────────────

/// Which recorded field a [`FieldHit`] came from. Shells key display
/// decisions off this (name/roaster hits highlight in place; everything else
/// gets a "matched in" line), so it is a closed enum rather than a string.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SearchField {
    /// The bag name, or the roastery name on a roaster hit.
    Name,
    /// The bag's roastery (resolved through `Bean::roaster_id`).
    Roaster,
    /// `origin.country`.
    Country,
    /// `origin.region`.
    Region,
    /// `origin.farm`.
    Farm,
    /// `origin.farmer` — the producer.
    Farmer,
    /// `origin.variety` — the cultivar.
    Variety,
    /// `origin.elevation`.
    Elevation,
    /// `origin.processing`.
    Processing,
    /// `origin.harvest_time`.
    Harvest,
    /// Free-form user tags.
    Tags,
    /// The tasting-notes box.
    TastingNotes,
    /// The free-text cupping / quality score.
    QualityScore,
    /// The free-form notes box (distinct from tasting notes).
    Notes,
    /// Bean-scoped grinder name.
    Grinder,
    /// Bean-scoped grind setting.
    GrinderSetting,
    /// Where the bag was bought.
    PlaceOfPurchase,
    /// Buy-again URL.
    Url,
    /// A roaster's city.
    City,
    /// A roaster's website.
    Website,
    /// Derived words that are true of the bag but typed into a search box
    /// like any other term — `decaf`, `blend`, `light roast`, `frozen`.
    /// See [`attributes_text`].
    Attributes,
}

impl SearchField {
    /// Human label for the "matched in …" line. Lives here rather than in
    /// each shell so the three UIs cannot drift apart on wording.
    pub fn label(self) -> &'static str {
        match self {
            SearchField::Name => "Name",
            SearchField::Roaster => "Roaster",
            SearchField::Country => "Country",
            SearchField::Region => "Region",
            SearchField::Farm => "Farm",
            SearchField::Farmer => "Producer",
            SearchField::Variety => "Variety",
            SearchField::Elevation => "Elevation",
            SearchField::Processing => "Process",
            SearchField::Harvest => "Harvest",
            SearchField::Tags => "Tag",
            SearchField::TastingNotes => "Tasting notes",
            SearchField::QualityScore => "Score",
            SearchField::Notes => "Notes",
            SearchField::Grinder => "Grinder",
            SearchField::GrinderSetting => "Grind setting",
            SearchField::PlaceOfPurchase => "Bought at",
            SearchField::Url => "Link",
            SearchField::City => "City",
            SearchField::Website => "Website",
            SearchField::Attributes => "Attributes",
        }
    }

    /// How much a match in this field is worth relative to a name match.
    /// The ordering is the point: what a user typed is far more likely to be
    /// a bag or roastery name than a fragment of a URL.
    fn weight(self) -> f32 {
        match self {
            SearchField::Name => 1.00,
            SearchField::Roaster => 0.92,
            SearchField::Tags => 0.82,
            SearchField::Country | SearchField::Region => 0.80,
            SearchField::Farm | SearchField::Farmer | SearchField::Variety => 0.78,
            SearchField::Processing => 0.75,
            SearchField::Attributes => 0.72,
            SearchField::TastingNotes => 0.68,
            SearchField::Notes | SearchField::City => 0.55,
            SearchField::PlaceOfPurchase | SearchField::Grinder | SearchField::QualityScore => 0.50,
            SearchField::GrinderSetting | SearchField::Elevation | SearchField::Harvest => 0.45,
            SearchField::Url | SearchField::Website => 0.30,
        }
    }
}

/// One run of snippet text, flagged as matched or not. A shell renders a
/// snippet by concatenating the segments and emphasising the `hit` ones.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchSegment {
    /// The literal text of this run, in the field's original casing.
    pub text: String,
    /// Whether the query matched here.
    pub hit: bool,
}

/// One field a row matched on, with a highlightable snippet of it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FieldHit {
    /// Which field matched.
    pub field: SearchField,
    /// Display label for [`FieldHit::field`], resolved core-side.
    pub label: String,
    /// A window of the field's text around the first match, pre-split into
    /// matched / unmatched runs. Ellipses are already folded into the edge
    /// segments — a shell renders exactly what it is given.
    pub snippet: Vec<SearchSegment>,
}

/// One row that matched, with its relevance score and what it matched on.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchHit {
    /// The `Bean::id` / `Roaster::id` that matched.
    pub id: String,
    /// Relevance in `0.0..=1.0`, higher is better. Comparable across rows
    /// (it is a mean over query tokens, not a sum), so a shell can sort on it
    /// directly.
    pub score: f32,
    /// The fields that carried the match, best-contribution first, capped at
    /// [`MAX_FIELD_HITS`].
    pub fields: Vec<FieldHit>,
}

/// How many matched fields a hit reports. Three is enough to explain a match
/// without turning a tile into a wall of snippets.
pub const MAX_FIELD_HITS: usize = 3;

/// Characters of context a snippet carries around the first match.
const SNIPPET_WINDOW: usize = 72;

/// Minimum mean token score for a row to be returned at all. Filters the
/// long tail of "every character happens to appear somewhere" subsequence
/// matches that would otherwise make a fuzzy search useless.
const SCORE_FLOOR: f32 = 0.20;

// ───────────────────────────────────────────────────────────────────
// Normalisation
// ───────────────────────────────────────────────────────────────────

/// Lowercase and ASCII-fold a string for matching.
///
/// Coffee metadata is full of accents — `Café Granja La Esperanza`,
/// `Sidamó`, `Huehuetenango`, `Nariño` — and nobody types them. Folding
/// Latin-1 Supplement + Latin Extended-A down to ASCII means `cafe` finds
/// `Café` and `narino` finds `Nariño`.
///
/// Hand-rolled rather than pulled from a crate: the core has no unicode
/// dependency and this covers every alphabet a bag label realistically uses.
/// Anything outside the table passes through lowercased and unchanged, so a
/// Cyrillic or CJK name still matches itself.
///
/// **Invariant**: folding is 1:1 on `char` count for every mapped character
/// except the four two-letter ligature expansions (`æ ß œ ĳ`), which are
/// excluded from the table for exactly that reason — [`find_substring`] maps
/// folded offsets back onto the original string by counting chars.
fn fold_char(c: char) -> char {
    match c {
        'à' | 'á' | 'â' | 'ã' | 'ä' | 'å' | 'ā' | 'ă' | 'ą' => 'a',
        'ç' | 'ć' | 'ĉ' | 'ċ' | 'č' => 'c',
        'ď' | 'đ' => 'd',
        'è' | 'é' | 'ê' | 'ë' | 'ē' | 'ĕ' | 'ė' | 'ę' | 'ě' => 'e',
        'ĝ' | 'ğ' | 'ġ' | 'ģ' => 'g',
        'ĥ' | 'ħ' => 'h',
        'ì' | 'í' | 'î' | 'ï' | 'ĩ' | 'ī' | 'ĭ' | 'į' | 'ı' => 'i',
        'ĵ' => 'j',
        'ķ' => 'k',
        'ĺ' | 'ļ' | 'ľ' | 'ł' => 'l',
        'ñ' | 'ń' | 'ņ' | 'ň' => 'n',
        'ò' | 'ó' | 'ô' | 'õ' | 'ö' | 'ø' | 'ō' | 'ŏ' | 'ő' => 'o',
        'ŕ' | 'ŗ' | 'ř' => 'r',
        'ś' | 'ŝ' | 'ş' | 'š' => 's',
        'ţ' | 'ť' | 'ŧ' => 't',
        'ù' | 'ú' | 'û' | 'ü' | 'ũ' | 'ū' | 'ŭ' | 'ů' | 'ű' | 'ų' => 'u',
        'ŵ' => 'w',
        'ý' | 'ÿ' | 'ŷ' => 'y',
        'ź' | 'ż' | 'ž' => 'z',
        other => other,
    }
}

/// [`fold_char`] over a whole string, lowercased first so the table only
/// needs its lowercase half.
fn fold(s: &str) -> String {
    s.to_lowercase().chars().map(fold_char).collect()
}

/// Whether a folded char starts a new word — used for the word-prefix tier
/// and for the typo tier's word splitting. Digits count as word characters so
/// `1900 masl` splits the way a reader would expect.
fn is_word_char(c: char) -> bool {
    c.is_alphanumeric()
}

// ───────────────────────────────────────────────────────────────────
// The matcher
// ───────────────────────────────────────────────────────────────────

/// How one token matched one field, and how good that match was.
#[derive(Debug, Clone, Copy, PartialEq)]
struct TokenMatch {
    /// Tier score before the field weight is applied, in `0.0..=1.0`.
    raw: f32,
    /// Char offset (into the *folded* text) where the highlight starts.
    start: usize,
    /// Char length of the highlighted run.
    len: usize,
}

/// The typo budget for a token of `n` chars. Short tokens get none: at three
/// characters a single edit reaches most of the dictionary, so allowing one
/// would mean `nat` matching `bat`, `not`, `oat` and `nut` alike.
fn typo_budget(n: usize) -> usize {
    match n {
        0..=3 => 0,
        4..=6 => 1,
        _ => 2,
    }
}

/// Find `needle` in `haystack` (both folded), returning the char offset of
/// the first occurrence. Works in chars, not bytes, so the offset can be
/// handed straight to the snippet builder.
fn find_substring(haystack: &[char], needle: &[char]) -> Option<usize> {
    if needle.is_empty() || needle.len() > haystack.len() {
        return None;
    }
    (0..=haystack.len() - needle.len()).find(|&i| haystack[i..i + needle.len()] == *needle)
}

/// fzf-style subsequence match: every char of `needle` appears in
/// `haystack` in order. Returns the span it covered, scored by density —
/// `esm` matching `Esmeralda` contiguously is worth far more than the same
/// three letters scattered across a sentence.
// Lengths here are a query token and one field of one bag — a handful of
// chars each. The f32 mantissa is exact to 2^24; nothing in this file is
// within nine orders of magnitude of that.
#[allow(clippy::cast_precision_loss)]
fn subsequence(haystack: &[char], needle: &[char]) -> Option<TokenMatch> {
    let mut first: Option<usize> = None;
    let mut last = 0usize;
    let mut ni = 0usize;
    for (i, c) in haystack.iter().enumerate() {
        if ni < needle.len() && *c == needle[ni] {
            if first.is_none() {
                first = Some(i);
            }
            last = i;
            ni += 1;
            if ni == needle.len() {
                break;
            }
        }
    }
    if ni < needle.len() {
        return None;
    }
    let start = first?;
    let span = last - start + 1;
    // density is 1.0 for a contiguous run (which substring already caught)
    // and falls off as the letters spread out.
    let density = needle.len() as f32 / span as f32;
    let at_word_start = start == 0 || !is_word_char(haystack[start - 1]);
    let base = 0.30 + 0.25 * density + if at_word_start { 0.05 } else { 0.0 };
    Some(TokenMatch {
        raw: base.min(0.60),
        start,
        len: span,
    })
}

/// Damerau–Levenshtein distance, capped at `max` (returns `max + 1` once it
/// is certain the distance exceeds the budget). The transposition case is
/// what makes it worth the extra row of bookkeeping over plain Levenshtein:
/// `guatemlaa` for `guatemala` is one keystroke slip, not two edits.
fn damerau_levenshtein(a: &[char], b: &[char], max: usize) -> usize {
    if a.len().abs_diff(b.len()) > max {
        return max + 1;
    }
    let (n, m) = (a.len(), b.len());
    if n == 0 {
        return m;
    }
    if m == 0 {
        return n;
    }
    let mut prev2 = vec![0usize; m + 1];
    let mut prev = (0..=m).collect::<Vec<_>>();
    let mut cur = vec![0usize; m + 1];
    for i in 1..=n {
        cur[0] = i;
        let mut row_min = cur[0];
        for j in 1..=m {
            let cost = usize::from(a[i - 1] != b[j - 1]);
            let mut v = (prev[j] + 1).min(cur[j - 1] + 1).min(prev[j - 1] + cost);
            if i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] {
                v = v.min(prev2[j - 2] + 1);
            }
            cur[j] = v;
            row_min = row_min.min(v);
        }
        if row_min > max {
            return max + 1;
        }
        std::mem::swap(&mut prev2, &mut prev);
        std::mem::swap(&mut prev, &mut cur);
    }
    prev[m]
}

/// Score one query token against one field's text, best tier wins.
///
/// The tier gates are what keep a fuzzy search honest:
///
/// - **1–2 char tokens** only ever match as a prefix or substring. Letting
///   `a` match by subsequence would return the entire library.
/// - **Subsequence** needs 3+ chars.
/// - **Typo tolerance** needs 4+ chars ([`typo_budget`]) and is compared
///   word-by-word, not against the whole field — otherwise a long tasting
///   note is within edit distance 2 of nearly any word.
// `d` is an edit distance bounded by 2 (see `typo_budget`).
#[allow(clippy::cast_precision_loss)]
fn token_score_for_text(text_folded: &[char], token: &[char]) -> Option<TokenMatch> {
    if token.is_empty() || text_folded.is_empty() {
        return None;
    }

    // Tier 1 — the field is exactly the token.
    if text_folded == token {
        return Some(TokenMatch {
            raw: 1.00,
            start: 0,
            len: token.len(),
        });
    }

    // Tiers 2 & 3 — substring, scored higher when it starts a word.
    if let Some(idx) = find_substring(text_folded, token) {
        // Prefer a word-start occurrence if one exists further along:
        // "ras" in "Costa Rica Rasuna" should highlight the word, not the
        // fragment inside a longer one.
        let word_start = (0..=text_folded.len().saturating_sub(token.len())).find(|&i| {
            text_folded[i..i + token.len()] == *token
                && (i == 0 || !is_word_char(text_folded[i - 1]))
        });
        return Some(match word_start {
            Some(i) => TokenMatch {
                raw: 0.90,
                start: i,
                len: token.len(),
            },
            None => TokenMatch {
                raw: 0.75,
                start: idx,
                len: token.len(),
            },
        });
    }

    if token.len() < 3 {
        return None;
    }

    // Tier 5 — typo tolerance, word by word.
    let budget = typo_budget(token.len());
    let mut best_typo: Option<TokenMatch> = None;
    if budget > 0 {
        let mut start = 0usize;
        let mut i = 0usize;
        while i <= text_folded.len() {
            let at_end = i == text_folded.len();
            if at_end || !is_word_char(text_folded[i]) {
                if i > start {
                    let word = &text_folded[start..i];
                    // Only compare against words of a plausible length; the
                    // length guard inside damerau_levenshtein handles the rest.
                    let d = damerau_levenshtein(token, word, budget);
                    if d <= budget && d > 0 {
                        let raw = 0.55 - 0.12 * d as f32;
                        let better = best_typo.map(|b| raw > b.raw).unwrap_or(true);
                        if better {
                            best_typo = Some(TokenMatch {
                                raw,
                                start,
                                len: word.len(),
                            });
                        }
                    }
                }
                start = i + 1;
            }
            i += 1;
        }
    }

    // Tier 4 — subsequence. Compared against the typo tier so whichever
    // explains the token better wins.
    let sub = subsequence(text_folded, token);
    match (sub, best_typo) {
        (Some(s), Some(t)) => Some(if s.raw >= t.raw { s } else { t }),
        (Some(s), None) => Some(s),
        (None, t) => t,
    }
}

// ───────────────────────────────────────────────────────────────────
// Corpus
// ───────────────────────────────────────────────────────────────────

/// One searchable field of one row: the display text plus its folded form.
struct Doc {
    field: SearchField,
    text: String,
    folded: Vec<char>,
}

fn doc(field: SearchField, text: impl Into<String>) -> Option<Doc> {
    let text: String = text.into();
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return None;
    }
    let folded = fold(trimmed).chars().collect();
    Some(Doc {
        field,
        text: trimmed.to_owned(),
        folded,
    })
}

/// The derived words that are true of a bag and that people type into a
/// search box: `decaf`, `blend`, `single origin`, the roast band and roast
/// type, and the lifecycle states. Without these, "decaf" is unsearchable
/// even though it is right there on the tile.
fn attributes_text(bean: &Bean) -> String {
    let mut parts: Vec<&str> = Vec::new();
    if bean.decaf {
        parts.push("decaf");
    }
    match bean.mix {
        Some(BeanMix::Blend) => parts.push("blend"),
        Some(BeanMix::Single) => parts.push("single origin"),
        None => {}
    }
    match bean.roast_type {
        Some(BeanRoastType::Espresso) => parts.push("espresso roast"),
        Some(BeanRoastType::Filter) => parts.push("filter roast"),
        Some(BeanRoastType::Omni) => parts.push("omni roast"),
        None => {}
    }
    let band = bean
        .roast_level
        .map(|l| roast_band(i32::from(l)).as_str())
        .map(|b| match b {
            "light" => "light roast",
            "medium" => "medium roast",
            _ => "dark roast",
        });
    if let Some(b) = band {
        parts.push(b);
    }
    if bean.frozen_on.is_some() && bean.defrosted_on.is_none() {
        parts.push("frozen");
    }
    if bean.archived_at.is_some() {
        parts.push("archived");
    }
    if bean.favourite {
        parts.push("favourite");
    }
    parts.join(" · ")
}

/// Every searchable field of one bag, in no particular order (the weights,
/// not the order, decide what wins).
fn bean_docs(bean: &Bean, roaster_name: Option<&str>) -> Vec<Doc> {
    let o = &bean.origin;
    [
        doc(SearchField::Name, bean.name.clone()),
        roaster_name.and_then(|n| doc(SearchField::Roaster, n)),
        o.country.clone().and_then(|v| doc(SearchField::Country, v)),
        o.region.clone().and_then(|v| doc(SearchField::Region, v)),
        o.farm.clone().and_then(|v| doc(SearchField::Farm, v)),
        o.farmer.clone().and_then(|v| doc(SearchField::Farmer, v)),
        o.variety.clone().and_then(|v| doc(SearchField::Variety, v)),
        o.elevation
            .clone()
            .and_then(|v| doc(SearchField::Elevation, v)),
        o.processing
            .clone()
            .and_then(|v| doc(SearchField::Processing, v)),
        o.harvest_time
            .clone()
            .and_then(|v| doc(SearchField::Harvest, v)),
        doc(SearchField::Tags, bean.tags.join(" · ")),
        doc(SearchField::TastingNotes, bean.tasting_notes.clone()),
        doc(SearchField::QualityScore, bean.quality_score.clone()),
        doc(SearchField::Notes, bean.notes.clone()),
        doc(SearchField::Grinder, bean.grinder.clone()),
        doc(SearchField::GrinderSetting, bean.grinder_setting.clone()),
        bean.place_of_purchase
            .clone()
            .and_then(|v| doc(SearchField::PlaceOfPurchase, v)),
        bean.url.clone().and_then(|v| doc(SearchField::Url, v)),
        doc(SearchField::Attributes, attributes_text(bean)),
    ]
    .into_iter()
    .flatten()
    .collect()
}

/// Every searchable field of one roastery.
fn roaster_docs(roaster: &Roaster) -> Vec<Doc> {
    [
        doc(SearchField::Name, roaster.name.clone()),
        roaster.city.clone().and_then(|v| doc(SearchField::City, v)),
        roaster
            .country
            .clone()
            .and_then(|v| doc(SearchField::Country, v)),
        doc(SearchField::Notes, roaster.notes.clone()),
        roaster
            .website
            .clone()
            .and_then(|v| doc(SearchField::Website, v)),
    ]
    .into_iter()
    .flatten()
    .collect()
}

// ───────────────────────────────────────────────────────────────────
// Snippets
// ───────────────────────────────────────────────────────────────────

/// Build the highlightable snippet for a match: a window of at most
/// [`SNIPPET_WINDOW`] chars around it, with the matched run split out and
/// ellipses folded into the edge segments.
///
/// Offsets are char offsets into the folded text; folding is 1:1 on chars
/// (see [`fold_char`]), so they index the original text's chars too — which
/// is what lets the snippet keep the user's original casing.
fn snippet_for(text: &str, start: usize, len: usize) -> Vec<SearchSegment> {
    let chars: Vec<char> = text.chars().collect();
    let end = (start + len).min(chars.len());
    let start = start.min(end);
    if chars.len() <= SNIPPET_WINDOW {
        return split_segments(&chars, 0, chars.len(), start, end, false, false);
    }
    // Centre the window on the match, then clamp into range.
    let pad = SNIPPET_WINDOW.saturating_sub(end - start) / 2;
    let mut from = start.saturating_sub(pad);
    let mut to = (from + SNIPPET_WINDOW).min(chars.len());
    from = to.saturating_sub(SNIPPET_WINDOW);
    // Snap the edges to word boundaries so a snippet does not start mid-word.
    while from > 0 && is_word_char(chars[from]) && is_word_char(chars[from - 1]) {
        from += 1;
    }
    while to < chars.len() && is_word_char(chars[to - 1]) && is_word_char(chars[to]) {
        to -= 1;
    }
    if from > start {
        from = start;
    }
    if to < end {
        to = end;
    }
    split_segments(&chars, from, to, start, end, from > 0, to < chars.len())
}

fn split_segments(
    chars: &[char],
    from: usize,
    to: usize,
    hit_start: usize,
    hit_end: usize,
    lead_ellipsis: bool,
    trail_ellipsis: bool,
) -> Vec<SearchSegment> {
    let take = |a: usize, b: usize| {
        chars[a.min(chars.len())..b.min(chars.len())]
            .iter()
            .collect::<String>()
    };
    let mut out: Vec<SearchSegment> = Vec::new();
    let head = format!(
        "{}{}",
        if lead_ellipsis { "…" } else { "" },
        take(from, hit_start)
    );
    if !head.is_empty() {
        out.push(SearchSegment {
            text: head,
            hit: false,
        });
    }
    let hit = take(hit_start, hit_end);
    if !hit.is_empty() {
        out.push(SearchSegment {
            text: hit,
            hit: true,
        });
    }
    let tail = format!(
        "{}{}",
        take(hit_end, to),
        if trail_ellipsis { "…" } else { "" }
    );
    if !tail.is_empty() {
        out.push(SearchSegment {
            text: tail,
            hit: false,
        });
    }
    out
}

// ───────────────────────────────────────────────────────────────────
// Search
// ───────────────────────────────────────────────────────────────────

/// Split a query into tokens. Whitespace-separated, folded, empties dropped.
fn tokens(query: &str) -> Vec<Vec<char>> {
    fold(query.trim())
        .split_whitespace()
        .filter(|t| !t.is_empty())
        .map(|t| t.chars().collect())
        .collect()
}

/// Score one row's docs against the query tokens.
///
/// Every token must land somewhere (AND across tokens, OR across fields) —
/// `ethiopia natural` should mean *the naturals from Ethiopia*, not
/// everything from either. The score is the **mean** of the per-token bests
/// so it stays in `0..=1` and is comparable between a one-word and a
/// three-word query.
// `toks.len()` is the word count of a search box; f32 is exact well past it.
#[allow(clippy::cast_precision_loss)]
fn score_docs(docs: &[Doc], toks: &[Vec<char>]) -> Option<SearchHit> {
    if toks.is_empty() {
        return None;
    }
    // Best (score, match) per (token, field).
    let mut total = 0.0f32;
    // Accumulated contribution per field, and the best match to highlight.
    let mut per_field: Vec<(SearchField, f32, usize, usize, usize)> = Vec::new();

    for tok in toks {
        let mut best: Option<(f32, usize, TokenMatch)> = None;
        for (di, d) in docs.iter().enumerate() {
            let Some(m) = token_score_for_text(&d.folded, tok) else {
                continue;
            };
            let weighted = m.raw * d.field.weight();
            if best.map(|(b, _, _)| weighted > b).unwrap_or(true) {
                best = Some((weighted, di, m));
            }
        }
        let (weighted, di, m) = best?; // a token that matched nothing kills the row
        total += weighted;
        let field = docs[di].field;
        match per_field.iter_mut().find(|e| e.0 == field) {
            Some(e) => {
                e.1 += weighted;
                // Keep the earliest match as the highlight anchor so a
                // multi-token hit in one field reads left-to-right.
                if m.start < e.3 {
                    e.2 = di;
                    e.3 = m.start;
                    e.4 = m.len;
                }
            }
            None => per_field.push((field, weighted, di, m.start, m.len)),
        }
    }

    let score = total / toks.len() as f32;
    if score < SCORE_FLOOR {
        return None;
    }

    per_field.sort_by(|a, b| b.1.total_cmp(&a.1));
    let fields = per_field
        .into_iter()
        .take(MAX_FIELD_HITS)
        .map(|(field, _, di, start, len)| FieldHit {
            field,
            label: field.label().to_owned(),
            snippet: snippet_for(&docs[di].text, start, len),
        })
        .collect();

    Some(SearchHit {
        id: String::new(), // filled by the caller, which owns the row id
        score,
        fields,
    })
}

/// Rank `beans` against `query`, best match first.
///
/// Returns an empty vec for a blank query — "no query" is not "no results",
/// and the caller keeps its own unfiltered ordering in that case. Roasters
/// are passed in so a bag can match on its roastery's name.
pub fn search_beans(beans: &[Bean], roasters: &[Roaster], query: &str) -> Vec<SearchHit> {
    let toks = tokens(query);
    if toks.is_empty() {
        return Vec::new();
    }
    let mut hits: Vec<SearchHit> = beans
        .iter()
        .filter_map(|b| {
            let roaster_name = b
                .roaster_id
                .as_deref()
                .and_then(|id| roasters.iter().find(|r| r.id == id))
                .map(|r| r.name.as_str());
            let docs = bean_docs(b, roaster_name);
            score_docs(&docs, &toks).map(|mut h| {
                h.id = b.id.clone();
                h
            })
        })
        .collect();
    sort_hits(&mut hits);
    hits
}

/// Rank `roasters` against `query`, best match first. Same contract as
/// [`search_beans`].
pub fn search_roasters(roasters: &[Roaster], query: &str) -> Vec<SearchHit> {
    let toks = tokens(query);
    if toks.is_empty() {
        return Vec::new();
    }
    let mut hits: Vec<SearchHit> = roasters
        .iter()
        .filter_map(|r| {
            score_docs(&roaster_docs(r), &toks).map(|mut h| {
                h.id = r.id.clone();
                h
            })
        })
        .collect();
    sort_hits(&mut hits);
    hits
}

/// Score descending, id ascending — the id tiebreak keeps the order stable
/// (and therefore the three shells identical) when two rows tie exactly.
fn sort_hits(hits: &mut [SearchHit]) {
    hits.sort_by(|a, b| b.score.total_cmp(&a.score).then_with(|| a.id.cmp(&b.id)));
}

// ───────────────────────────────────────────────────────────────────
// JSON bridges — the shape both FFI surfaces speak
// ───────────────────────────────────────────────────────────────────

/// [`search_beans`] over JSON: `beans_json` and `roasters_json` are arrays of
/// `Bean` / `Roaster`, and the result is an array of [`SearchHit`].
pub fn search_beans_json(
    beans_json: &str,
    roasters_json: &str,
    query: &str,
) -> Result<String, String> {
    let beans: Vec<Bean> = serde_json::from_str(beans_json).map_err(|e| e.to_string())?;
    let roasters: Vec<Roaster> = serde_json::from_str(roasters_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&search_beans(&beans, &roasters, query)).map_err(|e| e.to_string())
}

/// [`search_roasters`] over JSON.
pub fn search_roasters_json(roasters_json: &str, query: &str) -> Result<String, String> {
    let roasters: Vec<Roaster> = serde_json::from_str(roasters_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&search_roasters(&roasters, query)).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bean::BeanOrigin;

    fn bean(name: &str) -> Bean {
        Bean::new("bean:1".into(), name.into(), 0)
    }

    fn roaster(id: &str, name: &str) -> Roaster {
        Roaster::new(id.into(), name.into(), 0)
    }

    fn plain(hit: &SearchHit) -> String {
        hit.fields[0]
            .snippet
            .iter()
            .map(|s| s.text.as_str())
            .collect()
    }

    #[test]
    fn a_blank_query_returns_no_hits() {
        let b = vec![bean("Yirgacheffe")];
        assert!(search_beans(&b, &[], "").is_empty());
        assert!(search_beans(&b, &[], "   ").is_empty());
    }

    #[test]
    fn the_name_is_matched_case_insensitively() {
        let b = vec![bean("Geisha Esmeralda")];
        let hits = search_beans(&b, &[], "ESMERALDA");
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].fields[0].field, SearchField::Name);
    }

    #[test]
    fn accents_fold_so_cafe_finds_café() {
        let mut b = bean("Café Granja La Esperanza");
        b.origin.country = Some("Colombia".into());
        let hits = search_beans(&[b], &[], "cafe granja");
        assert_eq!(hits.len(), 1);
    }

    #[test]
    fn nariño_is_found_by_typing_narino() {
        let mut b = bean("Lot 42");
        b.origin.region = Some("Nariño".into());
        let hits = search_beans(&[b], &[], "narino");
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].fields[0].field, SearchField::Region);
        // The snippet keeps the ORIGINAL spelling, not the folded one.
        assert_eq!(plain(&hits[0]), "Nariño");
    }

    #[test]
    fn every_origin_field_is_searchable() {
        let mut b = bean("Lot 42");
        b.origin = BeanOrigin {
            country: Some("Ethiopia".into()),
            region: Some("Yirgacheffe".into()),
            farm: Some("Halo Hartume".into()),
            farmer: Some("Tarekech Geleta".into()),
            variety: Some("Heirloom".into()),
            elevation: Some("1900-2100 masl".into()),
            processing: Some("Natural".into()),
            harvest_time: Some("2024 Spring".into()),
        };
        let lib = [b];
        for q in [
            "ethiopia",
            "yirgacheffe",
            "hartume",
            "tarekech",
            "heirloom",
            "masl",
            "natural",
            "2024",
        ] {
            assert_eq!(
                search_beans(&lib, &[], q).len(),
                1,
                "query {q:?} found nothing"
            );
        }
    }

    #[test]
    fn the_fields_the_old_search_missed_are_covered() {
        let mut b = bean("Lot 42");
        b.notes = "Bought at the cafe down the road".into();
        b.grinder = "Niche Zero".into();
        b.grinder_setting = "18 clicks".into();
        b.place_of_purchase = Some("Counter Culture Durham".into());
        b.quality_score = "88".into();
        let lib = [b];
        for q in ["niche", "clicks", "durham", "88", "road"] {
            assert_eq!(
                search_beans(&lib, &[], q).len(),
                1,
                "query {q:?} found nothing"
            );
        }
    }

    #[test]
    fn tags_are_searchable_on_every_shell() {
        let mut b = bean("Lot 42");
        b.tags = vec!["daily-driver".into(), "comp".into()];
        let hits = search_beans(&[b], &[], "daily");
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].fields[0].field, SearchField::Tags);
    }

    #[test]
    fn a_bag_matches_on_its_roastery_name() {
        let mut b = bean("Lot 42");
        b.roaster_id = Some("roaster:1".into());
        let hits = search_beans(&[b], &[roaster("roaster:1", "Onyx Coffee Lab")], "onyx");
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].fields[0].field, SearchField::Roaster);
    }

    #[test]
    fn derived_attributes_are_typeable_terms() {
        let mut decaf = bean("Sleepy");
        decaf.decaf = true;
        let mut blend = Bean::new("bean:2".into(), "House".into(), 0);
        blend.mix = Some(BeanMix::Blend);
        let lib = [decaf, blend];
        let hits = search_beans(&lib, &[], "decaf");
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].id, "bean:1");
        let hits = search_beans(&lib, &[], "blend");
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].id, "bean:2");
    }

    #[test]
    fn every_token_must_match_somewhere() {
        let mut a = bean("Ethiopia Natural");
        a.origin.country = Some("Ethiopia".into());
        a.origin.processing = Some("Natural".into());
        let mut b = Bean::new("bean:2".into(), "Ethiopia Washed".into(), 0);
        b.origin.country = Some("Ethiopia".into());
        b.origin.processing = Some("Washed".into());
        let hits = search_beans(&[a, b], &[], "ethiopia natural");
        assert_eq!(hits.len(), 1, "AND semantics: the washed lot must drop out");
        assert_eq!(hits[0].id, "bean:1");
    }

    #[test]
    fn a_name_hit_outranks_a_tasting_note_hit() {
        let named = bean("Jasmine Lot");
        let mut noted = Bean::new("bean:2".into(), "Lot 9".into(), 0);
        noted.tasting_notes = "peach, jasmine, syrupy".into();
        let hits = search_beans(&[noted, named], &[], "jasmine");
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0].id, "bean:1", "the name hit must sort first");
        assert!(hits[0].score > hits[1].score);
    }

    #[test]
    fn a_word_start_outranks_a_mid_word_hit() {
        let start = bean("Rasuna Estate");
        let mid = Bean::new("bean:2".into(), "Terraswork".into(), 0);
        let hits = search_beans(&[mid, start], &[], "ras");
        assert_eq!(hits[0].id, "bean:1");
    }

    #[test]
    fn a_typo_still_finds_the_bag() {
        let mut b = bean("Lot 42");
        b.origin.region = Some("Yirgacheffe".into());
        assert_eq!(search_beans(&[b.clone()], &[], "yirgacheff").len(), 1);
        let mut g = bean("Lot 42");
        g.origin.country = Some("Guatemala".into());
        assert_eq!(search_beans(&[g], &[], "guatamala").len(), 1);
    }

    #[test]
    fn a_transposition_counts_as_one_edit() {
        let mut b = bean("Lot 42");
        b.origin.country = Some("Guatemala".into());
        // "guatemlaa" is a single transposition away — plain Levenshtein
        // would score it 2 and (at 9 chars) still pass, so assert the
        // tighter case: an 8-char token with budget 2.
        assert_eq!(search_beans(&[b], &[], "guatemlaa").len(), 1);
    }

    #[test]
    fn short_tokens_do_not_get_typo_tolerance() {
        let b = bean("Bat");
        // "nat" is one edit from "bat" but three chars — no budget.
        assert!(search_beans(&[b], &[], "nat").is_empty());
    }

    #[test]
    fn a_one_character_token_does_not_subsequence_match() {
        let mut b = bean("Zzz");
        b.tasting_notes = "quiet".into();
        // 'q' is a substring of "quiet" so it matches; 'x' is nowhere.
        assert_eq!(search_beans(&[b.clone()], &[], "q").len(), 1);
        assert!(search_beans(&[b], &[], "x").is_empty());
    }

    #[test]
    fn scattered_letters_do_not_pass_the_floor() {
        let mut b = bean("Lot 9");
        b.url = Some("https://example.com/shop/coffee/lot-9".into());
        // Every letter of "chef" appears in that URL in order, but only as a
        // sparse subsequence in the lowest-weighted field.
        assert!(search_beans(&[b], &[], "chef").is_empty());
    }

    #[test]
    fn a_hit_reports_at_most_three_fields() {
        let mut b = bean("Natural Natural");
        b.origin.processing = Some("Natural".into());
        b.origin.region = Some("Natural".into());
        b.tasting_notes = "natural".into();
        b.notes = "natural".into();
        b.tags = vec!["natural".into()];
        let hits = search_beans(&[b], &[], "natural");
        assert!(hits[0].fields.len() <= MAX_FIELD_HITS);
    }

    #[test]
    fn a_snippet_windows_a_long_note_around_the_match() {
        let mut b = bean("Lot 9");
        b.tasting_notes = format!("{} jasmine {}", "a".repeat(200), "z".repeat(200));
        let hits = search_beans(&[b], &[], "jasmine");
        let joined = plain(&hits[0]);
        assert!(joined.chars().count() < 100, "snippet was {joined:?}");
        assert!(joined.starts_with('…') && joined.ends_with('…'));
        let hit: String = hits[0].fields[0]
            .snippet
            .iter()
            .filter(|s| s.hit)
            .map(|s| s.text.as_str())
            .collect();
        assert_eq!(hit, "jasmine");
    }

    #[test]
    fn a_short_field_is_not_ellipsised() {
        let b = bean("Yirgacheffe");
        let hits = search_beans(&[b], &[], "yirg");
        assert_eq!(hits[0].fields[0].snippet.len(), 2);
        assert_eq!(hits[0].fields[0].snippet[0].text, "Yirg");
        assert!(hits[0].fields[0].snippet[0].hit);
        assert_eq!(hits[0].fields[0].snippet[1].text, "acheffe");
    }

    #[test]
    fn roasters_search_over_name_city_country_and_notes() {
        let mut r = roaster("roaster:1", "Onyx Coffee Lab");
        r.city = Some("Rogers".into());
        r.country = Some("USA".into());
        r.notes = "subscription every fortnight".into();
        let lib = [r];
        for q in ["onyx", "rogers", "usa", "fortnight"] {
            assert_eq!(
                search_roasters(&lib, q).len(),
                1,
                "query {q:?} found nothing"
            );
        }
        assert!(search_roasters(&lib, "kalita").is_empty());
    }

    #[test]
    fn ties_break_on_id_so_every_shell_agrees() {
        let a = Bean::new("bean:b".into(), "Same".into(), 0);
        let b = Bean::new("bean:a".into(), "Same".into(), 0);
        let hits = search_beans(&[a, b], &[], "same");
        assert_eq!(hits[0].id, "bean:a");
        assert_eq!(hits[1].id, "bean:b");
    }

    #[test]
    fn the_json_bridge_round_trips() {
        let b = bean("Yirgacheffe");
        let beans = serde_json::to_string(&[b]).unwrap();
        let out = search_beans_json(&beans, "[]", "yirg").unwrap();
        let hits: Vec<SearchHit> = serde_json::from_str(&out).unwrap();
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].fields[0].label, "Name");
    }

    #[test]
    fn the_json_bridge_reports_bad_input_rather_than_panicking() {
        assert!(search_beans_json("not json", "[]", "x").is_err());
        assert!(search_roasters_json("{}", "x").is_err());
    }
}
