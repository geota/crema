//! Repair storage (SPEC §8, "A pool that never installs", S5) and the raw
//! OPFS reads it is built on.
//!
//! A slot with an invalid header fails every install of its pool, which
//! would be a lasting `unavailable`. [`repair`] reads the pool directory's
//! slot files through OPFS directly, not through `sahpool`, and copies every
//! slot whose header names a file (the database, its journal or WAL) into a
//! new pool generation, `<dir>-<g>/`, byte for byte, header included, so the
//! new pool installs with the same names. It **deletes nothing and writes
//! nothing in the source**: files are read with `getFile()`, which takes no
//! write lock, and the old directory stays as it is for a later attempt;
//! only Erase removes it.
//!
//! Skipped and reported, never copied: slots whose header is invalid, slots
//! with no name (free, or a temporary file), and every slot of a name that
//! two slots claim (which would fail the new pool's install the same way).
//! A target directory that already holds anything is refused.

use js_sys::{Array, Reflect, Uint8Array};
use serde::Serialize;
use sqlite_wasm_rs::{
    SQLITE_OPEN_DELETEONCLOSE, SQLITE_OPEN_MAIN_DB, SQLITE_OPEN_MAIN_JOURNAL,
    SQLITE_OPEN_SUBJOURNAL, SQLITE_OPEN_SUPER_JOURNAL, SQLITE_OPEN_TEMP_DB,
    SQLITE_OPEN_TEMP_JOURNAL, SQLITE_OPEN_TRANSIENT_DB, SQLITE_OPEN_WAL,
};
use wasm_bindgen::{JsCast, JsValue};
use wasm_bindgen_futures::JsFuture;
use web_sys::{
    Blob, DedicatedWorkerGlobalScope, File, FileSystemDirectoryHandle, FileSystemFileHandle,
    FileSystemGetDirectoryOptions, FileSystemGetFileOptions, FileSystemReadWriteOptions,
    FileSystemSyncAccessHandle,
};

use crate::pools::{OPFS_PREFIX, Which};

/// `sahpool`'s slot layout (`sqlite-wasm-vfs` 0.3.0, `sahpool.rs`): a
/// NUL-padded UTF-8 name in the first 512 bytes, big-endian open flags in the
/// next 4, and the file's own bytes from offset 4096.
const NAME_BYTES: usize = 512;
const HEADER_BYTES: usize = NAME_BYTES + 4;
const DATA_OFFSET: u64 = 4096;
/// The directory of slot files inside a pool directory.
pub const SLOTS_DIR: &str = ".opaque";
/// Bytes per read and write while copying.
const CHUNK: u64 = 1 << 20;

const PERSISTENT: u32 =
    (SQLITE_OPEN_MAIN_DB | SQLITE_OPEN_MAIN_JOURNAL | SQLITE_OPEN_SUPER_JOURNAL | SQLITE_OPEN_WAL)
        as u32;
const TEMPORARY: u32 = (SQLITE_OPEN_TEMP_DB
    | SQLITE_OPEN_TEMP_JOURNAL
    | SQLITE_OPEN_TRANSIENT_DB
    | SQLITE_OPEN_SUBJOURNAL) as u32;

/// What a slot's header says, by `sahpool`'s own rules.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Slot {
    /// No file in it: free, or a temporary file deleted on close.
    Unnamed,
    /// It holds the file of this name.
    Named(String),
    /// The header doesn't parse: `sahpool` refuses the pool for it.
    Invalid(&'static str),
}

