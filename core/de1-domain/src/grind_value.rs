//! # grind_value
//!
//! One grind value for Brew, shots and history (#488, save-boundaries round
//! 2, REVIEW-CORE H1, H2, L1). Both shells and the core's shot capture and
//! history read paths use these rules, so a grind reads the same on the
//! screen, on the bag and in the shot.
//!
//! **A grind is text.** Stored and recorded as typed or as the dial wrote it,
//! never clamped or rewritten. A value that isn't a plain number ("2.1.3",
//! "6 + a tooth") is kept and shown as typed.
//!
//! - [`grind_text`]: a dial number as text: whole numbers without a decimal,
//!   otherwise as many decimals as the scale's step needs (0.05 → two). A
//!   value off the step's decimals (typed 2.123 on a 0.1 scale) keeps its own
//!   decimals, up to [`MAX_OFF_STEP_DECIMALS`]; no one-decimal rounding.
//! - [`same_grind`]: numeric when both parse ("15" = "15.0"), else trimmed
//!   text.
//! - **Thirds.** A scale whose step is one third (the Fellow Ode: 1–11, 31
//!   settings) counts in thirds of a whole number: [`grind_text`] writes
//!   `4`, `4⅓`, `4⅔` (never 4.33), [`grind_number`] reads those and
//!   `4 1/3`, and the chips and the blank start land on thirds. The step is
//!   stored as the nearest `f32` to 1/3; see [`counts_in_thirds`].
//! - [`format_grind`]: the unit only after a plain number ("480 μm", "2.1.3").
//! - [`grind_scale_refusal`]: why a typed scale can't be stored.
//! - [`brew_grind_view`]: what Brew's grind is (decided 2026-11-22, DESIGN
//!   "No grind set is its own state"): `None` = not set, never a placeholder
//!   number. What the header shows is what the shot records.
//! - [`grind_chips`]: the Quick Controls chips, single steps around the value.
//!
//! **Text grinds on Brew (N2), one rule for both shells:** a grind that isn't
//! a plain number (typed, or picked from a bag's saved grind) *is* Brew's
//! grind: the header shows it as typed (no unit), the shot records it, the
//! marker compares it as trimmed text and Save saves it. It has no dial
//! number ([`BrewGrindView::dial`] is `None`), so the stepper's first + or −
//! starts at [`BrewGrindView::start_from_blank`], as from blank, and no chips
//! show. A pick never clears it to blank.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::bag_grind::{NO_GRINDER_ID, saved_grind};
use crate::bean::Bean;
use crate::grinder::{GrindScale, Grinder};

/// The most decimals a step is read with: finer steps are treated as this.
pub const MAX_STEP_DECIMALS: usize = 6;

/// The most decimals [`grind_text`] keeps for a value that isn't on the
/// step's decimals (a typed 2.123 on a 0.1 scale).
pub const MAX_OFF_STEP_DECIMALS: usize = 4;

/// An `f32` as the `f64` its shortest text names: `4.2f32` → `4.2`, not
/// `4.199999809…`. Scales are stored as `f32`.
fn clean(v: f32) -> f64 {
    format!("{v}").parse().unwrap_or_else(|_| f64::from(v))
}

/// How many decimals `v` is written with: `1` → 0, `0.1` → 1, `0.05` → 2.
/// At most [`MAX_STEP_DECIMALS`].
fn decimals_of(v: f32) -> usize {
    let text = format!("{v}");
    text.split_once('.')
        .map_or(0, |(_, frac)| frac.trim_end_matches('0').len())
        .min(MAX_STEP_DECIMALS)
}

/// How many decimals a value on `scale`'s step needs: the step's, or the
/// lowest setting's when finer (the steps count from it: 0.5, 1.5, … in 1).
fn scale_decimals(scale: &GrindScale) -> usize {
    let r = scale.step_range();
    decimals_of(r.step).max(decimals_of(r.min))
}

/// Whether `scale` counts in thirds: its step is one third, as stored (the
/// nearest `f32`, 0.33333334) or typed to four places (0.3333). A step of
/// 0.333 is a decimal step.
#[must_use]
pub fn counts_in_thirds(scale: &GrindScale) -> bool {
    (f64::from(scale.step_range().step) * 3.0 - 1.0).abs() < 5e-4
}

/// `v` as whole thirds (`4`, `4⅓`, `⅔`), when it is one, within float noise
/// and not below 0; `None` otherwise (written with decimals instead).
// The thirds count saturates (`as` on a float, never negative here); a grind
// is far below 2^52.
#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn thirds_text(v: f64) -> Option<String> {
    let n = (v * 3.0).round();
    if n < 0.0 || (v * 3.0 - n).abs() > (v.abs() * 3e-6).max(1e-4) {
        return None;
    }
    let n = n as u64;
    let (whole, rem) = (n / 3, n % 3);
    let frac = match rem {
        1 => "⅓",
        2 => "⅔",
        _ => "",
    };
    Some(if rem == 0 {
        whole.to_string()
    } else if whole == 0 {
        frac.to_owned()
    } else {
        format!("{whole}{frac}")
    })
}

