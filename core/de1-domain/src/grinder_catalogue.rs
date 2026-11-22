//! # grinder_catalogue
//!
//! Well-known grinders, built in and shared by both shells (#488, "Bag
//! grinds"): maker, model, the names people type for them, and the kind
//! (electric or hand).
//!
//! **Only grinders we are sure of** (#488): an entry is here only when its
//! maker's name for it, its popularity among home espresso or high-end
//! filter brewers, and its range are all certain. Every entry carries a [`VerifiedScale`] checked
//! against the maker's manual or page, cited in [`VerifiedScale::source`].
//! A grinder whose range can't be checked from its maker, or whose dial isn't
//! one numeric range (Baratza macro plus micro, 1Zpresso
//! rotation.number.click, Fellow Opus with its inner ring), isn't listed: the
//! user creates it by name and it gets the default scale (0–20, step 0.1).
//! Each entry's comment quotes what its source says.
//!
//! **Owned grinders never change with the list.** A [`crate::grinder::Grinder`]
//! stores its own name and scale; its `preset_id` only links back. When an
//! entry leaves the list, a grinder added from it keeps everything it has,
//! and [`crate::grinder::Grinder::catalogue_entry`] answers `None`.
//!
//! **Two kinds of matching.**
//! - [`catalogue_entry_for_name`] is strict: the name, ignoring case,
//!   accents, spaces and punctuation, is the entry's name or one of its
//!   aliases. Imports and the migration use it, so "niche", "Niche Zero" and
//!   "NicheZero" become one grinder, while "EK43" never merges into
//!   "EK43 S".
//! - [`crate::grinder::match_grinders`] is the type-or-pick box: typo
//!   tolerant ("nihce" finds Niche Zero), the user picks from the list.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::grinder::GrindScale;

/// Electric or hand.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum GrinderKind {
    /// A motor grinder.
    Electric,
    /// A hand grinder.
    Hand,
}

/// A range checked against the maker's manual or product page.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct VerifiedScale {
    /// Lowest setting.
    pub min: f32,
    /// Highest setting.
    pub max: f32,
    /// Step between settings.
    pub step: f32,
    /// Unit label; empty for none.
    pub unit: &'static str,
    /// The maker's page the range was checked against.
    pub source: &'static str,
}

impl VerifiedScale {
    /// As a [`GrindScale`].
    #[must_use]
    pub fn scale(&self) -> GrindScale {
        GrindScale {
            min: self.min,
            max: self.max,
            step: self.step,
            unit: self.unit.to_owned(),
        }
    }
}

/// One built-in grinder.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CatalogueGrinder {
    /// Stable id, stored in [`crate::grinder::Grinder::preset_id`]. The ten
    /// #494 presets keep their ids.
    pub id: &'static str,
    /// The maker: `"Niche"`.
    pub maker: &'static str,
    /// The model: `"Zero"`.
    pub model: &'static str,
    /// Other names that name it, matched like [`Self::name`].
    pub aliases: &'static [&'static str],
    /// Electric or hand.
    pub kind: GrinderKind,
    /// Its range, checked against the maker.
    pub scale: VerifiedScale,
}

impl CatalogueGrinder {
    /// Display name: maker and model, `"Niche Zero"`.
    #[must_use]
    pub fn name(&self) -> String {
        format!("{} {}", self.maker, self.model)
    }

    /// Every name it answers to: its name, then its aliases.
    #[must_use]
    pub fn names(&self) -> Vec<String> {
        std::iter::once(self.name())
            .chain(self.aliases.iter().map(|a| (*a).to_owned()))
            .collect()
    }

    /// The scale a grinder added from it starts with: its verified one.
    #[must_use]
    pub fn starting_scale(&self) -> GrindScale {
        self.scale.scale()
    }
}

/// What the strict match compares: lowercased, accents folded, letters,
/// digits and `+` only. `"Niche-Zero "` and `"nichezero"` are the same key.
#[must_use]
pub fn compact_key(name: &str) -> String {
    crate::bean_search::fold_text(name.trim())
        .chars()
        .filter(|c| c.is_alphanumeric() || *c == '+')
        .collect()
}

/// The entry `name` names strictly (its name or an alias, see the module
/// docs), if any.
#[must_use]
pub fn catalogue_entry_for_name(name: &str) -> Option<&'static CatalogueGrinder> {
    let key = compact_key(name);
    if key.is_empty() {
        return None;
    }
    GRINDER_CATALOGUE
        .iter()
        .find(|g| g.names().iter().any(|n| compact_key(n) == key))
}