/// Reads a slot's header as `sahpool`'s install does
/// (`SyncAccessFile::associated_filename`). `size` is the file's length;
/// `head` its first bytes, at most [`HEADER_BYTES`] of them needed, and every
/// byte for a file shorter than [`DATA_OFFSET`].
#[must_use]
pub fn read_header(size: u64, head: &[u8]) -> Slot {
    if size == 0 {
        return Slot::Unnamed;
    }
    if size < DATA_OFFSET {
        // An interrupted initialisation leaves zeros, which hold nothing.
        return if head.iter().all(|&b| b == 0) {
            Slot::Unnamed
        } else {
            Slot::Invalid("file is shorter than its header")
        };
    }
    if head.len() < HEADER_BYTES {
        return Slot::Invalid("incomplete header");
    }
    let name = &head[..NAME_BYTES];
    let flags = u32::from_be_bytes([
        head[NAME_BYTES],
        head[NAME_BYTES + 1],
        head[NAME_BYTES + 2],
        head[NAME_BYTES + 3],
    ]);
    let Some(end) = name.iter().position(|&b| b == 0) else {
        return Slot::Invalid("filename is not NUL-terminated");
    };
    if name[end..].iter().any(|&b| b != 0) {
        return Slot::Invalid("nonzero bytes after filename terminator");
    }
    if end == 0 {
        return if flags == 0 {
            Slot::Unnamed
        } else {
            Slot::Invalid("unnamed slot has nonzero flags")
        };
    }
    let Ok(name) = std::str::from_utf8(&name[..end]) else {
        return Slot::Invalid("filename is not valid UTF-8");
    };
    let kind = flags & (PERSISTENT | TEMPORARY);
    if kind.count_ones() != 1 {
        return Slot::Invalid("missing or conflicting file types");
    }
    if flags & SQLITE_OPEN_DELETEONCLOSE as u32 != 0 {
        return Slot::Unnamed;
    }
    if kind & TEMPORARY != 0 {
        return Slot::Invalid("temporary file is missing DELETEONCLOSE");
    }
    Slot::Named(name.to_owned())
}

/// What went wrong in OPFS.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OpfsError {
    /// `notSupported`, `notFound`, `targetExists`, `sameDirectory`,
    /// `badName`, `opfs`.
    pub code: &'static str,
    /// The text.
    pub message: String,
}

impl OpfsError {
    fn new(code: &'static str, message: impl Into<String>) -> OpfsError {
        OpfsError {
            code,
            message: message.into(),
        }
    }

    fn js(what: &str, e: &JsValue) -> OpfsError {
        let name = Reflect::get(e, &"name".into())
            .ok()
            .and_then(|v| v.as_string());
        let message = Reflect::get(e, &"message".into())
            .ok()
            .and_then(|v| v.as_string())
            .unwrap_or_else(|| format!("{e:?}"));
        let code = if name.as_deref() == Some("NotFoundError") {
            "notFound"
        } else {
            "opfs"
        };
        OpfsError::new(
            code,
            format!(
                "{what}: {}{message}",
                name.map(|n| n + ": ").unwrap_or_default()
            ),
        )
    }
}

type Result<T> = std::result::Result<T, OpfsError>;

async fn wait(what: &str, p: js_sys::Promise) -> Result<JsValue> {
    JsFuture::from(p).await.map_err(|e| OpfsError::js(what, &e))
}

/// The OPFS root, from a dedicated Worker.
async fn root() -> Result<FileSystemDirectoryHandle> {
    let scope = js_sys::global()
        .dyn_into::<DedicatedWorkerGlobalScope>()
        .map_err(|_| OpfsError::new("notSupported", "not in a dedicated Worker"))?;
    let storage = scope.navigator().storage();
    Ok(wait("get the OPFS root", storage.get_directory())
        .await?
        .into())
}

/// A directory below `parent`: `None` when it doesn't exist and `create` is
/// off.
async fn subdir(
    parent: &FileSystemDirectoryHandle,
    name: &str,
    create: bool,
) -> Result<Option<FileSystemDirectoryHandle>> {
    let opts = FileSystemGetDirectoryOptions::new();
    opts.set_create(create);
    match JsFuture::from(parent.get_directory_handle_with_options(name, &opts)).await {
        Ok(h) => Ok(Some(h.into())),
        Err(e) => {
            let err = OpfsError::js(&format!("open {name}"), &e);
            if err.code == "notFound" {
                Ok(None)
            } else {
                Err(err)
            }
        }
    }
}

