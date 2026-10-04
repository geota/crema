//! # de1-domain
//!
//! The Crema espresso **domain model** — the layer above the wire protocol.
//!
//! Sans-IO, like the rest of the core:
//!
//! - [`profile`] — the [`Profile`] recipe model and its assembly into the
//!   `de1-protocol` upload packets.
//! - [`profile_import`] — importers for the legacy DE1-app profile formats
//!   (v2 JSON and the original Tcl-dictionary `.tcl` files), plus a v2 JSON
//!   exporter.
//! - [`builtin`] — the standard DE1 profiles, vendored and shipped as built-in
//!   Crema [`Profile`]s ("batteries included").
//! - [`shot`] — the [`ShotMonitor`] state machine, which observes a shot and
//!   records it.
//! - [`water`] — the [`WaterMonitor`] state machine, the sibling of
//!   [`ShotMonitor`] for hot-water and flush sessions.
//! - [`steam`] — the [`SteamMonitor`] state machine, the sibling for steam
//!   sessions, eco mode, and steam-clog detection.
//! - [`history`] — [`StoredShot`], a completed shot persisted to history.
//! - [`history_import`] / [`history_export`] — legacy `.shot` and modern v2
//!   `.shot.json` importers, plus the symmetric v2 exporter so a shell can
//!   share / upload a Crema shot in the community contract.
//! - [`decent_shot_record`](mod@decent_shot_record) / [`decent_wire`] — the decentespresso.com
//!   shot upload: a [`StoredShot`] as a decaid `ShotRecord`, and the
//!   classification of the support API's HTTP replies, shared by both shells.
//! - [`stop`] — [`AutoStop`], the stop-at-weight / stop-at-volume controller.
//! - [`flow`] — [`FlowEstimator`], robust weight/mass-flow estimation for SAW.
//! - [`filter`] — [`median`](filter::median) computation, for smoothing a
//!   vibration-noisy signal.
//! - [`session`] — [`SessionTimer`], the timing core shared by the monitors.
//! - [`bean`] — pure roast-classification helpers ([`roast_band`],
//!   [`days_off_roast`], [`roast_freshness`]) plus the bean library
//!   types ([`Bean`], [`Roaster`], [`ShotBean`], [`BeanOrigin`],
//!   [`BeanMix`]) that every shell consumes via `#[typeshare]`.
//! - [`visualizer_sync`] — pure Visualizer-sync helpers: djb2-based
//!   de-dup signatures ([`signature_for_shot`], [`signature_for_bean`],
//!   [`signature_for_roaster`]) and the [`reconcile_shots`] action
//!   planner, ported from the web shell's `shot-sync-signatures.ts`
//!   so every shell shares one algorithm.
//! - [`bean_sync`] — the pure halves of the Visualizer bean / roaster sync
//!   (write envelopes, catalogue-link resolution, push planning) and the
//!   roaster duplicate detection + merge plan, shared by every shell.
//! - [`visualizer_catalogue`] — the Visualizer canonical-catalogue search
//!   response parsers, the pick → bean / roaster autofill rules
//!   ([`catalogue_pick`], [`catalogue_roaster_autofill`]) and the clash lists
//!   ([`catalogue_clashes`]) behind the bean and roaster forms' catalogue
//!   search.
//! - [`ids`] — [`new_profile_id`], the one canonical profile-ID minter
//!   (UUID v7, RFC 9562). Built-in IDs are pre-generated into
//!   `profiles/builtin.json` by the `gen-builtin-ids` binary in this
//!   crate; custom profiles call this from the shell via the wasm /
//!   UniFFI bridges.

pub mod app_settings;
pub mod auto_tare;
pub mod bean;
pub mod bean_coerce;
pub mod bean_filter;
pub mod bean_search;
pub mod bean_sync;
pub mod beanconqueror;
pub mod brand;
pub mod brew;
pub mod brew_builtin;
pub mod brew_custom;
pub mod brew_session;
pub mod builtin;
/// Lenient number-or-string deserialization shared by the JSON importers.
mod coerce;
pub mod cold_maintenance;
pub mod connect_sweep;
pub mod crema_jsonl;
pub mod crema_profile;
pub mod decent_shot_record;
pub mod decent_wire;
pub mod error;
pub mod filter;
pub mod flow;
pub mod history;
pub mod history_export;
pub mod history_import;
pub mod ids;
pub mod maintenance;
pub mod mode_targets;
pub mod profile;
pub mod profile_bounds;
pub mod profile_fingerprint;
pub mod profile_import;
pub mod profile_roast;
pub mod replay;
pub mod saw_learning;
pub mod session;
pub mod settings_import;
pub mod shot;
pub mod shot_quality;
pub mod steam;
pub mod step_weight;
pub mod stolen_serials;
pub mod stop;
pub mod tank;
pub mod units;
pub mod visualizer_catalogue;
pub mod visualizer_error;
pub mod visualizer_sync;
pub mod visualizer_wire;
pub mod volume;
pub mod water;
pub mod weight_gate;

