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

/// What reaches `sahpool`: only `pools.rs` may say any of these.
const SAHPOOL_NAMES: &[&str] = &[
    "sqlite_wasm_vfs",
    "sahpool::",
    "OpfsSAHPoolUtil",
    "OpfsSAHPoolCfg",
];

/// The deleting calls `pools.rs` may not make outside its tests.
const DELETING: &[&str] = &[
    ".clear(",
    ".remove(",
    "reduce_capacity",
    "clear_on_init(true)",
    "clear_on_init: true",
];

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

/// What `path` (relative to the repository) may not say, given its text.
fn violations(rel: &str, text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let is_pools = rel == "core/de1-history-wasm/src/pools.rs";
    let is_self = rel == "core/de1-history-wasm/tests/sources.rs";
    if rel.ends_with(".rs") {
        if is_pools {
            for (n, line) in code_lines(without_tests(text)) {
                for bad in DELETING {
                    if line.contains(bad) {
                        out.push(format!("{rel}:{n}: `{bad}` (a deleting call)"));
                    }
                }
            }
        } else if !is_self {
            for (n, line) in code_lines(text) {
                for name in SAHPOOL_NAMES {
                    if line.contains(name) {
                        out.push(format!("{rel}:{n}: `{name}` outside pools.rs"));
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
    for other in [
        "core/de1-history-wasm/src/db.rs",
        "core/de1-history/src/store/mod.rs",
    ] {
        for bad in [
            "use sqlite_wasm_vfs::sahpool::OpfsSAHPoolUtil;",
            "sahpool::install::<C>(&cfg, false)",
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
