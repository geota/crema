//! The search fold (SPEC §5): one function folds both the indexed text and the
//! query, so the trigram index, the scan path and the reference rules all see
//! the same characters.

use unicode_normalization::UnicodeNormalization;
use unicode_normalization::char::is_combining_mark;

/// A search shorter than this many folded characters is no search (owner
/// question 3): the list shows what the other filters leave.
pub const MIN_SEARCH_CHARS: usize = 3;

/// The separator between searchable fields in `search_text`. It is a control
/// character, so [`fold`] removes it from every field and no match can span
/// two of them.
pub const FIELD_SEPARATOR: char = '\u{1F}';

/// `text` folded for search: lowercased, decomposed (NFD), with combining
/// marks and control characters removed. So "Café" and "CAFE" both fold to
/// "cafe", and "が" to "か".
///
/// Lowercasing can itself produce a combining mark ("İ" lowercases to "i"
/// and U+0307), and a decomposed character can lowercase further, so the
/// steps repeat until nothing changes. That keeps the fold idempotent, which
/// the index relies on: a folded query folds to itself.
#[must_use]
pub fn fold(text: &str) -> String {
    let mut out = fold_once(text);
    loop {
        let next = fold_once(&out);
        if next == out {
            return out;
        }
        out = next;
    }
}

fn fold_once(text: &str) -> String {
    text.to_lowercase()
        .nfd()
        .filter(|c| !is_combining_mark(*c) && !c.is_control())
        .collect()
}

/// The folded search for a typed `text`, or `None` when it is no search:
/// blank, or under [`MIN_SEARCH_CHARS`] folded characters once trimmed.
#[must_use]
pub fn search_needle(text: &str) -> Option<String> {
    let folded = fold(text);
    let needle = folded.trim();
    (needle.chars().count() >= MIN_SEARCH_CHARS).then(|| needle.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accented_latin_folds_to_its_base_letters() {
        assert_eq!(fold("Café Crème"), "cafe creme");
        assert_eq!(fold("Colômbia El Paraíso"), "colombia el paraiso");
        assert_eq!(fold("Zoë"), "zoe");
        assert_eq!(fold("Zuckersüß"), "zuckersuß");
        assert_eq!(fold("ÅNGSTRÖM"), "angstrom");
        assert_eq!(fold("Ñandú"), "nandu");
    }

    #[test]
    fn greek_folds_case_and_tonos() {
        assert_eq!(fold("Καφές"), "καφες");
        assert_eq!(fold("ΆΈΉ"), "αεη");
        // Final sigma lowercases in context, then stays.
        assert_eq!(fold("ΟΔΟΣ"), "οδος");
    }

    #[test]
    fn cjk_keeps_its_characters_and_drops_kana_voicing_marks() {
        assert_eq!(fold("珈琲"), "珈琲");
        assert_eq!(fold("苦い"), "苦い");
        // The dakuten is a combining mark after NFD.
        assert_eq!(fold("ガ"), "カ");
        assert_eq!(fold("が"), "か");
        // Hangul syllables decompose to conjoining jamo, which are letters, not marks.
        assert_eq!(fold("한"), "\u{1112}\u{1161}\u{11AB}");
    }

    #[test]
    fn emoji_pass_through_and_variation_selectors_go() {
        assert_eq!(fold("☕ Good"), "☕ good");
        assert_eq!(fold("🇯🇵"), "🇯🇵");
        // U+FE0F is a nonspacing mark, so the presentation selector is removed.
        assert_eq!(fold("❤\u{FE0F}"), "❤");
    }

    #[test]
    fn combining_marks_and_control_characters_are_removed() {
        assert_eq!(fold("e\u{0301}"), "e");
        assert_eq!(fold("a\u{0308}\u{0304}"), "a");
        assert_eq!(fold("one\u{1F}two\ttab\nline"), "onetwotabline");
        assert_eq!(fold("\u{0}x\u{7F}"), "x");
    }

    #[test]
    fn dotted_capital_i_folds_to_i() {
        assert_eq!(fold("İstanbul"), "istanbul");
    }

    #[test]
    fn fold_is_idempotent_on_the_golden_inputs() {
        for s in ["Café", "İ", "ΟΔΟΣ", "ガ", "한국", "❤\u{FE0F}", "ǅ", "ẞ"] {
            assert_eq!(fold(&fold(s)), fold(s), "{s}");
        }
    }

    #[test]
    fn searches_under_three_folded_characters_are_no_search() {
        assert_eq!(search_needle(""), None);
        assert_eq!(search_needle("   "), None);
        assert_eq!(search_needle("ki"), None);
        assert_eq!(search_needle(" ki "), None);
        // Two letters with marks are still two characters.
        assert_eq!(search_needle("é\u{0301}a"), None);
        assert_eq!(search_needle("Sey"), Some("sey".to_owned()));
        assert_eq!(search_needle("  CAFÉ "), Some("cafe".to_owned()));
        // Inner spaces count.
        assert_eq!(search_needle("a b"), Some("a b".to_owned()));
    }
}
