//! Built-in espresso profiles — "batteries included".
//!
//! Crema ships every standard profile from the legacy DE1 tablet app
//! ([de1app](https://github.com/decentespresso/de1app)) as a built-in Crema
//! [`Profile`]. The raw `*.tcl` files are vendored verbatim under
//! `de1-domain/profiles/` (see the `README.md` there for origin and license)
//! and embedded into the crate at compile time with [`include_str!`] — that is
//! a build-time read, not runtime I/O, so the core stays sans-IO.
//!
//! The single public accessor, [`builtin_profiles`], is **infallible**: it
//! never returns a [`Result`] and never panics into the caller. Each vendored
//! file is parsed once via [`import_legacy_tcl`](crate::import_legacy_tcl) and
//! the resulting list is memoized for the process lifetime. Any file that
//! somehow failed to import would simply be skipped rather than panicking; the
//! `builtin_profiles_all_import` test guarantees that never happens — the
//! whole corpus imports and assembles.

use std::sync::OnceLock;

use crate::profile::Profile;
use crate::profile_import::import_legacy_tcl;

/// Every vendored legacy profile file, embedded at compile time.
///
/// The contents are the de1app `*.tcl` profile files, copied verbatim. Each
/// entry is the raw Tcl-dictionary text; [`builtin_profiles`] converts them.
const RAW_PROFILES: &[&str] = &[
    include_str!("../profiles/7g basket.tcl"),
    include_str!("../profiles/80s_Espresso.tcl"),
    include_str!("../profiles/A-Flow____default-dark.tcl"),
    include_str!("../profiles/A-Flow____default-like-dflow.tcl"),
    include_str!("../profiles/A-Flow____default-medium.tcl"),
    include_str!("../profiles/A-Flow____default-very-dark.tcl"),
    include_str!("../profiles/adaptive_allonge.tcl"),
    include_str!("../profiles/adaptive_espresso.tcl"),
    include_str!("../profiles/Advanced spring lever.tcl"),
    include_str!("../profiles/Best overall pressure profile.tcl"),
    include_str!("../profiles/best_practice_light.tcl"),
    include_str!("../profiles/best_practice.tcl"),
    include_str!("../profiles/Blooming allonge.tcl"),
    include_str!("../profiles/Blooming espresso.tcl"),
    include_str!("../profiles/Classic Italian espresso.tcl"),
    include_str!("../profiles/Cleaning_forward_flush_x5.tcl"),
    include_str!("../profiles/cleaning_forward_flush.tcl"),
    include_str!("../profiles/cold_brew.tcl"),
    include_str!("../profiles/Cremina.tcl"),
    include_str!("../profiles/Damian's LM Leva.tcl"),
    include_str!("../profiles/Damian's LRv2.tcl"),
    include_str!("../profiles/Damian's LRv3.tcl"),
    include_str!("../profiles/Damians_Q.tcl"),
    include_str!("../profiles/default.tcl"),
    include_str!("../profiles/e61 classic at 9 bar.tcl"),
    include_str!("../profiles/e61 classic gently up to 10 bar.tcl"),
    include_str!("../profiles/e61 rocketing up to 10 bar.tcl"),
    include_str!("../profiles/e61 with fast preinfusion to 9 bar.tcl"),
    include_str!("../profiles/easy_blooming_active_pressure_decline.tcl"),
    include_str!("../profiles/EspressoForge_Dark.tcl"),
    include_str!("../profiles/EspressoForge_Light.tcl"),
    include_str!("../profiles/Extractamundo_Dos.tcl"),
    include_str!("../profiles/Filter_20.tcl"),
    include_str!("../profiles/Filter_21.tcl"),
    include_str!("../profiles/filter3.tcl"),
    include_str!("../profiles/Flow profile for milky drinks.tcl"),
    include_str!("../profiles/Flow profile for straight espresso.tcl"),
    include_str!("../profiles/flow_calibration.tcl"),
    include_str!("../profiles/Gentle and sweet.tcl"),
    include_str!("../profiles/Gentle flat 2.5 ml per second.tcl"),
    include_str!("../profiles/Gentle preinfusion flow profile.tcl"),
    include_str!("../profiles/Gentler but still traditional 8.4 bar.tcl"),
    include_str!("../profiles/Hybrid pour over espresso.tcl"),
    include_str!("../profiles/I_got_your_back.tcl"),
    include_str!("../profiles/Innovative long preinfusion.tcl"),
    include_str!("../profiles/Italian Australian espresso.tcl"),
    include_str!("../profiles/kalita_20.tcl"),
    include_str!("../profiles/Londonium.tcl"),
    include_str!("../profiles/Low pressure lever machine at 6 bar.tcl"),
    include_str!("../profiles/manual_flow.tcl"),
    include_str!("../profiles/manual_pressure.tcl"),
    include_str!("../profiles/Pour over.tcl"),
    include_str!("../profiles/Preinfuse then 45ml of water.tcl"),
    include_str!("../profiles/rao_allonge.tcl"),
    include_str!("../profiles/Steam_only.tcl"),
    include_str!("../profiles/tea_in_a_basket.tcl"),
    include_str!("../profiles/Tea_portafilter__Blue_Willow_Black_Honey_Oolong.tcl"),
    include_str!("../profiles/Tea_portafilter__Blue_Willow_black_phoenix_1.tcl"),
    include_str!("../profiles/Tea_portafilter__Blue_Willow_black_phoenix_2.tcl"),
    include_str!("../profiles/Tea_portafilter__Blue_Willow_lunar_winter.tcl"),
    include_str!("../profiles/Tea_portafilter__Blue_Willow_Tsuyuhikari_Sencha.tcl"),
    include_str!("../profiles/Tea_portafilter__Blue_Willow_Tsuyuhikari_Sensha.tcl"),
    include_str!("../profiles/tea_portafilter_chinese_green.tcl"),
    include_str!("../profiles/tea_portafilter_japanese_green.tcl"),
    include_str!("../profiles/tea_portafilter_no_pressure.tcl"),
    include_str!("../profiles/tea_portafilter_oolong_dark.tcl"),
    include_str!("../profiles/tea_portafilter_oolong.tcl"),
    include_str!("../profiles/tea_portafilter_pressurized.tcl"),
    include_str!("../profiles/tea_portafilter_tisane.tcl"),
    include_str!("../profiles/tea_portafilter_white.tcl"),
    include_str!("../profiles/tea_portafilter.tcl"),
    include_str!("../profiles/test_for_a_small_low_pressure_leak.tcl"),
    include_str!("../profiles/test_leak.tcl"),
    include_str!("../profiles/test_pressure_calibration.tcl"),
    include_str!("../profiles/test_pressure_release.tcl"),
    include_str!("../profiles/Test_profile_editor_demo.tcl"),
    include_str!("../profiles/test_temperature_calibration.tcl"),
    include_str!("../profiles/test_temperature.tcl"),
    include_str!("../profiles/Traditional lever machine.tcl"),
    include_str!("../profiles/Trendy 6 bar low pressure shot.tcl"),
    include_str!("../profiles/TurboBloom.tcl"),
    include_str!("../profiles/TurboTurbo.tcl"),
    include_str!("../profiles/Two spring lever machine to 9 bar.tcl"),
    include_str!("../profiles/v60-15g.tcl"),
    include_str!("../profiles/v60-20g.tcl"),
    include_str!("../profiles/v60-22g.tcl"),
    include_str!("../profiles/weber_spring_clean.tcl"),
    include_str!("../profiles/Weiss_advanced_spring_lever.tcl"),
];

