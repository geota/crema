//! The Worker's surface in Node (PLAN C4): `HistoryDb` on `memvfs`, through
//! the same JSON the Worker passes. C2's own suite runs on wasm32 too, in
//! `de1-history` (`cargo test -p de1-history --target wasm32-unknown-unknown`);
//! these tests cover what this crate adds: the JSON, the error shape, the
//! place and role rules, and the version check.

#![cfg(all(target_family = "wasm", target_os = "unknown"))]

use de1_history_wasm::db::HistoryDb;
use js_sys::Promise;
use serde_json::{Value, json};
use wasm_bindgen::JsValue;
use wasm_bindgen_futures::JsFuture;
use wasm_bindgen_test::wasm_bindgen_test as test;

/// A `Promise` of JSON text, parsed; or the thrown JSON, parsed.
async fn wait(p: Promise) -> Result<Value, Value> {
    match JsFuture::from(p).await {
        Ok(v) => Ok(parse_js(&v)),
        Err(e) => Err(parse_js(&e)),
    }
}

fn parse_js(v: &JsValue) -> Value {
    match v.as_string() {
        Some(s) => serde_json::from_str(&s).unwrap_or(Value::String(s)),
        None => Value::Null,
    }
}

fn sync(r: Result<String, JsValue>) -> Result<Value, Value> {
    r.map(|s| serde_json::from_str(&s).expect("JSON"))
        .map_err(|e| parse_js(&e))
}

fn code(e: &Value) -> &str {
    e["code"].as_str().unwrap_or("")
}

async fn open(v: Value) -> Result<HistoryDb, Value> {
    HistoryDb::opened(&v.to_string())
        .await
        .map_err(|e| parse_js(&e))
}

fn index_file(file: &str, create: bool) -> Value {
    json!({ "place": "memory", "file": file, "role": "index", "create": create,
            "generation": 1, "journal": "exclusiveWal", "nowMs": 1_700_000_000_000_i64 })
}

fn row(id: &str, at: i64, notes: &str) -> Value {
    json!({ "rowJson": json!({
        "id": id, "completedAt": at, "profileName": "Turbo",
        "metadata": { "dose": 18.0, "yieldOut": 36.0, "rating": 3, "notes": notes },
        "record": { "duration": 30000 }, "tags": [],
        "summary": { "v": 1, "sampleCount": 120, "finalWeight": 36.0 }
    }).to_string() })
}

#[test]
fn the_sqlite_version_is_the_native_one() {
    assert_eq!(
        de1_history_wasm::sqlite_version(),
        de1_history::store::SQLITE_VERSION
    );
}

#[test]
fn the_built_pools_never_clear_and_hold_their_capacity() {
    let specs = serde_json::to_value(de1_history_wasm::pools::specs()).expect("JSON");
    assert_eq!(
        specs,
        json!([
            { "vfs": "crema-index", "dir": ".crema-history-index", "capacity": 6, "clearOnInit": false },
            { "vfs": "crema-db", "dir": ".crema-history-db", "capacity": 16, "clearOnInit": false },
        ])
    );
}

#[test]
async fn an_index_file_writes_reads_and_reopens() {
    let db = open(index_file("node-a.db", true)).await.expect("opens");
    let rows = json!([row("a", 1_000, "fruity"), row("b", 2_000, "chocolate")]);
    let written = wait(db.upsert(&rows.to_string())).await.expect("writes");
    assert_eq!(written["change"]["upserted"], json!(["a", "b"]));
    assert_eq!(sync(db.count("{}")).expect("counts"), json!(2));
    let page = sync(db.page(&json!({ "query": {}, "anchor": "start", "limit": 10 }).to_string()))
        .expect("pages");
    let ids: Vec<&str> = page["rows"]
        .as_array()
        .expect("rows")
        .iter()
        .map(|r| r["id"].as_str().expect("an id"))
        .collect();
    assert_eq!(ids, ["b", "a"]);
    assert_eq!(page["atEnd"], json!(true));
    let found = sync(db.count(&json!({ "text": "choc" }).to_string())).expect("searches");
    assert_eq!(found, json!(1));
    let stats = sync(db.stats("{}")).expect("stats");
    assert_eq!(stats["count"], json!(2));
    assert_eq!(sync(db.info()).expect("info")["role"], json!("index"));
    let projection = sync(db.sync_projection()).expect("projects");
    assert_eq!(projection["rows"].as_array().map(Vec::len), Some(2));
    db.close().expect("closes");
    // Closed: refused, not a panic.
    assert_eq!(code(&sync(db.count("{}")).expect_err("closed")), "refused");
    let again = open(index_file("node-a.db", false)).await.expect("reopens");
    assert_eq!(sync(again.count("{}")).expect("counts"), json!(2));
    again.close().expect("closes");
}

