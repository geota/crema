//! Drive retention stays bounded and holds on to the fullest copies, for any
//! history of counts (restore-safety design, "Backups can't eat each other").
//! The review's scenarios are random walks of these: at the 300 cap with curves
//! wandering, a delete a day, and two devices sharing Drive.

use de1_domain::{
    BackupCounts, BackupFile, auto_backup_file_name, auto_backup_retention, newest_auto_backup,
};
use proptest::prelude::*;

const DAY: i64 = 24 * 60 * 60 * 1000;
const START: i64 = 20_000 * DAY;
const MANUAL: &str = "crema-backup-tablet-20260101-0800.crema.zip";

/// One daily upload from a device.
#[derive(Debug, Clone)]
struct Upload {
    device: usize,
    counts: BackupCounts,
    /// An older build's name, with no counts.
    legacy: bool,
}

fn uploads() -> impl Strategy<Value = Vec<Upload>> {
    let step = (
        0_i32..2,
        -40_i32..40,
        -40_i32..40,
        prop::bool::weighted(0.1),
    );
    prop::collection::vec(step, 1..120).prop_map(|steps| {
        let mut shots = 150_i32;
        let mut curves = 150_i32;
        steps
            .into_iter()
            .map(|(device, ds, dc, legacy)| {
                shots = (shots + ds).clamp(0, 300);
                curves = (curves + dc).clamp(0, shots);
                Upload {
                    device: device as usize,
                    counts: BackupCounts {
                        shots: shots as u32,
                        curves: curves as u32,
                    },
                    legacy,
                }
            })
            .collect()
    })
}

fn stamp(day: usize) -> String {
    format!("2026{:04}-0800", day % 10_000)
}

fn file(device: usize, day: usize, upload: &Upload) -> BackupFile {
    let label = ["tablet", "phone"][device];
    let manual = format!("crema-backup-{label}-{}.crema.zip", stamp(day));
    let name = if upload.legacy {
        format!("crema-backup-auto-{label}~a1b2c3-{}.crema.zip", stamp(day))
    } else {
        auto_backup_file_name(&manual, "a1b2c3", upload.counts)
    };
    BackupFile {
        id: format!("{label}-{day}"),
        name,
        modified_ms: START + i64::try_from(day).unwrap() * DAY,
    }
}

fn counts_of(uploads: &[(BackupFile, BackupCounts, bool)], id: &str) -> BackupCounts {
    uploads.iter().find(|(f, _, _)| f.id == id).unwrap().1
}

proptest! {
    /// Day after day, as a device runs it: delete what the plan says, upload
    /// again, with a store damaged on some day or never.
    #[test]
    fn a_daily_run_stays_bounded_and_keeps_the_fullest(
        keep in 0_u32..12,
        days in uploads(),
        damage in prop::option::of(0_usize..120),
    ) {
        // Device 0 sees a damage on that day: before its upload, the shell pins
        // its newest copy, which can only be from before the damage.
        let mut pinned: Option<String> = None;
        let mut drive = vec![BackupFile {
            id: "manual".to_owned(),
            name: MANUAL.to_owned(),
            modified_ms: START,
        }];
        let mut history: Vec<(BackupFile, BackupCounts, bool)> = Vec::new();
        for (day, upload) in days.iter().enumerate() {
            let new = file(upload.device, day, upload);
            if upload.device == 0 && damage == Some(day) {
                pinned = newest_auto_backup(&drive, &new.name);
            }
            drive.push(new.clone());
            history.push((new.clone(), upload.counts, upload.legacy));

            let pin = if upload.device == 0 { pinned.as_deref() } else { None };
            let plan = auto_backup_retention(&drive, &new.name, keep, pin);

            // The pinned copy stays, however many thin copies follow.
            if let Some(pin) = pin {
                prop_assert!(!plan.delete.iter().any(|id| id == pin), "the pre-damage copy went");
            }
            for id in &plan.delete {
                prop_assert!(id != "manual", "a manual copy was deleted");
                prop_assert_eq!(
                    id.split('-').next(),
                    new.id.split('-').next(),
                    "another device's copy was deleted"
                );
            }
            drive.retain(|f| !plan.delete.contains(&f.id));

            let own: Vec<&BackupFile> = drive
                .iter()
                .filter(|f| f.id.split('-').next() == new.id.split('-').next())
                .collect();
            let bound = keep.max(1) as usize + 3;
            prop_assert!(own.len() <= bound, "{} copies, bound {bound}", own.len());
            prop_assert!(own.iter().any(|f| f.id == new.id), "the new copy went");

            // The fullest counted copy this device ever made is still there, or
            // an equal one is.
            let ever: Vec<BackupCounts> = history
                .iter()
                .filter(|(f, _, legacy)| !legacy && f.id.split('-').next() == new.id.split('-').next())
                .map(|(_, c, _)| *c)
                .collect();
            let held: Vec<BackupCounts> = own
                .iter()
                .filter(|f| history.iter().any(|(h, _, legacy)| h.id == f.id && !legacy))
                .map(|f| counts_of(&history, &f.id))
                .collect();
            let most = |of: fn(&BackupCounts) -> u32, list: &[BackupCounts]| list.iter().map(of).max();
            prop_assert_eq!(most(|c| c.shots, &held), most(|c| c.shots, &ever));
            prop_assert_eq!(most(|c| c.curves, &held), most(|c| c.curves, &ever));
        }
    }

    /// One listing, any shape: the newest copy of the most shots and of the most
    /// curves are never what goes.
    #[test]
    fn the_newest_fullest_copies_are_never_deleted(keep in 0_u32..6, days in uploads()) {
        let files: Vec<(BackupFile, BackupCounts, bool)> = days
            .iter()
            .enumerate()
            .map(|(day, u)| (file(0, day, u), u.counts, u.legacy))
            .collect();
        let drive: Vec<BackupFile> = files.iter().map(|(f, _, _)| f.clone()).collect();
        let (newest, _, _) = files.last().unwrap();
        let plan = auto_backup_retention(&drive, &newest.name, keep, None);

        let counted: Vec<_> = files.iter().filter(|(_, _, legacy)| !legacy).collect();
        for of in [|c: &BackupCounts| c.shots, |c: &BackupCounts| c.curves] {
            if let Some(most) = counted.iter().map(|(_, c, _)| of(c)).max() {
                let fullest = counted.iter().rev().find(|(_, c, _)| of(c) == most).unwrap();
                prop_assert!(!plan.delete.contains(&fullest.0.id));
            }
        }
    }
}