/// The entry with this id, if any.
#[must_use]
pub fn catalogue_entry(id: &str) -> Option<&'static CatalogueGrinder> {
    GRINDER_CATALOGUE.iter().find(|g| g.id == id)
}

/// A catalogue entry as the shells list it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogueGrinderInfo {
    /// [`CatalogueGrinder::id`].
    pub id: String,
    /// Display name.
    pub name: String,
    /// The maker.
    pub maker: String,
    /// The model.
    pub model: String,
    /// Other names that name it.
    pub aliases: Vec<String>,
    /// Electric or hand.
    pub kind: GrinderKind,
    /// Its verified scale. Every entry has one now; it stays optional so the
    /// shells' "Range not set" path keeps compiling and the JSON shape holds.
    pub scale: Option<GrindScale>,
    /// Where the scale was checked; set whenever `scale` is.
    pub source: Option<String>,
}

impl From<&CatalogueGrinder> for CatalogueGrinderInfo {
    fn from(g: &CatalogueGrinder) -> Self {
        Self {
            id: g.id.to_owned(),
            name: g.name(),
            maker: g.maker.to_owned(),
            model: g.model.to_owned(),
            aliases: g.aliases.iter().map(|a| (*a).to_owned()).collect(),
            kind: g.kind,
            scale: Some(g.scale.scale()),
            source: Some(g.scale.source.to_owned()),
        }
    }
}

/// The whole catalogue, in list order.
#[must_use]
pub fn grinder_catalogue() -> Vec<CatalogueGrinderInfo> {
    GRINDER_CATALOGUE
        .iter()
        .map(CatalogueGrinderInfo::from)
        .collect()
}

/// [`grinder_catalogue`] as JSON.
#[must_use]
pub fn grinder_catalogue_json() -> String {
    serde_json::to_string(&grinder_catalogue()).unwrap_or_default()
}

use GrinderKind::Electric as E;

const fn entry(
    id: &'static str,
    maker: &'static str,
    model: &'static str,
    aliases: &'static [&'static str],
    kind: GrinderKind,
    scale: VerifiedScale,
) -> CatalogueGrinder {
    CatalogueGrinder {
        id,
        maker,
        model,
        aliases,
        kind,
        scale,
    }
}

const fn scale(
    min: f32,
    max: f32,
    step: f32,
    unit: &'static str,
    source: &'static str,
) -> VerifiedScale {
    VerifiedScale {
        min,
        max,
        step,
        unit,
        source,
    }
}

