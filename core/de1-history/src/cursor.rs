//! Keyset cursors (SPEC §7): a row's place in one query's order, opaque to the
//! shells. A cursor carries the row's sort tuple `(key, completed_at, id)` and
//! the query's hash, so a cursor from another filter or sort is refused.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::derive::Derived;
use crate::query::{HistoryQuery, SortKey, SortValue};

/// The encoding's version.
const CURSOR_V: u64 = 1;

/// A row's place in a query's order.
///
/// Its serde form is the opaque text of [`Cursor::encode`], as the FFI gives
/// it. As a JSON object its `u64` hash would pass 2^53, which JavaScript's
/// `JSON.parse` rounds, and its `f64` key would rest on the reader's float
/// parsing; either way the store would refuse the cursor or skip a row.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(into = "String", try_from = "String")]
pub struct Cursor {
    /// [`HistoryQuery::hash`] of the query it came from.
    pub query_hash: u64,
    /// The row's sort value.
    pub key: SortValue,
    /// The row's `completed_at`.
    pub completed_at: i64,
    /// The row's id.
    pub id: String,
}

/// Why a cursor was refused.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CursorError {
    /// The text isn't a cursor this build wrote.
    Malformed,
    /// The cursor came from a different query.
    OtherQuery,
}

impl std::fmt::Display for CursorError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CursorError::Malformed => f.write_str("not a history cursor"),
            CursorError::OtherQuery => f.write_str("the cursor belongs to another query"),
        }
    }
}

impl std::error::Error for CursorError {}

impl From<Cursor> for String {
    fn from(c: Cursor) -> String {
        c.encode()
    }
}

impl TryFrom<String> for Cursor {
    type Error = CursorError;

    fn try_from(text: String) -> Result<Cursor, CursorError> {
        Cursor::decode(&text)
    }
}

impl Cursor {
    /// The cursor for row `d` (whose id is `id`) in query `q`.
    #[must_use]
    pub fn for_row(q: &HistoryQuery, id: &str, d: &Derived) -> Cursor {
        Cursor {
            query_hash: q.hash(),
            key: d.sort_value(q.sort.key),
            completed_at: d.completed_at,
            id: id.to_owned(),
        }
    }

    /// The opaque text a shell holds: base64url of a small JSON array.
    #[must_use]
    pub fn encode(&self) -> String {
        let key = match &self.key {
            SortValue::Date => Value::Null,
            SortValue::Int(i) => Value::from(*i),
            // As its bits: JSON's decimal text may not parse back to the same
            // f64, and a cursor one ulp off skips or repeats a row.
            SortValue::Real(r) => Value::from(format!("{:016x}", r.to_bits())),
            SortValue::Text(t) => Value::from(t.as_str()),
        };
        let kind = match self.key {
            SortValue::Date => "d",
            SortValue::Int(_) => "i",
            SortValue::Real(_) => "r",
            SortValue::Text(_) => "t",
        };
        let arr = Value::Array(vec![
            Value::from(CURSOR_V),
            Value::from(format!("{:016x}", self.query_hash)),
            Value::from(kind),
            key,
            Value::from(self.completed_at),
            Value::from(self.id.as_str()),
        ]);
        base64url_encode(arr.to_string().as_bytes())
    }

    /// The cursor `text` encodes.
    ///
    /// # Errors
    /// [`CursorError::Malformed`] for anything [`Cursor::encode`] didn't write.
    pub fn decode(text: &str) -> Result<Cursor, CursorError> {
        let bytes = base64url_decode(text).ok_or(CursorError::Malformed)?;
        let v: Value = serde_json::from_slice(&bytes).map_err(|_| CursorError::Malformed)?;
        let [ver, hash, kind, key, completed_at, id] = v
            .as_array()
            .and_then(|a| <&[Value; 6]>::try_from(a.as_slice()).ok())
            .ok_or(CursorError::Malformed)?;
        if ver.as_u64() != Some(CURSOR_V) {
            return Err(CursorError::Malformed);
        }
        let query_hash = hash
            .as_str()
            .filter(|h| h.len() == 16)
            .and_then(|h| u64::from_str_radix(h, 16).ok())
            .ok_or(CursorError::Malformed)?;
        let key = match (kind.as_str(), key) {
            (Some("d"), Value::Null) => SortValue::Date,
            (Some("i"), k) if k.is_i64() => SortValue::Int(k.as_i64().unwrap_or_default()),
            (Some("r"), Value::String(bits)) => Some(bits.as_str())
                .filter(|b| b.len() == 16)
                .and_then(|b| u64::from_str_radix(b, 16).ok())
                .map(f64::from_bits)
                .filter(|r| r.is_finite())
                .map(SortValue::Real)
                .ok_or(CursorError::Malformed)?,
            (Some("t"), Value::String(s)) => SortValue::Text(s.clone()),
            _ => return Err(CursorError::Malformed),
        };
        Ok(Cursor {
            query_hash,
            key,
            completed_at: completed_at.as_i64().ok_or(CursorError::Malformed)?,
            id: id.as_str().ok_or(CursorError::Malformed)?.to_owned(),
        })
    }

