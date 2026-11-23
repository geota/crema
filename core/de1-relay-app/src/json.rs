//! The JSON reader and writer the relay frames use: the rules of the
//! Android shell's kotlinx.serialization (1.13, `ignoreUnknownKeys`, not
//! lenient) as its frame codec applied them, so a hub and a remote accept,
//! skip and refuse exactly what they did before the codec moved here.
//!
//! [`parse`] is kotlinx's tree reader (`Json.parseToJsonElement`):
//! - whitespace is only space, tab, `\n` and `\r`;
//! - a value is a quoted string, an object, an array, `null`, or any run of
//!   other characters (a *literal*: `12`, `true`, `1e3`, and also `tru` or
//!   `NaN`; only the field that reads it decides whether it is good);
//! - a string's raw characters are kept as they are, control characters
//!   included; its escapes are `\b \t \n \f \r \/ \" \\` and `\uXXXX`;
//! - a duplicate key keeps its last value;
//! - at most 400 nested objects and arrays;
//! - nothing but whitespace may follow the value.
//!
//! One difference: an escaped lone surrogate (`\ud800` not followed by an
//! escaped low half, or a low half on its own) reads as U+FFFD, since a Rust
//! string can't hold one (kotlinx keeps the lone UTF-16 unit).
//!
//! [`parse_long`] is kotlinx's `JsonPrimitive.long`, quirks included: a
//! quoted number, leading zeros and an integral exponent (`1e3`) are
//! accepted. [`write_string`] is kotlinx's string quoting.

use std::collections::HashMap;

/// A parsed JSON value, as kotlinx's `JsonElement` holds it.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    /// The unquoted literal `null`.
    Null,
    /// A primitive: a quoted string (`quoted`), or an unquoted literal.
    Literal {
        /// The text, unescaped when quoted.
        content: String,
        /// Whether it was a quoted string.
        quoted: bool,
    },
    /// An array.
    Array(Vec<Value>),
    /// An object: its keys in first-seen order, each with its last value.
    Object(Vec<(String, Value)>),
}

impl Value {
    /// The value under `key`, when this is an object that has it.
    #[must_use]
    pub fn get(&self, key: &str) -> Option<&Value> {
        match self {
            Self::Object(entries) => entries.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }
}

/// Why a text isn't JSON kotlinx would read.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JsonError(pub String);

impl std::fmt::Display for JsonError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for JsonError {}

/// kotlinx's `maxNestingDepth` default.
const MAX_NESTING: usize = 400;

fn fail<T>(why: &str) -> Result<T, JsonError> {
    Err(JsonError(why.to_owned()))
}

/// kotlinx's token classes, for the characters that have one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Token {
    Other,
    Str,
    Esc,
    Comma,
    Colon,
    BeginObj,
    EndObj,
    BeginList,
    EndList,
    Invalid,
    Eof,
}

fn token_of(c: char) -> Token {
    match c {
        '"' => Token::Str,
        '\\' => Token::Esc,
        ',' => Token::Comma,
        ':' => Token::Colon,
        '{' => Token::BeginObj,
        '}' => Token::EndObj,
        '[' => Token::BeginList,
        ']' => Token::EndList,
        // Whitespace is skipped before a token is read; the rest of
        // U+0000–U+0020 starts nothing.
        '\0'..=' ' => Token::Invalid,
        _ => Token::Other,
    }
}

fn is_ws(c: char) -> bool {
    matches!(c, ' ' | '\n' | '\r' | '\t')
}

struct Reader<'a> {
    src: &'a str,
    pos: usize,
    depth: usize,
}