/// A directory's entries: `(name, handle, is_file)`, sorted by name.
async fn entries(dir: &FileSystemDirectoryHandle) -> Result<Vec<(String, JsValue, bool)>> {
    let iter = dir.entries();
    let mut out = Vec::new();
    loop {
        let next = wait(
            "list a directory",
            iter.next()
                .map_err(|e| OpfsError::js("list a directory", &e))?,
        )
        .await?;
        let done = Reflect::get(&next, &"done".into())
            .map(|v| v.is_truthy())
            .unwrap_or(true);
        if done {
            break;
        }
        let pair: Array = Reflect::get(&next, &"value".into())
            .map_err(|e| OpfsError::js("list a directory", &e))?
            .into();
        let name = pair.get(0).as_string().unwrap_or_default();
        let handle = pair.get(1);
        let kind = Reflect::get(&handle, &"kind".into())
            .ok()
            .and_then(|k| k.as_string());
        out.push((name, handle, kind.as_deref() == Some("file")));
    }
    out.sort_by(|a, b| a.0.cmp(&b.0));
    Ok(out)
}

/// A file's `File`, read-only: `getFile()` takes no write lock.
async fn file_of(handle: &FileSystemFileHandle) -> Result<File> {
    Ok(wait("read a file", handle.get_file()).await?.into())
}

/// `[start, end)` of a `File`.
async fn bytes(file: &File, start: u64, end: u64) -> Result<Vec<u8>> {
    #[allow(clippy::cast_precision_loss)]
    let blob: Blob = file
        .slice_with_f64_and_f64(start as f64, end as f64)
        .map_err(|e| OpfsError::js("slice a file", &e))?;
    let buf = wait("read a file", blob.array_buffer()).await?;
    Ok(Uint8Array::new(&buf).to_vec())
}

fn size_of(file: &File) -> u64 {
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    {
        file.size() as u64
    }
}

/// Every file under the OPFS directory `path` (`a/b`), recursively, with its
/// bytes: `(path below it, bytes)`, sorted. Read-only. `None` when the
/// directory doesn't exist. For tests that compare a pool before and after,
/// and for the shell's diagnostics; holds every byte in memory.
///
/// # Errors
/// An OPFS error.
pub async fn snapshot(path: &str) -> Result<Option<Vec<(String, Vec<u8>)>>> {
    let Some(dir) = dir_at(path, false).await? else {
        return Ok(None);
    };
    let mut out = Vec::new();
    let mut stack = vec![(String::new(), dir)];
    while let Some((prefix, dir)) = stack.pop() {
        for (name, handle, is_file) in entries(&dir).await? {
            let full = if prefix.is_empty() {
                name.clone()
            } else {
                format!("{prefix}/{name}")
            };
            if is_file {
                let file = file_of(&handle.unchecked_into()).await?;
                let len = size_of(&file);
                out.push((full, bytes(&file, 0, len).await?));
            } else {
                stack.push((full, handle.unchecked_into()));
            }
        }
    }
    out.sort_by(|a, b| a.0.cmp(&b.0));
    Ok(Some(out))
}

/// The directory at `path` below the root (`a/b`).
async fn dir_at(path: &str, create: bool) -> Result<Option<FileSystemDirectoryHandle>> {
    let mut dir = root().await?;
    for part in path.split('/').filter(|p| !p.is_empty()) {
        if matches!(part, "." | "..") {
            return Err(OpfsError::new("badName", format!("{path:?}")));
        }
        match subdir(&dir, part, create).await? {
            Some(d) => dir = d,
            None => return Ok(None),
        }
    }
    Ok(Some(dir))
}

/// Writes a new file `name` into the OPFS directory `path`, creating the
/// directory. Refused when the file exists: it never overwrites. For repair
/// and for tests that plant a slot.
///
/// # Errors
/// `targetExists` for an existing file; an OPFS error.
pub async fn write_new(path: &str, name: &str, data: &[u8]) -> Result<()> {
    let dir = dir_at(path, true)
        .await?
        .ok_or_else(|| OpfsError::new("notFound", path))?;
    let sah = create_new(&dir, name).await?;
    let written = write_at(&sah, data, 0).and_then(|()| flush(&sah));
    sah.close();
    written
}

