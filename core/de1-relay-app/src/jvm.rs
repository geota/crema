//! The Kotlin/JVM text and number rules the relayed-command checks were
//! written against on Android, kept here so a hub accepts, refuses and words
//! exactly what it did before the checks moved into the core.
//!
//! - Lengths count UTF-16 code units, as a Kotlin `String.length` does.
//! - Whitespace is Kotlin's `Char.isWhitespace()` on the JVM, which
//!   `trim()` and `isBlank()` use; a control character is
//!   `Char.isISOControl()`; a format character is a BMP character whose
//!   `Character.getType` is `FORMAT` (Kotlin looks at UTF-16 units, so a
//!   supplementary format character never counted).
//! - [`parse_double`] is Kotlin's `String.toDoubleOrNull()` (the JVM's
//!   `Double.valueOf` grammar, hex floats and `f`/`d` suffixes included).
//! - [`double_to_string`] and [`float_to_string`] are Java's
//!   `Double.toString` and `Float.toString` (JDK 19 and later: the shortest
//!   decimal that rounds back, never fewer than two digits).
//!
//! `tests/jvm.rs` checks all of it against vectors generated on a JDK.

/// Kotlin's `Char.isWhitespace()` on the JVM (`Character.isWhitespace` or
/// `Character.isSpaceChar`). Unlike Rust's [`char::is_whitespace`] it takes
/// U+001C–U+001F and leaves out U+0085.
#[must_use]
pub fn is_whitespace(c: char) -> bool {
    matches!(
        c,
        '\u{9}'..='\u{D}'
            | '\u{1C}'..='\u{20}'
            | '\u{A0}'
            | '\u{1680}'
            | '\u{2000}'..='\u{200A}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{202F}'
            | '\u{205F}'
            | '\u{3000}'
    )
}

/// Kotlin's `String.trim()`.
#[must_use]
pub fn trim(s: &str) -> &str {
    s.trim_matches(is_whitespace)
}

/// Kotlin's `String.isBlank()`: empty, or whitespace only.
#[must_use]
pub fn is_blank(s: &str) -> bool {
    s.chars().all(is_whitespace)
}

/// Kotlin's `Char.isISOControl()`: U+0000–U+001F and U+007F–U+009F, the
/// same set as [`char::is_control`].
#[must_use]
pub fn is_iso_control(c: char) -> bool {
    c.is_control()
}

/// Whether `c` is a BMP format character (general category `Cf`, Unicode
/// 15), the ones `Character.getType(char) == FORMAT` finds: the soft hyphen,
/// the bidi marks, embeddings, overrides and isolates, the zero-width
/// characters, the BOM, the interlinear annotation marks and the like.
#[must_use]
pub fn is_bmp_format(c: char) -> bool {
    matches!(
        c,
        '\u{AD}'
            | '\u{600}'..='\u{605}'
            | '\u{61C}'
            | '\u{6DD}'
            | '\u{70F}'
            | '\u{890}'..='\u{891}'
            | '\u{8E2}'
            | '\u{180E}'
            | '\u{200B}'..='\u{200F}'
            | '\u{202A}'..='\u{202E}'
            | '\u{2060}'..='\u{2064}'
            | '\u{2066}'..='\u{206F}'
            | '\u{FEFF}'
            | '\u{FFF9}'..='\u{FFFB}'
    )
}

/// `s.length` in Kotlin: its UTF-16 code units.
#[must_use]
pub fn utf16_len(s: &str) -> usize {
    s.chars().map(char::len_utf16).sum()
}

/// Kotlin's `s.take(n)`: the first `n` UTF-16 code units. A surrogate pair
/// that straddles the cut is left out whole (Kotlin would keep its lone high
/// half, which no Rust string can hold).
#[must_use]
pub fn take_utf16(s: &str, n: usize) -> &str {
    let mut units = 0;
    for (i, c) in s.char_indices() {
        units += c.len_utf16();
        if units > n {
            return &s[..i];
        }
    }
    s
}

