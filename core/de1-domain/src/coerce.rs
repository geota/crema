//! Lenient numeric deserialization shared by the JSON importers.
//!
//! Both source apps stringify numbers in their JSON exports: de1app (Tcl) runs
//! everything through `huddle jsondump`, which never types numbers — so scalars
//! *and* array elements come out quoted (`"clock":"0"`, `"elapsed":["0.0",…]`);
//! and Beanconqueror (a JS app) quotes user-entered numbers inconsistently
//! (`"grind_size":5` on one grinder, `"5.5"` on another). These
//! `#[serde(deserialize_with = "…")]` helpers accept a JSON **number or a
//! numeric string**, so one struct definition reads both the stringified export
//! and a cleanly number-typed one.
//!
//! Coercion is deliberately forgiving — a non-numeric / null value yields
//! `0` / `None` / `""` rather than aborting the parse, because a single odd
//! scalar must not lose the whole import. Reach for these on any field that a
//! Tcl/JS producer might quote.

use serde::{Deserialize, Deserializer};
use serde_json::Value;

/// The shared primitive: a JSON value as `f64` when it is a number or a numeric
/// string, else `None`. Every typed helper below is built on this.
pub(crate) fn as_f64(v: &Value) -> Option<f64> {
    match v {
        Value::Number(n) => n.as_f64(),
        Value::String(s) => s.trim().parse::<f64>().ok(),
        _ => None,
    }
}

/// A field that may arrive as a string OR a number, kept as a `String`
/// (numbers/bools are stringified). null / array / object → empty string.
pub(crate) fn string_or_number<'de, D: Deserializer<'de>>(d: D) -> Result<String, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::String(s) => s,
        Value::Number(n) => n.to_string(),
        Value::Bool(b) => b.to_string(),
        _ => String::new(),
    })
}

#[allow(clippy::cast_possible_truncation)]
pub(crate) fn de_f32<'de, D: Deserializer<'de>>(d: D) -> Result<f32, D::Error> {
    Ok(as_f64(&Value::deserialize(d)?).unwrap_or(0.0) as f32)
}

pub(crate) fn de_f64<'de, D: Deserializer<'de>>(d: D) -> Result<f64, D::Error> {
    Ok(as_f64(&Value::deserialize(d)?).unwrap_or(0.0))
}

#[allow(clippy::cast_possible_truncation)]
pub(crate) fn de_i32<'de, D: Deserializer<'de>>(d: D) -> Result<i32, D::Error> {
    Ok(as_f64(&Value::deserialize(d)?).unwrap_or(0.0) as i32)
}

#[allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
pub(crate) fn de_i64<'de, D: Deserializer<'de>>(d: D) -> Result<i64, D::Error> {
    Ok(as_f64(&Value::deserialize(d)?).unwrap_or(0.0) as i64)
}

/// Non-negative integer (e.g. epoch seconds). Kept in `f64` so values past
/// `f32`'s 24-bit mantissa — ~1.7e9 Unix seconds — don't lose precision.
#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
pub(crate) fn de_u64<'de, D: Deserializer<'de>>(d: D) -> Result<u64, D::Error> {
    Ok(as_f64(&Value::deserialize(d)?)
        .filter(|f| f.is_finite() && *f >= 0.0)
        .map_or(0, |f| f as u64))
}

/// Optional scalar: a number / numeric string → `Some`; null / absent /
/// non-numeric → `None`.
#[allow(clippy::cast_possible_truncation)]
pub(crate) fn de_opt_f32<'de, D: Deserializer<'de>>(d: D) -> Result<Option<f32>, D::Error> {
    Ok(as_f64(&Value::deserialize(d)?).map(|f| f as f32))
}

/// A homogeneous numeric array whose elements may each be a number or a numeric
/// string. Non-numeric elements → `0.0` (keeps a sample-aligned series intact).
#[allow(clippy::cast_possible_truncation)]
pub(crate) fn de_vec_f32<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<f32>, D::Error> {
    Ok(Vec::<Value>::deserialize(d)?
        .iter()
        .map(|e| as_f64(e).unwrap_or(0.0) as f32)
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;

    #[derive(Deserialize)]
    struct Probe {
        #[serde(deserialize_with = "de_u64")]
        clock: u64,
        #[serde(default, deserialize_with = "de_f32")]
        scalar: f32,
        #[serde(default, deserialize_with = "de_opt_f32")]
        opt: Option<f32>,
        #[serde(default, deserialize_with = "de_vec_f32")]
        series: Vec<f32>,
        #[serde(default, deserialize_with = "string_or_number")]
        label: String,
    }

    #[test]
    fn accepts_stringified_numbers_like_de1app_huddle() {
        // Every numeric is a quoted string — the de1app/huddle shape.
        let p: Probe = serde_json::from_str(
            r#"{"clock":"1700000000","scalar":"9.5","opt":"36.0","series":["0.0","1.5","2.0"],"label":"5"}"#,
        )
        .expect("stringified numbers parse");
        assert_eq!(p.clock, 1_700_000_000); // f64-backed → no f32 precision loss
        assert!((p.scalar - 9.5).abs() < 1e-6);
        assert_eq!(p.opt, Some(36.0));
        assert_eq!(p.series, vec![0.0, 1.5, 2.0]);
        assert_eq!(p.label, "5");
    }

    #[test]
    fn accepts_real_numbers_like_the_community_format() {
        let p: Probe = serde_json::from_str(
            r#"{"clock":1700000000,"scalar":9.5,"opt":36.0,"series":[0.0,1.5,2.0],"label":5}"#,
        )
        .expect("real numbers parse");
        assert_eq!(p.clock, 1_700_000_000);
        assert_eq!(p.opt, Some(36.0));
        assert_eq!(p.series, vec![0.0, 1.5, 2.0]);
        assert_eq!(p.label, "5"); // number stringified
    }

    #[test]
    fn non_numeric_and_null_coerce_rather_than_failing() {
        let p: Probe = serde_json::from_str(
            r#"{"clock":"oops","scalar":"x","opt":null,"series":["1.0","y"]}"#,
        )
        .expect("garbage coerces, does not abort");
        assert_eq!(p.clock, 0);
        assert_eq!(p.scalar, 0.0);
        assert_eq!(p.opt, None);
        assert_eq!(p.series, vec![1.0, 0.0]); // bad element → 0.0, series stays aligned
    }
}
