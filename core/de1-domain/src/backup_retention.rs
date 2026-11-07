//! Which automatic Drive backups to keep.
//!
//! A shell uploads one backup a day, so something has to delete old ones, and a
//! bad backup must not be able to push out the good ones. Every health check on
//! the shell's side holds for one launch only, so the rule here needs no idea
//! why a backup is small: each copy's name carries its counts, and the fullest
//! copies always stay.
//!
//! Per device label, keep the newest `keep`, the newest copy with the most
//! shots, the newest with the most curves, and the newest with no counts for 90
//! days, as it can't be compared. So a label holds at most `keep + 3`. A device
//! only ever prunes its own copies: compared across labels, a phone's small copy
//! would look like a tablet's shrink, every day. The label in a name carries a
//! random tag after the model (`Pixel_8~a1b2c3`), as two devices of one model
//! would otherwise prune each other. A copy from before the tag, or from an
//! earlier install, has another label, so nothing here ever touches it.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// What an automatic backup's name starts with. Holding "crema-backup", so
/// Drive's list query finds them, and telling them from manual ones, which
/// nothing here ever deletes.
pub const AUTO_BACKUP_PREFIX: &str = "crema-backup-auto-";

/// The start of a manual backup's name.
const BACKUP_PREFIX: &str = "crema-backup-";

/// The end of every backup's name.
const BACKUP_SUFFIX: &str = ".crema.zip";

/// Between a device's model and its tag, in a label. Model names never hold it.
const TAG_SEPARATOR: char = '~';

/// How long a copy with no counts is kept for, past the newest `keep`, counted
/// from the newest listed copy: this device's clock can be anything.
const NO_COUNTS_MS: i64 = 90 * 24 * 60 * 60 * 1000;

/// How many shots a backup holds, and how many of those have curves.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupCounts {
    pub shots: u32,
    pub curves: u32,
}

/// A file in Drive, as a shell lists it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupFile {
    pub id: String,
    pub name: String,
    #[typeshare(serialized_as = "I64")]
    pub modified_ms: i64,
}

/// An automatic backup's name, read back.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AutoBackupName {
    /// The device label, as it went into the name.
    pub label: String,
    /// `None` for a copy from before names held counts.
    pub counts: Option<BackupCounts>,
}

/// What a backup's name says about it, for a list to pick from. Any name will
/// do: what it doesn't say stays empty.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupFileInfo {
    /// The device label, as it went into the name.
    pub device: Option<String>,
    /// Made by the daily backup, not by hand.
    pub automatic: bool,
    pub counts: Option<BackupCounts>,
}

/// What dropped between a device's last two backups.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum BackupShrinkKind {
    Shots,
    Curves,
}

/// A backup holding under half of what the same device's last one held.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupShrink {
    pub kind: BackupShrinkKind,
    pub now: u32,
    pub was: u32,
}

/// What to do after an automatic upload.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AutoBackupRetention {
    /// Ids of the files to delete: this device's automatic copies, no others.
    pub delete: Vec<String>,
    /// Set when the newest copy is far smaller than the one before it.
    pub shrink: Option<BackupShrink>,
}

/// `backup_name` (`crema-backup-<model>-<stamp>.crema.zip`) as this install's
/// automatic backup holding `counts`:
/// `crema-backup-auto-<model>~<tag>-<stamp>-s240c238.crema.zip`. A name with no
/// stamp comes back with no label, so nothing can match it for pruning.
#[must_use]
pub fn auto_backup_file_name(backup_name: &str, tag: &str, counts: BackupCounts) -> String {
    let rest = backup_name
        .strip_prefix(BACKUP_PREFIX)
        .unwrap_or(backup_name);
    let stem = rest.strip_suffix(BACKUP_SUFFIX).unwrap_or(rest);
    let tagged = match split_stamp(stem) {
        Some((model, stamp)) => format!("{model}{TAG_SEPARATOR}{tag}-{stamp}"),
        None => stem.to_owned(),
    };
    let BackupCounts { shots, curves } = counts;
    format!("{AUTO_BACKUP_PREFIX}{tagged}-s{shots}c{curves}{BACKUP_SUFFIX}")
}

