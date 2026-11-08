//! # shot-merge
//!
//! What a pulled Visualizer shot may change on its local row, and how a tag
//! edit reaches Visualizer. Rule: every field has one owner, and a local edit
//! not yet acknowledged beats the remote. No clock comparison decides a shot
//! field.
//!
//! Tags go further. `tag_list` is `[]` on a free account and a lapsed one keeps
//! its tags, so `[]` carries no information, and a tag edit doesn't move
//! Visualizer's `updated_at`. So remote tags only ever add, and a push starts
//! from the server's current list: `(remote ∪ add) − remove`.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::visualizer_sync::WireShot;

/// A tag as Visualizer stores it (`Shot#tag_list=`): spaces squished, every
/// character outside ASCII `[A-Za-z0-9_]`, whitespace and `-` dropped, then
/// lowercased. `"Café"` is `"caf"`. Mirrored exactly, so a comparison here
/// agrees with the server's.
#[must_use]
pub fn normalize_tag(name: &str) -> String {
    let squished = name.split_whitespace().collect::<Vec<_>>().join(" ");
    squished
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-' || is_ruby_space(*c))
        .collect::<String>()
        .to_ascii_lowercase()
}

/// Ruby's `\s`: ASCII space, tab, newline, vertical tab, form feed, return.
fn is_ruby_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{0b}' | '\u{0c}' | '\r')
}

/// A tag list as Visualizer stores it: each entry split on commas, each name
/// normalised ([`normalize_tag`]), blank ones dropped, first of each kept.
#[must_use]
pub fn normalize_tags(list: &[String]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for name in list
        .iter()
        .flat_map(|entry| entry.split(','))
        .map(normalize_tag)
    {
        if !name.trim().is_empty() && !out.contains(&name) {
            out.push(name);
        }
    }
    out
}

/// Whether two names are one tag to Visualizer.
fn same_tag(a: &str, b: &str) -> bool {
    normalize_tag(a) == normalize_tag(b)
}

fn contains_tag(list: &[String], tag: &str) -> bool {
    list.iter().any(|t| same_tag(t, tag))
}

/// What a tag edit here changed, waiting to reach Visualizer.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct TagMarks {
    /// Tags added here.
    #[serde(default)]
    pub add: Vec<String>,
    /// Tags removed here.
    #[serde(default)]
    pub remove: Vec<String>,
}

impl TagMarks {
    /// Nothing waits.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.add.is_empty() && self.remove.is_empty()
    }
}

/// Record a tag edit from `before` to `after` onto `marks`. The latest edit of
/// a tag wins, and is always marked: re-adding a tag drops its remove mark and
/// marks the add, and the reverse. Opposite marks never just cancel, since the
/// first may be on its way to the server already, and its settle would then
/// leave nothing to undo it.
#[must_use]
pub fn mark_tag_edit(marks: &TagMarks, before: &[String], after: &[String]) -> TagMarks {
    let mut out = marks.clone();
    for tag in after.iter().filter(|t| !contains_tag(before, t)) {
        out.remove.retain(|t| !same_tag(t, tag));
        if !contains_tag(&out.add, tag) {
            out.add.push(tag.clone());
        }
    }
    for tag in before.iter().filter(|t| !contains_tag(after, t)) {
        out.add.retain(|t| !same_tag(t, tag));
        if !contains_tag(&out.remove, tag) {
            out.remove.push(tag.clone());
        }
    }
    out
}

/// The `tag_list` a push sends: the server's current list, plus what was added
/// here, minus what was removed here, so a change made elsewhere isn't undone.
/// `[]` is a real answer: it clears the remote tags.
#[must_use]
pub fn tag_push_list(remote: &[String], marks: &TagMarks) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for tag in remote.iter().chain(&marks.add) {
        if !contains_tag(&marks.remove, tag) && !contains_tag(&out, tag) {
            out.push(tag.clone());
        }
    }
    out
}