    /// This cursor, if it belongs to `q`.
    ///
    /// # Errors
    /// [`CursorError::OtherQuery`] when it came from another filter or sort.
    pub fn check(&self, q: &HistoryQuery) -> Result<&Cursor, CursorError> {
        let kind_fits = matches!(
            (q.sort.key, &self.key),
            (SortKey::Date, SortValue::Date)
                | (SortKey::Rating | SortKey::Time, SortValue::Int(_))
                | (SortKey::Yield, SortValue::Real(_))
                | (SortKey::Profile | SortKey::Bean, SortValue::Text(_))
        );
        if self.query_hash == q.hash() && kind_fits {
            Ok(self)
        } else {
            Err(CursorError::OtherQuery)
        }
    }
}

const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/// Base64url without padding.
fn base64url_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let n = chunk
            .iter()
            .enumerate()
            .fold(0u32, |n, (i, b)| n | (u32::from(*b) << (16 - 8 * i)));
        for i in 0..=chunk.len() {
            let six = (n >> (18 - 6 * i)) & 0x3f;
            out.push(char::from(ALPHABET[six as usize]));
        }
    }
    out
}

/// [`base64url_encode`]'s inverse; `None` for anything it can't have written.
fn base64url_decode(text: &str) -> Option<Vec<u8>> {
    if text.len() % 4 == 1 {
        return None;
    }
    let mut out = Vec::with_capacity(text.len() / 4 * 3 + 2);
    for chunk in text.as_bytes().chunks(4) {
        let mut n = 0u32;
        for (i, c) in chunk.iter().enumerate() {
            let six = ALPHABET.iter().position(|a| a == c)?;
            n |= u32::try_from(six).ok()? << (18 - 6 * i);
        }
        let bytes = n.to_be_bytes();
        let take = chunk.len() - 1;
        // The unused low bits must be zero, so each text has one decoding.
        let used_bits = 6 * chunk.len();
        let kept_bits = 8 * take;
        let spare = used_bits - kept_bits;
        if spare > 0 && (n >> (24 - used_bits)) & ((1 << spare) - 1) != 0 {
            return None;
        }
        out.extend_from_slice(&bytes[1..=take]);
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::query::{Anchor, Dir, Sort};

    fn q(key: SortKey) -> HistoryQuery {
        HistoryQuery {
            sort: Sort { key, dir: Dir::Asc },
            ..HistoryQuery::default()
        }
    }

    #[test]
    fn every_kind_round_trips() {
        for (key, value) in [
            (SortKey::Date, SortValue::Date),
            (SortKey::Rating, SortValue::Int(-3)),
            (SortKey::Yield, SortValue::Real(36.25)),
            (SortKey::Yield, SortValue::Real(0.1 + 0.2)),
            (SortKey::Yield, SortValue::Real(-2.725_951_355_265_183e-33)),
            (
                SortKey::Bean,
                SortValue::Text("sey · kenya \"aa\" ☕".into()),
            ),
        ] {
            let c = Cursor {
                query_hash: q(key).hash(),
                key: value,
                completed_at: -1,
                id: "shot:a:b,c".into(),
            };
            let text = c.encode();
            assert!(
                text.bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
            );
            let back = Cursor::decode(&text).expect("decodes");
            assert_eq!(back, c);
            assert!(back.check(&q(key)).is_ok());
        }
    }

    /// `v` as JavaScript's `JSON.parse` then `JSON.stringify` leave it: every
    /// number an f64, written without a fraction when it is whole.
    fn through_js(v: Value) -> Value {
        match v {
            Value::Number(n) => {
                let f = n.as_f64().expect("a JSON number is an f64 in JS");
                #[allow(clippy::cast_possible_truncation)] // whole and in range
                let whole = (f.fract() == 0.0 && f.abs() < 2f64.powi(63)).then_some(f as i64);
                whole.map_or_else(|| Value::from(f), Value::from)
            }
            Value::Array(a) => Value::Array(a.into_iter().map(through_js).collect()),
            Value::Object(o) => {
                Value::Object(o.into_iter().map(|(k, v)| (k, through_js(v))).collect())
            }
            v => v,
        }
    }

    #[test]
    fn a_cursor_survives_json_through_js() {
        let big = (1u64 << 53) + 1;
        assert_ne!(
            through_js(Value::from(big)),
            Value::from(big),
            "an f64 rounds it"
        );
        for (query_hash, key) in [
            (big, SortValue::Date),
            (u64::MAX, SortValue::Int(4)),
            (q(SortKey::Yield).hash(), SortValue::Real(0.1 + 0.2)),
            // serde_json's default float parsing reads this one ulp off.
            (big, SortValue::Real(-2.725_951_355_265_183e-33)),
            (0, SortValue::Text("a \"b\" ☕".into())),
        ] {
            let c = Cursor {
                query_hash,
                key,
                completed_at: 1_700_000_000_000,
                id: "shot:a".into(),
            };
            let json = serde_json::to_value(Anchor::After(c.clone())).expect("serialises");
            assert_eq!(json, serde_json::json!({ "after": c.encode() }));
            let text = serde_json::to_string(&through_js(json)).expect("text");
            let back: Anchor = serde_json::from_str(&text).expect("deserialises");
            assert_eq!(back, Anchor::After(c));
        }
    }

    #[test]
    fn json_that_isnt_a_cursors_text_is_refused() {
        let c = Cursor {
            query_hash: 1,
            key: SortValue::Date,
            completed_at: 1,
            id: "a".into(),
        };
        for json in [
            serde_json::json!({ "queryHash": 1, "key": "date", "completedAt": 1, "id": "a" }),
            serde_json::json!(5),
            serde_json::json!("!"),
            serde_json::json!(format!("{}A", c.encode())),
        ] {
            assert!(
                serde_json::from_value::<Cursor>(json.clone()).is_err(),
                "{json}"
            );
        }
    }

    #[test]
    fn a_cursor_from_another_query_is_refused() {
        let c = Cursor {
            query_hash: q(SortKey::Rating).hash(),
            key: SortValue::Int(3),
            completed_at: 1,
            id: "a".into(),
        };
        assert_eq!(c.check(&q(SortKey::Time)), Err(CursorError::OtherQuery));
        let filtered = HistoryQuery {
            pinned_only: true,
            ..q(SortKey::Rating)
        };
        assert_eq!(c.check(&filtered), Err(CursorError::OtherQuery));
        // The right hash with the wrong kind of value is refused too.
        let odd = Cursor {
            key: SortValue::Text("3".into()),
            ..c
        };
        assert_eq!(odd.check(&q(SortKey::Rating)), Err(CursorError::OtherQuery));
    }

    #[test]
    fn junk_is_malformed() {
        for text in [
            "",
            "!",
            "A",
            "AAAA",
            &base64url_encode(b"[2,\"0000000000000000\",\"d\",null,1,\"a\"]"),
            &base64url_encode(b"[1,\"00\",\"d\",null,1,\"a\"]"),
            &base64url_encode(b"[1,\"0000000000000000\",\"i\",1.5,1,\"a\"]"),
            &base64url_encode(b"[1,\"0000000000000000\",\"d\",null,1]"),
            &base64url_encode(b"{}"),
        ] {
            assert_eq!(Cursor::decode(text), Err(CursorError::Malformed), "{text}");
        }
    }

    #[test]
    fn base64url_round_trips_every_length() {
        for len in 0..10 {
            let bytes: Vec<u8> = (0..len)
                .map(|i: u32| u8::try_from((i * 37 + 200) % 256).unwrap_or_default())
                .collect();
            let text = base64url_encode(&bytes);
            assert_eq!(base64url_decode(&text), Some(bytes));
        }
        // A spare bit set is a second spelling of the same bytes: refused.
        assert_eq!(base64url_decode("AB"), None);
        assert_eq!(base64url_decode("AA"), Some(vec![0]));
    }
}
