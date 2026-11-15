//! Source checks, run natively in `cargo test --workspace` (PLAN C4):
//!
//! - **No deleting calls** (M7, S5). `sqlite-wasm-vfs`'s `sahpool` can delete:
//!   `clear_on_init(true)` frees every slot at install, and its util's
//!   `clear`, `remove` and `reduce_capacity` drop files and slots. Only
//!   `de1-history-wasm/src/pools.rs` may touch `sahpool` at all, and it may
//!   call none of those outside its own tests. The web sources may not name
//!   the official `opfs-sahpool`'s deleting calls either.
//! - **H0's names.** The pools' VFS names and directories in `pools.rs`
//!   equal `web/src/lib/history-db/names.ts` (N2), so neither pool is the
//!   default `.opfs-sahpool`.

#![cfg(not(all(target_family = "wasm", target_os = "unknown")))]

use std::fs;
use std::path::{Path, PathBuf};

fn repo() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()
        .expect("the repository")
}

/// Every file under `dir` whose extension is one of `exts`, skipping build
/// output, dependencies, and the generated wasm-bindgen bundles (`wasm/`),
/// whose docs are this crate's own.
fn files(dir: &Path, exts: &[&str]) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        let Ok(entries) = fs::read_dir(&d) else {
            continue;
        };
        for e in entries.flatten() {
            let p = e.path();
            let name = e.file_name().to_string_lossy().into_owned();
            if p.is_dir() {
                if !matches!(
                    name.as_str(),
                    "target" | "node_modules" | ".git" | "build" | ".svelte-kit" | "fuzz" | "wasm"
                ) && !name.starts_with(".wasm")
                {
                    stack.push(p);
                }
            } else if p
                .extension()
                .and_then(|x| x.to_str())
                .is_some_and(|x| exts.contains(&x))
            {
                out.push(p);
            }
        }
    }
    out.sort();
    out
}

/// What reaches `sahpool`: only `pools.rs` may say any of these. The
/// traits are what carry `clear` and `remove` (REVIEW-444 note 1).
const SAHPOOL_NAMES: &[&str] = &[
    "sqlite_wasm_vfs",
    "rsqlite_vfs",
    "sahpool::",
    "OpfsSAHPoolUtil",
    "OpfsSAHPoolCfg",
    "VfsFilesManager",
    "DbTransfer",
];

/// The deleting methods, as calls: the word, then `(` or a turbofish, or
/// reached by path (`::clear`). Never in `pools.rs` outside its tests;
/// `reduce_capacity`, an inherent method a leaked util would offer without
/// naming anything, nowhere else either.
const DELETING_CALLS: &[&str] = &["clear", "remove", "reduce_capacity"];

/// The clearing install option, with spaces taken out.
const CLEAR_ON_INIT: &[&str] = &["clear_on_init(true)", "clear_on_init:true"];

/// What a visible item of `pools.rs` may not name: the util, or the traits
/// that delete through it.
const LEAKS: &[&str] = &[
    "OpfsSAHPoolUtil",
    "VfsFilesManager",
    "DbTransfer",
    "sahpool::",
];