/// The parsed built-in profiles, memoized on first access.
static BUILTIN: OnceLock<Vec<Profile>> = OnceLock::new();

/// The number of vendored profile files embedded in the crate.
///
/// This is the count of raw `*.tcl` files; every one of them imports
/// successfully, so it also equals [`builtin_profiles`]`.len()`.
pub const BUILTIN_PROFILE_COUNT: usize = RAW_PROFILES.len();

/// Every standard DE1 profile, as a built-in Crema [`Profile`].
///
/// The vendored legacy `*.tcl` files are parsed once with
/// [`import_legacy_tcl`](crate::import_legacy_tcl) and the result is cached for
/// the rest of the process. The returned slice is in a stable order (the
/// vendored files sorted by file name).
///
/// This accessor is **infallible** — it returns no [`Result`] and never panics
/// into the caller. The whole vendored corpus is known to import (enforced by
/// the crate's tests); were a file ever to fail, it would simply be omitted.
pub fn builtin_profiles() -> &'static [Profile] {
    BUILTIN.get_or_init(|| {
        RAW_PROFILES
            .iter()
            .filter_map(|raw| import_legacy_tcl(raw).ok())
            .collect()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtin_profiles_all_import() {
        // Every vendored file must parse — `filter_map` would silently drop a
        // failure, so the count must match the raw file count exactly.
        let profiles = builtin_profiles();
        assert_eq!(
            profiles.len(),
            BUILTIN_PROFILE_COUNT,
            "{} of {} vendored profiles failed to import",
            BUILTIN_PROFILE_COUNT - profiles.len(),
            BUILTIN_PROFILE_COUNT,
        );
    }

    #[test]
    fn builtin_profiles_all_assemble() {
        // Every built-in profile must assemble into protocol packets, i.e. be
        // uploadable to a DE1.
        for profile in builtin_profiles() {
            profile
                .assemble()
                .unwrap_or_else(|e| panic!("profile {:?} failed to assemble: {e}", profile.title));
        }
    }

    #[test]
    fn builtin_profiles_all_have_a_title_and_steps() {
        for profile in builtin_profiles() {
            assert!(
                !profile.title.trim().is_empty(),
                "a built-in profile has an empty title",
            );
            assert!(
                !profile.steps.is_empty(),
                "built-in profile {:?} has no steps",
                profile.title,
            );
        }
    }

    #[test]
    fn builtin_profiles_is_memoized() {
        // Two calls return the very same cached slice.
        let first = builtin_profiles();
        let second = builtin_profiles();
        assert!(std::ptr::eq(first, second));
    }

    #[test]
    fn builtin_profiles_round_trip_through_serde() {
        // Each built-in profile serializes and deserializes losslessly, which
        // is what the JSON-string FFI surface relies on.
        for profile in builtin_profiles() {
            let json = serde_json::to_string(profile).expect("serialize");
            let back: Profile = serde_json::from_str(&json).expect("deserialize");
            assert_eq!(&back, profile);
        }
    }
}