/// A sync access handle on a new, empty file `name` in `dir`.
async fn create_new(
    dir: &FileSystemDirectoryHandle,
    name: &str,
) -> Result<FileSystemSyncAccessHandle> {
    let probe = FileSystemGetFileOptions::new();
    probe.set_create(false);
    if JsFuture::from(dir.get_file_handle_with_options(name, &probe))
        .await
        .is_ok()
    {
        return Err(OpfsError::new("targetExists", name.to_owned()));
    }
    let create = FileSystemGetFileOptions::new();
    create.set_create(true);
    let handle: FileSystemFileHandle = wait(
        "create a file",
        dir.get_file_handle_with_options(name, &create),
    )
    .await?
    .into();
    Ok(wait("open a file", handle.create_sync_access_handle())
        .await?
        .into())
}

fn write_at(sah: &FileSystemSyncAccessHandle, data: &[u8], at: u64) -> Result<()> {
    let opts = FileSystemReadWriteOptions::new();
    #[allow(clippy::cast_precision_loss)]
    opts.set_at(at as f64);
    let n = sah
        .write_with_u8_array_and_options(data, &opts)
        .map_err(|e| OpfsError::js("write a file", &e))?;
    #[allow(clippy::cast_precision_loss)]
    if n == data.len() as f64 {
        Ok(())
    } else {
        Err(OpfsError::new("opfs", "short write"))
    }
}

fn flush(sah: &FileSystemSyncAccessHandle) -> Result<()> {
    sah.flush().map_err(|e| OpfsError::js("flush a file", &e))
}

/// One slot as repair saw it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SlotReport {
    /// The slot file's own (random) name.
    pub opaque: String,
    /// The file it holds, when its header names one.
    pub name: Option<String>,
    /// Its length, header included.
    pub bytes: u64,
    /// Why it wasn't copied, when it wasn't: `invalid: <reason>`, `unnamed`,
    /// `duplicate`.
    pub skipped: Option<String>,
}

/// What [`repair`] did.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RepairReport {
    /// The source directory, unchanged.
    pub from: String,
    /// The new pool generation's directory.
    pub to: String,
    /// Every slot of the source, copied or not.
    pub slots: Vec<SlotReport>,
    /// The names copied, sorted.
    pub copied: Vec<String>,
}

/// Copies every named slot of `which`'s pool at generation `from` into a
/// new directory for generation `to` (module docs). The source is read only.
/// The pool must not be installed at `from` in this Worker (it would hold
/// the handles); the shell runs repair after a failed install.
///
/// # Errors
/// `sameDirectory`; `notFound` for a missing source; `targetExists` when the
/// target holds anything; an OPFS error. A failure part way leaves the
/// source as it was and the target partly written: the shell retries with a
/// higher generation.
pub async fn repair(which: Which, from: u32, to: u32) -> Result<RepairReport> {
    let spec = which.spec();
    let (from_dir, to_dir) = (spec.dir_for(from), spec.dir_for(to));
    if from_dir == to_dir {
        return Err(OpfsError::new("sameDirectory", from_dir));
    }
    debug_assert!(to_dir.starts_with(OPFS_PREFIX));
    let src = dir_at(&format!("{from_dir}/{SLOTS_DIR}"), false)
        .await?
        .ok_or_else(|| OpfsError::new("notFound", format!("{from_dir}/{SLOTS_DIR}")))?;
    if let Some(existing) = dir_at(&to_dir, false).await?
        && !entries(&existing).await?.is_empty()
    {
        return Err(OpfsError::new("targetExists", to_dir));
    }
    // Read every header first, so names two slots claim are known before
    // anything is written.
    let mut slots = Vec::new();
    for (opaque, handle, is_file) in entries(&src).await? {
        if !is_file {
            continue;
        }
        let file = file_of(&handle.unchecked_into()).await?;
        let len = size_of(&file);
        let head = bytes(&file, 0, len.min(DATA_OFFSET)).await?;
        slots.push((opaque, file, len, read_header(len, &head)));
    }
    let mut seen = std::collections::BTreeMap::<String, u32>::new();
    for (_, _, _, slot) in &slots {
        if let Slot::Named(n) = slot {
            *seen.entry(n.clone()).or_default() += 1;
        }
    }
    let target = dir_at(&format!("{to_dir}/{SLOTS_DIR}"), true)
        .await?
        .ok_or_else(|| OpfsError::new("notFound", to_dir.clone()))?;
    let mut report = RepairReport {
        from: from_dir,
        to: to_dir,
        slots: Vec::new(),
        copied: Vec::new(),
    };
    for (opaque, file, len, slot) in slots {
        let (name, skipped) = match slot {
            Slot::Invalid(reason) => (None, Some(format!("invalid: {reason}"))),
            Slot::Unnamed => (None, Some("unnamed".to_owned())),
            Slot::Named(n) if seen.get(&n).copied().unwrap_or(0) > 1 => {
                (Some(n), Some("duplicate".to_owned()))
            }
            Slot::Named(n) => (Some(n), None),
        };
        if skipped.is_none() {
            copy(&file, len, &target, &opaque).await?;
            if let Some(n) = &name {
                report.copied.push(n.clone());
            }
        }
        report.slots.push(SlotReport {
            opaque,
            name,
            bytes: len,
            skipped,
        });
    }
    report.copied.sort();
    Ok(report)
}