fn ident(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

/// Each call of the method `word` in `line`: `word` as a whole word,
/// followed (after spaces) by `(` or `::<`, or preceded by `::`.
fn calls(line: &str, word: &str) -> usize {
    let mut n = 0;
    let mut from = 0;
    while let Some(i) = line[from..].find(word) {
        let at = from + i;
        let end = at + word.len();
        from = end;
        let before = line[..at].chars().next_back();
        if before.is_some_and(ident) || line[end..].chars().next().is_some_and(ident) {
            continue;
        }
        let rest = line[end..].trim_start();
        if rest.starts_with('(') || rest.starts_with("::<") || line[..at].ends_with("::") {
            n += 1;
        }
    }
    n
}

/// The official `opfs-sahpool`'s deleting calls and options, in web code.
const WEB_DELETING: &[&str] = &[
    "removeVfs",
    "wipeFiles",
    "reduceCapacity",
    "clearOnInit",
    "preserveOnInitFailure: false",
];

/// `text` without its `#[cfg(test)] mod tests { … }` block, which starts at
/// the attribute and runs to the end of the file (the crate's layout).
fn without_tests(text: &str) -> &str {
    match text.find("#[cfg(test)]\nmod tests") {
        Some(i) => &text[..i],
        None => text,
    }
}

/// Lines with their comments taken off, so a doc comment may name a call
/// it rules out.
fn code_lines(text: &str) -> impl Iterator<Item = (usize, &str)> {
    text.lines().enumerate().map(|(i, l)| {
        let code = match l.find("//") {
            Some(c) => &l[..c],
            None => l,
        };
        (i + 1, code)
    })
}

/// The visible items of `text` (`pub`, `pub(crate)`, …), each as its line
/// number and its signature up to `{` or `;`.
fn visible_items(text: &str) -> Vec<(usize, String)> {
    let lines: Vec<(usize, &str)> = code_lines(text).collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < lines.len() {
        let (n, line) = lines[i];
        if line.trim_start().starts_with("pub") {
            let mut sig = String::new();
            while i < lines.len() {
                let l = lines[i].1;
                sig.push_str(l);
                sig.push(' ');
                if l.contains('{') || l.contains(';') {
                    break;
                }
                i += 1;
            }
            out.push((n, sig));
        }
        i += 1;
    }
    out
}

/// What `path` (relative to the repository) may not say, given its text.
fn violations(rel: &str, text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let is_pools = rel == "core/de1-history-wasm/src/pools.rs";
    let is_self = rel == "core/de1-history-wasm/tests/sources.rs";
    if rel.ends_with(".rs") {
        if is_self {
            return out;
        }
        let code = if is_pools { without_tests(text) } else { text };
        for (n, line) in code_lines(code) {
            let tight: String = line.chars().filter(|c| !c.is_whitespace()).collect();
            for bad in CLEAR_ON_INIT {
                if tight.contains(bad) {
                    out.push(format!("{rel}:{n}: `{bad}` (clears at install)"));
                }
            }
            for word in DELETING_CALLS {
                if (is_pools || *word == "reduce_capacity") && calls(line, word) > 0 {
                    out.push(format!("{rel}:{n}: `{word}(` (a deleting call)"));
                }
            }
            if !is_pools {
                for name in SAHPOOL_NAMES {
                    if line.contains(name) {
                        out.push(format!("{rel}:{n}: `{name}` outside pools.rs"));
                    }
                }
            }
        }
        if is_pools {
            for (n, sig) in visible_items(code) {
                for name in LEAKS {
                    if sig.contains(name) {
                        out.push(format!("{rel}:{n}: a visible item exposes `{name}`"));
                    }
                }
            }
        }
    } else {
        for (n, line) in code_lines(text) {
            for bad in WEB_DELETING {
                if line.contains(bad) {
                    out.push(format!("{rel}:{n}: `{bad}`"));
                }
            }
        }
    }
    out
}

#[test]
fn no_code_but_the_pool_module_reaches_sahpool_and_it_never_deletes() {
    let root = repo();
    let mut found = Vec::new();
    let mut scanned = 0;
    let mut sources = files(&root.join("core"), &["rs"]);
    sources.extend(files(&root.join("web/src"), &["ts", "js", "svelte"]));
    for path in sources {
        let rel = path
            .strip_prefix(&root)
            .expect("in the repository")
            .to_string_lossy()
            .replace('\\', "/");
        let text = fs::read_to_string(&path).unwrap_or_default();
        found.extend(violations(&rel, &text));
        scanned += 1;
    }
    assert!(scanned > 100, "only {scanned} files scanned");
    assert!(found.is_empty(), "deleting calls:\n{}", found.join("\n"));
}

#[test]
fn the_check_catches_what_it_is_for() {
    // A mutation check: each forbidden form, planted, is found.
    let pools = "core/de1-history-wasm/src/pools.rs";
    for bad in [
        "    util.clear()?;",
        "    let _ = util.remove(\"/history.db\");",
        "    util.reduce_capacity(2).await?;",
        "    .clear_on_init(true)",
        "    clear_on_init: true,",
        "    .clear_on_init( true )",
        // REVIEW-444 P4: by path, and with a space before the parenthesis.
        "    let _ = sqlite_wasm_rs::vfs::VfsFilesManager::clear(&util);",
        "    let _ = <OpfsSAHPoolUtil as VfsFilesManager>::remove(&util, \"/h.db\");",
        "    let _ = util.clear ();",
        "    let _ = util.clear::<()>();",
    ] {
        let text = format!("fn f() {{\n{bad}\n}}\n");
        assert_eq!(violations(pools, &text).len(), 1, "{bad}");
        // Allowed in the module's own tests, and in comments.
        let in_tests = format!("fn f() {{}}\n#[cfg(test)]\nmod tests {{\n{bad}\n}}\n");
        assert!(violations(pools, &in_tests).is_empty(), "{bad}");
        assert!(
            violations(pools, &format!("// {bad}\n")).is_empty(),
            "{bad}"
        );
    }
    // Not deleting calls: other words, and fields.
    for fine in [
        "    .clear_on_init(spec.clear_on_init)",
        "    p.borrow_mut().retain(|k, _| *k != which);",
        "    let cleared = remove_me + clearance(1);",
    ] {
        assert!(
            violations(pools, &format!("fn f() {{\n{fine}\n}}\n")).is_empty(),
            "{fine}"
        );
    }
    // A visible item that hands out the util, or a trait that deletes.
    for leak in [
        "pub fn leak(which: Which) -> impl sqlite_wasm_rs::vfs::VfsFilesManager {\n    todo!()\n}\n",
        "pub(crate) fn util(\n    which: Which,\n) -> OpfsSAHPoolUtil {\n    todo!()\n}\n",
        "pub struct Leaky {\n    pub util: OpfsSAHPoolUtil,\n}\n",
        "pub use sqlite_wasm_rs::vfs::DbTransfer;\n",
    ] {
        assert!(!violations(pools, leak).is_empty(), "{leak}");
    }
    assert!(violations(pools, "struct Pool {\n    util: OpfsSAHPoolUtil,\n}\n").is_empty());
    for other in [
        "core/de1-history-wasm/src/db.rs",
        "core/de1-history/src/store/mod.rs",
    ] {
        for bad in [
            "use sqlite_wasm_vfs::sahpool::OpfsSAHPoolUtil;",
            "sahpool::install::<C>(&cfg, false)",
            "use sqlite_wasm_rs::vfs::VfsFilesManager as _;",
            "use rsqlite_vfs::transfer::DbTransfer;",
            "let _ = pools::leak(which).reduce_capacity(2);",
        ] {
            assert!(!violations(other, bad).is_empty(), "{other}: {bad}");
        }
    }
    for bad in [
        "await poolUtil.removeVfs();",
        "await util.wipeFiles();",
        "{ clearOnInit: true }",
        "installOpfsSAHPoolVfs({ preserveOnInitFailure: false })",
    ] {
        assert_eq!(
            violations("web/src/lib/history-db/worker.ts", bad).len(),
            1,
            "{bad}"
        );
    }
}

/// The `'…'` value of `export const NAME = '…';` in `text`.
fn ts_const<'a>(text: &'a str, name: &str) -> &'a str {
    let start = text
        .find(&format!("export const {name} = '"))
        .unwrap_or_else(|| panic!("{name} in names.ts"))
        + format!("export const {name} = '").len();
    let end = text[start..].find('\'').expect("a closing quote") + start;
    &text[start..end]
}

