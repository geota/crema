//! # grinder_catalogue
//!
//! Well-known grinders, built in and shared by both shells (#488, "Bag
//! grinds"): maker, model, the names people type for them, and the kind
//! (electric or hand).
//!
//! **A scale only where it was verified.** An entry carries a
//! [`VerifiedScale`] only when its range was checked against the maker's
//! manual or product page, cited in [`VerifiedScale::source`]; every other
//! entry has none, and a grinder added from it gets the default scale (0–20,
//! step 0.1) for the user to set on Devices. Composite dials (Baratza macro
//! plus micro, 1Zpresso rotation.number.click, Fellow Opus with its inner
//! ring) never carry one. Ranges that couldn't be checked when the list was
//! made (DF64, DF83, Eureka Mignon, EK43, Timemore Sculptor, Comandante,
//! Kingrinder, Lagom P64, Zerno, Varia VS3 and the rest) are left without.
//!
//! **Two kinds of matching.**
//! - [`catalogue_entry_for_name`] is strict: the name, ignoring case,
//!   accents, spaces and punctuation, is the entry's name or one of its
//!   aliases. Imports and the migration use it, so "niche", "Niche Zero" and
//!   "NicheZero" become one grinder, while "DF54" never merges into "DF64".
//! - [`crate::grinder::match_grinders`] is the type-or-pick box: typo
//!   tolerant ("df46" finds DF64), the user picks from the list.

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
    /// Its range, only when verified.
    pub scale: Option<VerifiedScale>,
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

    /// The scale a grinder added from it starts with: the verified one, else
    /// the default.
    #[must_use]
    pub fn starting_scale(&self) -> GrindScale {
        self.scale
            .as_ref()
            .map_or_else(GrindScale::default, VerifiedScale::scale)
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
    /// Its verified scale; `None` = "Range not set: check your grinder's
    /// dial".
    pub scale: Option<GrindScale>,
    /// Where the scale was checked; `None` without one.
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
            scale: g.scale.as_ref().map(VerifiedScale::scale),
            source: g.scale.as_ref().map(|s| s.source.to_owned()),
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

use GrinderKind::{Electric as E, Hand as H};

const fn entry(
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

const fn verified(
    mut g: CatalogueGrinder,
    min: f32,
    max: f32,
    step: f32,
    unit: &'static str,
    source: &'static str,
) -> CatalogueGrinder {
    g.scale = Some(VerifiedScale {
        min,
        max,
        step,
        unit,
        source,
    });
    g
}

/// The catalogue. See the module docs for which entries carry a scale.
pub const GRINDER_CATALOGUE: &[CatalogueGrinder] = &[
    // ── Verified scales (the #494 presets) ──
    // `bladeGap`, the grind size in μm, 0–999.
    verified(
        entry(
            "bookoo-motto-80",
            "BOOKOO",
            "Motto 80",
            &["Motto 80", "Motto80", "BOOKOO MT80", "MT80"],
            E,
        ),
        0.0,
        999.0,
        1.0,
        "μm",
        "https://github.com/BooKooCode/OpenSource/tree/main/bookoo_motto80",
    ),
    // The manual's dial drawing runs 0 to 50; stepless, whole-number marks.
    verified(
        entry("niche-zero", "Niche", "Zero", &["Niche", "NZ"], E),
        0.0,
        50.0,
        1.0,
        "",
        "https://cdn.shopify.com/s/files/1/0081/2076/2450/files/Niche_Zero_IFU.pdf",
    ),
    // '0' is where the burrs touch; the dial drawing runs to 70, one tick per unit.
    verified(
        entry("niche-duo", "Niche", "Duo", &["Niche Duo grinder"], E),
        0.0,
        70.0,
        1.0,
        "",
        "https://cdn.shopify.com/s/files/1/0081/2076/2450/files/N685_INSTRUCTION_BOOK_ROW_83F_1_080323_WEB.pdf",
    ),
    // "from very coarse (grind setting 40) to very fine (grind setting 1)".
    verified(
        entry("baratza-encore", "Baratza", "Encore", &["Encore"], E),
        1.0,
        40.0,
        1.0,
        "",
        "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG485/Encore+Documents/manual-encore-en-v4-3-120122-1.pdf",
    ),
    // "the espresso range (settings 1-20) and the filter coffee range (settings 21-40)".
    verified(
        entry(
            "baratza-encore-esp",
            "Baratza",
            "Encore ESP",
            &["Encore ESP"],
            E,
        ),
        1.0,
        40.0,
        1.0,
        "",
        "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG495/Encore+ESP+Documents/manual-encoreesp-v1-0-en-010923.pdf",
    ),
    // "from very coarse (grind setting 40) to very fine (grind setting 1)".
    verified(
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
        ),
        1.0,
        40.0,
        1.0,
        "",
        "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG587/Virtuoso%2B+Documents/manual-virtuosoplus-en-v1-6.pdf",
    ),
    // "The adjustment ring moves from coarse (31) to fine (1)"; macro ring only.
    verified(
        entry("baratza-sette-30", "Baratza", "Sette 30", &["Sette 30"], E),
        1.0,
        31.0,
        1.0,
        "",
        "https://breville-production-aem-assets.s3.us-west-2.amazonaws.com/ZCG1130/Sette+30+Documents/manual-sette30-en-v3.pdf",
    ),
    // "60 custom grind settings"; the inner upper-burr setting only recalibrates.
    verified(
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
        ),
        1.0,
        60.0,
        1.0,
        "",
        "https://assets.breville.com/BCG820/BCG820_USCM_IB_L26_LR.pdf",
    ),
    // "60 grind settings, from espresso to plunger".
    verified(
        entry(
            "breville-dose-control-pro",
            "Breville",
            "Dose Control Pro",
            &["Dose Control Pro", "Sage Dose Control Pro", "BCG600"],
            E,
        ),
        1.0,
        60.0,
        1.0,
        "",
        "https://assets.breville.com/Instruction-Booklets/ANZ/BCG600_instruction_manual.pdf",
    ),
    // Stepless; the scale's finest setting is "01", the coarsest "35".
    verified(
        entry(
            "mahlkonig-x54",
            "Mahlkönig",
            "X54",
            &["X54", "Mahlkonig X54", "Mahlkoenig X54"],
            E,
        ),
        1.0,
        35.0,
        1.0,
        "",
        "https://downloads.mahlkoenig.de/Service/Mahlkoenig_X54_Allround_Home_Grinder_Original_instruction_manual_EN.pdf",
    ),
    // ── No verified scale ──
    // Baratza (the Sette 270 and the Varios are macro plus micro: composite).
    entry(
        "baratza-sette-270",
        "Baratza",
        "Sette 270",
        &["Sette 270"],
        E,
    ),
    entry(
        "baratza-sette-270wi",
        "Baratza",
        "Sette 270Wi",
        &["Sette 270Wi", "270Wi"],
        E,
    ),
    entry("baratza-vario", "Baratza", "Vario", &["Vario"], E),
    entry(
        "baratza-vario-plus",
        "Baratza",
        "Vario+",
        &["Vario+", "Vario Plus"],
        E,
    ),
    entry("baratza-vario-w", "Baratza", "Vario-W", &["Vario-W"], E),
    entry("baratza-forte-ap", "Baratza", "Forté AP", &["Forte AP"], E),
    entry("baratza-forte-bg", "Baratza", "Forté BG", &["Forte BG"], E),
    // Mahlkönig.
    entry(
        "mahlkonig-ek43",
        "Mahlkönig",
        "EK43",
        &["EK43", "EK 43", "Mahlkonig EK43"],
        E,
    ),
    entry(
        "mahlkonig-ek43-s",
        "Mahlkönig",
        "EK43 S",
        &["EK43 S", "Mahlkonig EK43 S"],
        E,
    ),
    entry("mahlkonig-e65s", "Mahlkönig", "E65S", &["E65S"], E),
    entry(
        "mahlkonig-e80-supreme",
        "Mahlkönig",
        "E80 Supreme",
        &["E80 Supreme"],
        E,
    ),
    // Eureka.
    entry(
        "eureka-mignon-specialita",
        "Eureka",
        "Mignon Specialità",
        &["Specialita", "Mignon Specialita", "Eureka Specialita"],
        E,
    ),
    entry(
        "eureka-mignon-silenzio",
        "Eureka",
        "Mignon Silenzio",
        &["Mignon Silenzio", "Silenzio"],
        E,
    ),
    entry(
        "eureka-mignon-notte",
        "Eureka",
        "Mignon Notte",
        &["Mignon Notte"],
        E,
    ),
    entry(
        "eureka-mignon-manuale",
        "Eureka",
        "Mignon Manuale",
        &["Mignon Manuale"],
        E,
    ),
    entry(
        "eureka-mignon-facile",
        "Eureka",
        "Mignon Facile",
        &["Mignon Facile"],
        E,
    ),
    entry(
        "eureka-mignon-perfetto",
        "Eureka",
        "Mignon Perfetto",
        &["Mignon Perfetto", "Perfetto"],
        E,
    ),
    entry(
        "eureka-mignon-libra",
        "Eureka",
        "Mignon Libra",
        &["Mignon Libra"],
        E,
    ),
    entry("eureka-mignon-xl", "Eureka", "Mignon XL", &["Mignon XL"], E),
    entry(
        "eureka-mignon-zero",
        "Eureka",
        "Mignon Zero",
        &["Mignon Zero"],
        E,
    ),
    entry(
        "eureka-mignon-crono",
        "Eureka",
        "Mignon Crono",
        &["Mignon Crono"],
        E,
    ),
    entry(
        "eureka-oro-mignon-single-dose",
        "Eureka",
        "Oro Mignon Single Dose",
        &["Eureka Oro Single Dose", "Oro Mignon Single Dose"],
        E,
    ),
    entry("eureka-atom-75", "Eureka", "Atom 75", &["Atom 75"], E),
    // DF single-dosers.
    entry(
        "df64",
        "Turin",
        "DF64",
        &["DF64", "G-IOTA", "MiiCoffee DF64"],
        E,
    ),
    entry(
        "df64-gen-2",
        "Turin",
        "DF64 Gen 2",
        &["DF64 Gen 2", "DF64 II", "DF64 V2"],
        E,
    ),
    entry("df64v", "Turin", "DF64V", &["DF64V"], E),
    entry("df83", "Turin", "DF83", &["DF83"], E),
    entry("df83v", "Turin", "DF83V", &["DF83V"], E),
    entry("df54", "Turin", "DF54", &["DF54"], E),
    // Option-O.
    entry(
        "lagom-p64",
        "Option-O",
        "Lagom P64",
        &["Lagom P64", "P64"],
        E,
    ),
    entry(
        "lagom-p100",
        "Option-O",
        "Lagom P100",
        &["Lagom P100", "P100"],
        E,
    ),
    entry("lagom-mini", "Option-O", "Lagom Mini", &["Lagom Mini"], H),
    // Weber Workshops.
    entry(
        "weber-eg-1",
        "Weber Workshops",
        "EG-1",
        &["EG-1", "Weber EG-1"],
        E,
    ),
    entry(
        "weber-key",
        "Weber Workshops",
        "Key",
        &["Weber Key", "Key Grinder"],
        H,
    ),
    entry(
        "weber-hg-1",
        "Weber Workshops",
        "HG-1",
        &["HG-1", "Weber HG-1"],
        H,
    ),
    // Other single-dosers.
    entry("zerno-z1", "Zerno", "Z1", &["Zerno"], E),
    entry("varia-vs3", "Varia", "VS3", &["VS3"], E),
    entry("varia-vs6", "Varia", "VS6", &["VS6"], E),
    entry(
        "kafatek-monolith-flat",
        "Kafatek",
        "Monolith Flat",
        &["Monolith Flat"],
        E,
    ),
    entry(
        "kafatek-monolith-conical",
        "Kafatek",
        "Monolith Conical",
        &["Monolith Conical"],
        E,
    ),
    entry("profitec-t64", "Profitec", "T64", &["Profitec Pro T64"], E),
    // Fellow (the Opus has an inner ring: composite).
    entry("fellow-ode", "Fellow", "Ode", &["Fellow Ode Gen 1"], E),
    entry(
        "fellow-ode-gen-2",
        "Fellow",
        "Ode Gen 2",
        &["Ode Gen 2", "Fellow Ode 2"],
        E,
    ),
    entry("fellow-opus", "Fellow", "Opus", &[], E),
    // Timemore.
    entry(
        "timemore-sculptor-064",
        "Timemore",
        "Sculptor 064",
        &["Sculptor 064", "Sculptor 64"],
        E,
    ),
    entry(
        "timemore-sculptor-078",
        "Timemore",
        "Sculptor 078",
        &["Sculptor 078", "Sculptor 78"],
        E,
    ),
    entry(
        "timemore-sculptor-064s",
        "Timemore",
        "Sculptor 064S",
        &["Sculptor 064S"],
        E,
    ),
    entry(
        "timemore-sculptor-078s",
        "Timemore",
        "Sculptor 078S",
        &["Sculptor 078S"],
        E,
    ),
    entry(
        "timemore-c2",
        "Timemore",
        "Chestnut C2",
        &["Timemore C2"],
        H,
    ),
    entry(
        "timemore-c3",
        "Timemore",
        "Chestnut C3",
        &["Timemore C3"],
        H,
    ),
    entry(
        "timemore-c3-esp",
        "Timemore",
        "Chestnut C3 ESP",
        &["Timemore C3 ESP"],
        H,
    ),
    entry(
        "timemore-chestnut-x",
        "Timemore",
        "Chestnut X",
        &["Timemore X"],
        H,
    ),
    entry("timemore-slim-plus", "Timemore", "Slim Plus", &[], H),
    entry("timemore-nano", "Timemore", "Nano", &[], H),
    // Hand grinders (1Zpresso's rotation.number.click dials are composite).
    entry(
        "comandante-c40",
        "Comandante",
        "C40 MK4",
        &["Comandante C40", "C40", "C40 MK4"],
        H,
    ),
    entry(
        "comandante-c40-mk3",
        "Comandante",
        "C40 MK3",
        &["C40 MK3"],
        H,
    ),
    entry("kingrinder-k6", "Kingrinder", "K6", &[], H),
    entry("kingrinder-k4", "Kingrinder", "K4", &[], H),
    entry("1zpresso-j-max", "1Zpresso", "J-Max", &["J-Max"], H),
    entry("1zpresso-j-ultra", "1Zpresso", "J-Ultra", &["J-Ultra"], H),
    entry("1zpresso-jx-pro", "1Zpresso", "JX-Pro", &["JX-Pro"], H),
    entry("1zpresso-jx", "1Zpresso", "JX", &[], H),
    entry("1zpresso-k-ultra", "1Zpresso", "K-Ultra", &["K-Ultra"], H),
    entry("1zpresso-k-max", "1Zpresso", "K-Max", &["K-Max"], H),
    entry("1zpresso-k-plus", "1Zpresso", "K-Plus", &["K-Plus"], H),
    entry(
        "1zpresso-zp6-special",
        "1Zpresso",
        "ZP6 Special",
        &["ZP6 Special", "ZP6"],
        H,
    ),
    entry("1zpresso-q2", "1Zpresso", "Q2", &[], H),
    entry("1zpresso-q-air", "1Zpresso", "Q Air", &[], H),
    entry("1zpresso-x-pro", "1Zpresso", "X-Pro", &[], H),
    entry("1zpresso-x-ultra", "1Zpresso", "X-Ultra", &["X-Ultra"], H),
    entry(
        "hario-skerton-pro",
        "Hario",
        "Skerton Pro",
        &["Skerton Pro"],
        H,
    ),
    entry(
        "hario-mini-mill-slim",
        "Hario",
        "Mini Mill Slim",
        &["Hario Mini Mill"],
        H,
    ),
    entry("porlex-mini", "Porlex", "Mini", &["Porlex Mini II"], H),
    entry("porlex-tall", "Porlex", "Tall", &["Porlex Tall II"], H),
    entry(
        "kinu-m47-phoenix",
        "Kinu",
        "M47 Phoenix",
        &["M47 Phoenix"],
        H,
    ),
    entry(
        "kinu-m47-classic",
        "Kinu",
        "M47 Classic",
        &["M47 Classic", "Kinu M47"],
        H,
    ),
    entry(
        "kinu-m47-simplicity",
        "Kinu",
        "M47 Simplicity",
        &["M47 Simplicity"],
        H,
    ),
    entry("fiorenzato-pietro", "Fiorenzato", "Pietro", &["Pietro"], H),
    // Electric, home and café.
    entry(
        "rocket-faustino",
        "Rocket Espresso",
        "Faustino",
        &["Rocket Faustino"],
        E,
    ),
    entry("rancilio-rocky", "Rancilio", "Rocky", &[], E),
    entry("la-marzocco-lux-d", "La Marzocco", "Lux D", &["Lux D"], E),
    entry("mazzer-mini", "Mazzer", "Mini", &[], E),
    entry(
        "mazzer-super-jolly",
        "Mazzer",
        "Super Jolly",
        &["Super Jolly"],
        E,
    ),
    entry("mazzer-philos", "Mazzer", "Philos", &[], E),
    entry("mazzer-zm", "Mazzer", "ZM", &[], E),
    entry("compak-k3", "Compak", "K3", &[], E),
    entry("compak-k10", "Compak", "K10", &[], E),
    entry("ceado-e37s", "Ceado", "E37S", &["E37S"], E),
    entry("ceado-e37j", "Ceado", "E37J", &["E37J"], E),
    entry(
        "fiorenzato-f64-evo",
        "Fiorenzato",
        "F64 EVO",
        &["F64 EVO"],
        E,
    ),
    entry(
        "fiorenzato-allground",
        "Fiorenzato",
        "AllGround",
        &["AllGround"],
        E,
    ),
    entry("wilfa-uniform", "Wilfa", "Uniform", &[], E),
    entry("wilfa-svart", "Wilfa", "Svart", &[], E),
    entry(
        "oxo-brew-conical-burr",
        "OXO",
        "Brew Conical Burr",
        &["OXO Conical Burr"],
        E,
    ),
    entry("capresso-infinity", "Capresso", "Infinity", &[], E),
    entry("bodum-bistro", "Bodum", "Bistro", &[], E),
    entry("gaggia-mdf", "Gaggia", "MDF", &[], E),
    entry("lelit-fred", "Lelit", "Fred", &[], E),
    entry(
        "delonghi-dedica-kg521",
        "De'Longhi",
        "Dedica KG521",
        &["KG521"],
        E,
    ),
    entry("delonghi-kg79", "De'Longhi", "KG79", &["KG79"], E),
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_are_unique_and_the_list_is_large() {
        assert!(
            GRINDER_CATALOGUE.len() >= 100,
            "{}",
            GRINDER_CATALOGUE.len()
        );
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
    fn every_scale_is_verified_with_a_source_and_usable() {
        let mut with_scale = 0;
        for g in GRINDER_CATALOGUE {
            let Some(s) = g.scale else { continue };
            with_scale += 1;
            assert!(s.source.starts_with("https://"), "{}", g.id);
            assert!(
                s.min.is_finite() && s.max.is_finite() && s.min < s.max,
                "{}",
                g.id
            );
            assert!(s.step > 0.0 && s.step <= s.max - s.min, "{}", g.id);
        }
        // The ten #494 presets, and nothing guessed.
        assert_eq!(with_scale, 10);
    }

    #[test]
    fn composite_dials_carry_no_scale() {
        for id in [
            "baratza-sette-270",
            "baratza-vario",
            "fellow-opus",
            "1zpresso-j-max",
            "1zpresso-k-ultra",
        ] {
            assert!(catalogue_entry(id).unwrap().scale.is_none(), "{id}");
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
            ("Mahlkonig X54", "mahlkonig-x54"),
            ("mahlkönig x54", "mahlkonig-x54"),
            ("Eureka Mignon Specialita", "eureka-mignon-specialita"),
            ("Virtuoso+", "baratza-virtuoso-plus"),
            ("df64", "df64"),
            ("DF54", "df54"),
        ] {
            assert_eq!(
                catalogue_entry_for_name(name).map(|g| g.id),
                Some(id),
                "{name}"
            );
        }
        // Strict: a typo, another model or a blank names nothing.
        for name in ["df46", "Motto 80 Pro", "Niche Zero 2", "   ", ""] {
            assert_eq!(catalogue_entry_for_name(name), None, "{name}");
        }
    }

    #[test]
    fn the_catalogue_lists_as_json_with_scales_only_where_verified() {
        let list: Vec<CatalogueGrinderInfo> =
            serde_json::from_str(&grinder_catalogue_json()).unwrap();
        assert_eq!(list.len(), GRINDER_CATALOGUE.len());
        for g in &list {
            assert_eq!(g.scale.is_some(), g.source.is_some(), "{}", g.id);
        }
        let nz = list.iter().find(|g| g.id == "niche-zero").unwrap();
        assert_eq!(
            (nz.name.as_str(), nz.kind),
            ("Niche Zero", GrinderKind::Electric)
        );
    }
}
