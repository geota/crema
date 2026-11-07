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
//! only ever deletes its own copies: compared across labels, a phone's small
//! copy would look like a tablet's shrink, every day.

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

/// How long a copy with no counts is kept for, past the newest `keep`.
const NO_COUNTS_MS: i64 = 90 * 24 * 60 * 60 * 1000;

/// Copies kept on a new install.
const NEW_INSTALL_KEEP: u32 = 3;

/// Copies kept on an install that already had automatic backups: what it kept
/// before the setting existed, so an update deletes nothing with no preview.
const EXISTING_KEEP: u32 = 10;

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

/// `backup_name` (`crema-backup-<label>-<stamp>.crema.zip`) as an automatic
/// backup holding `counts`: `crema-backup-auto-<label>-<stamp>-s240c238.crema.zip`.
/// A name that isn't in that form comes back with only the prefix changed.
#[must_use]
pub fn auto_backup_file_name(backup_name: &str, counts: BackupCounts) -> String {
    let rest = backup_name
        .strip_prefix(BACKUP_PREFIX)
        .unwrap_or(backup_name);
    let stem = rest.strip_suffix(BACKUP_SUFFIX).unwrap_or(rest);
    let BackupCounts { shots, curves } = counts;
    format!("{AUTO_BACKUP_PREFIX}{stem}-s{shots}c{curves}{BACKUP_SUFFIX}")
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
    let (rest, time) = rest.rsplit_once('-')?;
    let (label, date) = rest.rsplit_once('-')?;
    let is_stamp = date.len() == 8 && time.len() == 4 && all_digits(date) && all_digits(time);
    (is_stamp && !label.is_empty()).then_some(label)
}

fn all_digits(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
}

fn digits(s: &str) -> Option<u32> {
    if all_digits(s) { s.parse().ok() } else { None }
}

/// The copies to keep when none is set yet, once. An install that has already
/// run automatic backups keeps what it did. So does a new install that finds
/// more of its own copies in Drive than a new install keeps: a reinstall, whose
/// old copies mustn't go with no preview. `own_copies` counts the upload just
/// made.
#[must_use]
pub fn initial_auto_backup_keep(first_auto_backup: bool, own_copies: u32) -> u32 {
    if first_auto_backup && own_copies <= NEW_INSTALL_KEEP {
        NEW_INSTALL_KEEP
    } else {
        EXISTING_KEEP
    }
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

/// How many of `files` are automatic copies from the device that made `uploaded`.
#[must_use]
pub fn own_auto_backup_count(files: &[BackupFile], uploaded: &str) -> u32 {
    u32::try_from(own_copies(files, uploaded).len()).unwrap_or(u32::MAX)
}

/// After uploading `uploaded`, which of `files` to delete, and whether it shrank.
/// See the module docs for the rule. `files` must be the whole listing: one cut
/// short would hide a fullest copy.
#[must_use]
pub fn auto_backup_retention(
    files: &[BackupFile],
    uploaded: &str,
    keep: u32,
    now_ms: i64,
) -> AutoBackupRetention {
    let own = own_copies(files, uploaded);
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
        .filter(|(f, _)| now_ms.saturating_sub(f.modified_ms) <= NO_COUNTS_MS)
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
        shrink: shrink_of(&own),
    }
}

/// Whether the newest copy holds under half of the counted copy before it.
fn shrink_of(own: &[(&BackupFile, AutoBackupName)]) -> Option<BackupShrink> {
    let mut counted = own.iter().map(|(_, n)| n.counts);
    let now = counted.next()??;
    let was = counted.flatten().next()?;
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
    now_ms: i64,
) -> Result<String, String> {
    let files: Vec<BackupFile> = serde_json::from_str(files_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&auto_backup_retention(&files, uploaded, keep, now_ms))
        .map_err(|e| e.to_string())
}

