//! # profile-roast
//!
//! Classify a profile's **roast suitability** — which roast a recipe is tuned
//! for — by reading its title and notes together. Ported from the web shell's
//! `roastFromProfile` (`profiles/model.ts`) so the Profiles-page Light/Med/Dark
//! filter agrees across shells.
//!
//! This is *recipe-suitability* metadata, NOT bean identity (that is
//! [`crate::roast_band`], which buckets a 1..10 bean level). Roast stays
//! **optional**: `None` means "no roast clearly known", never "medium by
//! default". Tea / cleaning / calibration / pour-over / steam profiles have no
//! roast; espresso profiles resolve from an explicit roast phrase (most-
//! specific first) or a titled `default-*` slug, else `None`. An explicit
//! per-title override always wins.
//!
//! Uses `regex-lite` for the phrase matching: the patterns carry word
//! boundaries + flexible `[\s-]*` separators that a hand-rolled matcher would
//! reproduce only fragilely, and the input is the FIXED built-in corpus (no
//! prior test net), so a faithful regex port is the safe choice.

use regex_lite::Regex;
use std::sync::OnceLock;

/// A roast suitability band — the lowercase wire spelling matches the TS
/// `Roast` union (`'light' | 'medium' | 'dark'`).
fn light() -> &'static str {
    "light"
}
fn medium() -> &'static str {
    "medium"
}
fn dark() -> &'static str {
    "dark"
}

/// Explicit roast overrides keyed by **exact** built-in title — the escape
/// hatch for profiles the phrase heuristic mis-reads (adaptive / wide-range /
/// turbo profiles whose notes name *both* light and dark to describe a span).
/// `Some(value)` = overridden to `value` (`None` inside = "no roast");
/// outer `None` = not overridden. Mirrors `ROAST_OVERRIDES`.
fn roast_override(title: &str) -> Option<Option<&'static str>> {
    match title {
        "Gagné/Adaptive Shot 92C v1.0"
        | "Easy blooming - active pressure decline"
        | "Adaptive v2"
        | "I got your back"
        | "TurboBloom"
        | "TurboTurbo"
        | "Extractamundo Dos!" => Some(None),
        _ => None,
    }
}

/// Built-in profiles whose title is a `Tea/` or `Tea portafilter/` leaf — no
/// roast. Mirrors `isTeaProfile`.
fn is_tea_profile(title: &str) -> bool {
    let t = title.to_lowercase();
    t.starts_with("tea/") || t.starts_with("tea portafilter/")
}

/// Built-in non-coffee utility profiles (cleaning / calibration / GHC /
/// steam-only) — no roast. Mirrors `isUtilityProfile`.
fn is_utility_profile(title: &str) -> bool {
    let t = title.to_lowercase();
    t.starts_with("cleaning/")
        || t.starts_with("test/")
        || t.starts_with("ghc/")
        || t == "steam only"
}

