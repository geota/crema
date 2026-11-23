//! The Kotlin/JVM rules against vectors a JDK 21 generated
//! (`tests/vectors/jvm/JvmVectors.java`).

use std::{fs, path::Path};

use de1_relay_app::jvm;

fn vectors(name: &str) -> String {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/vectors/jvm")
        .join(name);
    fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

/// `\u{hex}` escapes back to characters.
fn unescape(s: &str) -> String {
    let mut out = String::new();
    let mut rest = s;
    while let Some(i) = rest.find("\\u{") {
        out.push_str(&rest[..i]);
        let end = rest[i..].find('}').expect("closing brace") + i;
        let code = u32::from_str_radix(&rest[i + 3..end], 16).expect("hex");
        out.push(char::from_u32(code).expect("scalar"));
        rest = &rest[end + 1..];
    }
    out + rest
}

#[test]
fn double_to_string_is_javas() {
    let mut n = 0;
    for line in vectors("double_to_string.txt").lines() {
        let (bits, expected) = line.split_once(' ').expect("bits and text");
        let v = f64::from_bits(u64::from_str_radix(bits, 16).expect("hex bits"));
        assert_eq!(jvm::double_to_string(v), expected, "{bits}");
        n += 1;
    }
    assert!(n > 4000);
}

#[test]
fn float_to_string_is_javas() {
    let mut n = 0;
    for line in vectors("float_to_string.txt").lines() {
        let (bits, expected) = line.split_once(' ').expect("bits and text");
        let v = f32::from_bits(u32::from_str_radix(bits, 16).expect("hex bits"));
        assert_eq!(jvm::float_to_string(v), expected, "{bits}");
        n += 1;
    }
    assert!(n > 8000);
}

#[test]
fn parse_double_is_kotlins() {
    for line in vectors("parse_double.txt").lines() {
        let (input, expected) = line.split_once('\t').expect("input and bits");
        let input = unescape(input);
        let got = jvm::parse_double(&input);
        if expected == "null" {
            assert_eq!(got, None, "{input:?}");
        } else {
            let bits = u64::from_str_radix(expected, 16).expect("hex bits");
            let v = got.unwrap_or_else(|| panic!("{input:?} should parse"));
            if f64::from_bits(bits).is_nan() {
                assert!(v.is_nan(), "{input:?}");
            } else {
                assert_eq!(v.to_bits(), bits, "{input:?}");
            }
        }
    }
}

#[test]
fn printed_doubles_parse_back() {
    for line in vectors("double_to_string.txt").lines() {
        let (bits, text) = line.split_once(' ').expect("bits and text");
        let v = jvm::parse_double(text).expect(text);
        assert_eq!(format!("{:016x}", v.to_bits()), bits, "{text}");
    }
}