impl Reader<'_> {
    fn peek_char(&self) -> Option<char> {
        self.src[self.pos..].chars().next()
    }

    fn skip_ws(&mut self) {
        while let Some(c) = self.peek_char() {
            if !is_ws(c) {
                break;
            }
            self.pos += 1;
        }
    }

    /// The next token's class, after whitespace, without consuming it.
    fn peek(&mut self) -> Token {
        self.skip_ws();
        self.peek_char().map_or(Token::Eof, token_of)
    }

    /// Consume the next token (after whitespace) and return its class.
    fn next_token(&mut self) -> Token {
        let t = self.peek();
        if let Some(c) = self.peek_char() {
            self.pos += c.len_utf8();
        }
        t
    }

    fn expect(&mut self, want: Token) -> Result<(), JsonError> {
        if self.next_token() == want {
            Ok(())
        } else {
            fail("unexpected token")
        }
    }

    /// Whether a value may start here: not at the end, nor at `} ] : ,`.
    fn can_consume_value(&mut self) -> bool {
        !matches!(
            self.peek(),
            Token::Eof | Token::EndObj | Token::EndList | Token::Colon | Token::Comma
        )
    }

    fn read(&mut self) -> Result<Value, JsonError> {
        match self.peek() {
            Token::Str => Ok(Value::Literal {
                content: self.string()?,
                quoted: true,
            }),
            Token::Other => {
                let start = self.pos;
                while self
                    .peek_char()
                    .is_some_and(|c| token_of(c) == Token::Other)
                {
                    self.pos += self.peek_char().map_or(1, char::len_utf8);
                }
                let content = &self.src[start..self.pos];
                Ok(if content == "null" {
                    Value::Null
                } else {
                    Value::Literal {
                        content: content.to_owned(),
                        quoted: false,
                    }
                })
            }
            Token::BeginObj => self.nested(Self::object),
            Token::BeginList => self.nested(Self::array),
            _ => fail("cannot read a JSON element here"),
        }
    }

    fn nested(
        &mut self,
        read: fn(&mut Self) -> Result<Value, JsonError>,
    ) -> Result<Value, JsonError> {
        self.depth += 1;
        if self.depth > MAX_NESTING {
            return fail("JSON input is too nested");
        }
        let v = read(self)?;
        self.depth -= 1;
        Ok(v)
    }

    fn object(&mut self) -> Result<Value, JsonError> {
        self.expect(Token::BeginObj)?;
        if self.peek() == Token::Comma {
            return fail("unexpected leading comma");
        }
        let mut entries: Vec<(String, Value)> = Vec::new();
        // Where each key's entry is, so a duplicate replaces it (last wins)
        // in constant time: a hostile object's thousands of keys stay linear.
        let mut index: HashMap<String, usize> = HashMap::new();
        let mut last = Token::BeginObj;
        while self.can_consume_value() {
            if self.peek() != Token::Str {
                return fail("expected a quoted key");
            }
            let key = self.string()?;
            self.expect(Token::Colon)?;
            let value = self.read()?;
            match index.get(&key) {
                Some(&at) => entries[at].1 = value,
                None => {
                    index.insert(key.clone(), entries.len());
                    entries.push((key, value));
                }
            }
            last = self.next_token();
            match last {
                Token::Comma => {}
                Token::EndObj => break,
                _ => return fail("expected the end of the object or a comma"),
            }
        }
        match last {
            Token::BeginObj => self.expect(Token::EndObj)?,
            Token::Comma => return fail("trailing comma"),
            _ => {}
        }
        Ok(Value::Object(entries))
    }

    fn array(&mut self) -> Result<Value, JsonError> {
        self.expect(Token::BeginList)?;
        if self.peek() == Token::Comma {
            return fail("unexpected leading comma");
        }
        let mut items = Vec::new();
        let mut last = Token::BeginList;
        while self.can_consume_value() {
            items.push(self.read()?);
            last = self.next_token();
            if last != Token::Comma {
                if last != Token::EndList {
                    return fail("expected the end of the array or a comma");
                }
                break;
            }
        }
        match last {
            Token::BeginList => self.expect(Token::EndList)?,
            Token::Comma => return fail("trailing comma"),
            _ => {}
        }
        Ok(Value::Array(items))
    }

    /// A quoted string, the reader at its opening quote.
    fn string(&mut self) -> Result<String, JsonError> {
        self.expect(Token::Str)?;
        let mut out = String::new();
        // UTF-16 units of `\u` escapes not yet written: a high half waits
        // for its low half.
        let mut pending_high: Option<u16> = None;
        loop {
            let Some(c) = self.peek_char() else {
                return fail("unterminated string");
            };
            self.pos += c.len_utf8();
            if c != '\\' {
                if let Some(_high) = pending_high.take() {
                    out.push(char::REPLACEMENT_CHARACTER);
                }
                if c == '"' {
                    return Ok(out);
                }
                out.push(c);
                continue;
            }
            let Some(e) = self.peek_char() else {
                return fail("unterminated escape");
            };
            self.pos += e.len_utf8();
            if e == 'u' {
                let unit = self.hex4()?;
                match (pending_high.take(), unit) {
                    (Some(high), 0xDC00..=0xDFFF) => {
                        let scalar = 0x10000
                            + ((u32::from(high) - 0xD800) << 10)
                            + (u32::from(unit) - 0xDC00);
                        out.push(char::from_u32(scalar).unwrap_or(char::REPLACEMENT_CHARACTER));
                    }
                    (high, _) => {
                        if high.is_some() {
                            out.push(char::REPLACEMENT_CHARACTER);
                        }
                        match unit {
                            0xD800..=0xDBFF => pending_high = Some(unit),
                            _ => out.push(
                                char::from_u32(u32::from(unit))
                                    .unwrap_or(char::REPLACEMENT_CHARACTER),
                            ),
                        }
                    }
                }
                continue;
            }
            if pending_high.take().is_some() {
                out.push(char::REPLACEMENT_CHARACTER);
            }
            out.push(match e {
                'b' => '\u{8}',
                't' => '\t',
                'n' => '\n',
                'f' => '\u{C}',
                'r' => '\r',
                '/' => '/',
                '"' => '"',
                '\\' => '\\',
                _ => return fail("invalid escape"),
            });
        }
    }

    /// The four hex digits of a `\u` escape. kotlinx wants a fifth character
    /// after them (the string's closing quote at the least).
    fn hex4(&mut self) -> Result<u16, JsonError> {
        let rest = &self.src.as_bytes()[self.pos..];
        if rest.len() < 5 {
            return fail("unexpected end in a unicode escape");
        }
        let mut unit: u16 = 0;
        for &b in &rest[..4] {
            let d = char::from(b)
                .to_digit(16)
                .ok_or_else(|| JsonError("invalid hex digit".into()))?;
            unit = unit << 4 | u16::try_from(d).unwrap_or(0);
        }
        self.pos += 4;
        Ok(unit)
    }
}