/// Kotlin's `String.toDoubleOrNull()` on the JVM: `Double.valueOf`'s
/// grammar. Leading and trailing U+0000–U+0020 are ignored; then an optional
/// sign and `NaN`, `Infinity`, a decimal (`12`, `1.`, `.5`, `1e5`) or a hex
/// float (`0x1.8p1`, binary exponent required), the last two with an
/// optional `f`, `F`, `d` or `D` suffix. ASCII digits only. Correctly
/// rounded, as the JVM is.
#[must_use]
pub fn parse_double(s: &str) -> Option<f64> {
    let t = s.trim_matches(|c: char| c <= ' ');
    let (negative, body) = match t.as_bytes().first() {
        Some(b'-') => (true, &t[1..]),
        Some(b'+') => (false, &t[1..]),
        _ => (false, t),
    };
    let magnitude = match body {
        "NaN" => f64::NAN,
        "Infinity" => f64::INFINITY,
        _ => {
            let number = body.strip_suffix(['f', 'F', 'd', 'D']).unwrap_or(body);
            match number
                .strip_prefix("0x")
                .or_else(|| number.strip_prefix("0X"))
            {
                Some(hex) => parse_hex(hex)?,
                None => parse_decimal(number)?,
            }
        }
    };
    Some(if negative { -magnitude } else { magnitude })
}

fn all_digits(s: &str) -> bool {
    s.bytes().all(|b| b.is_ascii_digit())
}

/// `Digits [. Digits] [e [sign] Digits]` with at least one mantissa digit,
/// unsigned; Rust's parser rounds it (correctly, as the JVM's does).
fn parse_decimal(s: &str) -> Option<f64> {
    let (mantissa, exponent) = match s.find(['e', 'E']) {
        Some(i) => (&s[..i], Some(&s[i + 1..])),
        None => (s, None),
    };
    let (int, frac) = mantissa.split_once('.').unwrap_or((mantissa, ""));
    if (int.is_empty() && frac.is_empty()) || !all_digits(int) || !all_digits(frac) {
        return None;
    }
    if let Some(e) = exponent {
        let digits = e.strip_prefix(['+', '-']).unwrap_or(e);
        if digits.is_empty() || !all_digits(digits) {
            return None;
        }
    }
    s.parse().ok()
}

/// The part of a hex float after `0x`: `HexDigits [. HexDigits] (p|P) [sign]
/// Digits`, at least one hex digit, rounded to nearest, ties to even.
fn parse_hex(s: &str) -> Option<f64> {
    let (mantissa, exponent) = s.split_once(['p', 'P'])?;
    let (int, frac) = mantissa.split_once('.').unwrap_or((mantissa, ""));
    let hex = |t: &str| t.bytes().all(|b| b.is_ascii_hexdigit());
    if (int.is_empty() && frac.is_empty()) || !hex(int) || !hex(frac) {
        return None;
    }
    let exp_digits = exponent.strip_prefix(['+', '-']).unwrap_or(exponent);
    if exp_digits.is_empty() || !all_digits(exp_digits) {
        return None;
    }
    // Saturated far past any finite double, so the arithmetic below can't overflow.
    const EXP_CAP: i64 = 1 << 40;
    let mut exp = exp_digits
        .bytes()
        .fold(0_i64, |e, b| (e * 10 + i64::from(b - b'0')).min(EXP_CAP));
    if exponent.starts_with('-') {
        exp = -exp;
    }
    // The leading 64 bits of the mantissa, the rest folded into `sticky`;
    // the value is `bits × 2^exp`.
    let mut bits: u64 = 0;
    let mut sticky = false;
    let digit = |b: u8| u64::from(char::from(b).to_digit(16).unwrap_or(0));
    for b in int.bytes() {
        if bits >> 60 == 0 {
            bits = bits << 4 | digit(b);
        } else {
            sticky |= digit(b) != 0;
            exp = (exp + 4).min(EXP_CAP);
        }
    }
    for b in frac.bytes() {
        if bits >> 60 == 0 {
            bits = bits << 4 | digit(b);
            exp = (exp - 4).max(-EXP_CAP);
        } else {
            sticky |= digit(b) != 0;
        }
    }
    Some(round_to_f64(bits, exp, sticky))
}