pub use auto_tare::{
    AUTO_TARE_HOLDOFF_MS, AUTO_TARE_SETTLE_BAND_G, AUTO_TARE_SETTLE_SAMPLES, AUTO_TARE_THRESHOLD_G,
    MAX_PRE_SHOT_ZERO_OFFSET_G, TareSettleWindow, pre_shot_zero_offset,
};
pub use bean::{
    BREWS_REMAINING_WINDOW, Bean, BeanMix, BeanOrigin, BeanRoastType, DEFAULT_DOSE_PER_BREW_G,
    RoastBand, RoastFreshness, Roaster, ShotBean, brews_remaining_estimate, credit_remaining,
    days_off_roast, debit_remaining, resettle_remaining, roast_band, roast_band5, roast_freshness,
};
pub use bean_coerce::{coerce_bean, coerce_bean_json, coerce_roaster, coerce_roaster_json};
pub use bean_filter::{
    BeanFilterQuery, BeanFilterResult, BeanRoastCounts, BeanStatusCounts, BeanStatusFilter,
    BeanTagCount, filter_beans, filter_beans_json,
};
pub use bean_search::{
    FieldHit, SearchField, SearchHit, SearchSegment, search_beans, search_beans_json,
    search_roasters, search_roasters_json,
};
pub use bean_sync::{
    BeanPushItem, BeanSyncScope, RoasterDeletePlan, RoasterDuplicate, RoasterLinkPatch,
    RoasterMergePlan, RoasterPushItem, bean_sync_scope, bean_sync_scope_json,
    coffee_bag_write_request, coffee_bag_write_request_json, detect_roaster_duplicates,
    detect_roaster_duplicates_json, merge_pulled_bag, merge_pulled_roaster,
    merge_pulled_roaster_json, plan_bean_push, plan_bean_push_json, plan_roaster_delete,
    plan_roaster_delete_json, plan_roaster_link_patches, plan_roaster_link_patches_json,
    plan_roaster_merge, plan_roaster_merge_json, plan_roaster_push, plan_roaster_push_json,
    remote_ids_needing_detail, remote_ids_needing_detail_json, remote_roaster_unlinked,
    remote_roaster_unlinked_json, resolve_roaster_catalogue_link,
    resolve_roaster_catalogue_link_json, roaster_write_request, roaster_write_request_json,
    sync_direction_pulls, sync_direction_pushes,
};
pub use beanconqueror::{
    ImportDiagnostics, ImportPlan, ImportedShot, crema_to_bc_main_json,
    crema_to_bc_main_json_from_envelope, import_beanconqueror_json,
};
pub use brew::{
    BREW_METHOD_OTHER, BrewHistoryStats, BrewLogPrefill, BrewLogSeedInput, BrewLogSeeds,
    BrewMethodPreset, BrewRecipe, BrewSample, BrewSeedInput, BrewSeries, BrewStatInput, BrewStep,
    BrewStepKind, DEFAULT_LOG_METHOD, RecipeTimeEstimate, StageMark, StairSegment, StepAdvance,
    blank_recipe, blank_recipe_json, brew_history_stats, brew_log_seeds, brew_log_seeds_json,
    brew_method_preset, brew_method_presets, brew_method_presets_json, is_espresso_method,
    max_planned_target, max_planned_target_json, normalize_brew_method, planned_staircase,
    planned_staircase_json, ratio_for_method, recipe_estimated_duration_json,
    recipe_planned_pour_total_g_json, stage_mark_for, stage_mark_for_json,
};
pub use brew_builtin::{
    BUILTIN_BREW_RECIPE_COUNT, BUILTIN_RECIPE_ID_PREFIX, RecipeLibrary, RecipeLibraryMigration,
    builtin_brew_recipe, builtin_brew_recipes, builtin_brew_recipes_json,
    default_builtin_recipe_id, duplicate_recipe, duplicate_recipe_json, is_builtin_recipe,
    is_legacy_default_recipe, migrate_recipe_library, migrate_recipe_library_json,
};
pub use brew_custom::{
    BrewMethodStyle, CUSTOM_METHOD_ID_PREFIX, CUSTOM_METHOD_LABEL_MAX_CHARS, CustomBrewMethod,
    CustomMethodLabelCheck, CustomMethodLabelError, CustomMethodLabelInput, blank_recipe_for_style,
    blank_recipe_for_style_json, brew_method_presets_with_custom,
    brew_method_presets_with_custom_json, brew_method_style_seeds, brew_method_style_seeds_json,
    custom_method_preset, is_custom_method_id, resolve_brew_method_preset,
    validate_custom_method_label, validate_custom_method_label_json,
};
pub use brew_session::{
    APPROACH_LEAD, BREW_SAMPLE_MIN_INTERVAL, BrewCue, BrewSessionEvent, BrewSessionMonitor,
    BrewSessionPhase, BrewSessionSummary, MAX_BREW_SAMPLES, START_ON_POUR_THRESHOLD_G,
};
pub use builtin::{BUILTIN_PROFILE_COUNT, builtin_profiles};
pub use cold_maintenance::{
    COLD_MAINTENANCE_MIN_FIRMWARE_BUILD, DESCALE_SCHEDULE, DESCALE_TOTAL_SECONDS, DescaleProgress,
    DescaleTracker, MaintenancePhase, cold_maintenance_profile, firmware_drops_cold_requests,
    is_machine_heating, is_maintenance_state, needs_cold_workaround,
};
pub use connect_sweep::{
    ConnectSweepSettings, USB_CHARGER_CHECK_INTERVAL_MS, UsbChargingMode, usb_charger_decision,
};
pub use crema_jsonl::{
    BackupHeader, BackupImportPlan, CremaExportHeader, export_backup_jsonl_from_json, export_jsonl,
    export_jsonl_from_json, import_backup_jsonl_to_plan_json, import_jsonl_to_plan_json,
    parse_backup_jsonl, parse_jsonl,
};
pub use crema_profile::{
    BrewDefaults, CremaProfile, ProfileSegment, ProfileSource, Roast, blank_crema_profile_json,
    blank_profile, builtin_crema_profiles_json, crema_profile_from_wire_json,
    crema_profile_to_wire_json, default_brew_defaults_json, default_segments,
    default_segments_json, from_wire, to_wire,
};
pub use decent_shot_record::{
    BREW_LOG_NOT_UPLOADABLE, decent_shot_record, decent_shot_record_json, firmware_build_number,
};
pub use decent_wire::{
    DecentLoginReply, DecentMachine, DecentMachinesReply, DecentUploadReply, decent_login_token,
    decent_login_token_json, decent_machines, decent_machines_json, decent_shot_view_url,
    decent_upload_reply, decent_upload_reply_json, extract_decent_id, parse_decent_machines,
};
pub use error::{DomainError, ImportError};
pub use flow::{Estimate, FlowAlgorithm, FlowEstimator};
pub use history::{
    HistoryStats, STORED_SHOT_FORMAT_VERSION, ShotMachine, ShotMetadata, ShotStatInput, StoredShot,
    brew_ratio, history_stats,
};
pub use history_export::{export_v2_json_shot, export_v2_json_shot_full};
pub use history_import::{import_legacy_tcl_shot, import_v2_json_shot};
pub use ids::{new_custom_method_id, new_profile_id, new_recipe_id, new_shot_id};
pub use maintenance::{
    MaintenanceReadout, MaintenanceState, maintenance_readout, maintenance_readout_json,
};
pub use mode_targets::{
    ModeTargetInputs, ModeTargets, READY_TOLERANCE_C, group_at_temperature, resolve_mode_targets,
    steam_at_temperature,
};
pub use profile::{
    AssembledProfile, BeverageType, Compare, ExitCondition, ExitMetric, Limiter, Profile,
    ProfileStep, Pump, TempSensor, Transition,
};
pub use profile_fingerprint::profile_fingerprint;
pub use profile_import::{export_v2_json, import_legacy_tcl, import_v2_json};
pub use profile_roast::roast_from_profile;
pub use replay::{ReplayMeta, ReplayMetaBean, fold_meta_jsonl, fold_meta_jsonl_json};
pub use session::SessionTimer;
pub use settings_import::{ImportedDe1AppSettings, import_settings_tdb};
pub use shot::{
    ABORTED_MAX_DURATION_MS, ABORTED_MAX_WEIGHT_G, MAX_SHOT_SAMPLES, STORAGE_SAMPLE_CAP,
    ShotDisposition, ShotEvent, ShotMetrics, ShotMonitor, ShotPeaks, ShotPhase, ShotRecord,
    TimedSample, downsample_indices, shot_disposition,
};
pub use steam::{
    MAX_STEAM_SAMPLES, STEAM_ECO_DELAY, SteamClogReason, SteamEvent, SteamMonitor, SteamRecord,
    SteamSample,
};
pub use step_weight::{SKIP_RETRY_AFTER, SKIP_RETRY_MAX, StepWeightExit};
pub use stolen_serials::{
    STOLEN_SERIALS_REFRESH_MS, STOLEN_SERIALS_URL, parse_stolen_serials, serial_on_stolen_list,
    stolen_serials_list_is_valid, stolen_serials_refresh_due,
};
pub use stop::{
    AutoStop, STOP_WEIGHT_BEFORE, StopCapture, StopConfig, StopReason, StopTargets,
    sav_counts_volume, volume_stop_arms,
};
pub use tank::{
    DEFAULT_REFILL_POINT_MM, SENSOR_OFFSET_MM, TANK_FULL_ML, TANK_LEVEL_SMOOTHING_TAU_S,
    TANK_MM_TO_ML, TankLevelSmoother, water_tank_depth_mm, water_tank_ml, water_tank_percent,
};
pub use units::{
    WeightUnit, bar_to_psi, celsius_to_fahrenheit, fahrenheit_to_celsius, fl_oz_to_ml, grams_to_oz,
    ml_to_fl_oz, oz_to_grams, psi_to_bar,
};
pub use visualizer_catalogue::{
    CatalogueAutofill, CatalogueCoffeeBag, CatalogueField, CataloguePage, CataloguePick,
    CataloguePickRoaster, CatalogueRoaster, CatalogueRoasterAutofill, CatalogueRoasterPage,
    catalogue_autofill, catalogue_autofill_json, catalogue_clashes, catalogue_clashes_json,
    catalogue_pick, catalogue_pick_json, catalogue_roaster_autofill,
    catalogue_roaster_autofill_json, catalogue_roaster_clashes, catalogue_roaster_clashes_json,
    find_catalogue_roaster, parse_catalogue_coffee_bags, parse_catalogue_coffee_bags_json,
    parse_catalogue_roasters, parse_catalogue_roasters_json, roaster_match_key,
};
pub use visualizer_error::{
    VISUALIZER_DEFAULT_DAILY_LIMIT, VisualizerCallError, is_recoverable, retry_backoff_ms,
    visualizer_quota_limit,
};
pub use visualizer_sync::{
    BeanReconcileAction, LocalShotRef, PulledBag, ReconcileAction, RoasterReconcileAction,
    WireShot, reconcile_beans, reconcile_beans_json, reconcile_pulled_bags, reconcile_roasters,
    reconcile_roasters_json, reconcile_shots, reconcile_shots_json, signature_for_bean,
    signature_for_roaster, signature_for_shot,
};
pub use visualizer_wire::{
    BagWire, RoasterWire, ShotPatchInputs, VISUALIZER_PREMIUM_SHOT_FIELDS, VISUALIZER_SHOT_FIELDS,
    bean_from_wire, bean_from_wire_json, bean_to_wire, bean_to_wire_json, rating_to_flavor,
    roast_level_from_wire, roast_level_to_wire, roaster_from_wire, roaster_from_wire_json,
    roaster_to_wire, roaster_to_wire_json, samples_from_visualizer_detail,
    samples_from_visualizer_detail_json, visualizer_shot_patch, visualizer_shot_patch_for_account,
    visualizer_shot_patch_json, wire_shot_from_detail, wire_shot_from_detail_json,
};
pub use volume::{LineFreqDetector, VolumeIntegrator};
pub use water::{WaterEvent, WaterMonitor, WaterRecord, WaterSessionKind};
pub use weight_gate::WeightSpikeGate;