#[test]
async fn authority_files_never_open_with_create() {
    // REVIEW-439 note 10: a missing truth file is `lost`, never recreated.
    let err = open(
        json!({ "place": "memory", "file": "node-truth.db", "role": "authority",
                           "create": true, "journal": "exclusiveWal" }),
    )
    .await
    .err()
    .expect("refused");
    assert_eq!(code(&err), "refused");
    let err = open(
        json!({ "place": "memory", "file": "node-truth.db", "role": "authority",
                           "journal": "exclusiveWal" }),
    )
    .await
    .err()
    .expect("missing");
    assert_eq!(code(&err), "cantOpen");
}

#[test]
async fn a_file_opens_only_in_its_roles_pool() {
    // M7: the index never shares a pool with the truth. Checked before any
    // pool is looked at.
    for (place, role) in [("db", "index"), ("index", "authority")] {
        let err = open(json!({ "place": place, "file": "x.db", "role": role,
                               "journal": "truncate" }))
        .await
        .err()
        .expect("refused");
        assert_eq!(code(&err), "refused", "{place} {role}");
    }
    // A pool that isn't installed.
    let err = open(json!({ "place": "index", "file": "x.db", "role": "index",
                           "create": true, "journal": "truncate" }))
    .await
    .err()
    .expect("not installed");
    assert_eq!(code(&err), "pool.notInstalled");
}

#[test]
async fn names_are_plain_and_one_file_has_one_connection() {
    for bad in ["", "a/b.db", "../x.db", ".hidden", "-x"] {
        let err = open(index_file(bad, true)).await.err().expect("refused");
        assert_eq!(code(&err), "refused", "{bad:?}");
    }
    let held = open(index_file("node-s4.db", true)).await.expect("opens");
    let err = open(index_file("node-s4.db", false))
        .await
        .err()
        .expect("a second connection");
    assert_eq!(code(&err), "alreadyOpen");
    held.close().expect("closes");
}

#[test]
async fn writes_apply_in_order_per_tab() {
    let db = open(index_file("node-apply.db", true))
        .await
        .expect("opens");
    let write = |seq: u64, id: &str| {
        json!({ "tab": "t1", "seq": seq, "atMs": 5,
                "write": { "kind": "upsert", "rows": [row(id, 1, "")] } })
        .to_string()
    };
    let done = wait(db.apply(&write(1, "a"))).await.expect("applies");
    assert_eq!(done["outcome"], json!("done"));
    let again = wait(db.apply(&write(1, "a"))).await.expect("answers");
    assert_eq!(again, json!({ "outcome": "alreadyApplied", "last": 1 }));
    let gap = wait(db.apply(&write(3, "c"))).await.expect("answers");
    assert_eq!(gap, json!({ "outcome": "gap", "last": 1 }));
    assert_eq!(sync(db.applied_seq("t1")).expect("seq"), json!(1));
    let delete = json!({ "tab": "t1", "seq": 2, "atMs": 6,
                         "write": { "kind": "delete", "ids": ["a"] } });
    let deleted = wait(db.apply(&delete.to_string())).await.expect("applies");
    assert_eq!(deleted["written"]["change"]["deleted"], json!(["a"]));
    db.close().expect("closes");
}