/// Copies `file` (`len` bytes) into a new file `name` in `dir`, in chunks,
/// and flushes it.
async fn copy(file: &File, len: u64, dir: &FileSystemDirectoryHandle, name: &str) -> Result<()> {
    let sah = create_new(dir, name).await?;
    let mut done = Ok(());
    let mut at = 0;
    while at < len {
        let end = (at + CHUNK).min(len);
        let chunk = match bytes(file, at, end).await {
            Ok(c) => c,
            Err(e) => {
                done = Err(e);
                break;
            }
        };
        if let Err(e) = write_at(&sah, &chunk, at) {
            done = Err(e);
            break;
        }
        at = end;
    }
    let done = done.and_then(|()| flush(&sah));
    sah.close();
    done
}

/// Throws `e` as JSON text.
pub(crate) fn throw(e: &OpfsError) -> JsValue {
    JsValue::from_str(&serde_json::to_string(e).unwrap_or_else(|_| e.message.clone()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use wasm_bindgen_test::wasm_bindgen_test as test;

    fn header(name: &str, flags: u32) -> Vec<u8> {
        let mut h = vec![0u8; 4096];
        h[..name.len()].copy_from_slice(name.as_bytes());
        h[NAME_BYTES..HEADER_BYTES].copy_from_slice(&flags.to_be_bytes());
        h
    }

    #[test]
    fn headers_read_as_sahpool_reads_them() {
        let db = SQLITE_OPEN_MAIN_DB as u32 | 0x2;
        assert_eq!(
            read_header(8192, &header("/history.db", db)),
            Slot::Named("/history.db".into())
        );
        assert_eq!(read_header(0, &[]), Slot::Unnamed);
        assert_eq!(read_header(4096, &header("", 0)), Slot::Unnamed);
        assert_eq!(read_header(100, &[0; 100]), Slot::Unnamed);
        assert!(matches!(read_header(100, &[1; 100]), Slot::Invalid(_)));
        assert!(matches!(
            read_header(8192, &[0x41; 4096]),
            Slot::Invalid("filename is not NUL-terminated")
        ));
        assert!(matches!(
            read_header(8192, &header("", 5)),
            Slot::Invalid(_)
        ));
        let temp = SQLITE_OPEN_TEMP_JOURNAL as u32;
        assert!(matches!(
            read_header(8192, &header("/x", temp)),
            Slot::Invalid(_)
        ));
        let gone = temp | SQLITE_OPEN_DELETEONCLOSE as u32;
        assert_eq!(read_header(8192, &header("/x", gone)), Slot::Unnamed);
        let journal = SQLITE_OPEN_MAIN_JOURNAL as u32;
        assert_eq!(
            read_header(8192, &header("/history.db-journal", journal)),
            Slot::Named("/history.db-journal".into())
        );
    }
}
