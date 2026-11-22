//! # grinder_catalogue
//!
//! Well-known grinders, built in and shared by both shells (#488, "Bag
//! grinds"): maker, model, the names people type for them, and the kind
//! (electric or hand).
//!
//! **Three kinds of entry** (#488). Every entry's maker name is certain,
//! and listed ones are popular with home espresso or high-end filter
//! brewers.
//! - **Verified** ([`ScaleTier::Verified`]): the range is the maker's, from
//!   its manual or page, cited in [`CatalogueScale::source`].
//! - **Typical** ([`ScaleTier::Typical`]): the maker states no single range;
//!   it is inferred with high or medium confidence from the printed dial, a
//!   maker drawing, consistent community charts, or a sibling model with the
//!   same dial (its evidence), the evidence cited in
//!   [`CatalogueScale::source`]. The shells label it "Typical range — check
//!   your dial".
//! - **Hidden** (no scale): a grinder whose range couldn't be inferred (a
//!   composite dial like 1Zpresso rotation.number.click, or no evidence).
//!   It is never listed and never given a range; it only lets imports, the
//!   upgrade and Create merge its spellings ("P64" and "Lagom P64"). A
//!   grinder created from it gets the default scale (0–20, step 0.1).
//!
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

/// How sure the catalogue is of an entry's range.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ScaleTier {
    /// Checked against the maker's manual or page.
    Verified,
    /// Inferred from the printed dial, a maker drawing or consistent
    /// community charts, where the maker states no single range: shown as
    /// "Typical range — check your dial".
    Typical,
}

/// An entry's range and where it comes from.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CatalogueScale {
    /// Lowest setting.
    pub min: f32,
    /// Highest setting.
    pub max: f32,
    /// Step between settings.
    pub step: f32,
    /// Unit label; empty for none.
    pub unit: &'static str,
    /// The page the range comes from: the maker's for
    /// [`ScaleTier::Verified`], the evidence for [`ScaleTier::Typical`].
    pub source: &'static str,
    /// Verified or typical.
    pub tier: ScaleTier,
}

impl CatalogueScale {
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
    /// Its range; `None` for a hidden entry (see the module docs), which
    /// only merges spellings and is never listed.
    pub scale: Option<CatalogueScale>,
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

    /// Whether the shells list it: it has a range (verified or typical).
    #[must_use]
    pub fn listed(&self) -> bool {
        self.scale.is_some()
    }

    /// The scale a grinder added from it starts with: its range, else (a
    /// hidden entry) the default.
    #[must_use]
    pub fn starting_scale(&self) -> GrindScale {
        self.scale
            .as_ref()
            .map_or_else(GrindScale::default, CatalogueScale::scale)
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
    /// Its scale. Every listed entry has one; it stays optional so the
    /// shells' "Range not set" path keeps compiling and the JSON shape holds.
    pub scale: Option<GrindScale>,
    /// Where the scale comes from; set whenever `scale` is.
    pub source: Option<String>,
    /// Verified, or typical ("Typical range — check your dial" wherever a
    /// verified entry says it comes with its scale).
    pub tier: ScaleTier,
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
            scale: g.scale.as_ref().map(CatalogueScale::scale),
            source: g.scale.as_ref().map(|s| s.source.to_owned()),
            tier: g.scale.map_or(ScaleTier::Typical, |s| s.tier),
        }
    }
}

/// The listed catalogue (verified, then typical), in list order; hidden
/// entries are left out.
#[must_use]
pub fn grinder_catalogue() -> Vec<CatalogueGrinderInfo> {
    GRINDER_CATALOGUE
        .iter()
        .filter(|g| g.listed())
        .map(CatalogueGrinderInfo::from)
        .collect()
}

/// [`grinder_catalogue`] as JSON.
#[must_use]
pub fn grinder_catalogue_json() -> String {
    serde_json::to_string(&grinder_catalogue()).unwrap_or_default()
}

use GrinderKind::{Electric as E, Hand as H};

const fn entry(
    id: &'static str,
    maker: &'static str,
    model: &'static str,
    aliases: &'static [&'static str],
    kind: GrinderKind,
    scale: CatalogueScale,
) -> CatalogueGrinder {
    CatalogueGrinder {
        id,
        maker,
        model,
        aliases,
        kind,
        scale: Some(scale),
    }
}