#[test]
async fn an_export_walk_takes_its_token_as_text() {
    let db = open(index_file("node-walk.db", true)).await.expect("opens");
    let rows: Vec<Value> = (0..5).map(|i| row(&format!("r{i}"), i, "")).collect();
    wait(db.upsert(&Value::Array(rows).to_string()))
        .await
        .expect("writes");
    let walk = wait(db.export_begin()).await.expect("begins");
    assert_eq!(walk["count"], json!(5));
    let token = walk["token"].as_str().expect("a text token").to_owned();
    let first =
        sync(db.export_next(&json!({ "token": token, "n": 3 }).to_string())).expect("walks");
    assert_eq!(first.as_array().map(Vec::len), Some(3));
    // A delete during the walk is held (S6).
    wait(db.delete(&json!(["r0"]).to_string()))
        .await
        .expect("deletes");
    let rest =
        sync(db.export_next(&json!({ "token": token, "n": 10 }).to_string())).expect("walks");
    assert_eq!(rest.as_array().map(Vec::len), Some(2));
    wait(db.export_end(&token)).await.expect("ends");
    assert_eq!(sync(db.release_holds()).expect("holds"), json!(["r0"]));
    let err =
        sync(db.export_next(&json!({ "token": "123", "n": 1 }).to_string())).expect_err("unknown");
    assert_eq!(code(&err), "unknownWalk");
    db.close().expect("closes");
}

#[test]
async fn a_copy_and_a_recovery_file_are_how_truth_files_begin() {
    let index = open(index_file("node-flip-src.db", true))
        .await
        .expect("opens");
    wait(index.upsert(&json!([row("a", 1, ""), row("b", 2, "")]).to_string()))
        .await
        .expect("writes");
    wait(
        index.copy_to(
            &json!({ "place": "memory", "file": "node-flip-dst.db", "journal": "exclusiveWal" })
                .to_string(),
        ),
    )
    .await
    .expect("copies");
    // The copy is whole: it opens and holds both rows.
    let copy = open(index_file("node-flip-dst.db", false))
        .await
        .expect("opens");
    assert_eq!(sync(copy.count("{}")).expect("counts"), json!(2));
    copy.close().expect("closes");
    // A copy into the index pool is refused: copies are the truth's (M7).
    let err =
        wait(index.copy_to(
            &json!({ "place": "index", "file": "x.db", "journal": "truncate" }).to_string(),
        ))
        .await
        .expect_err("refused");
    assert_eq!(code(&err), "refused");
    index.close().expect("closes");

    let recovery = HistoryDb::recovered(
        &json!({ "place": "memory", "file": "node-history-2.db", "generation": 2,
                 "journal": "exclusiveWal",
                 "recovery": { "migrationSource": ["a", "b"], "lateBaseline": "{}" } })
        .to_string(),
    )
    .await
    .map_err(|e| parse_js(&e))
    .expect("creates");
    assert_eq!(
        sync(recovery.info()).expect("info")["role"],
        json!("authority")
    );
    assert_eq!(
        sync(recovery.migration_state()).expect("state"),
        json!("done")
    );
    assert_eq!(
        sync(recovery.migration_source_len()).expect("len"),
        json!(2)
    );
    recovery.close().expect("closes");
    // Now it exists, it opens as the truth, with create off.
    let truth = open(json!({ "place": "memory", "file": "node-history-2.db",
                             "role": "authority", "journal": "exclusiveWal" }))
    .await
    .expect("opens");
    assert_eq!(
        sync(truth.health()).expect("health")["writable"],
        json!(true)
    );
    truth.close().expect("closes");
}

#[test]
async fn bad_json_is_badjson() {
    let db = open(index_file("node-bad.db", true)).await.expect("opens");
    assert_eq!(
        code(&wait(db.upsert("not json")).await.expect_err("bad")),
        "badJson"
    );
    assert_eq!(code(&sync(db.count("[1]")).expect_err("bad")), "badJson");
    db.close().expect("closes");
}
