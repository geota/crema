//! The method preset labels moved into the core (PLAN C1): the core's table
//! must equal both shells' current tables, web's `method-labels.ts` and
//! Android's `BrewMethods.kt`, and cover every preset the core defines.
// Reads the repository's files with `std::fs`: native only.
#![cfg(not(target_arch = "wasm32"))]

use std::fs;
use std::path::PathBuf;

use de1_history::derive::PRESET_METHOD_LABELS;

fn repo(path: &str) -> String {
    let p = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path);
    fs::read_to_string(&p).unwrap_or_else(|e| panic!("{}: {e}", p.display()))
}

/// The text between the first pair of single (`'`) or double (`"`) quotes.
fn quoted(s: &str, q: char) -> Option<&str> {
    let start = s.find(q)? + 1;
    let len = s[start..].find(q)?;
    Some(&s[start..start + len])
}

/// `PRESET_METHOD_LABELS` in `method-labels.ts`: lines `\tid: 'Label',`.
fn web_table() -> Vec<(String, String)> {
    let text = repo("web/src/lib/brew/method-labels.ts");
    let body = text
        .split("PRESET_METHOD_LABELS")
        .nth(1)
        .and_then(|t| t.split('{').nth(1))
        .and_then(|t| t.split('}').next())
        .expect("the web table");
    body.lines()
        .filter_map(|l| {
            let (id, label) = l.trim().split_once(':')?;
            Some((id.trim().to_owned(), quoted(label, '\'')?.to_owned()))
        })
        .collect()
}

/// `METHOD_FACES` in `BrewMethods.kt`: lines `"id" to ("Label" to "icon"),`.
fn android_table() -> Vec<(String, String)> {
    let text = repo("android/app/src/main/java/coffee/crema/brew/BrewMethods.kt");
    let body = text
        .split("METHOD_FACES: Map<String, Pair<String, String>> = mapOf(")
        .nth(1)
        .and_then(|t| t.split("\n)").next())
        .expect("the Android table");
    body.lines()
        .filter_map(|l| {
            let (id, face) = l.trim().split_once(" to (")?;
            Some((quoted(id, '"')?.to_owned(), quoted(face, '"')?.to_owned()))
        })
        .collect()
}

fn core_table() -> Vec<(String, String)> {
    PRESET_METHOD_LABELS
        .iter()
        .map(|(id, label)| ((*id).to_owned(), (*label).to_owned()))
        .collect()
}

#[test]
fn the_core_labels_equal_the_web_table() {
    assert_eq!(web_table(), core_table());
}

#[test]
fn the_core_labels_equal_the_android_table() {
    assert_eq!(android_table(), core_table());
}

#[test]
fn every_core_preset_has_a_label() {
    let ids: Vec<String> = de1_domain::brew_method_presets()
        .into_iter()
        .map(|p| p.id)
        .collect();
    let labelled: Vec<String> = core_table().into_iter().map(|(id, _)| id).collect();
    for id in &ids {
        assert!(labelled.contains(id), "{id} has no label");
    }
    assert_eq!(ids.len(), labelled.len());
}