/// Ordered roast phrases, most-specific first — a range resolves to the end the
/// notes lead with ("medium to dark" → dark). Mirrors `ROAST_PHRASES`, same
/// patterns + order. Compiled once.
fn roast_phrases() -> &'static [(Regex, &'static str)] {
    static PHRASES: OnceLock<Vec<(Regex, &'static str)>> = OnceLock::new();
    PHRASES.get_or_init(|| {
        let p: &[(&str, &'static str)] = &[
            // Explicit prescriptive ranges.
            (r"\bmedium[\s-]*to[\s-]*dark\b", "dark"),
            (r"\bmedium[\s-]*dark\b", "dark"),
            (r"\bdark[\s-]*to[\s-]*medium\b", "dark"),
            (r"\bmedium[\s-]*to[\s-]*light\b", "light"),
            (r"\bmedium[\s-]*light\b", "light"),
            (r"\blight[\s-]*to[\s-]*medium\b", "light"),
            // Single prescriptive roast words / phrases.
            (r"\b(?:very[\s-]+)?dark[\s-]+roast(?:ed|s)?\b", "dark"),
            (r"\b(?:a\s+)?dark[\s-]+roast\b", "dark"),
            (r"\bdark[\s-]+roasted\b", "dark"),
            (r"\bmedium[\s-]+roast(?:ed|s)?\b", "medium"),
            (r"\b(?:light(?:ly)?)[\s-]+roast(?:ed|s)?\b", "light"),
        ];
        p.iter()
            .map(|(re, roast)| (Regex::new(re).expect("static roast phrase compiles"), *roast))
            .collect()
    })
}

/// Titled roast hints used by the A-Flow family (`default-dark`,
/// `default-very-dark`, `default-medium`, …) — the roast rides in the title
/// slug, not the notes. `(very dark | default-dark, default-medium,
/// default-light)`. Compiled once.
fn title_slug_patterns() -> &'static (Regex, Regex, Regex) {
    static SLUGS: OnceLock<(Regex, Regex, Regex)> = OnceLock::new();
    SLUGS.get_or_init(|| {
        (
            Regex::new(r"\bvery[\s-]*dark\b|\bdefault-dark\b").expect("dark slug compiles"),
            Regex::new(r"\bdefault-medium\b").expect("medium slug compiles"),
            Regex::new(r"\bdefault-light\b").expect("light slug compiles"),
        )
    })
}

/// Classify a profile's roast suitability from its `title` + `notes`. Returns
/// the lowercase wire string (`"light"` / `"medium"` / `"dark"`) or `None`.
/// Mirrors `roastFromProfile`.
#[must_use]
pub fn roast_from_profile(title: &str, notes: &str) -> Option<&'static str> {
    if let Some(over) = roast_override(title) {
        return over;
    }
    if is_tea_profile(title) || is_utility_profile(title) {
        return None;
    }
    let text = format!("{title}\n{notes}").to_lowercase();
    for (re, roast) in roast_phrases() {
        if re.is_match(&text) {
            return Some(roast);
        }
    }
    let title_l = title.to_lowercase();
    let (dark_slug, medium_slug, light_slug) = title_slug_patterns();
    if dark_slug.is_match(&title_l) {
        return Some(dark());
    }
    if medium_slug.is_match(&title_l) {
        return Some(medium());
    }
    if light_slug.is_match(&title_l) {
        return Some(light());
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn overrides_win_and_resolve_to_none() {
        for title in [
            "Gagné/Adaptive Shot 92C v1.0",
            "Adaptive v2",
            "I got your back",
            "TurboBloom",
            "TurboTurbo",
            "Extractamundo Dos!",
            "Easy blooming - active pressure decline",
        ] {
            // Even with a roast word in the notes, the override forces None.
            assert_eq!(roast_from_profile(title, "a dark roast"), None, "{title}");
        }
    }

    #[test]
    fn tea_and_utility_profiles_have_no_roast() {
        assert_eq!(roast_from_profile("Tea/Green", "dark roast"), None);
        assert_eq!(roast_from_profile("Tea portafilter/Oolong", ""), None);
        assert_eq!(roast_from_profile("Cleaning/Backflush", ""), None);
        assert_eq!(roast_from_profile("Test/Leak", ""), None);
        assert_eq!(roast_from_profile("GHC/whatever", ""), None);
        assert_eq!(roast_from_profile("Steam only", ""), None);
    }

    #[test]
    fn ranges_resolve_to_the_leading_end() {
        assert_eq!(roast_from_profile("X", "medium to dark roast"), Some("dark"));
        assert_eq!(roast_from_profile("X", "medium-dark"), Some("dark"));
        assert_eq!(roast_from_profile("X", "dark to medium"), Some("dark"));
        assert_eq!(roast_from_profile("X", "medium to light"), Some("light"));
        assert_eq!(roast_from_profile("X", "medium light"), Some("light"));
        assert_eq!(roast_from_profile("X", "light to medium"), Some("light"));
    }

    #[test]
    fn single_roast_words() {
        assert_eq!(roast_from_profile("X", "a dark roast"), Some("dark"));
        assert_eq!(roast_from_profile("X", "very dark roasted beans"), Some("dark"));
        assert_eq!(roast_from_profile("X", "dark roasted"), Some("dark"));
        assert_eq!(roast_from_profile("X", "medium roast"), Some("medium"));
        assert_eq!(roast_from_profile("X", "medium roasts"), Some("medium"));
        assert_eq!(roast_from_profile("X", "light roast"), Some("light"));
        assert_eq!(roast_from_profile("X", "lightly roasted"), Some("light"));
    }

    #[test]
    fn title_slugs_classify_the_a_flow_family() {
        assert_eq!(roast_from_profile("Default-very-dark", ""), Some("dark"));
        assert_eq!(roast_from_profile("Default-dark", ""), Some("dark"));
        assert_eq!(roast_from_profile("Default-medium", ""), Some("medium"));
        assert_eq!(roast_from_profile("Default-light", ""), Some("light"));
    }

    #[test]
    fn no_clear_roast_is_none() {
        assert_eq!(roast_from_profile("Best Overall", "great everyday shot"), None);
        assert_eq!(roast_from_profile("Blooming espresso", ""), None);
        // A bare "dark" with no "roast" word does NOT match a phrase.
        assert_eq!(roast_from_profile("X", "this is a dark and stormy day"), None);
    }

    #[test]
    fn phrase_order_prefers_the_range_over_the_bare_word() {
        // "medium to dark roast" must hit the range (→ dark), not "medium roast".
        assert_eq!(
            roast_from_profile("X", "tuned for medium to dark roast coffee"),
            Some("dark")
        );
    }
}