/// A grind written in thirds (`4⅓`, `4 ⅓`, `⅔`, `4 1/3`, `2/3`) as a number:
/// the whole part plus the third. `None` for anything else.
fn thirds_number(raw: &str) -> Option<f64> {
    let (whole, thirds, ascii) = if let Some(w) = raw.strip_suffix('⅓') {
        (w, 1_u32, false)
    } else if let Some(w) = raw.strip_suffix('⅔') {
        (w, 2, false)
    } else if let Some(w) = raw.strip_suffix("1/3") {
        (w, 1, true)
    } else {
        (raw.strip_suffix("2/3")?, 2, true)
    };
    // "41/3" is forty-one thirds, not four and a third: a typed fraction
    // after a whole number needs a space.
    if ascii && !whole.is_empty() && !whole.ends_with(char::is_whitespace) {
        return None;
    }
    let whole = whole.trim_end();
    let whole = if whole.is_empty() {
        0
    } else if whole.bytes().all(|b| b.is_ascii_digit()) {
        whole.parse::<u32>().ok()?
    } else {
        return None;
    };
    Some(f64::from(whole) + f64::from(thirds) / 3.0)
}

/// `v` with `decimals` decimals, trailing zeros and a bare point dropped;
/// `-0` reads `0`.
fn fixed(v: f64, decimals: usize) -> String {
    let mut s = format!("{v:.decimals$}");
    if s.contains('.') {
        s.truncate(s.trim_end_matches('0').trim_end_matches('.').len());
    }
    if s == "-0" { "0".to_owned() } else { s }
}

/// The dial number `dial` as stored text on `scale`: whole numbers without a
/// decimal (`15`), otherwise as many decimals as the step needs (`2.25` on a
/// 0.05 step, `4.2` on 0.1), trailing zeros dropped. Float noise from the
/// stepper or an `f32` dial (`4.199999809`) is absorbed. A value that isn't
/// on the step's decimals keeps its own, up to [`MAX_OFF_STEP_DECIMALS`]
/// (`2.25` on a 0.1 scale stays `2.25`): never rounded to one decimal, never
/// moved onto the step. Empty for a value that isn't finite.
#[must_use]
pub fn grind_text(dial: f64, scale: &GrindScale) -> String {
    if !dial.is_finite() {
        return String::new();
    }
    if counts_in_thirds(scale) {
        return thirds_text(dial).unwrap_or_else(|| fixed(dial, MAX_OFF_STEP_DECIMALS));
    }
    let decimals = scale_decimals(scale);
    let on_step = fixed(dial, decimals);
    // Noise is far below the step's last decimal, and grows with an `f32`
    // dial's magnitude.
    let noise =
        (0.1_f64.powi(i32::try_from(decimals).unwrap_or(i32::MAX)) * 1e-3).max(dial.abs() * 1e-6);
    match on_step.parse::<f64>() {
        Ok(v) if (v - dial).abs() <= noise => on_step,
        _ => fixed(dial, decimals.max(MAX_OFF_STEP_DECIMALS)),
    }
}

/// The largest grind (either sign) [`grind_number`] reads as a number; past
/// it a grind stays text. Far past any grinder's scale.
pub const MAX_GRIND_NUMBER: f64 = 1e6;

/// The longest grind (trimmed, in bytes) [`grind_number`] reads as a number.
pub const MAX_GRIND_NUMBER_LEN: usize = 32;

/// Whether `raw` is a plain decimal: an optional sign, then digits with at
/// most one point (`12`, `1.`, `.5`, `+2.25`). No exponent, no words.
fn plain_decimal(raw: &str) -> bool {
    let digits = raw.strip_prefix(['+', '-']).unwrap_or(raw);
    let (int, frac) = digits.split_once('.').unwrap_or((digits, ""));
    let all_digits = |t: &str| t.bytes().all(|b| b.is_ascii_digit());
    (!int.is_empty() || !frac.is_empty()) && all_digits(int) && all_digits(frac)
}

/// A grind as a number, when it is a plain one: trimmed, a decimal comma
/// read as a point (`"2,5"` → 2.5), or thirds (`"4⅓"`, `"4 1/3"`, see the
/// module docs). `None` for text ("2.1.3", "6 + a tooth"), blank, a word
/// Rust would parse ("inf", "NaN"), an exponent (`"1e3"`: an imported
/// `"1e999999999"` stays text, never a number to expand), more than
/// [`MAX_GRIND_NUMBER_LEN`] bytes, or past [`MAX_GRIND_NUMBER`]. Both
/// shells read typed and stored grinds through this.
#[must_use]
pub fn grind_number(grind: &str) -> Option<f64> {
    let trimmed = grind.trim();
    if trimmed.len() > MAX_GRIND_NUMBER_LEN {
        return None;
    }
    let n = match thirds_number(trimmed) {
        Some(n) => n,
        None => {
            let raw = trimmed.replace(',', ".");
            if !plain_decimal(&raw) {
                return None;
            }
            raw.parse::<f64>().ok()?
        }
    };
    (n.is_finite() && n.abs() <= MAX_GRIND_NUMBER).then_some(n)
}

/// Whether two grinds are the same: numerically when both are plain numbers
/// (`"15"` = `"15.0"`, `"2,5"` = `"2.5"`), else as trimmed text.
#[must_use]
pub fn same_grind(a: &str, b: &str) -> bool {
    match (grind_number(a), grind_number(b)) {
        (Some(x), Some(y)) => x == y,
        _ => a.trim() == b.trim(),
    }
}

/// `grind` as shown with `scale`'s unit: `"480 μm"`; a value that isn't a
/// plain number as typed, without a unit (`"2.1.3"`); `"—"` for a blank
/// one. The value itself is never rewritten.
#[must_use]
pub fn format_grind(grind: &str, scale: &GrindScale) -> String {
    let text = grind.trim();
    if text.is_empty() {
        return "—".to_owned();
    }
    let unit = scale.unit.trim();
    if unit.is_empty() || grind_number(text).is_none() {
        text.to_owned()
    } else {
        format!("{text} {unit}")
    }
}