/// What the marks are after a push of `sent` built from `pushed_from`, given
/// the server's reply tags, in any order. `None` when the reply doesn't hold
/// what was sent: a tier signal (a free account ignores tags), and the marks
/// stay. Otherwise the entries sent are cleared, and any made since stay.
#[must_use]
pub fn settle_tag_push(
    current: &TagMarks,
    sent: &TagMarks,
    pushed: &[String],
    reply: &[String],
) -> Option<TagMarks> {
    // `tags.pluck(:name)` has no order, so this compares as sets.
    let (mut pushed, mut reply) = (normalize_tags(pushed), normalize_tags(reply));
    pushed.sort_unstable();
    reply.sort_unstable();
    if pushed != reply {
        return None;
    }
    Some(TagMarks {
        add: current
            .add
            .iter()
            .filter(|t| !contains_tag(&sent.add, t))
            .cloned()
            .collect(),
        remove: current
            .remove
            .iter()
            .filter(|t| !contains_tag(&sent.remove, t))
            .cloned()
            .collect(),
    })
}

/// `local` with the tags of `other` it lacks appended, keeping local spelling.
#[must_use]
pub fn tag_union(local: &[String], other: &[String]) -> Vec<String> {
    let mut out = local.to_vec();
    for tag in other {
        if !contains_tag(&out, tag) {
            out.push(tag.clone());
        }
    }
    out
}

/// What the merge reads of the local row.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotMergeInput {
    /// The local tags.
    #[serde(default)]
    pub tags: Vec<String>,
    /// A tag edit here waits to reach Visualizer.
    #[serde(default)]
    pub tags_pending: bool,
    /// The local barista.
    #[serde(default)]
    pub barista: Option<String>,
    /// A barista edit here waits to reach Visualizer.
    #[serde(default)]
    pub barista_pending: bool,
    /// The row has its telemetry.
    #[serde(default)]
    pub has_series: bool,
    /// The row has its recipe.
    #[serde(default)]
    pub has_profile: bool,
    /// A brew log: no telemetry by nature.
    #[serde(default)]
    pub is_brew: bool,
}

/// What a pull may change on a row, and nothing else: a merge can't touch a
/// rating, notes, a bean or a bag debit, because there is no field for them.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotPatch {
    /// The new tag list, when the remote adds any.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tags: Option<Vec<String>>,
    /// A barista to fill into a blank one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub barista: Option<String>,
    /// Take the pulled telemetry: the row has none.
    #[serde(default)]
    pub backfill_samples: bool,
    /// Fetch and store the recipe: the row has none and the detail offers one.
    #[serde(default)]
    pub fill_profile: bool,
    /// The remote row's `updated_at` (unix s), written last, once the rest landed.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[typeshare(serialized_as = "Option<I64>")]
    pub remote_version: Option<i64>,
}

/// Merge a pulled shot onto its local row. `profile_offered`: the detail
/// carries a `profile_url`. Idempotent, and it never removes a tag or changes a
/// field with an edit pending.
#[must_use]
pub fn merge_remote_shot(
    local: &ShotMergeInput,
    remote: &WireShot,
    profile_offered: bool,
) -> ShotPatch {
    let remote_tags = remote.tag_list.as_deref().unwrap_or(&[]);
    let tags = (!local.tags_pending)
        .then(|| tag_union(&local.tags, remote_tags))
        .filter(|union| union.len() != local.tags.len());
    let barista = (!local.barista_pending
        && local.barista.as_deref().is_none_or(|b| b.trim().is_empty()))
    .then(|| {
        remote
            .barista
            .as_deref()
            .map(str::trim)
            .filter(|b| !b.is_empty())
            .map(str::to_owned)
    })
    .flatten();
    ShotPatch {
        tags,
        barista,
        backfill_samples: !local.is_brew && !local.has_series,
        fill_profile: !local.has_profile && profile_offered,
        remote_version: remote.updated_at_ms.map(|ms| ms.div_euclid(1000)),
    }
}

/// Whether a row changed while its detail was fetched: the merge then skips it,
/// writing no version, since the local copy is the newer one (rule 4).
#[must_use]
pub fn edited_during_fetch(before: &ShotMergeInput, now: &ShotMergeInput) -> bool {
    before.tags != now.tags
        || before.tags_pending != now.tags_pending
        || before.barista != now.barista
        || before.barista_pending != now.barista_pending
}

/// [`merge_remote_shot`] for the bridges:
/// `{"local": ShotMergeInput, "remote": WireShot, "profileOffered": bool}` in,
/// a `ShotPatch` out.
///
/// # Errors
/// When the input doesn't parse.
pub fn merge_remote_shot_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct In {
        local: ShotMergeInput,
        remote: WireShot,
        #[serde(default)]
        profile_offered: bool,
    }
    let input: In = serde_json::from_str(payload).map_err(|e| e.to_string())?;
    serde_json::to_string(&merge_remote_shot(
        &input.local,
        &input.remote,
        input.profile_offered,
    ))
    .map_err(|e| e.to_string())
}