/// Parse `text` as kotlinx's `Json.parseToJsonElement` does.
///
/// # Errors
/// When kotlinx would throw: malformed JSON, nesting past 400, or anything
/// but whitespace after the value.
pub fn parse(text: &str) -> Result<Value, JsonError> {
    let mut r = Reader {
        src: text,
        pos: 0,
        depth: 0,
    };
    let v = r.read()?;
    if r.next_token() != Token::Eof {
        return fail("expected the end of the input");
    }
    Ok(v)
}

/// kotlinx's `JsonPrimitive.long` on a literal's content: optional
/// whitespace, an optional opening quote, an optional `-`, digits, an
/// optional exponent (`e`, then any `+`/`-` signs and digits) whose result
/// must be integral, the closing quote if one opened, then only whitespace.
/// Its arithmetic is kept as it is (64-bit wrapping where kotlinx wraps),
/// so the same texts give the same numbers or fail.
///
/// # Errors
/// When kotlinx would fail to read a Long.
pub fn parse_long(content: &str) -> Result<i64, JsonError> {
    let s = content.as_bytes();
    let mut cur = 0;
    while cur < s.len() && matches!(s[cur], b' ' | b'\n' | b'\r' | b'\t') {
        cur += 1;
    }
    if cur >= s.len() {
        return fail("EOF");
    }
    let quoted = s[cur] == b'"';
    if quoted {
        cur += 1;
        if cur == s.len() {
            return fail("EOF");
        }
    }
    let mut acc: i64 = 0;
    let mut exp_acc: i64 = 0;
    let mut negative = false;
    let mut exp_positive = false;
    let mut has_exp = false;
    let start = cur;
    while cur != s.len() {
        let ch = s[cur];
        if (ch == b'e' || ch == b'E') && !has_exp {
            if cur == start {
                return fail("unexpected symbol in a numeric literal");
            }
            exp_positive = true;
            has_exp = true;
            cur += 1;
            continue;
        }
        if (ch == b'-' || ch == b'+') && has_exp {
            if cur == start {
                return fail("unexpected symbol in a numeric literal");
            }
            exp_positive = ch == b'+';
            cur += 1;
            continue;
        }
        if ch == b'-' {
            if cur != start {
                return fail("unexpected symbol in a numeric literal");
            }
            negative = true;
            cur += 1;
            continue;
        }
        // A non-ASCII character is a literal character, never a digit.
        let class = if ch >= 0x80 {
            Token::Other
        } else {
            token_of(char::from(ch))
        };
        if class != Token::Other {
            break;
        }
        cur += 1;
        if !ch.is_ascii_digit() {
            return fail("unexpected symbol in a numeric literal");
        }
        let digit = i64::from(ch - b'0');
        if has_exp {
            exp_acc = exp_acc.wrapping_mul(10).wrapping_add(digit);
            continue;
        }
        acc = acc.wrapping_mul(10).wrapping_sub(digit);
        if acc > 0 {
            return fail("numeric value overflow");
        }
    }
    if start == cur || (negative && start == cur - 1) {
        return fail("expected a numeric literal");
    }
    if quoted {
        if cur >= s.len() || s[cur] != b'"' {
            return fail("expected a closing quotation mark");
        }
        cur += 1;
    }
    while cur < s.len() && matches!(s[cur], b' ' | b'\n' | b'\r' | b'\t') {
        cur += 1;
    }
    if cur != s.len() {
        return fail("expected a single number");
    }
    if has_exp {
        #[allow(clippy::cast_precision_loss)] // kotlinx's own Long-to-Double conversion
        let (mantissa, exponent) = (acc as f64, exp_acc as f64);
        let scale = if exp_positive {
            10f64.powf(exponent)
        } else {
            10f64.powf(-exponent)
        };
        let d = mantissa * scale;
        #[allow(clippy::cast_precision_loss)] // the bounds as kotlinx compares them
        let (max, min) = (i64::MAX as f64, i64::MIN as f64);
        if d > max || d < min {
            return fail("numeric value overflow");
        }
        #[allow(clippy::float_cmp)] // an exact integrality test, as kotlinx's
        if d.floor() != d {
            return fail("not an integral value");
        }
        #[allow(clippy::cast_possible_truncation)] // in range and integral, checked above
        {
            acc = d as i64;
        }
    }
    if negative {
        Ok(acc)
    } else if acc != i64::MIN {
        Ok(-acc)
    } else {
        fail("numeric value overflow")
    }
}