/// `field: "…"` inside the `pub const NAME: PoolSpec = PoolSpec { … };` of
/// `text`.
fn rs_field<'a>(text: &'a str, name: &str, field: &str) -> &'a str {
    let start = text
        .find(&format!("pub const {name}: PoolSpec = PoolSpec {{"))
        .unwrap_or_else(|| panic!("{name} in pools.rs"));
    let body = &text[start..start + text[start..].find("};").expect("the end")];
    let at = body
        .find(&format!("{field}: "))
        .unwrap_or_else(|| panic!("{name}.{field}"))
        + field.len()
        + 2;
    let rest = &body[at..];
    let end = rest.find(',').expect("a comma");
    rest[..end].trim().trim_matches('"')
}

#[test]
fn the_pools_use_h0s_names() {
    let root = repo();
    let names = fs::read_to_string(root.join("web/src/lib/history-db/names.ts")).expect("names.ts");
    let pools =
        fs::read_to_string(root.join("core/de1-history-wasm/src/pools.rs")).expect("pools.rs");
    assert_eq!(
        rs_field(&pools, "INDEX", "vfs"),
        ts_const(&names, "INDEX_POOL_VFS")
    );
    assert_eq!(
        rs_field(&pools, "INDEX", "dir"),
        ts_const(&names, "INDEX_POOL_DIR")
    );
    assert_eq!(
        rs_field(&pools, "DB", "vfs"),
        ts_const(&names, "DB_POOL_VFS")
    );
    assert_eq!(
        rs_field(&pools, "DB", "dir"),
        ts_const(&names, "DB_POOL_DIR")
    );
    // The capacities SPEC §8 sets (S5), and no clearing at install.
    assert_eq!(rs_field(&pools, "INDEX", "capacity"), "6");
    assert_eq!(rs_field(&pools, "DB", "capacity"), "16");
    assert_eq!(rs_field(&pools, "INDEX", "clear_on_init"), "false");
    assert_eq!(rs_field(&pools, "DB", "clear_on_init"), "false");
    let prefix = ts_const(&names, "HISTORY_SQLITE_OPFS_PREFIX");
    assert!(pools.contains(&format!("pub const OPFS_PREFIX: &str = \"{prefix}\";")));
    for dir in ["INDEX_POOL_DIR", "DB_POOL_DIR"] {
        assert!(ts_const(&names, dir).starts_with(prefix), "{dir}");
    }
}