fn parse<T: serde::de::DeserializeOwned>(json: &str) -> Result<T, String> {
    serde_json::from_str(json).map_err(|e| e.to_string())
}

/// [`normalize_tags`] for the bridges: a JSON string array in and out.
///
/// # Errors
/// When the input isn't a string array.
pub fn normalize_tags_json(list: &str) -> Result<String, String> {
    serde_json::to_string(&normalize_tags(&parse::<Vec<String>>(list)?)).map_err(|e| e.to_string())
}

/// [`mark_tag_edit`] for the bridges: `TagMarks` and two string arrays in, `TagMarks` out.
///
/// # Errors
/// When an input doesn't parse.
pub fn mark_tag_edit_json(marks: &str, before: &str, after: &str) -> Result<String, String> {
    let marks: TagMarks = parse(marks)?;
    serde_json::to_string(&mark_tag_edit(
        &marks,
        &parse::<Vec<String>>(before)?,
        &parse::<Vec<String>>(after)?,
    ))
    .map_err(|e| e.to_string())
}

/// [`tag_push_list`] for the bridges: a string array and `TagMarks` in, a string array out.
///
/// # Errors
/// When an input doesn't parse.
pub fn tag_push_list_json(remote: &str, marks: &str) -> Result<String, String> {
    serde_json::to_string(&tag_push_list(
        &parse::<Vec<String>>(remote)?,
        &parse(marks)?,
    ))
    .map_err(|e| e.to_string())
}