/// Read an automatic backup's name, from the end, as the label is free text. A
/// name that doesn't hold the whole form is `None`: nothing deletes a file it
/// can't read.
#[must_use]
pub fn parse_auto_backup_name(name: &str) -> Option<AutoBackupName> {
    let stem = name
        .strip_prefix(AUTO_BACKUP_PREFIX)?
        .strip_suffix(BACKUP_SUFFIX)?;
    let (rest, counts) = match split_counts(stem) {
        Some((rest, counts)) => (rest, Some(counts)),
        None => (stem, None),
    };
    Some(AutoBackupName {
        label: strip_stamp(rest)?.to_owned(),
        counts,
    })
}

/// What `name` says about a backup, automatic or manual, from either shell.
#[must_use]
pub fn describe_backup_file(name: &str) -> BackupFileInfo {
    if let Some(auto) = parse_auto_backup_name(name) {
        return BackupFileInfo {
            device: Some(device_of(&auto.label).to_owned()),
            automatic: true,
            counts: auto.counts,
        };
    }
    let stem = name.strip_prefix(BACKUP_PREFIX).and_then(|rest| {
        [BACKUP_SUFFIX, ".crema.jsonl", ".jsonl"]
            .iter()
            .find_map(|suffix| rest.strip_suffix(suffix))
    });
    BackupFileInfo {
        device: stem
            .and_then(|s| strip_stamp(s).or_else(|| strip_web_stamp(s)))
            .map(str::to_owned),
        automatic: false,
        counts: None,
    }
}

/// A label as a person reads it: the device's name, without its install's tag.
fn device_of(label: &str) -> &str {
    label
        .split_once(TAG_SEPARATOR)
        .map_or(label, |(device, _)| device)
}

/// `<label>-YYYYMMDDTHHMM`, the web's stamp, as the label.
fn strip_web_stamp(rest: &str) -> Option<&str> {
    let (label, stamp) = rest.rsplit_once('-')?;
    let (date, time) = stamp.split_once('T')?;
    let is_stamp = date.len() == 8 && time.len() == 4 && all_digits(date) && all_digits(time);
    (is_stamp && !label.is_empty()).then_some(label)
}

/// [`describe_backup_file`] as JSON, for the shells.
#[must_use]
pub fn describe_backup_file_json(name: &str) -> String {
    serde_json::to_string(&describe_backup_file(name)).unwrap_or_else(|_| "{}".to_owned())
}

/// `<rest>-s240c238` as `(rest, counts)`.
fn split_counts(stem: &str) -> Option<(&str, BackupCounts)> {
    let (rest, tail) = stem.rsplit_once("-s")?;
    let (shots, curves) = tail.split_once('c')?;
    let counts = BackupCounts {
        shots: digits(shots)?,
        curves: digits(curves)?,
    };
    // `-s…` right after a stamp: otherwise it's the end of a label.
    strip_stamp(rest).map(|_| (rest, counts))
}

/// `<label>-YYYYMMDD-HHMM` as the label, which can't be empty.
fn strip_stamp(rest: &str) -> Option<&str> {
    split_stamp(rest).map(|(label, _)| label)
}

/// `<label>-YYYYMMDD-HHMM` as `(label, stamp)`.
fn split_stamp(rest: &str) -> Option<(&str, &str)> {
    let (head, time) = rest.rsplit_once('-')?;
    let (label, date) = head.rsplit_once('-')?;
    let is_stamp = date.len() == 8 && time.len() == 4 && all_digits(date) && all_digits(time);
    (is_stamp && !label.is_empty()).then(|| (label, &rest[label.len() + 1..]))
}

fn all_digits(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
}

fn digits(s: &str) -> Option<u32> {
    if all_digits(s) { s.parse().ok() } else { None }
}

/// `files`' automatic copies with the same label as `uploaded`, parsed, newest
/// first.
fn own_copies<'a>(
    files: &'a [BackupFile],
    uploaded: &str,
) -> Vec<(&'a BackupFile, AutoBackupName)> {
    let Some(label) = parse_auto_backup_name(uploaded).map(|n| n.label) else {
        return Vec::new();
    };
    let mut own: Vec<_> = files
        .iter()
        .filter_map(|f| parse_auto_backup_name(&f.name).map(|n| (f, n)))
        .filter(|(_, n)| n.label == label)
        .collect();
    own.sort_by(|(a, _), (b, _)| {
        b.modified_ms
            .cmp(&a.modified_ms)
            .then_with(|| b.name.cmp(&a.name))
    });
    own
}