/// The catalogue: every entry's range checked against its maker (see the
/// module docs), the source quoted above each entry.
pub const GRINDER_CATALOGUE: &[CatalogueGrinder] = &[
    // `bladeGap`, the grind size in μm, 0–999.
    entry(
        "bookoo-motto-80",
        "BOOKOO",
        "Motto 80",
        &["Motto 80", "Motto80", "BOOKOO MT80", "MT80"],
        E,
        scale(
            0.0,
            999.0,
            1.0,
            "μm",
            "https://github.com/BooKooCode/OpenSource/tree/main/bookoo_motto80",
        ),
    ),
    // The manual's dial drawing runs 0 to 50; stepless, whole-number marks.
    entry(
        "niche-zero",
        "Niche",
        "Zero",
        &["Niche", "NZ"],
        E,
        scale(
            0.0,
            50.0,
            1.0,
            "",
            "https://cdn.shopify.com/s/files/1/0081/2076/2450/files/Niche_Zero_IFU.pdf",
        ),
    ),
    // '0' is where the burrs touch; the dial drawing runs to 70, one tick per unit.
    entry(
        "niche-duo",
        "Niche",
        "Duo",
        &["Niche Duo grinder"],
        E,
        scale(
            0.0,
            70.0,
            1.0,
            "",
            "https://cdn.shopify.com/s/files/1/0081/2076/2450/files/N685_INSTRUCTION_BOOK_ROW_83F_1_080323_WEB.pdf",
        ),
    ),
    // "from very coarse (grind setting 40) to very fine (grind setting 1)".
    entry(
        "baratza-encore",
        "Baratza",
        "Encore",
        &["Encore"],
        E,
        scale(
            1.0,
            40.0,
            1.0,
            "",
            "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG485/Encore+Documents/manual-encore-en-v4-3-120122-1.pdf",
        ),
    ),
    // "the espresso range (settings 1-20) and the filter coffee range (settings 21-40)".
    entry(
        "baratza-encore-esp",
        "Baratza",
        "Encore ESP",
        &["Encore ESP"],
        E,
        scale(
            1.0,
            40.0,
            1.0,
            "",
            "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG495/Encore+ESP+Documents/manual-encoreesp-v1-0-en-010923.pdf",
        ),
    ),
    // "from very coarse (grind setting 40) to very fine (grind setting 1)".
    entry(
        "baratza-virtuoso-plus",
        "Baratza",
        "Virtuoso+",
        &[
            "Virtuoso+",
            "Virtuoso",
            "Virtuoso Plus",
            "Baratza Virtuoso Plus",
        ],
        E,
        scale(
            1.0,
            40.0,
            1.0,
            "",
            "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG587/Virtuoso%2B+Documents/manual-virtuosoplus-en-v1-6.pdf",
        ),
    ),
    // "The adjustment ring moves from coarse (31) to fine (1)"; macro ring only.
    entry(
        "baratza-sette-30",
        "Baratza",
        "Sette 30",
        &["Sette 30"],
        E,
        scale(
            1.0,
            31.0,
            1.0,
            "",
            "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG1130/Sette+30+Documents/manual-sette30-en-v3.pdf",
        ),
    ),
    // "60 custom grind settings"; the inner upper-burr setting only recalibrates.
    entry(
        "breville-smart-grinder-pro",
        "Breville",
        "Smart Grinder Pro",
        &[
            "Smart Grinder Pro",
            "Sage Smart Grinder Pro",
            "SGP",
            "BCG820",
        ],
        E,
        scale(
            1.0,
            60.0,
            1.0,
            "",
            "https://assets.breville.com/BCG820/BCG820_USCM_IB_L26_LR.pdf",
        ),
    ),
    // "60 grind settings, from espresso to plunger".
    entry(
        "breville-dose-control-pro",
        "Breville",
        "Dose Control Pro",
        &["Dose Control Pro", "Sage Dose Control Pro", "BCG600"],
        E,
        scale(
            1.0,
            60.0,
            1.0,
            "",
            "https://assets.breville.com/Instruction-Booklets/ANZ/BCG600_instruction_manual.pdf",
        ),
    ),
    // Stepless; the scale's finest setting is "01", the coarsest "35".
    entry(
        "mahlkonig-x54",
        "Mahlkönig",
        "X54",
        &["X54", "Mahlkonig X54", "Mahlkoenig X54"],
        E,
        scale(
            1.0,
            35.0,
            1.0,
            "",
            "https://downloads.mahlkoenig.de/Service/Mahlkoenig_X54_Allround_Home_Grinder_Original_instruction_manual_EN.pdf",
        ),
    ),
    // "The finest setting is "0" and the coarsest setting is "16"" (one
    // manual for the EK43 and EK43 S, scale as fitted since 2020); stepless,
    // Fig. G draws ten ticks per number.
    entry(
        "mahlkonig-ek43",
        "Mahlkönig",
        "EK43",
        &["EK43", "EK 43", "Mahlkonig EK43", "Mahlkoenig EK43"],
        E,
        scale(
            0.0,
            16.0,
            0.1,
            "",
            "https://www.lamarzoccousa.com/documents/mahlkonig-ek43-ek43-s-operating-manual/",
        ),
    ),
    entry(
        "mahlkonig-ek43-s",
        "Mahlkönig",
        "EK43 S",
        &["EK43 S", "Mahlkonig EK43 S", "Mahlkoenig EK43 S"],
        E,
        scale(
            0.0,
            16.0,
            0.1,
            "",
            "https://www.lamarzoccousa.com/documents/mahlkonig-ek43-ek43-s-operating-manual/",
        ),
    ),
    // "one full turn covers the whole range — 16 marked increments, espresso
    // between 1 and 4 … French press 13 to 16. It stops at both ends";
    // stepless, and the maker gives no subdivision: 0.1, as for the EK43.
    entry(
        "timemore-sculptor-064s",
        "Timemore",
        "Sculptor 064S",
        &[
            "Sculptor 064S",
            "064S",
            "Timemore 064S",
            "Sculptor 064S II",
            "Sculptor 064S Gen II",
        ],
        E,
        scale(
            0.0,
            16.0,
            0.1,
            "",
            "https://www.timemore.com/blogs/read-our-blog/sculptor-064s-gen-ii",
        ),
    ),
    // "There are 41 grind size setting"; the chart runs espresso "3-7" to
    // steep "39-41". The WSFB-100S, and the WSFBS-200B with a scale.
    entry(
        "wilfa-uniform",
        "Wilfa",
        "Uniform",
        &[
            "Wilfa Uniform",
            "Wilfa Svart Uniform",
            "WSFB-100S",
            "WSFBS-200B",
        ],
        E,
        scale(
            1.0,
            41.0,
            1.0,
            "",
            "https://wilfa.com/pages/uniform-wsfb-use",
        ),
    ),
    // "(1= fine, 11= coarse)"; the quick start's dial runs 1 to 11 with two
    // ticks between numbers, 31 settings: a step of a third, stored as the
    // nearest f32 to 1/3 (30 steps from 1 to 11).
    entry(
        "fellow-ode",
        "Fellow",
        "Ode",
        &[
            "Ode",
            "Ode Brew Grinder",
            "Fellow Ode Brew Grinder",
            "Fellow Ode Gen 1",
            "Ode Gen 1",
        ],
        E,
        scale(
            1.0,
            11.0,
            1.0 / 3.0,
            "",
            "https://cdn.shopify.com/s/files/1/0057/6235/1219/files/Ode-BrewGrinder_QuickStartGuide_ae5a2762-cc34-4f50-9f4d-62b11d28cf1a.pdf",
        ),
    ),
    // The manual: "(1 = fine, 11 = coarse)", its Grind Guide dial 1 to 11
    // with two ticks between numbers; Fellow: "Ode Gen 2's 31 grind
    // settings" (fellowproducts.com/pages/compare-grinders). Thirds, as Gen 1.
    entry(
        "fellow-ode-gen-2",
        "Fellow",
        "Ode Gen 2",
        &[
            "Ode Gen 2",
            "Ode 2",
            "Fellow Ode 2",
            "Ode Brew Grinder Gen 2",
            "Fellow Ode Brew Grinder Gen 2",
        ],
        E,
        scale(
            1.0,
            11.0,
            1.0 / 3.0,
            "",
            "https://www.seattlecoffeegear.com/cdn/shop/files/fellow-ode-2-user-manual.pdf",
        ),
    ),
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_are_unique() {
        for g in GRINDER_CATALOGUE {
            assert_eq!(
                GRINDER_CATALOGUE.iter().filter(|h| h.id == g.id).count(),
                1,
                "{}",
                g.id
            );
        }
    }

    #[test]
    fn no_two_entries_answer_to_the_same_name() {
        for g in GRINDER_CATALOGUE {
            for name in g.names() {
                let hits: Vec<&str> = GRINDER_CATALOGUE
                    .iter()
                    .filter(|h| {
                        h.names()
                            .iter()
                            .any(|n| compact_key(n) == compact_key(&name))
                    })
                    .map(|h| h.id)
                    .collect();
                assert_eq!(hits, [g.id], "{name}");
            }
        }
    }

    #[test]
    fn every_entry_has_a_usable_scale_and_a_source() {
        for g in GRINDER_CATALOGUE {
            let s = g.scale;
            assert!(s.source.starts_with("https://"), "{}", g.id);
            assert!(
                s.min.is_finite() && s.max.is_finite() && s.min < s.max,
                "{}",
                g.id
            );
            assert!(s.step > 0.0 && s.step <= s.max - s.min, "{}", g.id);
            assert_eq!(g.starting_scale(), s.scale(), "{}", g.id);
        }
    }

    #[test]
    fn the_ten_494_presets_stay_with_their_ids_and_ranges() {
        for (id, min, max, step) in [
            ("bookoo-motto-80", 0.0, 999.0, 1.0),
            ("niche-zero", 0.0, 50.0, 1.0),
            ("niche-duo", 0.0, 70.0, 1.0),
            ("baratza-encore", 1.0, 40.0, 1.0),
            ("baratza-encore-esp", 1.0, 40.0, 1.0),
            ("baratza-virtuoso-plus", 1.0, 40.0, 1.0),
            ("baratza-sette-30", 1.0, 31.0, 1.0),
            ("breville-smart-grinder-pro", 1.0, 60.0, 1.0),
            ("breville-dose-control-pro", 1.0, 60.0, 1.0),
            ("mahlkonig-x54", 1.0, 35.0, 1.0),
        ] {
            let s = catalogue_entry(id).unwrap().scale;
            assert_eq!((s.min, s.max, s.step), (min, max, step), "{id}");
        }
    }

    #[test]
    fn the_odes_have_31_settings_from_1_to_11_in_thirds() {
        for id in ["fellow-ode", "fellow-ode-gen-2"] {
            let s = catalogue_entry(id).unwrap().scale;
            assert_eq!((s.min, s.max), (1.0, 11.0), "{id}");
            let steps = (s.max - s.min) / s.step;
            assert!((steps - 30.0).abs() < 1e-4, "{id}: {steps}");
        }
    }

    #[test]
    fn the_motto_80_is_0_to_999_microns_in_steps_of_1() {
        let g = catalogue_entry_for_name("bookoo motto 80").unwrap();
        assert_eq!(
            g.starting_scale(),
            GrindScale {
                min: 0.0,
                max: 999.0,
                step: 1.0,
                unit: "μm".to_owned()
            }
        );
    }

    #[test]
    fn strict_matching_ignores_case_spacing_accents_and_punctuation() {
        for (name, id) in [
            ("niche", "niche-zero"),
            ("Niche Zero", "niche-zero"),
            ("NicheZero", "niche-zero"),
            ("  niche-zero ", "niche-zero"),
            ("NZ", "niche-zero"),
            ("Mahlkonig X54", "mahlkonig-x54"),
            ("mahlkönig x54", "mahlkonig-x54"),
            ("Virtuoso+", "baratza-virtuoso-plus"),
            ("Virtuoso Plus", "baratza-virtuoso-plus"),
            ("Sage Smart Grinder Pro", "breville-smart-grinder-pro"),
            ("BCG820", "breville-smart-grinder-pro"),
            ("Sage Dose Control Pro", "breville-dose-control-pro"),
            ("MT80", "bookoo-motto-80"),
            ("ek43", "mahlkonig-ek43"),
            ("EK 43", "mahlkonig-ek43"),
            ("EK-43", "mahlkonig-ek43"),
            ("Mahlkönig EK43", "mahlkonig-ek43"),
            ("EK43 S", "mahlkonig-ek43-s"),
            ("ek43s", "mahlkonig-ek43-s"),
            ("Timemore Sculptor 064S", "timemore-sculptor-064s"),
            ("Sculptor 064S", "timemore-sculptor-064s"),
            ("064s", "timemore-sculptor-064s"),
            ("Sculptor 064S Gen II", "timemore-sculptor-064s"),
            ("Wilfa Uniform", "wilfa-uniform"),
            ("WSFBS-200B", "wilfa-uniform"),
            ("Fellow Ode", "fellow-ode"),
            ("Ode Brew Grinder", "fellow-ode"),
            ("Fellow Ode Gen 2", "fellow-ode-gen-2"),
            ("ode2", "fellow-ode-gen-2"),
        ] {
            assert_eq!(
                catalogue_entry_for_name(name).map(|g| g.id),
                Some(id),
                "{name}"
            );
        }
        // Strict: a typo, another model or a blank names nothing.
        for name in [
            "ek34",
            "Motto 80 Pro",
            "Niche Zero 2",
            "Sculptor 064",
            "Sculptor 078S",
            "Encore ESP Pro",
            "Uniform Evo",
            "Wilfa Svart",
            "   ",
            "",
        ] {
            assert_eq!(catalogue_entry_for_name(name), None, "{name}");
        }
    }

    #[test]
    fn grinders_left_out_by_the_research_are_not_listed() {
        // Ranges that couldn't be checked from the maker, or composite dials.
        for name in [
            "DF64",
            "Comandante C40",
            "Eureka Mignon Specialita",
            "1Zpresso J-Ultra",
            "Fellow Opus",
            "Baratza Sette 270",
            "Lagom P64",
            "Fellow Opus",
        ] {
            assert_eq!(catalogue_entry_for_name(name), None, "{name}");
        }
        for id in ["df64", "comandante-c40", "fellow-opus", "baratza-sette-270"] {
            assert_eq!(catalogue_entry(id), None, "{id}");
        }
    }

    #[test]
    fn the_catalogue_lists_as_json_each_with_its_scale_and_source() {
        let list: Vec<CatalogueGrinderInfo> =
            serde_json::from_str(&grinder_catalogue_json()).unwrap();
        assert_eq!(list.len(), GRINDER_CATALOGUE.len());
        for (info, g) in list.iter().zip(GRINDER_CATALOGUE) {
            assert_eq!(info.scale.as_ref(), Some(&g.starting_scale()), "{}", g.id);
            assert_eq!(info.source.as_deref(), Some(g.scale.source), "{}", g.id);
        }
        let nz = list.iter().find(|g| g.id == "niche-zero").unwrap();
        assert_eq!(
            (nz.name.as_str(), nz.kind),
            ("Niche Zero", GrinderKind::Electric)
        );
    }
}