/// [`settle_tag_push`] for the bridges: `TagMarks` or `null` out.
///
/// # Errors
/// When an input doesn't parse.
pub fn settle_tag_push_json(
    current: &str,
    sent: &str,
    pushed: &str,
    reply: &str,
) -> Result<String, String> {
    let settled = settle_tag_push(
        &parse(current)?,
        &parse(sent)?,
        &parse::<Vec<String>>(pushed)?,
        &parse::<Vec<String>>(reply)?,
    );
    serde_json::to_string(&settled).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tags(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| (*s).to_owned()).collect()
    }

    fn remote(tag_list: Option<&[&str]>) -> WireShot {
        WireShot {
            id: "r-1".to_owned(),
            clock: 0,
            duration_ms: 0,
            profile_title: None,
            final_weight_g: None,
            notes: None,
            rating: None,
            updated_at_ms: Some(1_700_000_123_456),
            tag_list: tag_list.map(tags),
            bean: None,
            barista: None,
        }
    }

    /// Fixture from `Shot#tag_list=`: `split(",")`, `squish`,
    /// `gsub(/[^\w\s-]/, "")`, `downcase`, `compact_blank`, `uniq`.
    #[test]
    fn the_bridges_speak_json() {
        assert_eq!(
            normalize_tags_json(r#"["Café, x"]"#).unwrap(),
            r#"["caf","x"]"#
        );
        assert_eq!(
            mark_tag_edit_json("{}", r#"["a"]"#, r#"["b"]"#).unwrap(),
            r#"{"add":["b"],"remove":["a"]}"#
        );
        assert_eq!(
            tag_push_list_json(r#"["a"]"#, r#"{"remove":["a"]}"#).unwrap(),
            "[]"
        );
        assert_eq!(
            settle_tag_push_json("{}", "{}", r#"["a"]"#, "[]").unwrap(),
            "null"
        );
        assert!(merge_remote_shot_json("nope").is_err());
    }

    #[test]
    fn normalize_tag_mirrors_the_server() {
        for (name, stored) in [
            ("Café", "caf"),
            ("  Daily   Driver ", "daily driver"),
            ("light-roast", "light-roast"),
            ("A & B", "a  b"),
            ("Ünïcödé", "ncd"),
            ("snake_case", "snake_case"),
            ("tab\tsep", "tab sep"),
            ("😀", ""),
        ] {
            assert_eq!(normalize_tag(name), stored, "{name:?}");
        }
        assert_eq!(
            normalize_tags(&tags(&["a,b", "B", "😀", "c , a", "d &"])),
            tags(&["a", "b", "c", "d "])
        );
    }

    #[test]
    fn free_pull_keeps_tags() {
        let local = ShotMergeInput {
            tags: tags(&["mine"]),
            ..ShotMergeInput::default()
        };
        for list in [Some(&[][..]), None] {
            assert_eq!(
                merge_remote_shot(&local, &remote(list), false).tags,
                None,
                "{list:?}"
            );
        }
    }

    #[test]
    fn a_pull_adds_remote_tags_in_local_spelling() {
        let local = ShotMergeInput {
            tags: tags(&["Light Roast"]),
            ..ShotMergeInput::default()
        };
        let patch = merge_remote_shot(&local, &remote(Some(&["light roast", "dial-in"])), false);
        assert_eq!(patch.tags, Some(tags(&["Light Roast", "dial-in"])));
    }

    #[test]
    fn a_pending_tag_edit_wins() {
        let local = ShotMergeInput {
            tags: tags(&["mine"]),
            tags_pending: true,
            ..ShotMergeInput::default()
        };
        assert_eq!(
            merge_remote_shot(&local, &remote(Some(&["theirs"])), false).tags,
            None
        );
    }

    #[test]
    fn barista_fills_only_a_blank_one_with_no_edit_pending() {
        let mut wire = remote(None);
        wire.barista = Some(" Ana ".to_owned());
        let blank = ShotMergeInput::default();
        assert_eq!(
            merge_remote_shot(&blank, &wire, false).barista,
            Some("Ana".to_owned())
        );
        let pending = ShotMergeInput {
            barista_pending: true,
            ..ShotMergeInput::default()
        };
        assert_eq!(merge_remote_shot(&pending, &wire, false).barista, None);
        let set = ShotMergeInput {
            barista: Some("Bo".to_owned()),
            ..ShotMergeInput::default()
        };
        assert_eq!(merge_remote_shot(&set, &wire, false).barista, None);
    }

    #[test]
    fn telemetry_and_recipe_fill_only_what_is_missing() {
        let bare = ShotMergeInput::default();
        let patch = merge_remote_shot(&bare, &remote(None), true);
        assert!(patch.backfill_samples && patch.fill_profile);
        let brew = ShotMergeInput {
            is_brew: true,
            ..ShotMergeInput::default()
        };
        assert!(!merge_remote_shot(&brew, &remote(None), true).backfill_samples);
        let full = ShotMergeInput {
            has_series: true,
            has_profile: true,
            ..ShotMergeInput::default()
        };
        let patch = merge_remote_shot(&full, &remote(None), true);
        assert!(!patch.backfill_samples && !patch.fill_profile);
        assert!(
            !merge_remote_shot(&bare, &remote(None), false).fill_profile,
            "only when offered"
        );
    }

    #[test]
    fn the_version_is_the_remote_second() {
        assert_eq!(
            merge_remote_shot(&ShotMergeInput::default(), &remote(None), false).remote_version,
            Some(1_700_000_123)
        );
    }

    #[test]
    fn the_latest_edit_of_a_tag_wins_and_stays_marked() {
        let marks = mark_tag_edit(&TagMarks::default(), &tags(&["a", "b"]), &tags(&["a", "c"]));
        assert_eq!(
            marks,
            TagMarks {
                add: tags(&["c"]),
                remove: tags(&["b"])
            }
        );
        let back = mark_tag_edit(&marks, &tags(&["a", "c"]), &tags(&["a", "b"]));
        assert_eq!(
            back,
            TagMarks {
                add: tags(&["b"]),
                remove: tags(&["c"])
            }
        );
    }

    #[test]
    fn a_tag_re_added_while_its_removal_is_out_is_kept() {
        let sent = mark_tag_edit(&TagMarks::default(), &tags(&["a", "b"]), &tags(&["a"]));
        // The push of that removal is out when "b" comes back.
        let current = mark_tag_edit(&sent, &tags(&["a"]), &tags(&["a", "b"]));
        let pushed = tag_push_list(&tags(&["a", "b"]), &sent);
        let settled =
            settle_tag_push(&current, &sent, &pushed, &pushed).expect("the reply matched");
        assert_eq!(
            settled,
            TagMarks {
                add: tags(&["b"]),
                remove: vec![]
            },
            "the re-add still goes up"
        );
        assert_eq!(tag_push_list(&pushed, &settled), tags(&["a", "b"]));
    }

    #[test]
    fn tag_push_keeps_website_changes() {
        let marks = TagMarks {
            add: tags(&["mine"]),
            remove: tags(&["gone"]),
        };
        // The website added "theirs" and still has "gone".
        assert_eq!(
            tag_push_list(&tags(&["theirs", "Gone"]), &marks),
            tags(&["theirs", "mine"])
        );
    }

    #[test]
    fn tag_clear_reaches_remote() {
        let marks = TagMarks {
            add: vec![],
            remove: tags(&["only"]),
        };
        assert_eq!(
            tag_push_list(&tags(&["only"]), &marks),
            Vec::<String>::new()
        );
    }

    #[test]
    fn a_matching_reply_clears_only_what_was_sent() {
        let sent = TagMarks {
            add: tags(&["a"]),
            remove: vec![],
        };
        let since = TagMarks {
            add: tags(&["a", "b"]),
            remove: vec![],
        };
        assert_eq!(
            settle_tag_push(&since, &sent, &tags(&["a"]), &tags(&["A"])),
            Some(TagMarks {
                add: tags(&["b"]),
                remove: vec![]
            })
        );
    }

    #[test]
    fn tag_400_is_a_tier_signal() {
        let sent = TagMarks {
            add: tags(&["a"]),
            remove: vec![],
        };
        assert_eq!(
            settle_tag_push(&sent, &sent, &tags(&["a"]), &[]),
            None,
            "a free account ignored them"
        );
    }

    #[test]
    fn a_reply_in_another_order_settles() {
        let sent = TagMarks {
            add: tags(&["b"]),
            remove: vec![],
        };
        assert_eq!(
            settle_tag_push(&sent, &sent, &tags(&["a", "b"]), &tags(&["B", "a"])),
            Some(TagMarks::default())
        );
    }

    #[test]
    fn tag_only_patch_during_fetch_not_reverted() {
        let before = ShotMergeInput {
            tags: tags(&["a"]),
            tags_pending: true,
            ..ShotMergeInput::default()
        };
        // The PATCH landed during the fetch and cleared the mark.
        let now = ShotMergeInput {
            tags: tags(&["a"]),
            ..ShotMergeInput::default()
        };
        assert!(edited_during_fetch(&before, &now));
        assert!(!edited_during_fetch(&now, &now));
    }

    /// Property 3, over seeded random inputs: the merge never removes a tag,
    /// never changes a pending field, and is idempotent.
    #[test]
    fn merge_never_removes_a_tag_or_touches_a_pending_field() {
        let names = ["a", "B", "c d", "Café", "x-y", "", "a"];
        let mut rng = crate::pull_run::tests_support::Rng::new(7);
        for _ in 0..2_000 {
            let pick = |rng: &mut crate::pull_run::tests_support::Rng| -> Vec<String> {
                (0..rng.below(4))
                    .map(|_| names[rng.index(names.len())].to_owned())
                    .collect()
            };
            let local = ShotMergeInput {
                tags: pick(&mut rng),
                tags_pending: rng.below(2) == 0,
                barista: (rng.below(2) == 0).then(|| "Ana".to_owned()),
                barista_pending: rng.below(2) == 0,
                has_series: rng.below(2) == 0,
                has_profile: rng.below(2) == 0,
                is_brew: rng.below(4) == 0,
            };
            let mut wire = remote(Some(&[]));
            wire.tag_list = (rng.below(3) != 0).then(|| pick(&mut rng));
            wire.barista = (rng.below(2) == 0).then(|| "Bo".to_owned());
            let patch = merge_remote_shot(&local, &wire, rng.below(2) == 0);
            let after = patch.tags.clone().unwrap_or_else(|| local.tags.clone());
            assert!(
                local.tags.iter().all(|t| after.contains(t)),
                "{local:?} {wire:?}"
            );
            if local.tags_pending {
                assert_eq!(patch.tags, None);
            }
            if local.barista_pending {
                assert_eq!(patch.barista, None);
            }
            let again = ShotMergeInput {
                tags: after,
                barista: patch.barista.clone().or(local.barista.clone()),
                ..local.clone()
            };
            let second = merge_remote_shot(&again, &wire, false);
            assert_eq!(second.tags, None, "idempotent: {local:?} {wire:?}");
            assert_eq!(second.barista, None);
        }
    }
}