/// Why a typed scale can't be stored (Devices' grinder fields), first
/// failing rule first.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ScaleRefusal {
    /// A field isn't a number: "Enter a number in each field".
    NotANumber,
    /// "Lowest must be below Highest".
    LowestNotBelowHighest,
    /// "Step must be more than 0".
    StepNotPositive,
    /// "Step must fit between Lowest and Highest".
    StepTooLarge,
}

/// Why `scale` can't be stored, or `None`: every value finite, the lowest
/// below the highest, the step above 0 and no more than the range.
#[must_use]
pub fn grind_scale_refusal(scale: &GrindScale) -> Option<ScaleRefusal> {
    let GrindScale { min, max, step, .. } = *scale;
    if ![min, max, step].iter().all(|v| v.is_finite()) {
        return Some(ScaleRefusal::NotANumber);
    }
    let (min, max, step) = (clean(min), clean(max), clean(step));
    if min >= max {
        Some(ScaleRefusal::LowestNotBelowHighest)
    } else if step <= 0.0 {
        Some(ScaleRefusal::StepNotPositive)
    } else if step > max - min + 1e-9 {
        Some(ScaleRefusal::StepTooLarge)
    } else {
        None
    }
}

/// `scale`'s stepper range as clean `f64`s: (lowest, highest, step,
/// decimals). A thirds scale's step is exactly 1/3.
fn grid(scale: &GrindScale) -> (f64, f64, f64, usize) {
    let r = scale.step_range();
    let step = if counts_in_thirds(scale) {
        1.0 / 3.0
    } else {
        clean(r.step)
    };
    (clean(r.min), clean(r.max), step, scale_decimals(scale))
}

/// A point on `scale`'s grid as (value, text): thirds on a thirds scale,
/// else `decimals` decimals.
fn on_grid(v: f64, scale: &GrindScale, decimals: usize) -> (f64, String) {
    if counts_in_thirds(scale) {
        let text = thirds_text(v).unwrap_or_else(|| fixed(v, MAX_OFF_STEP_DECIMALS));
        let value = grind_number(&text).unwrap_or(v);
        return (value, text);
    }
    let text = fixed(v, decimals);
    (text.parse().unwrap_or(v), text)
}

/// Where the stepper's first + or − goes from a blank grind: the middle of
/// `scale` on its step (counted from the lowest setting): 10 on the default
/// 0–20 scale, 500 on 0–999 in 1.
#[must_use]
pub fn grind_start_from_blank(scale: &GrindScale) -> f64 {
    let (lo, hi, step, decimals) = grid(scale);
    let mid = lo + ((hi - lo) / 2.0 / step).round() * step;
    on_grid(mid.min(hi), scale, decimals).0
}

/// One Quick Controls grind chip.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GrindChip {
    /// The chip's value, for the stepper.
    pub value: f64,
    /// The value as Brew's grind takes it on a tap ([`grind_text`]).
    pub text: String,
}

/// Up to `count` chips on `scale`'s step around `around` (Brew's grind):
/// single steps centred on it (snapped onto the step, a value outside the
/// scale taken at the nearer end), shifted to stay inside the scale. None
/// when Brew's grind is unset or isn't a plain number.
#[must_use]
// The step indices saturate (`as` on a float), and a scale has far fewer
// than 2^52 steps.
#[allow(clippy::cast_possible_truncation, clippy::cast_precision_loss)]
pub fn grind_chips(scale: &GrindScale, around: Option<&str>, count: usize) -> Vec<GrindChip> {
    let Some(around) = around.and_then(grind_number) else {
        return Vec::new();
    };
    let (lo, hi, step, decimals) = grid(scale);
    let last = ((hi - lo) / step + 1e-9).floor().max(0.0) as i64;
    let centre = ((around.clamp(lo, hi) - lo) / step).round() as i64;
    let count = i64::try_from(count).unwrap_or(i64::MAX).min(last + 1);
    let first = (centre - count / 2).clamp(0, (last + 1 - count).max(0));
    (first..first + count)
        .map(|i| {
            let (value, text) = on_grid(lo + i as f64 * step, scale, decimals);
            GrindChip { value, text }
        })
        .collect()
}

/// What [`brew_grind_view`] reads.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BrewGrindInput {
    /// The bag on Brew, if any.
    pub bag: Option<Bean>,
    /// The active grinder ([`crate::grinder::active_grinder`]); `None` = no
    /// grinder (the default scale, the "no grinder" slot).
    pub grinder: Option<Grinder>,
    /// Brew's grind (the per-device session value); `None` = not set.
    pub brew_grind: Option<String>,
}

/// The unsaved-grind marker.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UnsavedGrind {
    /// The bag's saved grind for the active grinder; `None` = nothing saved.
    pub saved: Option<String>,
    /// The marker: "Bag 14", or "Bag —" when nothing is saved.
    pub label: String,
}