/// `bits × 2^exp` (plus a nonzero remainder below `bits` when `sticky`),
/// rounded to the nearest double, ties to even.
fn round_to_f64(bits: u64, exp: i64, sticky: bool) -> f64 {
    if bits == 0 {
        return 0.0;
    }
    let lz = bits.leading_zeros();
    let m = bits << lz;
    // `m` has its top bit set: the value is 1.f × 2^top.
    let top = exp - i64::from(lz) + 63;
    if top > 1023 {
        return f64::INFINITY;
    }
    // How many of m's bits the double keeps: 53 when normal, fewer below.
    let kept = if top >= -1022 { 53 } else { top + 1075 };
    if kept < 0 {
        // Below half the least subnormal.
        return 0.0;
    }
    let drop = u32::try_from(64 - kept).unwrap_or(64);
    let wide = u128::from(m);
    let mut keep = wide >> drop;
    let half = 1_u128 << (drop - 1);
    let round = wide & half != 0;
    let rest = wide & (half - 1) != 0 || sticky;
    if round && (rest || keep & 1 == 1) {
        keep += 1;
    }
    let keep = u64::try_from(keep).unwrap_or(u64::MAX);
    if kept < 53 {
        // Subnormal, or the least normal when rounding carried into it.
        return f64::from_bits(keep);
    }
    let (keep, top) = if keep == 1 << 53 {
        (1 << 52, top + 1)
    } else {
        (keep, top)
    };
    if top > 1023 {
        return f64::INFINITY;
    }
    let biased = u64::try_from(top + 1023).unwrap_or(0);
    f64::from_bits(biased << 52 | (keep - (1 << 52)))
}

/// Java's `Double.toString(v)`: `NaN`, `Infinity`, `-0.0`, `18.0`,
/// `0.001`, `1.0E7`, `4.9E-324`.
#[must_use]
pub fn double_to_string(v: f64) -> String {
    if v.is_nan() {
        return "NaN".into();
    }
    let digits = |form: Form| match form {
        Form::Shortest => format!("{:e}", v.abs()),
        Form::TwoDigits => format!("{:.1e}", v.abs()),
        Form::Exact => format!("{:.800e}", v.abs()),
    };
    let rounds_back = |text: &str| text.parse::<f64>().is_ok_and(|b| b == v.abs());
    java_layout(
        v.is_sign_negative(),
        v.is_infinite(),
        v == 0.0,
        digits,
        rounds_back,
    )
}

/// Java's `Float.toString(v)`: as [`double_to_string`], on the float's own
/// shortest digits (`148.0`, `1.2`, `1.4E-45`).
#[must_use]
pub fn float_to_string(v: f32) -> String {
    if v.is_nan() {
        return "NaN".into();
    }
    let digits = |form: Form| match form {
        Form::Shortest => format!("{:e}", v.abs()),
        Form::TwoDigits => format!("{:.1e}", v.abs()),
        // Every float's exact decimal fits: 149 digits at most.
        Form::Exact => format!("{:.800e}", f64::from(v.abs())),
    };
    let rounds_back = |text: &str| text.parse::<f32>().is_ok_and(|b| b == v.abs());
    java_layout(
        v.is_sign_negative(),
        v.is_infinite(),
        v == 0.0,
        digits,
        rounds_back,
    )
}

/// The scientific forms of |v| [`java_layout`] reads.
#[derive(Clone, Copy)]
enum Form {
    /// Rust's shortest round-trip form (`1.25e-3`).
    Shortest,
    /// Two significant digits, correctly rounded.
    TwoDigits,
    /// Every digit of the exact binary value (767 at most, for a double).
    Exact,
}