/// Append `s` quoted as kotlinx writes a string: `"` and `\` escaped, the
/// C0 controls as `\b \t \n \f \r` or `\u00xx`, everything else as it is.
pub fn write_string(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\u{C}' => out.push_str("\\f"),
            '\0'..='\u{1F}' => {
                let n = u32::from(c);
                out.push_str("\\u00");
                out.push(char::from_digit(n >> 4, 16).unwrap_or('0'));
                out.push(char::from_digit(n & 0xF, 16).unwrap_or('0'));
            }
            _ => out.push(c),
        }
    }
    out.push('"');
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lit(s: &str, quoted: bool) -> Value {
        Value::Literal {
            content: s.into(),
            quoted,
        }
    }

    #[test]
    fn reads_what_kotlinx_reads() {
        assert_eq!(
            parse(" {\"a\":tru} \n").unwrap(),
            Value::Object(vec![("a".into(), lit("tru", false))])
        );
        assert_eq!(
            parse("{\"a\":1,\"a\":2}").unwrap(),
            Value::Object(vec![("a".into(), lit("2", false))])
        );
        assert_eq!(
            parse("[null,\"x\\n\\u0041\"]").unwrap(),
            Value::Array(vec![Value::Null, lit("x\nA", true)])
        );
        assert_eq!(parse("\"a\nb\"").unwrap(), lit("a\nb", true));
        assert_eq!(parse("\"\\ud83d\\ude00\"").unwrap(), lit("😀", true));
        assert_eq!(
            parse("\"\\ud800x\\udc00\"").unwrap(),
            lit("\u{FFFD}x\u{FFFD}", true)
        );
        assert_eq!(
            parse("\"\\ud800\\ud83d\\ude00\"").unwrap(),
            lit("\u{FFFD}😀", true)
        );
        for bad in [
            "",
            "{",
            "{,}",
            "{\"a\":1,}",
            "[1,]",
            "{\"a\" 1}",
            "{a:1}",
            "{}x",
            "{}\u{B}",
            "\"\\x\"",
            "\"\\u12\"",
        ] {
            assert!(parse(bad).is_err(), "{bad:?}");
        }
        let deep = |n: usize| format!("{}{}", "[".repeat(n), "]".repeat(n));
        assert!(parse(&deep(400)).is_ok());
        assert!(parse(&deep(401)).is_err());
    }

    /// A hostile peer's object with as many distinct keys as fit in a
    /// 64 KiB message, plus duplicates of each, reads in linear time.
    #[test]
    fn thousands_of_keys_read_quickly() {
        let keys = 5_000;
        let mut text = String::from("{\"type\":\"event\",\"text\":\"x\"");
        for i in 0..keys {
            text.push_str(&format!(",\"k{i}\":{i}"));
        }
        for i in 0..keys {
            text.push_str(&format!(",\"k{i}\":-{i}"));
        }
        text.push('}');
        assert!(text.len() < 2 * 65_536, "{}", text.len());
        let start = std::time::Instant::now();
        let Value::Object(entries) = parse(&text).unwrap() else {
            panic!("object")
        };
        assert!(
            start.elapsed() < std::time::Duration::from_secs(1),
            "{:?}",
            start.elapsed()
        );
        assert_eq!(entries.len(), keys + 2);
        assert_eq!(entries[2], ("k0".into(), lit("-0", false)));
        assert_eq!(
            entries[keys + 1],
            (
                format!("k{}", keys - 1),
                lit(&format!("-{}", keys - 1), false)
            )
        );
    }

    #[test]
    fn longs_as_kotlinx_reads_them() {
        for (text, want) in [
            ("7", Some(7)),
            ("\"7\"", Some(7)),
            (" 7", Some(7)),
            ("07", Some(7)),
            ("-0", Some(0)),
            ("1e3", Some(1000)),
            ("1E3", Some(1000)),
            ("10e-1", Some(1)),
            ("-9223372036854775808", Some(i64::MIN)),
            ("9223372036854775807", Some(i64::MAX)),
            ("9223372036854775808", None),
            ("92233720368547758080", Some(0)),
            ("1.5e3", None),
            ("7.0", None),
            ("+7", None),
            ("1e-1", None),
            ("", None),
            ("-", None),
            ("\"7", None),
            ("7x", None),
            ("null", None),
        ] {
            assert_eq!(parse_long(text).ok(), want, "{text:?}");
        }
    }

    #[test]
    fn writes_what_kotlinx_writes() {
        let mut out = String::new();
        write_string(&mut out, "a\"\\\t\u{8}\n\r\u{C}\u{1}\u{1F}/\u{7F}é");
        assert_eq!(out, "\"a\\\"\\\\\\t\\b\\n\\r\\f\\u0001\\u001f/\u{7F}é\"");
    }
}