/// What Brew's grind is, for both shells (DESIGN "No grind set is its own
/// state").
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewGrindView {
    /// The active grinder's scale, else the default.
    pub scale: GrindScale,
    /// Brew's grind, trimmed: what the header shows and what the shot
    /// records. `None` when not set (or blank): the shot records no grind.
    pub value: Option<String>,
    /// The value with its unit for the header and strip ("480 μm",
    /// "2.1.3"), "—" when not set ([`format_grind`]).
    pub display: String,
    /// The value as the stepper's number; `None` when not set or not a plain
    /// number.
    pub dial: Option<f64>,
    /// Where the stepper's first + or − goes when `dial` is `None`
    /// ([`grind_start_from_blank`]). Not a grind: nothing shows or records
    /// it until the user steps.
    pub start_from_blank: f64,
    /// The bag's saved grind for the active grinder (the "no grinder" slot
    /// with none), when there is one: the ringed chip, the tooltip.
    pub saved: Option<String>,
    /// The unsaved-grind marker: a bag is selected, the grind is set, and it
    /// differs from the saved one ([`same_grind`]) or nothing is saved. No
    /// bag, or no grind: none.
    pub marker: Option<UnsavedGrind>,
    /// The grind part of Quick Controls' Save: the value to save for the bag
    /// and the active grinder; `None` when the marker doesn't show.
    pub save_grind: Option<String>,
}

/// One answer for Brew's grind (REVIEW-CORE H2): the header and the shot
/// can't disagree, `None` is never shown as a number, and the marker and
/// Save follow the same comparison ([`same_grind`]).
#[must_use]
pub fn brew_grind_view(input: &BrewGrindInput) -> BrewGrindView {
    let scale = input
        .grinder
        .as_ref()
        .map_or_else(GrindScale::default, |g| g.scale.clone());
    let value = input
        .brew_grind
        .as_deref()
        .map(str::trim)
        .filter(|g| !g.is_empty())
        .map(str::to_owned);
    let grinder_id = input
        .grinder
        .as_ref()
        .map_or(NO_GRINDER_ID, |g| g.id.as_str());
    let saved = input
        .bag
        .as_ref()
        .and_then(|bag| saved_grind(bag, grinder_id))
        .map(|row| row.grind.trim())
        .filter(|g| !g.is_empty())
        .map(str::to_owned);
    let marker = match (&input.bag, &value) {
        (Some(_), Some(v)) if saved.as_deref().is_none_or(|s| !same_grind(v, s)) => {
            Some(UnsavedGrind {
                label: format!("Bag {}", saved.as_deref().unwrap_or("—")),
                saved: saved.clone(),
            })
        }
        _ => None,
    };
    BrewGrindView {
        display: format_grind(value.as_deref().unwrap_or_default(), &scale),
        dial: value.as_deref().and_then(grind_number),
        start_from_blank: grind_start_from_blank(&scale),
        save_grind: marker.as_ref().and(value.clone()),
        saved,
        marker,
        value,
        scale,
    }
}

// ── JSON bridges ──

fn parse<T: serde::de::DeserializeOwned>(json: &str) -> Result<T, String> {
    serde_json::from_str(json).map_err(|e| e.to_string())
}

fn to_json<T: Serialize>(value: &T) -> Result<String, String> {
    serde_json::to_string(value).map_err(|e| e.to_string())
}

/// [`grind_text`] with a `GrindScale` JSON.
///
/// # Errors
/// The JSON parse error string on a malformed scale.
pub fn grind_text_json(dial: f64, scale_json: &str) -> Result<String, String> {
    Ok(grind_text(dial, &parse(scale_json)?))
}

/// [`counts_in_thirds`] with a `GrindScale` JSON.
///
/// # Errors
/// The JSON parse error string on a malformed scale.
pub fn counts_in_thirds_json(scale_json: &str) -> Result<bool, String> {
    Ok(counts_in_thirds(&parse(scale_json)?))
}

/// [`format_grind`] with a `GrindScale` JSON.
///
/// # Errors
/// The JSON parse error string on a malformed scale.
pub fn format_grind_json(grind: &str, scale_json: &str) -> Result<String, String> {
    Ok(format_grind(grind, &parse(scale_json)?))
}

/// A typed scale as Devices hands it in: a `GrindScale` JSON whose numbers
/// may be `null` or missing (a field that didn't parse).
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ScaleDraft {
    #[serde(default)]
    min: Option<f32>,
    #[serde(default)]
    max: Option<f32>,
    #[serde(default)]
    step: Option<f32>,
}

/// [`grind_scale_refusal`] for a typed scale: a `GrindScale` JSON in, where a
/// number that didn't parse is `null` or missing ([`ScaleRefusal::NotANumber`]);
/// a [`ScaleRefusal`] JSON or `null` out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn grind_scale_refusal_json(scale_json: &str) -> Result<String, String> {
    let d: ScaleDraft = parse(scale_json)?;
    let scale = GrindScale {
        min: d.min.unwrap_or(f32::NAN),
        max: d.max.unwrap_or(f32::NAN),
        step: d.step.unwrap_or(f32::NAN),
        unit: String::new(),
    };
    to_json(&grind_scale_refusal(&scale))
}

/// [`brew_grind_view`]: a [`BrewGrindInput`] JSON in, a [`BrewGrindView`]
/// JSON out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn brew_grind_view_json(input_json: &str) -> Result<String, String> {
    to_json(&brew_grind_view(&parse(input_json)?))
}