/// [`own_auto_backup_count`] over JSON, for the shells.
///
/// # Errors
///
/// Returns the parse error when `files_json` isn't a list of [`BackupFile`].
pub fn own_auto_backup_count_json(files_json: &str, uploaded: &str) -> Result<u32, String> {
    let files: Vec<BackupFile> = serde_json::from_str(files_json).map_err(|e| e.to_string())?;
    Ok(own_auto_backup_count(&files, uploaded))
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAY: i64 = 24 * 60 * 60 * 1000;
    const NOW: i64 = 1_000 * DAY;

    fn counts(shots: u32, curves: u32) -> BackupCounts {
        BackupCounts { shots, curves }
    }

    fn name(label: &str, day: i64, c: Option<BackupCounts>) -> String {
        let stamp = format!("2026{day:04}-0800");
        let manual = format!("crema-backup-{label}-{stamp}.crema.zip");
        match c {
            Some(c) => auto_backup_file_name(&manual, c),
            None => format!("{AUTO_BACKUP_PREFIX}{label}-{stamp}.crema.zip"),
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
            counts(240, 238),
        );
        assert_eq!(
            name,
            "crema-backup-auto-Pixel-Tablet-20261103-0800-s240c238.crema.zip"
        );
        assert_eq!(
            parse_auto_backup_name(&name),
            Some(AutoBackupName {
                label: "Pixel-Tablet".to_owned(),
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
                counts(2, 1),
            );
            let parsed = parse_auto_backup_name(&with).unwrap();
            assert_eq!(parsed.label, label);
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
    fn keeps_the_newest_and_the_fullest() {
        // Fullest in the middle, then the counts fall away.
        let files = copies(
            "a",
            &[(5, 5), (240, 200), (6, 230), (5, 5), (5, 5), (5, 5), (5, 5)],
        );
        let plan = auto_backup_retention(&files, newest(&files), 3, NOW);
        assert_eq!(plan.delete, ["a3", "a0"]);
    }

    #[test]
    fn the_newest_of_equals_holds_a_slot() {
        let files = copies("a", &[(9, 9), (9, 9), (1, 1), (1, 1)]);
        let plan = auto_backup_retention(&files, newest(&files), 2, NOW);
        assert_eq!(plan.delete, ["a0"]);
    }

    #[test]
    fn stays_bounded_at_the_cap_with_curves_falling_a_little_a_day() {
        let list: Vec<(u32, u32)> = (0..280).map(|i| (300, 300 - i)).collect();
        let files = copies("a", &list);
        let plan = auto_backup_retention(&files, newest(&files), 3, NOW);
        assert_eq!(plan.delete.len(), 280 - 4);
    }

    #[test]
    fn another_devices_copies_are_left_alone() {
        let mut files = copies("tablet", &[(1, 1); 8]);
        files.extend(copies("phone", &[(1, 1); 8]));
        let plan = auto_backup_retention(&files, newest(&copies("phone", &[(1, 1); 8])), 3, NOW);
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
        let plan = auto_backup_retention(&files, &name("a", 4, Some(counts(1, 1))), 1, NOW);
        assert_eq!(plan.delete.len(), 4, "{plan:?}");
        assert!(plan.delete.iter().all(|id| id.starts_with('a')));
    }

    #[test]
    fn nothing_goes_when_the_upload_has_no_label_to_compare() {
        let files = copies("a", &[(1, 1); 5]);
        assert_eq!(
            auto_backup_retention(&files, "b", 0, NOW),
            AutoBackupRetention::default()
        );
    }

    #[test]
    fn keeping_ten_deletes_nothing_the_old_rule_kept() {
        // The rule before this one: the newest 10, across every label.
        let mut files = copies("a", &[(1, 1); 9]);
        files.extend(copies("b", &[(1, 1); 9]));
        let plan = auto_backup_retention(
            &files,
            &name("a", 8, Some(counts(1, 1))),
            EXISTING_KEEP,
            NOW,
        );
        assert!(plan.delete.is_empty());
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
        assert!(
            !auto_backup_retention(&files, &uploaded, 1, NOW)
                .delete
                .contains(&"old".to_owned())
        );
        assert!(
            auto_backup_retention(&files, &uploaded, 1, NOW + 1)
                .delete
                .contains(&"old".to_owned())
        );
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
        let plan = auto_backup_retention(&files, &name("a", 1, Some(counts(1, 1))), 1, NOW);
        assert_eq!(plan.delete, ["a0", "old2", "old1", "old0"]);
    }

    #[test]
    fn says_when_shots_or_curves_fall_under_half() {
        let shrink = |list: &[(u32, u32)]| {
            let files = copies("a", list);
            auto_backup_retention(&files, newest(&files), 3, NOW).shrink
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
    fn a_shrink_compares_within_a_device() {
        let mut files = copies("tablet", &[(240, 240)]);
        files.extend(copies("phone", &[(12, 12)]));
        let uploaded = &files[1].name.clone();
        assert_eq!(auto_backup_retention(&files, uploaded, 3, NOW).shrink, None);
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
        let plan = auto_backup_retention(&files, newest(&files), 3, NOW + 2 * DAY);
        assert_eq!(plan.shrink.map(|s| s.was), Some(240));
    }

    #[test]
    fn only_a_new_install_with_nothing_in_drive_starts_at_three() {
        assert_eq!(initial_auto_backup_keep(true, 1), 3);
        assert_eq!(initial_auto_backup_keep(true, 3), 3);
        assert_eq!(initial_auto_backup_keep(true, 4), 10, "a reinstall");
        assert_eq!(initial_auto_backup_keep(false, 1), 10, "an update");
    }

    #[test]
    fn bad_json_is_an_error_not_an_empty_list() {
        assert!(auto_backup_retention_json("{", "x", 3, 0).is_err());
        assert!(own_auto_backup_count_json("{", "x").is_err());
    }
}