/// Lays a value out as Java does, from its scientific forms (`sci`);
/// `rounds_back` says whether a decimal (`1.25e-3`) parses back to |v|.
/// - Java never prints fewer than two digits: when the shortest has one, it
///   picks the two-digit decimal closest to v (`4.9E-324`, not `5.0E-324`).
/// - When two shortest decimals are equally close, Java takes the one with
///   the even last digit (`265428.62` for 265428.625f); Rust's choice can
///   differ, so an exact tie is settled here.
fn java_layout(
    negative: bool,
    infinite: bool,
    zero: bool,
    sci: impl Fn(Form) -> String,
    rounds_back: impl Fn(&str) -> bool,
) -> String {
    let sign = if negative { "-" } else { "" };
    if infinite {
        return format!("{sign}Infinity");
    }
    if zero {
        return format!("{sign}0.0");
    }
    let (mut digits, mut exp) = split_sci(&sci(Form::Shortest));
    if digits.len() == 1 {
        (digits, exp) = split_sci(&sci(Form::TwoDigits));
    } else if let Some((even, even_exp)) = even_of_tie(&digits, exp, &sci(Form::Exact)) {
        // Only a decimal that rounds back to v is a candidate.
        if rounds_back(&format!("{}.{}e{even_exp}", &even[..1], &even[1..])) {
            (digits, exp) = (even, even_exp);
        }
    }
    let digits = digits.trim_end_matches('0');
    let digits = if digits.is_empty() { "0" } else { digits };
    let n = i64::try_from(digits.len()).unwrap_or(i64::MAX);
    let body = if (-3..7).contains(&exp) {
        if exp >= 0 {
            let int_len = usize::try_from(exp + 1).unwrap_or(0);
            if digits.len() > int_len {
                format!("{}.{}", &digits[..int_len], &digits[int_len..])
            } else {
                let zeros = "0".repeat(usize::try_from(exp + 1 - n).unwrap_or(0));
                format!("{digits}{zeros}.0")
            }
        } else {
            let zeros = "0".repeat(usize::try_from(-exp - 1).unwrap_or(0));
            format!("0.{zeros}{digits}")
        }
    } else {
        let rest = if digits.len() > 1 { &digits[1..] } else { "0" };
        format!("{}.{rest}E{exp}", &digits[..1])
    };
    format!("{sign}{body}")
}

/// When |v| (`exact`) lies exactly halfway between two `digits.len()`-digit
/// decimals, one of them `digits`, the one with the even last digit, and its
/// exponent (a carry out of `9…9` moves it).
fn even_of_tie(digits: &str, exp: i64, exact: &str) -> Option<(String, i64)> {
    let (exact_digits, exact_exp) = split_sci(exact);
    let exact_digits = exact_digits.trim_end_matches('0');
    let n = digits.len();
    if exact_exp != exp || exact_digits.len() != n + 1 || !exact_digits.ends_with('5') {
        return None;
    }
    let mut low = exact_digits.as_bytes()[..n].to_vec();
    if low.last().is_some_and(|d| (d - b'0').is_multiple_of(2)) {
        return Some((String::from_utf8(low).ok()?, exp));
    }
    // `low` + 1 in its last place.
    for d in low.iter_mut().rev() {
        if *d == b'9' {
            *d = b'0';
        } else {
            *d += 1;
            return Some((String::from_utf8(low).ok()?, exp));
        }
    }
    low.insert(0, b'1');
    low.pop();
    Some((String::from_utf8(low).ok()?, exp + 1))
}

/// `1.25e-3` → (`"125"`, -3).
fn split_sci(s: &str) -> (String, i64) {
    let (mantissa, exp) = s.split_once('e').unwrap_or((s, "0"));
    (mantissa.replace('.', ""), exp.parse().unwrap_or(0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn whitespace_is_kotlins() {
        assert!(is_whitespace('\u{1C}') && is_whitespace('\u{A0}'));
        assert!(!is_whitespace('\u{85}') && !is_whitespace('\u{200B}'));
        assert_eq!(trim("\u{1F} a \u{3000}"), "a");
        assert!(is_blank("") && is_blank(" \t") && !is_blank("\u{85}"));
    }

    #[test]
    fn take_counts_utf16_units() {
        assert_eq!(take_utf16("abc", 2), "ab");
        assert_eq!(take_utf16("a😀b", 3), "a😀");
        assert_eq!(take_utf16("a😀b", 2), "a");
        assert_eq!(utf16_len("a😀b"), 4);
    }

    #[test]
    fn layout() {
        assert_eq!(double_to_string(18.0), "18.0");
        assert_eq!(double_to_string(0.001), "0.001");
        assert_eq!(double_to_string(1e7), "1.0E7");
        assert_eq!(double_to_string(1e-4), "1.0E-4");
        assert_eq!(double_to_string(-0.0), "-0.0");
        assert_eq!(double_to_string(f64::NEG_INFINITY), "-Infinity");
        assert_eq!(double_to_string(f64::from_bits(1)), "4.9E-324");
        assert_eq!(float_to_string(f32::from_bits(1)), "1.4E-45");
        assert_eq!(float_to_string(1.2), "1.2");
        assert_eq!(double_to_string(f64::from(1.2_f32)), "1.2000000476837158");
        assert_eq!(double_to_string(1_234_567.0), "1234567.0");
    }
}