/// [`grind_chips`] with a `GrindScale` JSON: a `GrindChip` array JSON out.
///
/// # Errors
/// The JSON parse error string on a malformed scale.
pub fn grind_chips_json(
    scale_json: &str,
    around: Option<&str>,
    count: u32,
) -> Result<String, String> {
    let count = usize::try_from(count).unwrap_or(usize::MAX);
    to_json(&grind_chips(&parse(scale_json)?, around, count))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bag_grind::BagGrind;

    fn scale(min: f32, max: f32, step: f32, unit: &str) -> GrindScale {
        GrindScale {
            min,
            max,
            step,
            unit: unit.to_owned(),
        }
    }

    fn motto() -> GrindScale {
        scale(0.0, 999.0, 1.0, "μm")
    }

    #[test]
    fn grind_text_keeps_the_steps_decimals() {
        let fine = scale(0.0, 10.0, 0.05, "");
        // REVIEW-FINAL M2 / REVIEW-CORE H1: a 0.05 step, dial 2.25.
        assert_eq!(grind_text(2.25, &fine), "2.25");
        assert_eq!(grind_text(2.5, &fine), "2.5");
        assert_eq!(grind_text(2.0, &fine), "2");
        let quarter = scale(0.0, 10.0, 0.25, "");
        assert_eq!(grind_text(2.25, &quarter), "2.25");
        assert_eq!(grind_text(15.0, &GrindScale::default()), "15");
        assert_eq!(grind_text(15.5, &GrindScale::default()), "15.5");
        assert_eq!(grind_text(480.0, &motto()), "480");
        // Steps counted from a lowest setting of 0.5.
        assert_eq!(grind_text(5.5, &scale(0.5, 10.0, 1.0, "")), "5.5");
        assert_eq!(grind_text(-0.0, &motto()), "0");
        assert_eq!(grind_text(f64::NAN, &motto()), "");
        assert_eq!(grind_text(f64::INFINITY, &motto()), "");
    }

    #[test]
    fn grind_text_absorbs_float_noise() {
        // An Android f32 dial widened, a JS stepper sum.
        assert_eq!(
            grind_text(f64::from(4.2_f32), &GrindScale::default()),
            "4.2"
        );
        assert_eq!(grind_text(0.1 + 0.2, &GrindScale::default()), "0.3");
        let fine = scale(0.0, 10.0, 0.05, "");
        assert_eq!(grind_text(f64::from(2.2_f32 + 0.05_f32), &fine), "2.25");
        assert_eq!(
            grind_text(f64::from(999.1_f32), &GrindScale::default()),
            "999.1"
        );
        assert_eq!(grind_text(500.000_03, &motto()), "500");
    }

    #[test]
    fn grind_text_never_rounds_a_value_off_the_step() {
        // No one-decimal rounding: 2.25 on a 0.1 scale (or no scale) stays.
        assert_eq!(grind_text(2.25, &GrindScale::default()), "2.25");
        assert_eq!(grind_text(2.123, &GrindScale::default()), "2.123");
        assert_eq!(grind_text(4.2, &motto()), "4.2");
        assert_eq!(grind_text(f64::from(4.2_f32), &motto()), "4.2");
    }

    #[test]
    fn same_grind_is_numeric_when_both_parse() {
        assert!(same_grind("15", "15.0"));
        assert!(same_grind(" 2,5 ", "2.5"));
        assert!(same_grind("4.20", "4.2"));
        assert!(!same_grind("4.2", "4.25"));
        assert!(same_grind(" 2.1.3 ", "2.1.3"));
        assert!(!same_grind("2.1.3", "2.1.4"));
        assert!(!same_grind("15", "15 clicks"));
        assert!(same_grind("", "  "));
        // Words Rust would parse are text.
        assert!(!same_grind("inf", "infinity"));
        assert_eq!(grind_number("NaN"), None);
    }

    #[test]
    fn grind_number_reads_plain_decimals_within_the_bound() {
        for (text, n) in [
            ("15", 15.0),
            (" 2,5 ", 2.5),
            ("+2.25", 2.25),
            ("-1", -1.0),
            ("1.", 1.0),
            (".5", 0.5),
            ("1000000", MAX_GRIND_NUMBER),
            ("-1000000", -MAX_GRIND_NUMBER),
        ] {
            assert_eq!(grind_number(text), Some(n), "{text}");
        }
        // The web's parse (REVIEW-CORE H1, PLAN 29): no exponent, no hex or
        // words, nothing past 1e6 or longer than 32 bytes.
        for text in [
            "1e3",
            "1E3",
            "1e999999999",
            "1000000.5",
            "1000001",
            "0x10",
            "inf",
            "Infinity",
            ".",
            "-",
            "+",
            "1.2.3",
            "--1",
            "1 000",
            "",
        ] {
            assert_eq!(grind_number(text), None, "{text}");
        }
        let long = format!("0.{}", "1".repeat(MAX_GRIND_NUMBER_LEN - 2));
        assert!(grind_number(&long).is_some());
        assert_eq!(grind_number(&format!("{long}1")), None);
        // Thirds are bounded too.
        assert_eq!(grind_number("4000000 1/3"), None);
        // Text past the bound is still a grind: shown as typed.
        assert_eq!(format_grind("1e3", &motto()), "1e3");
        assert!(!same_grind("1e3", "1000"));
    }

    #[test]
    fn format_grind_adds_the_unit_only_after_a_plain_number() {
        assert_eq!(format_grind("480", &motto()), "480 μm");
        assert_eq!(format_grind(" 6 + a tooth ", &motto()), "6 + a tooth");
        assert_eq!(format_grind("14", &GrindScale::default()), "14");
        assert_eq!(format_grind("  ", &motto()), "—");
        // Never rewritten.
        assert_eq!(format_grind("15.0", &motto()), "15.0 μm");
    }

    #[test]
    fn scale_refusals() {
        use ScaleRefusal::*;
        assert_eq!(grind_scale_refusal(&GrindScale::default()), None);
        assert_eq!(grind_scale_refusal(&motto()), None);
        assert_eq!(
            grind_scale_refusal(&scale(f32::NAN, 10.0, 1.0, "")),
            Some(NotANumber)
        );
        assert_eq!(
            grind_scale_refusal(&scale(10.0, 10.0, 1.0, "")),
            Some(LowestNotBelowHighest)
        );
        assert_eq!(
            grind_scale_refusal(&scale(10.0, 0.0, 1.0, "")),
            Some(LowestNotBelowHighest)
        );
        assert_eq!(
            grind_scale_refusal(&scale(0.0, 10.0, 0.0, "")),
            Some(StepNotPositive)
        );
        assert_eq!(
            grind_scale_refusal(&scale(0.0, 10.0, -1.0, "")),
            Some(StepNotPositive)
        );
        assert_eq!(
            grind_scale_refusal(&scale(0.0, 1.0, 2.0, "")),
            Some(StepTooLarge)
        );
        // A step equal to the range fits, f32 noise included.
        assert_eq!(grind_scale_refusal(&scale(0.1, 0.3, 0.2, "")), None);
        assert_eq!(grind_scale_refusal(&scale(0.0, 10.0, 0.05, "")), None);
    }

    #[test]
    fn scale_refusal_json_reads_unparsed_fields_as_not_a_number() {
        let r = |j: &str| grind_scale_refusal_json(j).unwrap();
        assert_eq!(r(r#"{"min":0,"max":20,"step":0.1,"unit":""}"#), "null");
        assert_eq!(r(r#"{"min":null,"max":20,"step":0.1}"#), r#""notANumber""#);
        assert_eq!(r(r#"{"max":20,"step":0.1}"#), r#""notANumber""#);
        assert_eq!(r(r#"{"min":0,"max":1,"step":2}"#), r#""stepTooLarge""#);
    }

    fn ode() -> GrindScale {
        scale(1.0, 11.0, 1.0 / 3.0, "")
    }

    #[test]
    fn a_thirds_scale_writes_thirds_not_decimals() {
        assert!(counts_in_thirds(&ode()));
        assert!(counts_in_thirds(&scale(1.0, 11.0, 0.3333, "")));
        assert!(!counts_in_thirds(&scale(1.0, 11.0, 0.333, "")));
        assert!(!counts_in_thirds(&GrindScale::default()));
        for (dial, text) in [
            (4.0, "4"),
            (4.0 + 1.0 / 3.0, "4⅓"),
            (4.0 + 2.0 / 3.0, "4⅔"),
            (1.0 / 3.0, "⅓"),
            (11.0, "11"),
            (-(1.0 + 1.0 / 3.0), "-1.3333"),
        ] {
            assert_eq!(grind_text(dial, &ode()), text, "{dial}");
        }
        // A stepper on Android adds the f32 step; a JS sum carries noise.
        let mut dial = 1.0_f32;
        for _ in 0..10 {
            dial += 1.0 / 3.0;
        }
        assert_eq!(grind_text(f64::from(dial), &ode()), "4⅓");
        assert_eq!(grind_text(4.333_333_4, &ode()), "4⅓");
        // A typed value off the thirds keeps its decimals.
        assert_eq!(grind_text(4.25, &ode()), "4.25");
        assert_eq!(grind_text(4.333, &ode()), "4.333");
    }

    #[test]
    fn thirds_read_back_as_numbers() {
        let third = 1.0 / 3.0;
        for (text, n) in [
            ("4⅓", 4.0 + third),
            (" 4 ⅓ ", 4.0 + third),
            ("4⅔", 4.0 + 2.0 * third),
            ("⅓", third),
            ("4 1/3", 4.0 + third),
            ("2/3", 2.0 * third),
        ] {
            assert_eq!(grind_number(text), Some(n), "{text}");
        }
        for text in ["41/3", "a⅓", "4.5⅓", "-⅓", "1/2"] {
            assert_eq!(grind_number(text), None, "{text}");
        }
        assert!(same_grind("4⅓", "4 1/3"));
        assert!(!same_grind("4⅓", "4.33"));
        // What the stepper writes is what it reads.
        let dial = grind_number("6⅔").unwrap() + f64::from(1.0_f32 / 3.0);
        assert_eq!(grind_text(dial, &ode()), "7");
        assert_eq!(
            format_grind("4⅓", &scale(1.0, 11.0, 1.0 / 3.0, "clicks")),
            "4⅓ clicks"
        );
        // The step itself reads as a third, for Devices' "Steps of ⅓".
        assert_eq!(grind_text(f64::from(ode().step), &ode()), "⅓");
        assert_eq!(
            counts_in_thirds_json(r#"{"min":1,"max":11,"step":0.33333334,"unit":""}"#),
            Ok(true)
        );
        assert_eq!(
            counts_in_thirds_json(r#"{"min":0,"max":20,"step":0.1,"unit":""}"#),
            Ok(false)
        );
        assert!(counts_in_thirds_json("{").is_err());
    }

    #[test]
    fn a_thirds_scale_starts_and_chips_on_thirds() {
        assert_eq!(grind_start_from_blank(&ode()), 6.0);
        let chips = grind_chips(&ode(), Some("6"), 5);
        assert_eq!(
            chips.iter().map(|c| c.text.as_str()).collect::<Vec<_>>(),
            ["5⅓", "5⅔", "6", "6⅓", "6⅔"]
        );
        assert_eq!(chips[3].value, grind_number("6⅓").unwrap());
        let ends = grind_chips(&ode(), Some("10⅔"), 3);
        assert_eq!(
            ends.iter().map(|c| c.text.as_str()).collect::<Vec<_>>(),
            ["10⅓", "10⅔", "11"]
        );
        let view = brew_grind_view(&BrewGrindInput {
            bag: None,
            grinder: Some(Grinder {
                id: "grinder:ode".into(),
                name: "Fellow Ode Gen 2".into(),
                scale: ode(),
                ..Grinder::default()
            }),
            brew_grind: Some(" 4⅓ ".into()),
        });
        assert_eq!(
            (view.value.as_deref(), view.display.as_str(), view.dial),
            (Some("4⅓"), "4⅓", grind_number("4⅓"))
        );
    }

    #[test]
    fn start_from_blank_is_the_middle_on_the_step() {
        assert_eq!(grind_start_from_blank(&GrindScale::default()), 10.0);
        // Motto 80 (REVIEW-CORE divergence 2): 500 on both shells.
        assert_eq!(grind_start_from_blank(&motto()), 500.0);
        assert_eq!(grind_start_from_blank(&scale(0.0, 10.0, 0.05, "")), 5.0);
        assert_eq!(grind_start_from_blank(&scale(1.0, 4.0, 2.0, "")), 3.0);
        // On the step counted from the lowest setting.
        assert_eq!(grind_start_from_blank(&scale(0.5, 10.0, 1.0, "")), 5.5);
        // A broken scale uses its repaired range.
        assert_eq!(grind_start_from_blank(&scale(20.0, 0.0, -1.0, "")), 10.0);
    }

    #[test]
    fn chips_are_single_steps_around_the_value() {
        let texts = |s: &GrindScale, around: Option<&str>, n| {
            grind_chips(s, around, n)
                .into_iter()
                .map(|c| c.text)
                .collect::<Vec<_>>()
        };
        assert_eq!(
            texts(&GrindScale::default(), Some("4.2"), 5),
            ["4", "4.1", "4.2", "4.3", "4.4"]
        );
        assert_eq!(
            texts(&motto(), Some("480"), 5),
            ["478", "479", "480", "481", "482"]
        );
        assert_eq!(
            texts(&scale(0.0, 10.0, 0.05, ""), Some("2.25"), 3),
            ["2.2", "2.25", "2.3"]
        );
        // Even count: one more below.
        assert_eq!(
            texts(&motto(), Some("480"), 4),
            ["478", "479", "480", "481"]
        );
        // Shifted to stay inside the scale; outside it, the nearer end.
        assert_eq!(texts(&motto(), Some("0"), 5), ["0", "1", "2", "3", "4"]);
        assert_eq!(
            texts(&motto(), Some("1200"), 5),
            ["995", "996", "997", "998", "999"]
        );
        // Off the step: snapped.
        assert_eq!(texts(&motto(), Some("480.4"), 3), ["479", "480", "481"]);
        // A scale with fewer steps than chips.
        assert_eq!(
            texts(&scale(0.0, 2.0, 1.0, ""), Some("1"), 5),
            ["0", "1", "2"]
        );
        // None when unset, blank or text (REVIEW-CORE L1).
        assert!(grind_chips(&motto(), None, 5).is_empty());
        assert!(grind_chips(&motto(), Some(" "), 5).is_empty());
        assert!(grind_chips(&motto(), Some("2.1.3"), 5).is_empty());
        let chips = grind_chips(&GrindScale::default(), Some("4.2"), 1);
        assert_eq!(
            chips,
            [GrindChip {
                value: 4.2,
                text: "4.2".into()
            }]
        );
    }

    fn grinder(id: &str, s: GrindScale) -> Grinder {
        Grinder {
            id: id.into(),
            name: "Niche Zero".into(),
            scale: s,
            ..Grinder::default()
        }
    }

    fn bag(rows: &[(&str, &str)]) -> Bean {
        let mut b = Bean::new("bag-1".into(), "Guji".into(), 0);
        b.grinds = Some(
            rows.iter()
                .map(|(id, g)| BagGrind {
                    grinder_id: (*id).into(),
                    grinder_name: String::new(),
                    grind: (*g).into(),
                    starred: false,
                })
                .collect(),
        );
        b
    }

    fn view(bag: Option<Bean>, grinder: Option<Grinder>, grind: Option<&str>) -> BrewGrindView {
        brew_grind_view(&BrewGrindInput {
            bag,
            grinder,
            brew_grind: grind.map(str::to_owned),
        })
    }

    #[test]
    fn unset_grind_is_its_own_state() {
        // REVIEW-CORE divergence 3 / REVIEW-FINAL S1: a saved 14, Brew unset.
        // The header doesn't claim 14, the shot records nothing, no marker.
        let v = view(
            Some(bag(&[("grinder:nz", "14")])),
            Some(grinder("grinder:nz", GrindScale::default())),
            None,
        );
        assert_eq!(v.value, None);
        assert_eq!(v.display, "—");
        assert_eq!(v.dial, None);
        assert_eq!(v.marker, None);
        assert_eq!(v.save_grind, None);
        assert_eq!(v.saved.as_deref(), Some("14"));
        assert_eq!(v.start_from_blank, 10.0);
        // Blank is unset too.
        assert_eq!(view(Some(bag(&[])), None, Some("  ")).value, None);
        // Motto 80, nothing saved: no placeholder number anywhere.
        let m = view(None, Some(grinder("grinder:m80", motto())), None);
        assert_eq!(
            (m.value, m.display.as_str(), m.start_from_blank),
            (None, "—", 500.0)
        );
    }

    #[test]
    fn set_grind_is_shown_and_recorded_as_one_value() {
        let nz = grinder("grinder:nz", motto());
        let v = view(
            Some(bag(&[("grinder:nz", "480")])),
            Some(nz.clone()),
            Some(" 480 "),
        );
        assert_eq!(v.value.as_deref(), Some("480"));
        assert_eq!(v.display, "480 μm");
        assert_eq!(v.dial, Some(480.0));
        assert_eq!(v.marker, None);
        assert_eq!(v.save_grind, None);
        // Numerically the same as the saved spelling: no marker.
        let v = view(
            Some(bag(&[("grinder:nz", "480.0")])),
            Some(nz.clone()),
            Some("480"),
        );
        assert_eq!(v.marker, None);
        // Differs: the marker and Save's part.
        let v = view(
            Some(bag(&[("grinder:nz", "480")])),
            Some(nz.clone()),
            Some("490"),
        );
        assert_eq!(
            v.marker,
            Some(UnsavedGrind {
                saved: Some("480".into()),
                label: "Bag 480".into()
            })
        );
        assert_eq!(v.save_grind.as_deref(), Some("490"));
        // Nothing saved (no row, or a blank row): "Bag —".
        for b in [bag(&[]), bag(&[("grinder:nz", " ")])] {
            let v = view(Some(b), Some(nz.clone()), Some("490"));
            assert_eq!(v.marker.as_ref().map(|m| m.label.as_str()), Some("Bag —"));
            assert_eq!(v.marker.and_then(|m| m.saved), None);
        }
        // No bag: shown and recorded, but nothing to save to.
        let v = view(None, Some(nz), Some("490"));
        assert_eq!(
            (v.value.as_deref(), v.marker, v.save_grind),
            (Some("490"), None, None)
        );
    }

    #[test]
    fn no_grinder_uses_the_no_grinder_slot_and_default_scale() {
        let v = view(
            Some(bag(&[("grinder:nz", "14"), (NO_GRINDER_ID, "4.2")])),
            None,
            Some("4.2"),
        );
        assert_eq!(v.scale, GrindScale::default());
        assert_eq!(v.saved.as_deref(), Some("4.2"));
        assert_eq!(v.marker, None);
    }

    #[test]
    fn a_fine_step_value_is_kept_end_to_end() {
        // REVIEW-CORE H1 repro: 0.05 step, 2.25 saved and on Brew: no marker,
        // and the shot records 2.25 (see shot_capture's test).
        let g = grinder("grinder:fine", scale(0.0, 10.0, 0.05, ""));
        let text = grind_text(2.25, &g.scale);
        let v = view(Some(bag(&[("grinder:fine", "2.25")])), Some(g), Some(&text));
        assert_eq!(v.value.as_deref(), Some("2.25"));
        assert_eq!(v.marker, None);
    }

    #[test]
    fn a_text_grind_passes_through() {
        // N2: a non-numeric grind is Brew's grind, shown as typed.
        let nz = grinder("grinder:nz", motto());
        let v = view(
            Some(bag(&[("grinder:nz", "2.1.3")])),
            Some(nz.clone()),
            Some("2.1.3"),
        );
        assert_eq!(v.value.as_deref(), Some("2.1.3"));
        assert_eq!(v.display, "2.1.3");
        assert_eq!(v.dial, None);
        assert_eq!(v.start_from_blank, 500.0);
        assert_eq!(v.marker, None);
        let v = view(
            Some(bag(&[("grinder:nz", "480")])),
            Some(nz),
            Some("6 + a tooth"),
        );
        assert_eq!(v.save_grind.as_deref(), Some("6 + a tooth"));
        assert_eq!(v.marker.map(|m| m.label), Some("Bag 480".into()));
    }

    #[test]
    fn saves_and_selections_compare_with_same_grind() {
        use crate::bag_grind::{BagSelectInput, resolve_bag_selection, save_bag_grind};
        let nz = grinder("grinder:nz", GrindScale::default());
        // Saving 15 over a stored "15.0" is no change: the spelling stays.
        let b = bag(&[("grinder:nz", "15.0")]);
        let save = save_bag_grind(&b, Some(&nz), "15", 1);
        assert!(!save.changed);
        assert_eq!(save.bean.grinds.unwrap()[0].grind, "15.0");
        // Selecting a bag whose saved 14 Brew already holds as "14.0".
        let sel = resolve_bag_selection(&BagSelectInput {
            bag: Some(bag(&[("grinder:nz", "14")])),
            active_grinder_id: Some(nz.id.clone()),
            grind: Some("14.0".into()),
            grinders: vec![nz],
            ..BagSelectInput::default()
        });
        assert_eq!(sel.change.grind, None);
    }

    #[test]
    fn json_bridges() {
        let s = r#"{"min":0,"max":10,"step":0.05,"unit":"μm"}"#;
        assert_eq!(grind_text_json(2.25, s).unwrap(), "2.25");
        assert_eq!(format_grind_json("2.25", s).unwrap(), "2.25 μm");
        assert!(grind_text_json(1.0, "nope").is_err());
        let chips: Vec<GrindChip> =
            serde_json::from_str(&grind_chips_json(s, Some("2.25"), 3).unwrap()).unwrap();
        assert_eq!(chips.len(), 3);
        assert_eq!(grind_chips_json(s, None, 3).unwrap(), "[]");
        let out: serde_json::Value =
            serde_json::from_str(&brew_grind_view_json(r#"{"brewGrind":null}"#).unwrap()).unwrap();
        assert_eq!(out["value"], serde_json::Value::Null);
        assert_eq!(out["display"], "—");
        assert_eq!(out["startFromBlank"], 10.0);
    }
}