/// After uploading `uploaded`, which of `files` to prune, and whether it shrank.
/// See the module docs for the rule. At least the newest copy is always kept,
/// whatever `keep` says. `files` must be the whole listing: one cut short would
/// hide a fullest copy.
#[must_use]
pub fn auto_backup_retention(
    files: &[BackupFile],
    uploaded: &str,
    keep: u32,
) -> AutoBackupRetention {
    let own = own_copies(files, uploaded);
    let keep = keep.max(1);
    let newest_ms = own.first().map_or(0, |(f, _)| f.modified_ms);
    let newest_with = |count: fn(BackupCounts) -> u32| {
        // `own` is newest first, and `max_by_key` takes the last of equals.
        own.iter()
            .rev()
            .filter_map(|(f, n)| n.counts.map(|c| (f, count(c))))
            .max_by_key(|&(_, n)| n)
            .map(|(f, _)| f.id.as_str())
    };
    let most_shots = newest_with(|c| c.shots);
    let most_curves = newest_with(|c| c.curves);
    let no_counts = own
        .iter()
        .find(|(_, n)| n.counts.is_none())
        .filter(|(f, _)| newest_ms.saturating_sub(f.modified_ms) <= NO_COUNTS_MS)
        .map(|(f, _)| f.id.as_str());

    let is_slot = |id: &str| [most_shots, most_curves, no_counts].contains(&Some(id));
    let delete = own
        .iter()
        .enumerate()
        .filter(|(i, (f, _))| u32::try_from(*i).is_ok_and(|i| i >= keep) && !is_slot(f.id.as_str()))
        .map(|(_, (f, _))| f.id.clone())
        .collect();
    AutoBackupRetention {
        delete,
        shrink: shrink_of(&own, uploaded),
    }
}

/// Whether `uploaded` holds under half of the newest other copy with counts.
fn shrink_of(own: &[(&BackupFile, AutoBackupName)], uploaded: &str) -> Option<BackupShrink> {
    let now = parse_auto_backup_name(uploaded)?.counts?;
    let was = own
        .iter()
        .filter(|(f, _)| f.name != uploaded)
        .find_map(|(_, n)| n.counts)?;
    let halved = |now: u32, was: u32| u64::from(now) * 2 < u64::from(was);
    if halved(now.shots, was.shots) {
        Some(BackupShrink {
            kind: BackupShrinkKind::Shots,
            now: now.shots,
            was: was.shots,
        })
    } else if halved(now.curves, was.curves) {
        Some(BackupShrink {
            kind: BackupShrinkKind::Curves,
            now: now.curves,
            was: was.curves,
        })
    } else {
        None
    }
}