/// A hidden entry: names only, no range, never listed.
const fn hidden(
    id: &'static str,
    maker: &'static str,
    model: &'static str,
    aliases: &'static [&'static str],
    kind: GrinderKind,
) -> CatalogueGrinder {
    CatalogueGrinder {
        id,
        maker,
        model,
        aliases,
        kind,
        scale: None,
    }
}

/// A range checked against the maker.
const fn scale(
    min: f32,
    max: f32,
    step: f32,
    unit: &'static str,
    source: &'static str,
) -> CatalogueScale {
    CatalogueScale {
        min,
        max,
        step,
        unit,
        source,
        tier: ScaleTier::Verified,
    }
}

/// A typical range: see [`ScaleTier::Typical`].
const fn typical(
    min: f32,
    max: f32,
    step: f32,
    unit: &'static str,
    source: &'static str,
) -> CatalogueScale {
    CatalogueScale {
        min,
        max,
        step,
        unit,
        source,
        tier: ScaleTier::Typical,
    }
}

/// The catalogue: verified, typical, then hidden entries (see the module
/// docs), the source quoted above each entry.
pub const GRINDER_CATALOGUE: &[CatalogueGrinder] = &[
    // ── Verified ranges (ScaleTier::Verified) ──
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
    // ── Typical ranges (ScaleTier::Typical): no single range from the maker;
    // inferred, high or medium confidence. Shown as "Typical range — check
    // your dial". ──
    // Fellow: "The outer ring adjusts between 1-11 with 4 minor increments
    // (0.25) between each"; the inner ring adds micro steps (composite), so
    // the outer ring only. High confidence.
    entry(
        "fellow-opus",
        "Fellow",
        "Opus",
        &["Opus", "Fellow Opus Conical Burr Grinder"],
        E,
        typical(
            1.0,
            11.0,
            0.25,
            "",
            "https://fellowproducts.com/pages/gear-guide-opus",
        ),
    ),
    // The maker's manual draws an unnumbered stepless dial; the printed dial
    // and community charts run 0 (Turkish) to 90 (cold brew). Medium.
    entry(
        "df64-gen-2",
        "Turin",
        "DF64 Gen 2",
        &["DF64 Gen 2", "DF64 II", "DF64 V2", "Turin DF64 Gen 2"],
        E,
        typical(
            0.0,
            90.0,
            1.0,
            "",
            "https://honestcoffeeguide.com/turin-df64-gen-2-grind-settings/",
        ),
    ),
    // Clicks from burr contact on an unnumbered dial; charts run 2 (Turkish)
    // to 40 (cold brew). Medium.
    entry(
        "comandante-c40",
        "Comandante",
        "C40 MK4",
        &["Comandante C40", "C40", "C40 MK4"],
        H,
        typical(
            0.0,
            40.0,
            1.0,
            "clicks",
            "https://honestcoffeeguide.com/comandante-c40-mk4-grind-settings",
        ),
    ),
    // "60 clicks per full rotation (up to 4 rotations)", 16 μm a click:
    // total clicks from zero. Medium.
    entry(
        "kingrinder-k6",
        "KINGrinder",
        "K6",
        &["Kingrinder K6", "King Grinder K6"],
        H,
        typical(
            0.0,
            240.0,
            1.0,
            "clicks",
            "https://coffeexplore.com/kingrinder-k6-review/",
        ),
    ),
    // The manual charts "+1" to "+18" from its calibration "0"; community
    // charts ("numbers from zero") reach 19.6. Medium.
    entry(
        "varia-vs3-gen-2",
        "Varia",
        "VS3 Gen 2",
        &["VS3 Gen 2", "Varia VS3 Gen 2", "VS3 2nd Gen"],
        E,
        typical(
            0.0,
            20.0,
            0.1,
            "",
            "https://honestcoffeeguide.com/varia-vs3-gen-2-grind-settings/",
        ),
    ),
    // Mazzer gives the 0 point and "every notch corresponds to 6 microns";
    // owners report the dial locks at 0 and at 145, one turn. Medium.
    entry(
        "mazzer-philos",
        "Mazzer",
        "Philos",
        &["Philos"],
        E,
        typical(
            0.0,
            145.0,
            1.0,
            "",
            "https://www.home-barista.com/blog/mazzer-philos-comments-t93466-50.html",
        ),
    ),
    // The main (macro) ring, 1 to 31, as community charts use it; the micro
    // ring is left out (composite). Medium.
    entry(
        "baratza-sette-270",
        "Baratza",
        "Sette 270",
        &["Sette 270"],
        E,
        typical(
            1.0,
            31.0,
            1.0,
            "",
            "https://honestcoffeeguide.com/baratza-sette-270-grind-settings/",
        ),
    ),
    // ── Typical on a sibling's evidence (owner decision, #488 batch 6): the
    // same dial as the model above it, so the same range and source. ──
    // The Sette 270Wi is the Sette 270 with weight-based dosing: the same
    // macro ring, 1 to 31, so the Sette 270's charts.
    entry(
        "baratza-sette-270wi",
        "Baratza",
        "Sette 270Wi",
        &["Sette 270Wi", "270Wi"],
        E,
        typical(
            1.0,
            31.0,
            1.0,
            "",
            "https://honestcoffeeguide.com/baratza-sette-270-grind-settings/",
        ),
    ),
    // The Gen 1 DF64 has the Gen 2's printed stepless dial, 0 to 90, so the
    // Gen 2's charts.
    entry(
        "df64",
        "Turin",
        "DF64",
        &["DF64", "DF64 Gen 1", "G-IOTA", "MiiCoffee DF64"],
        E,
        typical(
            0.0,
            90.0,
            1.0,
            "",
            "https://honestcoffeeguide.com/turin-df64-gen-2-grind-settings/",
        ),
    ),
    // The C40 MK3 counts clicks from burr contact like the MK4, so the MK4's
    // 0 to 40 charts.
    entry(
        "comandante-c40-mk3",
        "Comandante",
        "C40 MK3",
        &["C40 MK3"],
        H,
        typical(
            0.0,
            40.0,
            1.0,
            "clicks",
            "https://honestcoffeeguide.com/comandante-c40-mk4-grind-settings",
        ),
    ),
    // ── Hidden: names only, never listed, never given a range. Kept so
    // imports, the upgrade and Create still merge their spellings ("P64",
    // "Lagom P64"). The research for each is in the PR. ──
    hidden("baratza-vario", "Baratza", "Vario", &["Vario"], E),
    hidden(
        "baratza-vario-plus",
        "Baratza",
        "Vario+",
        &["Vario+", "Vario Plus"],
        E,
    ),
    hidden("baratza-vario-w", "Baratza", "Vario-W", &["Vario-W"], E),
    hidden("baratza-forte-ap", "Baratza", "Forté AP", &["Forte AP"], E),
    hidden("baratza-forte-bg", "Baratza", "Forté BG", &["Forte BG"], E),
    hidden("mahlkonig-e65s", "Mahlkönig", "E65S", &["E65S"], E),
    hidden(
        "mahlkonig-e80-supreme",
        "Mahlkönig",
        "E80 Supreme",
        &["E80 Supreme"],
        E,
    ),
    hidden(
        "eureka-mignon-specialita",
        "Eureka",
        "Mignon Specialità",
        &["Specialita", "Mignon Specialita", "Eureka Specialita"],
        E,
    ),
    hidden(
        "eureka-mignon-silenzio",
        "Eureka",
        "Mignon Silenzio",
        &["Mignon Silenzio", "Silenzio"],
        E,
    ),
    hidden(
        "eureka-mignon-notte",
        "Eureka",
        "Mignon Notte",
        &["Mignon Notte"],
        E,
    ),
    hidden(
        "eureka-mignon-manuale",
        "Eureka",
        "Mignon Manuale",
        &["Mignon Manuale"],
        E,
    ),
    hidden(
        "eureka-mignon-facile",
        "Eureka",
        "Mignon Facile",
        &["Mignon Facile"],
        E,
    ),
    hidden(
        "eureka-mignon-perfetto",
        "Eureka",
        "Mignon Perfetto",
        &["Mignon Perfetto", "Perfetto"],
        E,
    ),
    hidden(
        "eureka-mignon-libra",
        "Eureka",
        "Mignon Libra",
        &["Mignon Libra"],
        E,
    ),
    hidden("eureka-mignon-xl", "Eureka", "Mignon XL", &["Mignon XL"], E),
    hidden(
        "eureka-mignon-zero",
        "Eureka",
        "Mignon Zero",
        &["Mignon Zero"],
        E,
    ),
    hidden(
        "eureka-mignon-crono",
        "Eureka",
        "Mignon Crono",
        &["Mignon Crono"],
        E,
    ),
    hidden(
        "eureka-oro-mignon-single-dose",
        "Eureka",
        "Oro Mignon Single Dose",
        &["Eureka Oro Single Dose", "Oro Mignon Single Dose"],
        E,
    ),
    hidden("eureka-atom-75", "Eureka", "Atom 75", &["Atom 75"], E),
    hidden("df64v", "Turin", "DF64V", &["DF64V"], E),
    hidden("df83", "Turin", "DF83", &["DF83"], E),
    hidden("df83v", "Turin", "DF83V", &["DF83V"], E),
    hidden("df54", "Turin", "DF54", &["DF54"], E),
    hidden(
        "lagom-p64",
        "Option-O",
        "Lagom P64",
        &["Lagom P64", "P64"],
        E,
    ),
    hidden(
        "lagom-p100",
        "Option-O",
        "Lagom P100",
        &["Lagom P100", "P100"],
        E,
    ),
    hidden("lagom-mini", "Option-O", "Lagom Mini", &["Lagom Mini"], H),
    hidden(
        "weber-eg-1",
        "Weber Workshops",
        "EG-1",
        &["EG-1", "Weber EG-1"],
        E,
    ),
    hidden(
        "weber-key",
        "Weber Workshops",
        "Key",
        &["Weber Key", "Key Grinder"],
        H,
    ),
    hidden(
        "weber-hg-1",
        "Weber Workshops",
        "HG-1",
        &["HG-1", "Weber HG-1"],
        H,
    ),
    hidden("zerno-z1", "Zerno", "Z1", &["Zerno"], E),
    hidden("varia-vs3", "Varia", "VS3", &["VS3"], E),
    hidden("varia-vs6", "Varia", "VS6", &["VS6"], E),
    hidden(
        "kafatek-monolith-flat",
        "Kafatek",
        "Monolith Flat",
        &["Monolith Flat"],
        E,
    ),
    hidden(
        "kafatek-monolith-conical",
        "Kafatek",
        "Monolith Conical",
        &["Monolith Conical"],
        E,
    ),
    hidden("profitec-t64", "Profitec", "T64", &["Profitec Pro T64"], E),
    hidden(
        "timemore-sculptor-064",
        "Timemore",
        "Sculptor 064",
        &["Sculptor 064", "Sculptor 64"],
        E,
    ),
    hidden(
        "timemore-sculptor-078",
        "Timemore",
        "Sculptor 078",
        &["Sculptor 078", "Sculptor 78"],
        E,
    ),
    hidden(
        "timemore-sculptor-078s",
        "Timemore",
        "Sculptor 078S",
        &["Sculptor 078S"],
        E,
    ),
    hidden(
        "timemore-c2",
        "Timemore",
        "Chestnut C2",
        &["Timemore C2"],
        H,
    ),
    hidden(
        "timemore-c3",
        "Timemore",
        "Chestnut C3",
        &["Timemore C3"],
        H,
    ),
    hidden(
        "timemore-c3-esp",
        "Timemore",
        "Chestnut C3 ESP",
        &["Timemore C3 ESP"],
        H,
    ),
    hidden(
        "timemore-chestnut-x",
        "Timemore",
        "Chestnut X",
        &["Timemore X"],
        H,
    ),
    hidden("timemore-slim-plus", "Timemore", "Slim Plus", &[], H),
    hidden("timemore-nano", "Timemore", "Nano", &[], H),
    hidden("kingrinder-k4", "Kingrinder", "K4", &[], H),
    hidden("1zpresso-j-max", "1Zpresso", "J-Max", &["J-Max"], H),
    hidden("1zpresso-j-ultra", "1Zpresso", "J-Ultra", &["J-Ultra"], H),
    hidden("1zpresso-jx-pro", "1Zpresso", "JX-Pro", &["JX-Pro"], H),
    hidden("1zpresso-jx", "1Zpresso", "JX", &[], H),
    hidden("1zpresso-k-ultra", "1Zpresso", "K-Ultra", &["K-Ultra"], H),
    hidden("1zpresso-k-max", "1Zpresso", "K-Max", &["K-Max"], H),
    hidden("1zpresso-k-plus", "1Zpresso", "K-Plus", &["K-Plus"], H),
    hidden(
        "1zpresso-zp6-special",
        "1Zpresso",
        "ZP6 Special",
        &["ZP6 Special", "ZP6"],
        H,
    ),
    hidden("1zpresso-q2", "1Zpresso", "Q2", &[], H),
    hidden("1zpresso-q-air", "1Zpresso", "Q Air", &[], H),
    hidden("1zpresso-x-pro", "1Zpresso", "X-Pro", &[], H),
    hidden("1zpresso-x-ultra", "1Zpresso", "X-Ultra", &["X-Ultra"], H),
    hidden(
        "hario-skerton-pro",
        "Hario",
        "Skerton Pro",
        &["Skerton Pro"],
        H,
    ),
    hidden(
        "hario-mini-mill-slim",
        "Hario",
        "Mini Mill Slim",
        &["Hario Mini Mill"],
        H,
    ),
    hidden("porlex-mini", "Porlex", "Mini", &["Porlex Mini II"], H),
    hidden("porlex-tall", "Porlex", "Tall", &["Porlex Tall II"], H),
    hidden(
        "kinu-m47-phoenix",
        "Kinu",
        "M47 Phoenix",
        &["M47 Phoenix"],
        H,
    ),
    hidden(
        "kinu-m47-classic",
        "Kinu",
        "M47 Classic",
        &["M47 Classic", "Kinu M47"],
        H,
    ),
    hidden(
        "kinu-m47-simplicity",
        "Kinu",
        "M47 Simplicity",
        &["M47 Simplicity"],
        H,
    ),
    hidden("fiorenzato-pietro", "Fiorenzato", "Pietro", &["Pietro"], H),
    hidden(
        "rocket-faustino",
        "Rocket Espresso",
        "Faustino",
        &["Rocket Faustino"],
        E,
    ),
    hidden("rancilio-rocky", "Rancilio", "Rocky", &[], E),
    hidden("la-marzocco-lux-d", "La Marzocco", "Lux D", &["Lux D"], E),
    hidden("mazzer-mini", "Mazzer", "Mini", &[], E),
    hidden(
        "mazzer-super-jolly",
        "Mazzer",
        "Super Jolly",
        &["Super Jolly"],
        E,
    ),
    hidden("mazzer-zm", "Mazzer", "ZM", &[], E),
    hidden("compak-k3", "Compak", "K3", &[], E),
    hidden("compak-k10", "Compak", "K10", &[], E),
    hidden("ceado-e37s", "Ceado", "E37S", &["E37S"], E),
    hidden("ceado-e37j", "Ceado", "E37J", &["E37J"], E),
    hidden(
        "fiorenzato-f64-evo",
        "Fiorenzato",
        "F64 EVO",
        &["F64 EVO"],
        E,
    ),
    hidden(
        "fiorenzato-allground",
        "Fiorenzato",
        "AllGround",
        &["AllGround"],
        E,
    ),
    hidden("wilfa-svart", "Wilfa", "Svart", &[], E),
    hidden(
        "oxo-brew-conical-burr",
        "OXO",
        "Brew Conical Burr",
        &["OXO Conical Burr"],
        E,
    ),
    hidden("capresso-infinity", "Capresso", "Infinity", &[], E),
    hidden("bodum-bistro", "Bodum", "Bistro", &[], E),
    hidden("gaggia-mdf", "Gaggia", "MDF", &[], E),
    hidden("lelit-fred", "Lelit", "Fred", &[], E),
    hidden(
        "delonghi-dedica-kg521",
        "De'Longhi",
        "Dedica KG521",
        &["KG521"],
        E,
    ),
    hidden("delonghi-kg79", "De'Longhi", "KG79", &["KG79"], E),
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
    fn every_listed_entry_has_a_usable_scale_and_a_source() {
        let tier = |t| {
            GRINDER_CATALOGUE
                .iter()
                .filter(|g| g.scale.is_some_and(|s| s.tier == t))
                .count()
        };
        assert_eq!(
            (tier(ScaleTier::Verified), tier(ScaleTier::Typical)),
            (16, 10)
        );
        for g in GRINDER_CATALOGUE.iter().filter(|g| g.listed()) {
            let s = g.scale.unwrap();
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
            let s = catalogue_entry(id).unwrap().scale.unwrap();
            assert_eq!(s.tier, ScaleTier::Verified, "{id}");
            assert_eq!((s.min, s.max, s.step), (min, max, step), "{id}");
        }
    }

    #[test]
    fn the_odes_have_31_settings_from_1_to_11_in_thirds() {
        for id in ["fellow-ode", "fellow-ode-gen-2"] {
            let s = catalogue_entry(id).unwrap().scale.unwrap();
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
            "Encore ESP Pro",
            "Uniform Evo",
            "VS3 Gen 3",
            "   ",
            "",
        ] {
            assert_eq!(catalogue_entry_for_name(name), None, "{name}");
        }
    }

    #[test]
    fn typical_entries_carry_their_tier_and_evidence() {
        for (name, id, min, max, step, unit) in [
            ("Fellow Opus", "fellow-opus", 1.0, 11.0, 0.25, ""),
            ("DF64 Gen 2", "df64-gen-2", 0.0, 90.0, 1.0, ""),
            ("c40", "comandante-c40", 0.0, 40.0, 1.0, "clicks"),
            ("Kingrinder K6", "kingrinder-k6", 0.0, 240.0, 1.0, "clicks"),
            ("Varia VS3 Gen 2", "varia-vs3-gen-2", 0.0, 20.0, 0.1, ""),
            ("Philos", "mazzer-philos", 0.0, 145.0, 1.0, ""),
            ("Sette 270", "baratza-sette-270", 1.0, 31.0, 1.0, ""),
            // On their siblings' evidence (#488 batch 6).
            ("Sette 270Wi", "baratza-sette-270wi", 1.0, 31.0, 1.0, ""),
            ("DF64", "df64", 0.0, 90.0, 1.0, ""),
            ("G-IOTA", "df64", 0.0, 90.0, 1.0, ""),
            ("DF64 Gen 1", "df64", 0.0, 90.0, 1.0, ""),
            ("C40 MK3", "comandante-c40-mk3", 0.0, 40.0, 1.0, "clicks"),
            (
                "Comandante C40 MK3",
                "comandante-c40-mk3",
                0.0,
                40.0,
                1.0,
                "clicks",
            ),
        ] {
            let g = catalogue_entry_for_name(name).unwrap();
            let s = g.scale.unwrap();
            assert_eq!(
                (g.id, s.tier, s.min, s.max, s.step, s.unit),
                (id, ScaleTier::Typical, min, max, step, unit),
                "{name}"
            );
        }
    }

    #[test]
    fn hidden_entries_merge_spellings_but_are_never_listed_or_ranged() {
        for (a, b, id) in [
            (
                "Specialita",
                "Eureka Mignon Specialità",
                "eureka-mignon-specialita",
            ),
            ("J-Ultra", "1Zpresso J-Ultra", "1zpresso-j-ultra"),
            ("P64", "Lagom P64", "lagom-p64"),
            (
                "Sculptor 078S",
                "Timemore Sculptor 078S",
                "timemore-sculptor-078s",
            ),
        ] {
            for name in [a, b] {
                let g = catalogue_entry_for_name(name).unwrap();
                assert_eq!(g.id, id, "{name}");
                assert!(!g.listed(), "{name}");
                assert_eq!(g.starting_scale(), GrindScale::default(), "{name}");
            }
        }
        let listed: Vec<String> = grinder_catalogue().into_iter().map(|g| g.id).collect();
        for g in GRINDER_CATALOGUE.iter().filter(|g| !g.listed()) {
            assert!(!listed.iter().any(|id| id == g.id), "{}", g.id);
        }
    }

    #[test]
    fn the_catalogue_lists_as_json_each_with_its_scale_and_source() {
        let list: Vec<CatalogueGrinderInfo> =
            serde_json::from_str(&grinder_catalogue_json()).unwrap();
        let listed: Vec<&CatalogueGrinder> =
            GRINDER_CATALOGUE.iter().filter(|g| g.listed()).collect();
        assert_eq!(list.len(), listed.len());
        for (info, g) in list.iter().zip(listed) {
            let s = g.scale.unwrap();
            assert_eq!(info.scale.as_ref(), Some(&g.starting_scale()), "{}", g.id);
            assert_eq!(info.source.as_deref(), Some(s.source), "{}", g.id);
            assert_eq!(info.tier, s.tier, "{}", g.id);
        }
        assert!(grinder_catalogue_json().contains(r#""tier":"typical""#));
        let nz = list.iter().find(|g| g.id == "niche-zero").unwrap();
        assert_eq!(
            (nz.name.as_str(), nz.kind),
            ("Niche Zero", GrinderKind::Electric)
        );
    }
}