/// [`auto_backup_retention`] over JSON, for the shells.
///
/// # Errors
///
/// Returns the parse error when `files_json` isn't a list of [`BackupFile`].
pub fn auto_backup_retention_json(
    files_json: &str,
    uploaded: &str,
    keep: u32,
) -> Result<String, String> {
    let files: Vec<BackupFile> = serde_json::from_str(files_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&auto_backup_retention(&files, uploaded, keep)).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAY: i64 = 24 * 60 * 60 * 1000;
    const NOW: i64 = 1_000 * DAY;
    const TAG: &str = "a1b2c3";

    fn counts(shots: u32, curves: u32) -> BackupCounts {
        BackupCounts { shots, curves }
    }

    fn name(label: &str, day: i64, c: Option<BackupCounts>) -> String {
        let stamp = format!("2026{day:04}-0800");
        let manual = format!("crema-backup-{label}-{stamp}.crema.zip");
        match c {
            Some(c) => auto_backup_file_name(&manual, TAG, c),
            None => format!("{AUTO_BACKUP_PREFIX}{label}{TAG_SEPARATOR}{TAG}-{stamp}.crema.zip"),
        }
    }

    /// `(shots, curves)` per copy, oldest first, one a day up to `NOW`.
    fn copies(label: &str, list: &[(u32, u32)]) -> Vec<BackupFile> {
        let days = i64::try_from(list.len()).unwrap();
        list.iter()
            .zip(0_i64..)
            .map(|(&(s, c), i)| {
                let name = name(label, i, Some(counts(s, c)));
                BackupFile {
                    id: format!("{label}{i}"),
                    name,
                    modified_ms: NOW - (days - 1 - i) * DAY,
                }
            })
            .collect()
    }

    fn newest(files: &[BackupFile]) -> &str {
        &files.last().unwrap().name
    }

    #[test]
    fn a_name_round_trips() {
        let name = auto_backup_file_name(
            "crema-backup-Pixel-Tablet-20261103-0800.crema.zip",
            TAG,
            counts(240, 238),
        );
        assert_eq!(
            name,
            "crema-backup-auto-Pixel-Tablet~a1b2c3-20261103-0800-s240c238.crema.zip"
        );
        assert_eq!(
            parse_auto_backup_name(&name),
            Some(AutoBackupName {
                label: "Pixel-Tablet~a1b2c3".to_owned(),
                counts: Some(counts(240, 238)),
            })
        );
    }

    #[test]
    fn a_name_from_before_counts_has_none() {
        assert_eq!(
            parse_auto_backup_name("crema-backup-auto-Pixel-20261103-0800.crema.zip"),
            Some(AutoBackupName {
                label: "Pixel".to_owned(),
                counts: None,
            })
        );
    }

    #[test]
    fn a_label_can_look_like_anything_the_name_holds() {
        for label in ["a-s1c1", "x-20260101-0000", "s1c1", "a-20260101-0000-s1c1"] {
            let with = auto_backup_file_name(
                &format!("crema-backup-{label}-20261103-0800.crema.zip"),
                TAG,
                counts(2, 1),
            );
            let parsed = parse_auto_backup_name(&with).unwrap();
            assert_eq!(parsed.label, format!("{label}~{TAG}"));
            assert_eq!(parsed.counts, Some(counts(2, 1)));

            let without = format!("crema-backup-auto-{label}-20261103-0800.crema.zip");
            let parsed = parse_auto_backup_name(&without).unwrap();
            assert_eq!(parsed.label, label);
            assert_eq!(parsed.counts, None);
        }
    }

    #[test]
    fn a_name_it_cannot_read_is_none() {
        for name in [
            "crema-backup-Pixel-20261103-0800.crema.zip",
            "crema-backup-auto-20261103-0800.crema.zip",
            "crema-backup-auto-Pixel.crema.zip",
            "crema-backup-auto-Pixel-20261103-0800.jsonl",
            "crema-backup-auto-Pixel-2026110-0800.crema.zip",
            "crema-backup-auto-Pixel-20261103-0800-s99999999999c1.crema.zip",
        ] {
            assert_eq!(parse_auto_backup_name(name), None, "{name}");
        }
    }

    #[test]
    fn describes_a_name_from_either_shell() {
        let info = |name| describe_backup_file(name);
        assert_eq!(
            info("crema-backup-auto-Pixel-Tablet~a1b2c3-20261103-0800-s240c238.crema.zip"),
            BackupFileInfo {
                device: Some("Pixel-Tablet".to_owned()),
                automatic: true,
                counts: Some(counts(240, 238)),
            }
        );
        assert_eq!(
            info("crema-backup-auto-Pixel-20261103-0800.crema.zip"),
            BackupFileInfo {
                device: Some("Pixel".to_owned()),
                automatic: true,
                counts: None,
            }
        );
        for (name, device) in [
            (
                "crema-backup-Pixel-Tablet-20261103-0800.crema.zip",
                "Pixel-Tablet",
            ),
            ("crema-backup-web-20261103T0800.crema.zip", "web"),
            ("crema-backup-Pixel-20261103-0800.jsonl", "Pixel"),
            ("crema-backup-web-20261103T0800.crema.jsonl", "web"),
        ] {
            assert_eq!(
                info(name),
                BackupFileInfo {
                    device: Some(device.to_owned()),
                    automatic: false,
                    counts: None,
                },
                "{name}"
            );
        }
    }

    #[test]
    fn a_name_that_says_nothing_describes_nothing() {
        for name in [
            "my-backup.zip",
            "crema-backup.crema.zip",
            "crema-backup-20261103-0800.crema.zip",
        ] {
            assert_eq!(
                describe_backup_file(name),
                BackupFileInfo {
                    device: None,
                    automatic: false,
                    counts: None
                },
                "{name}"
            );
        }
    }

    #[test]
    fn keeps_the_newest_and_the_fullest() {
        // Fullest in the middle, then the counts fall away.
        let files = copies(
            "a",
            &[(5, 5), (240, 200), (6, 230), (5, 5), (5, 5), (5, 5), (5, 5)],
        );
        let plan = auto_backup_retention(&files, newest(&files), 3);
        assert_eq!(plan.delete, ["a3", "a0"]);
    }

    #[test]
    fn the_newest_of_equals_holds_a_slot() {
        let files = copies("a", &[(9, 9), (9, 9), (1, 1), (1, 1)]);
        let plan = auto_backup_retention(&files, newest(&files), 2);
        assert_eq!(plan.delete, ["a0"]);
    }

    #[test]
    fn stays_bounded_at_the_cap_with_curves_falling_a_little_a_day() {
        let list: Vec<(u32, u32)> = (0..280).map(|i| (300, 300 - i)).collect();
        let files = copies("a", &list);
        let plan = auto_backup_retention(&files, newest(&files), 3);
        assert_eq!(plan.delete.len(), 280 - 4);
    }

    #[test]
    fn another_devices_copies_are_left_alone() {
        let mut files = copies("tablet", &[(1, 1); 8]);
        files.extend(copies("phone", &[(1, 1); 8]));
        let plan = auto_backup_retention(&files, newest(&copies("phone", &[(1, 1); 8])), 3);
        assert_eq!(plan.delete.len(), 8 - 3);
        assert!(plan.delete.iter().all(|id| id.starts_with("phone")));
    }

    #[test]
    fn a_manual_copy_or_a_name_it_cannot_read_is_left_alone() {
        let mut files = copies("a", &[(1, 1); 5]);
        let others = [
            "crema-backup-a-20260101-0000.crema.zip",
            "crema-backup-auto-a.crema.zip",
        ];
        files.extend(others.iter().map(|n| BackupFile {
            id: (*n).to_owned(),
            name: (*n).to_owned(),
            modified_ms: 0,
        }));
        let plan = auto_backup_retention(&files, &name("a", 4, Some(counts(1, 1))), 1);
        assert_eq!(plan.delete.len(), 4, "{plan:?}");
        assert!(plan.delete.iter().all(|id| id.starts_with('a')));
    }

    #[test]
    fn nothing_goes_when_the_upload_has_no_label_to_compare() {
        let files = copies("a", &[(1, 1); 5]);
        assert_eq!(
            auto_backup_retention(&files, "b", 0),
            AutoBackupRetention::default()
        );
    }

    #[test]
    fn two_devices_of_one_model_leave_each_other_alone() {
        let mine = name("Pixel", 8, Some(counts(1, 1)));
        let theirs = BackupFile {
            id: "theirs".to_owned(),
            name: auto_backup_file_name(
                "crema-backup-Pixel-20260101-0800.crema.zip",
                "ffffff",
                counts(1, 1),
            ),
            modified_ms: NOW - 400 * DAY,
        };
        let mut files = copies("Pixel", &[(1, 1); 9]);
        files.push(theirs);
        let plan = auto_backup_retention(&files, &mine, 1);
        assert_eq!(plan.delete.len(), 8);
        assert!(!plan.delete.contains(&"theirs".to_owned()));
    }

    #[test]
    fn a_copy_from_before_the_tag_is_left_alone() {
        let mut files = copies("Pixel", &[(1, 1); 3]);
        files.extend((0..20).map(|i| BackupFile {
            id: format!("old{i}"),
            name: format!("{AUTO_BACKUP_PREFIX}Pixel-2026{i:04}-0800.crema.zip"),
            modified_ms: NOW - 200 * DAY,
        }));
        let plan = auto_backup_retention(&files, &name("Pixel", 2, Some(counts(1, 1))), 1);
        assert!(
            plan.delete.iter().all(|id| !id.starts_with("old")),
            "{plan:?}"
        );
    }

    #[test]
    fn keep_is_never_under_one() {
        let files = copies("a", &[(9, 9), (1, 1), (1, 1)]);
        let plan = auto_backup_retention(&files, newest(&files), 0);
        assert!(!plan.delete.contains(&"a2".to_owned()), "{plan:?}");
    }

    #[test]
    fn a_copy_with_no_counts_holds_a_slot_for_90_days() {
        let mut files = vec![BackupFile {
            id: "old".to_owned(),
            name: name("a", 0, None),
            modified_ms: NOW - 90 * DAY,
        }];
        files.extend(copies("a", &[(1, 1); 5]));
        let uploaded = name("a", 4, Some(counts(1, 1)));
        let deleted = |files: &[BackupFile]| {
            auto_backup_retention(files, &uploaded, 1)
                .delete
                .contains(&"old".to_owned())
        };
        assert!(!deleted(&files));
        files[0].modified_ms -= 1;
        assert!(deleted(&files));
    }

    #[test]
    fn ninety_days_is_counted_from_drive_not_the_devices_clock() {
        // Every time here is far from any clock: only the listing can say.
        let mut files: Vec<BackupFile> = copies("a", &[(1, 1); 3]);
        for f in &mut files {
            f.modified_ms -= 100_000 * DAY;
        }
        files.push(BackupFile {
            id: "old".to_owned(),
            name: name("a", 9, None),
            modified_ms: NOW - 100_000 * DAY - 5 * DAY,
        });
        let plan = auto_backup_retention(&files, &name("a", 2, Some(counts(1, 1))), 1);
        assert!(!plan.delete.contains(&"old".to_owned()), "{plan:?}");
    }

    #[test]
    fn only_the_newest_copy_with_no_counts_holds_that_slot() {
        let mut files: Vec<BackupFile> = (0_i64..4)
            .map(|i| BackupFile {
                id: format!("old{i}"),
                name: name("a", i, None),
                modified_ms: NOW - (10 - i) * DAY,
            })
            .collect();
        files.extend(copies("a", &[(1, 1); 2]));
        let plan = auto_backup_retention(&files, &name("a", 1, Some(counts(1, 1))), 1);
        assert_eq!(plan.delete, ["a0", "old2", "old1", "old0"]);
    }

    #[test]
    fn says_when_shots_or_curves_fall_under_half() {
        let shrink = |list: &[(u32, u32)]| {
            let files = copies("a", list);
            auto_backup_retention(&files, newest(&files), 3).shrink
        };
        assert_eq!(
            shrink(&[(240, 240), (12, 12)]),
            Some(BackupShrink {
                kind: BackupShrinkKind::Shots,
                now: 12,
                was: 240
            })
        );
        assert_eq!(
            shrink(&[(240, 238), (240, 40)]),
            Some(BackupShrink {
                kind: BackupShrinkKind::Curves,
                now: 40,
                was: 238
            })
        );
        assert_eq!(shrink(&[(240, 240), (120, 120)]), None, "exactly half");
        assert_eq!(shrink(&[(240, 240)]), None, "no copy to compare");
    }

    #[test]
    fn a_shrink_compares_what_was_uploaded() {
        // A clock behind Drive's: the upload isn't the newest copy listed.
        let mut files = copies("a", &[(240, 240), (240, 240)]);
        let uploaded = name("a", 7, Some(counts(12, 12)));
        files.push(BackupFile {
            id: "new".to_owned(),
            name: uploaded.clone(),
            modified_ms: NOW - 10 * DAY,
        });
        let shrink = auto_backup_retention(&files, &uploaded, 3).shrink;
        assert_eq!(shrink.map(|s| (s.now, s.was)), Some((12, 240)));
    }

    #[test]
    fn a_shrink_compares_within_a_device() {
        let mut files = copies("tablet", &[(240, 240)]);
        files.extend(copies("phone", &[(12, 12)]));
        let uploaded = &files[1].name.clone();
        assert_eq!(auto_backup_retention(&files, uploaded, 3).shrink, None);
    }

    #[test]
    fn a_copy_with_no_counts_between_two_is_stepped_over() {
        let mut files = copies("a", &[(240, 240)]);
        files.push(BackupFile {
            id: "old".to_owned(),
            name: name("a", 5, None),
            modified_ms: NOW + DAY,
        });
        files.push(BackupFile {
            id: "new".to_owned(),
            name: name("a", 6, Some(counts(12, 12))),
            modified_ms: NOW + 2 * DAY,
        });
        let plan = auto_backup_retention(&files, newest(&files), 3);
        assert_eq!(plan.shrink.map(|s| s.was), Some(240));
    }

    #[test]
    fn bad_json_is_an_error_not_an_empty_list() {
        assert!(auto_backup_retention_json("{", "x", 3).is_err());
    }
}
