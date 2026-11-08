//! The model test: generated op sequences against the real core.

use de1_model::{De1Params, Ending, Invariant, Op, ScaleMode, Status, World};
use de1_protocol::MachineState;
use proptest::prelude::*;
use proptest::strategy::ValueTree;
use proptest::test_runner::{Config, RngAlgorithm, TestRng, TestRunner};

fn params() -> impl Strategy<Value = De1Params> {
    (
        any::<bool>(),
        prop_oneof![
            Just(Ending::None),
            Just(Ending::Prompt),
            Just(Ending::Late),
            Just(Ending::BackToPouring)
        ],
        prop_oneof![4 => Just(0u32), 1 => 1u32..8],
        any::<bool>(),
    )
        .prop_map(
            |(preinfusion, ending, idle_ignored, two_tap_steam)| De1Params {
                preinfusion,
                ending,
                idle_ignored,
                two_tap_steam,
                unknown_pour: false,
            },
        )
}

fn target() -> impl Strategy<Value = Option<f32>> {
    prop_oneof![
        Just(None),
        Just(Some(18.0f32)),
        Just(Some(36.0f32)),
        Just(Some(60.0f32))
    ]
}

fn scale_mode() -> impl Strategy<Value = ScaleMode> {
    prop_oneof![
        4 => Just(ScaleMode::Healthy),
        1 => Just(ScaleMode::Stall),
        1 => Just(ScaleMode::Silent),
        1 => (1u64..5).prop_map(|s| ScaleMode::Flap { period_ms: s * 1_000 }),
        1 => Just(ScaleMode::Rezero),
        1 => (1u8..20, 3u64..20).prop_map(|(g, t)| ScaleMode::Bump {
            grams: f32::from(g),
            ms: t * 100,
        }),
        1 => (1u8..250).prop_map(|g| ScaleMode::StepUp { grams: f32::from(g) }),
        1 => Just(ScaleMode::TransientZero),
        1 => (10u64..60).prop_map(|t| ScaleMode::GapRezero { ms: t * 100 }),
    ]
}

fn flow_mode() -> impl Strategy<Value = MachineState> {
    prop_oneof![
        4 => Just(MachineState::Espresso),
        1 => Just(MachineState::HotWater),
        1 => Just(MachineState::HotWaterRinse),
        1 => Just(MachineState::Steam),
    ]
}

/// What a user does.
fn user_op() -> impl Strategy<Value = Op> {
    prop_oneof![
        5 => flow_mode().prop_map(Op::GroupHead),
        2 => prop_oneof![Just(18.0f32), Just(36.0f32)].prop_map(|yield_g| Op::AppStart { yield_g }),
        2 => Just(Op::UserStop),
        1 => prop_oneof![
            Just(MachineState::Steam),
            Just(MachineState::HotWater),
            Just(MachineState::HotWaterRinse)
        ]
        .prop_map(Op::AppRequest),
        1 => target().prop_map(Op::SetProfileTarget),
        1 => target().prop_map(Op::Dial),
        1 => prop_oneof![Just(None), Just(Some(36.0f32)), Just(Some(80.0f32))]
            .prop_map(Op::SetVolumeLimit),
        1 => prop_oneof![Just(None), Just(Some(30.0f32))].prop_map(Op::SetMaxTime),
        1 => prop_oneof![Just(0.0f32), Just(148.0f32)].prop_map(Op::SetSteamTarget),
        1 => any::<bool>().prop_map(Op::SetAutoTare),
        1 => prop_oneof![Just(None), Just(Some(3.6f32))].prop_map(Op::SetStepWeight),
        1 => Just(Op::Attach),
    ]
}

/// What the scale and the link do.
fn fault_op() -> impl Strategy<Value = Op> {
    prop_oneof![
        3 => scale_mode().prop_map(Op::Scale),
        1 => prop_oneof![Just(Op::ScaleDisconnect), Just(Op::ScaleConnect)],
        1 => (1u32..4).prop_map(Op::LoseWrites),
        1 => (1u32..3).prop_map(Op::DuplicateWrites),
        1 => (1u32..3).prop_map(Op::LoseStates),
        1 => (1u64..120).prop_map(|t| Op::MuteSamples { ms: t * 100 }),
        1 => prop_oneof![
            Just(Op::HangWrite),
            Just(Op::ReorderWrites),
            (1u64..30).prop_map(|t| Op::DelayWrite { ms: t * 100 }),
        ],
        1 => (1u64..300).prop_map(|t| Op::LinkDown { ms: t * 100 }),
    ]
}

fn op() -> impl Strategy<Value = Op> {
    prop_oneof![
        3 => (1u64..300).prop_map(|t| Op::Advance { ms: t * 100 }),
        2 => user_op(),
        1 => fault_op(),
    ]
}

/// A world's fixed setup, then its ops.
#[derive(Debug, Clone)]
struct Case {
    params: De1Params,
    headspace_ml: f32,
    lane_timeout_ms: u64,
    read_only: bool,
    pre_flush: bool,
    ops: Vec<Op>,
}

fn case() -> impl Strategy<Value = Case> {
    (
        params(),
        prop_oneof![Just(0.0f32), Just(50.0f32)],
        prop_oneof![Just(5_000u64), Just(10_000u64)],
        prop_oneof![9 => Just(false), 1 => Just(true)],
        any::<bool>(),
        prop::collection::vec(op(), 1..60),
        // Drawn last, so the committed seeds still make their cases.
        prop_oneof![4 => Just(false), 1 => Just(true)],
    )
        .prop_map(
            |(params, headspace_ml, lane_timeout_ms, read_only, pre_flush, ops, unknown_pour)| {
                Case {
                    params: De1Params {
                        unknown_pour,
                        ..params
                    },
                    headspace_ml,
                    lane_timeout_ms,
                    read_only,
                    pre_flush,
                    ops,
                }
            },
        )
}

fn run(case: &Case) -> World {
    let mut world = World::new(
        case.params,
        case.headspace_ml,
        case.lane_timeout_ms,
        case.read_only,
        case.pre_flush,
    );
    for op in &case.ops {
        world.apply(op);
    }
    world.apply(&Op::Advance { ms: 90_000 });
    world
}

proptest! {
    /// Every enforced invariant, on every step. A panic in the core fails this
    /// too, which is I12's first half.
    #[test]
    fn enforced_invariants_hold(case in case()) {
        let world = run(&case);
        let broken: Vec<String> = world
            .violations
            .iter()
            .filter(|v| v.invariant.status() == Status::Enforced)
            .map(ToString::to_string)
            .collect();
        prop_assert!(broken.is_empty(), "{broken:#?}");
    }
}

/// `count` cases from a fixed seed.
fn sample_cases(count: u32) -> Vec<Case> {
    let rng = TestRng::from_seed(RngAlgorithm::ChaCha, &[7; 32]);
    let mut runner = TestRunner::new_with_rng(Config::default(), rng);
    let strategy = case();
    (0..count)
        .map(|_| strategy.new_tree(&mut runner).expect("a case").current())
        .collect()
}

/// A model test that never reaches a stop proves nothing, so this pins that
/// the generator gets to everything the invariants watch.
#[test]
fn the_generator_reaches_what_the_invariants_watch() {
    let mut total = de1_model::Coverage::default();
    for case in sample_cases(300) {
        let c = run(&case).coverage;
        total.shots += c.shots;
        total.water += c.water;
        total.steam += c.steam;
        total.stops += c.stops;
        total.weight_stops += c.weight_stops;
        total.estimated_stops += c.estimated_stops;
        total.unseen_loads += c.unseen_loads;
        total.volume_stops += c.volume_stops;
        total.retries += c.retries;
        total.not_honoured += c.not_honoured;
        total.read_states += c.read_states;
        total.skips += c.skips;
        total.unseen += c.unseen;
        total.unknown_flows += c.unknown_flows;
        total.stop_commands += c.stop_commands;
        total.starts_over_a_flow += c.starts_over_a_flow;
        total.tares += c.tares;
        total.stale += c.stale;
        total.dead += c.dead;
        total.drops += c.drops;
        total.starts += c.starts;
        total.started += c.started;
    }
    println!("{total:#?}");
    for (name, count) in [
        ("shots", total.shots),
        ("water", total.water),
        ("steam", total.steam),
        ("stops", total.stops),
        ("weight stops", total.weight_stops),
        ("estimated stops", total.estimated_stops),
        ("volume stops", total.volume_stops),
        ("retries", total.retries),
        ("not honoured", total.not_honoured),
        ("read states", total.read_states),
        ("skips", total.skips),
        ("unseen", total.unseen),
        ("unknown flows", total.unknown_flows),
        ("stop commands", total.stop_commands),
        ("starts over a flow", total.starts_over_a_flow),
        ("tares", total.tares),
        ("stale", total.stale),
        ("dead", total.dead),
        ("drops", total.drops),
        ("starts", total.starts),
        ("started", total.started),
    ] {
        assert!(count >= 5, "{name} reached only {count} times: {total:#?}");
    }
}

/// The pending invariants: run with `--ignored --nocapture` to see what each
/// still breaks, and move one to `Enforced` once its count is 0.
#[test]
#[ignore = "a report, not a gate: these don't hold yet"]
fn report_pending_invariants() {
    let cases = sample_cases(500);
    let runs: Vec<_> = cases.iter().map(|case| run(case).violations).collect();
    for invariant in Invariant::ALL {
        let hits: Vec<_> = runs
            .iter()
            .filter_map(|v| v.iter().find(|v| v.invariant == invariant))
            .collect();
        let first = hits.first().map(ToString::to_string);
        println!(
            "{invariant:?} {:?}: {}/500 {first:?}",
            invariant.status(),
            hits.len()
        );
    }
}

/// One line of a capture.
#[derive(serde::Deserialize)]
struct CaptureLine {
    t: u64,
    src: String,
    hex: String,
}

fn capture() -> Vec<(u64, String, Vec<u8>)> {
    include_str!("fixtures/20260524T105452.jsonl")
        .lines()
        .map(|line| {
            let line: CaptureLine = serde_json::from_str(line).expect("a capture line");
            let bytes = (0..line.hex.len() / 2)
                .map(|i| u8::from_str_radix(&line.hex[i * 2..i * 2 + 2], 16).expect("hex"))
                .collect();
            (line.t, line.src, bytes)
        })
        .collect()
}

/// Conformance: the sim's no-Preinfusion firmware matches the one real shot
/// with a scale on disk, so the parameter set covers what the machine did.
#[test]
fn the_capture_reports_no_preinfusion_and_water_reaches_the_cup_late() {
    let lines = capture();
    let states: Vec<&[u8]> = lines
        .iter()
        .filter(|(_, src, _)| src == "DE1_STATE")
        .map(|(_, _, data)| data.as_slice())
        .collect();
    assert_eq!(states, [&[4, 2], &[4, 5], &[4, 6], &[2, 0]]);
    let start = lines
        .iter()
        .find(|(_, src, data)| src == "DE1_STATE" && data.as_slice() == [4, 5])
        .map_or(0, |line| line.0);
    let empty_at_8_s = lines
        .iter()
        .filter(|(t, src, _)| src == "SCALE_WEIGHT" && (start..start + 8_000).contains(t))
        .all(|(_, _, data)| u32::from(data[8]) << 8 | u32::from(data[9]) < 500);
    assert!(empty_at_8_s, "under 5 g in the cup, 8 s into the pour");
}

/// The sim's `Ending::Prompt` over the same shape: two states on the way in,
/// one on the way out.
#[test]
fn the_sim_can_reproduce_the_captures_substates() {
    let mut world = World::new(
        De1Params {
            preinfusion: false,
            ..De1Params::default()
        },
        50.0,
        5_000,
        false,
        false,
    );
    world.apply(&Op::GroupHead(MachineState::Espresso));
    world.apply(&Op::Advance { ms: 2_000 });
    assert_eq!(world.de1.state_bytes(), [4, 5]);
    world.apply(&Op::Advance { ms: 8_000 });
    assert!(world.cup.grams < 1.0, "the headspace is still filling");
    world.apply(&Op::Advance { ms: 30_000 });
    assert!(world.cup.grams > 5.0);
}

fn plain_world() -> World {
    World::new(De1Params::default(), 0.0, 5_000, false, false)
}

#[test]
fn a_lost_state_report_leaves_the_core_behind_the_machine() {
    let mut world = plain_world();
    world.apply(&Op::LoseStates(1));
    world.apply(&Op::GroupHead(MachineState::HotWater));
    world.apply(&Op::Advance { ms: 100 });
    assert_eq!(world.de1.state, MachineState::HotWater);
    assert_eq!(world.heard(), Some(MachineState::Idle));
}

#[test]
fn muted_samples_leave_the_scale_running() {
    let mut world = plain_world();
    world.apply(&Op::GroupHead(MachineState::HotWater));
    world.apply(&Op::Advance { ms: 1_000 });
    world.apply(&Op::MuteSamples { ms: 2_000 });
    let (samples, weights) = world.heard_counts();
    world.apply(&Op::Advance { ms: 1_500 });
    let (samples_after, weights_after) = world.heard_counts();
    assert_eq!(samples_after, samples);
    assert_eq!(weights_after, weights + 15);
}

/// `group_flow` from a capture line.
fn flow_of(hex: &str) -> f32 {
    f32::from(u16::from_str_radix(&hex[8..12], 16).expect("hex")) / 4096.0
}

/// When `group_flow` first falls under 0.3 after Idle, ms.
fn capture_tail_ms() -> u64 {
    let lines: Vec<CaptureLine> =
        include_str!("../../de1-app/tests/fixtures/session-20260517-122732-shot-pull.jsonl")
            .lines()
            .filter_map(|line| serde_json::from_str(line).ok())
            .collect();
    let ending = lines
        .iter()
        .position(|l| l.src == "DE1_STATE" && l.hex == "0406")
        .expect("Ending");
    let idle = lines[ending..]
        .iter()
        .find(|l| l.src == "DE1_STATE" && l.hex == "0200")
        .expect("Idle")
        .t;
    let peak_in_ending = lines[ending..]
        .iter()
        .filter(|l| l.src == "DE1_SHOT_SAMPLE" && l.t < idle)
        .map(|l| flow_of(&l.hex))
        .fold(0.0, f32::max);
    assert!(peak_in_ending > 3.9, "the reported flow in Ending");
    lines[ending..]
        .iter()
        .find(|l| l.src == "DE1_SHOT_SAMPLE" && l.t >= idle && flow_of(&l.hex) < 0.3)
        .expect("the tail")
        .t
        - idle
}

/// Conformance: I17's 0.3 ml/s rule depends on how long the tail lasts, so the
/// sim's must match the capture's.
#[test]
fn the_sim_tail_matches_the_capture() {
    let mut de1 = de1_model::SimDe1::new(De1Params::default());
    de1.enter(MachineState::HotWater);
    for _ in 0..50 {
        de1.step(100);
    }
    de1.on_request(MachineState::Idle as u8);
    while de1.state != MachineState::Idle {
        de1.step(100);
    }
    // From the 4 ml/s the capture holds at the end of a 4 s `Ending`.
    let from = de1.group_flow();
    let mut sim_ms = 0;
    while de1.group_flow() >= 0.3 {
        de1.step(100);
        sim_ms += 100;
    }
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let scaled = (sim_ms as f32 * (4.0f32 / 0.3).ln() / (from / 0.3).ln()) as u64;
    let capture = capture_tail_ms();
    assert!(
        scaled.abs_diff(capture) <= 500,
        "sim {scaled} ms, capture {capture} ms"
    );
}

/// A world whose core opens on a shot already pouring, with a heavy cup.
fn unseen_shot() -> World {
    let mut world = plain_world();
    world.cup.grams = 60.0;
    world.apply(&Op::GroupHead(MachineState::Espresso));
    world.apply(&Op::Advance { ms: 3_000 });
    world.apply(&Op::Attach);
    world.apply(&Op::SetStepWeight(Some(3.6)));
    // Past the 5 s arming delay, so a weight leg or a skip could fire.
    world.apply(&Op::Advance { ms: 8_000 });
    world
}

/// I6 on the plainest case: the app opens mid-pour over a cup past target.
#[test]
fn an_attach_mid_pour_leaves_the_cup_alone() {
    let world = unseen_shot();
    assert_eq!(world.coverage.unseen, 1, "the core attached mid-pour");
    let broken: Vec<_> = world
        .violations
        .iter()
        .filter(|v| v.invariant == Invariant::I6)
        .collect();
    assert!(broken.is_empty(), "{broken:?}");
}

/// §7, "Start tapped during hot water": Idle and Espresso, as plain writes,
/// with the pour stopped. Whether a DE1 takes an Espresso that lands while the
/// pour winds down is H22's question; the sim's doesn't.
#[test]
fn a_start_tapped_during_hot_water_stops_it_and_pulls_the_shot() {
    let mut world = plain_world();
    // A first shot loads the profile, so the second start needs no upload,
    // which would wait for the pour to end.
    world.apply(&Op::AppStart { yield_g: 36.0 });
    world.apply(&Op::Advance { ms: 3_000 });
    world.apply(&Op::UserStop);
    world.apply(&Op::Advance { ms: 10_000 });
    assert_eq!(world.de1.state, MachineState::Idle);
    world.apply(&Op::GroupHead(MachineState::HotWater));
    world.apply(&Op::Advance { ms: 2_000 });
    let before = world.requests.len();
    world.apply(&Op::AppStart { yield_g: 36.0 });
    let idle = MachineState::Idle as u8;
    let espresso = MachineState::Espresso as u8;
    assert_eq!(world.requests[before..], [(idle, false), (espresso, false)]);
    world.apply(&Op::Advance { ms: 3_000 });
    assert_ne!(world.de1.state, MachineState::HotWater);
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// A flow begun after the tap is never bounced: the start stands down and
/// steam carries on.
#[test]
fn a_flow_begun_after_the_tap_stands_the_start_down() {
    let mut world = World::new(De1Params::default(), 0.0, 5_000, false, true);
    world.apply(&Op::LoseWrites(1));
    world.apply(&Op::AppStart { yield_g: 36.0 });
    world.apply(&Op::Advance { ms: 1_000 });
    world.apply(&Op::GroupHead(MachineState::Steam));
    world.apply(&Op::Advance { ms: 40_000 });
    assert_eq!(world.coverage.started, 0);
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// Review of #364, departure 4: Steam tapped while hot water pours and a
/// profile uploads waits out the guard, then goes out as the user's tap.
#[test]
fn a_users_held_tap_goes_out_once_the_guard_clears() {
    let mut world = plain_world();
    world.apply(&Op::AppStart { yield_g: 36.0 });
    world.apply(&Op::GroupHead(MachineState::HotWater));
    world.apply(&Op::Advance { ms: 100 });
    world.apply(&Op::AppRequest(MachineState::Steam));
    world.apply(&Op::Advance { ms: 3_000 });
    assert_eq!(world.coverage.held_releases, 1, "{:?}", world.requests);
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// Review of #373, must-fix 1: the machine honours a stop but its `Ending`
/// and `Idle` reports are lost, and the user starts a new shot soon after. The
/// new shot's pre-flow report is a new session, so the old owed stop never
/// fires at it.
#[test]
fn a_lost_idle_report_never_lets_an_old_stop_end_the_next_shot() {
    let mut world = plain_world();
    world.apply(&Op::GroupHead(MachineState::Espresso));
    while world.coverage.weight_stops == 0 {
        world.apply(&Op::Advance { ms: 100 });
        assert!(world.now_ms() < 120_000, "a weight stop");
    }
    world.apply(&Op::LoseStates(2));
    while world.de1.state != MachineState::Idle {
        world.apply(&Op::Advance { ms: 100 });
    }
    // Back within the read-back's reach: attempt 5 would read the new
    // shot's state, which is Espresso too.
    world.apply(&Op::Advance { ms: 1_500 });
    let before = world.requests.len();
    world.apply(&Op::GroupHead(MachineState::Espresso));
    world.apply(&Op::Advance { ms: 15_000 });
    let stops = world.requests[before..]
        .iter()
        .filter(|(_, stop)| *stop)
        .count();
    assert_eq!(stops, 0, "{:?}", &world.requests[before..]);
    assert_eq!(world.de1.state, MachineState::Espresso, "the new shot runs");
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// Review of #373, must-fix 2: the shrunk case where the core and the oracle
/// settled the 1 s outage-evidence boundary differently. A sample at or past
/// the wait is late for both.
#[test]
fn the_outage_evidence_boundary_is_settled_the_same_way() {
    for read_only in [false, true] {
        let mut world = World::new(
            De1Params {
                preinfusion: false,
                ending: Ending::None,
                ..De1Params::default()
            },
            0.0,
            5_000,
            read_only,
            false,
        );
        world.apply(&Op::Scale(ScaleMode::Stall));
        world.apply(&Op::GroupHead(MachineState::Espresso));
        world.apply(&Op::Advance { ms: 1_200 });
        world.apply(&Op::MuteSamples { ms: 7_400 });
        world.apply(&Op::LinkDown { ms: 6_500 });
        world.apply(&Op::Advance { ms: 20_000 });
        assert!(world.violations.is_empty(), "{:?}", world.violations);
    }
}

/// Review of #369, with auto-tare off: a hot-water pour that starts while the
/// scale's last reading is stale gets no weight target. When readings come
/// back, the weight is trusted but no weight stop is armed, so the app's `V`
/// leg must stay live. Without it, only the machine's 250 ml packet would
/// end the pour.
#[test]
fn hot_water_started_on_a_stale_scale_still_stops_at_its_volume() {
    let mut world = plain_world();
    // A dose the sim pours well inside the packet's 60 s timeout.
    world.set_hot_water_ml(60.0);
    world.apply(&Op::SetAutoTare(false));
    world.apply(&Op::SetSteamTarget(148.0));
    world.apply(&Op::Advance { ms: 2_000 });
    // The scale goes quiet right after a tick, and the pour's first report
    // lands a second later, before a tick can rewrite the packet to V: the
    // gap a connection-priority change or an HDS wake makes at a flow's start.
    while !world.now_ms().is_multiple_of(1_000) {
        world.apply(&Op::Advance { ms: 100 });
    }
    world.apply(&Op::Scale(ScaleMode::Stall));
    world.apply(&Op::Advance { ms: 900 });
    let before = world.requests.len();
    world.apply(&Op::GroupHead(MachineState::HotWater));
    world.apply(&Op::Advance { ms: 300 });
    world.apply(&Op::Scale(ScaleMode::Healthy));
    world.apply(&Op::Advance { ms: 120_000 });
    let stops = world.requests[before..]
        .iter()
        .filter(|(_, stop)| *stop)
        .count();
    assert!(stops >= 1, "an app stop: {:?}", &world.requests[before..]);
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// #316.1: a newer firmware's pour substate is a byte the app doesn't know, so
/// the core hears Heating, then `Unknown`. That is first flow (`DESIGN.md`
/// §3.3): the shot stops by weight at its 36 g target. Before, it never
/// armed a stop and ran to the end of its frames.
#[test]
fn a_pour_heard_only_as_unknown_stops_at_its_target() {
    for preinfusion in [false, true] {
        let mut world = World::new(
            De1Params {
                preinfusion,
                unknown_pour: true,
                ..De1Params::default()
            },
            0.0,
            5_000,
            false,
            false,
        );
        world.apply(&Op::GroupHead(MachineState::Espresso));
        world.apply(&Op::Advance { ms: 90_000 });
        assert_eq!(world.coverage.weight_stops, 1, "a weight stop");
        assert!(
            (34.0..40.0).contains(&world.cup.grams),
            "stopped at {} g",
            world.cup.grams
        );
        assert!(world.violations.is_empty(), "{:?}", world.violations);
    }
}

/// I20 after a lost Idle report: the next shot's pre-flow report opens a new
/// session, which counts its volume from zero and gets a fresh auto-stop, so
/// it too stops at its target.
#[test]
fn a_shot_after_a_lost_idle_report_gets_its_own_stop() {
    let mut world = plain_world();
    world.apply(&Op::GroupHead(MachineState::Espresso));
    while world.coverage.weight_stops == 0 {
        world.apply(&Op::Advance { ms: 100 });
        assert!(world.now_ms() < 120_000, "a weight stop");
    }
    world.apply(&Op::LoseStates(2));
    while world.de1.state != MachineState::Idle {
        world.apply(&Op::Advance { ms: 100 });
    }
    world.apply(&Op::Advance { ms: 1_500 });
    world.apply(&Op::GroupHead(MachineState::Espresso));
    world.apply(&Op::Advance { ms: 60_000 });
    assert_eq!(world.coverage.replaced, 1, "a new session in espresso");
    assert_eq!(world.coverage.weight_stops, 2, "the new shot's own stop");
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// I20 on the sample path: a shot ends and another starts during an outage.
/// The link comes back on the new one's first frame, a lower frame than the
/// last sample before the drop, so the core splits on that sample.
#[test]
fn a_shot_begun_during_an_outage_starts_from_zero() {
    let mut world = plain_world();
    world.apply(&Op::SetProfileTarget(None));
    world.apply(&Op::GroupHead(MachineState::Espresso));
    // Frame 2, 45 s into the mode.
    world.apply(&Op::Advance { ms: 45_000 });
    world.apply(&Op::LinkDown { ms: 32_000 });
    world.apply(&Op::Advance { ms: 24_000 });
    assert_eq!(world.de1.state, MachineState::Idle, "the first shot ended");
    world.apply(&Op::GroupHead(MachineState::Espresso));
    world.apply(&Op::Advance { ms: 20_000 });
    assert_eq!(world.coverage.replaced, 1, "split on the frame");
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// §7's model case, `[AppStart, Advance 4 s, Bump 18]`, on the capture's
/// shape, where water reaches the cup late: a hand on the cup, or a spoon left
/// on the scale, lands before the gate on a still cup. The settle moves the
/// zero by the step, weight stays live, and the shot stops at a true 36 g.
#[test]
fn a_hand_or_a_spoon_on_a_still_cup_still_stops_at_a_true_36_g() {
    for load in [
        ScaleMode::Bump {
            grams: 18.0,
            ms: 3_000,
        },
        ScaleMode::StepUp { grams: 18.0 },
    ] {
        let mut world = World::new(De1Params::default(), 50.0, 5_000, false, false);
        world.apply(&Op::AppStart { yield_g: 36.0 });
        world.apply(&Op::Advance { ms: 4_000 });
        world.apply(&Op::Scale(load));
        while world.coverage.stops == 0 {
            world.apply(&Op::Advance { ms: 100 });
            assert!(world.now_ms() < 120_000, "{load:?}: a stop");
        }
        assert_eq!(world.coverage.weight_stops, 1, "{load:?}: by weight");
        let cup = world.true_cup_g();
        assert!(
            (33.0..40.0).contains(&cup),
            "{load:?}: stopped at a true {cup} g"
        );
        assert!(
            world.violations.is_empty(),
            "{load:?}: {:?}",
            world.violations
        );
    }
}

/// The same load landing before the gate while the cup is already filling
/// can't hold still to the settle's 1 g band, so after 2 s the scale is
/// refused as disturbed: `Blind`, and the backstop ends the shot at 114 ml.
/// Never an early weight stop.
#[test]
fn a_load_on_a_filling_cup_before_the_gate_leaves_the_shot_to_the_backstop() {
    let mut world = plain_world();
    world.apply(&Op::AppStart { yield_g: 36.0 });
    world.apply(&Op::Advance { ms: 4_000 });
    world.apply(&Op::Scale(ScaleMode::StepUp { grams: 18.0 }));
    while world.coverage.stops == 0 {
        world.apply(&Op::Advance { ms: 100 });
        assert!(world.now_ms() < 120_000, "a stop");
    }
    assert_eq!(world.coverage.volume_stops, 1, "the backstop");
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// Rule (b), the shrunk repro: the scale stalls before the shot pours into the
/// cup and comes back with a 7 g hand on it, which is adopted (`Tared` has no
/// returning check). The hand lifts while the cup is still empty, so the zero
/// stays where it was and the shot stops by weight at a true 36 g, not 7 g
/// early.
#[test]
fn a_hand_lifting_off_an_empty_cup_leaves_the_zero_alone() {
    let mut world = World::new(
        De1Params {
            preinfusion: false,
            ending: Ending::None,
            ..De1Params::default()
        },
        50.0,
        5_000,
        false,
        false,
    );
    for op in [
        Op::Advance { ms: 200 },
        Op::AppStart { yield_g: 18.0 },
        Op::Advance { ms: 500 },
        Op::Scale(ScaleMode::Stall),
        Op::Advance { ms: 5_900 },
        Op::Scale(ScaleMode::Bump {
            grams: 7.0,
            ms: 300,
        }),
    ] {
        world.apply(&op);
    }
    while world.coverage.stops == 0 {
        world.apply(&Op::Advance { ms: 100 });
        assert!(world.now_ms() < 120_000, "a stop");
    }
    assert_eq!(world.coverage.weight_stops, 1, "by weight");
    let cup = world.true_cup_g();
    assert!((33.0..40.0).contains(&cup), "stopped at a true {cup} g");
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// §3.5's band, 3 g + 12 g/s × dt, after the gate: a 5 g or 7 g load is a
/// disturbance and the estimate finishes near target. A 4 g one sits on the
/// band's edge at 10 Hz (4.2 g with the pour's own gain): read as coffee, it
/// costs at most I13's 6 g.
#[test]
fn a_load_after_the_gate_stays_within_tolerance() {
    // 7 g is the band's own check: under a band widened to 8 g it would read
    // as coffee and stop the shot about 8 g early, which I13 catches.
    for grams in [7.0, 4.0, 5.0] {
        let mut world = plain_world();
        world.apply(&Op::GroupHead(MachineState::Espresso));
        while world.true_cup_g() < 25.0 {
            world.apply(&Op::Advance { ms: 100 });
        }
        world.apply(&Op::Scale(ScaleMode::Bump { grams, ms: 2_000 }));
        while world.coverage.stops == 0 {
            world.apply(&Op::Advance { ms: 100 });
            assert!(world.now_ms() < 120_000, "{grams} g: a stop");
        }
        // I13 first: the oracle's exemption doesn't cover a load seen
        // arriving on a live reading.
        assert!(
            world.violations.is_empty(),
            "{grams} g: {:?}",
            world.violations
        );
        let cup = world.true_cup_g();
        assert!(cup >= 30.0, "{grams} g: stopped at a true {cup} g");
        if grams >= 5.0 {
            assert_eq!(world.coverage.estimated_stops, 1, "{grams} g: on estimate");
        }
    }
}

/// The known limit (`DESIGN.md` §3.5): a scale back from a gap before the gate
/// already carrying a load reads it as coffee and can stop early. The oracle
/// skips I13 for exactly this shape, while the load stays.
#[test]
fn a_scale_back_loaded_before_the_gate_is_the_known_limit() {
    let mut world = World::new(De1Params::default(), 50.0, 5_000, false, false);
    world.apply(&Op::GroupHead(MachineState::Espresso));
    world.apply(&Op::Advance { ms: 3_000 });
    world.apply(&Op::Scale(ScaleMode::Silent));
    world.apply(&Op::Advance { ms: 5_000 });
    world.apply(&Op::Scale(ScaleMode::StepUp { grams: 23.0 }));
    world.apply(&Op::Advance { ms: 90_000 });
    assert_eq!(world.coverage.unseen_loads, 1);
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// Drip learning skips a stop that needed a retry. Three shots lose their
/// stop's first writes, so each overshoots while the core retries; that
/// overshoot is not drip. The next, clean shot still stops on target.
#[test]
fn a_clean_shot_after_ignored_stops_stops_on_target() {
    let mut world = plain_world();
    for shot in 0..4 {
        world.apply(&Op::GroupHead(MachineState::Espresso));
        if shot < 3 {
            // Lost before the stop goes out: two writes per shot, retried.
            world.apply(&Op::Advance { ms: 15_000 });
            world.apply(&Op::LoseWrites(2));
        }
        let stops = world.coverage.stops;
        while world.coverage.stops == stops {
            world.apply(&Op::Advance { ms: 100 });
            assert!(world.now_ms() < 600_000, "shot {shot} stops");
        }
        if shot == 3 {
            let cup = world.true_cup_g();
            assert!((33.0..40.0).contains(&cup), "stopped at a true {cup} g");
        }
        while world.de1.state != MachineState::Idle {
            world.apply(&Op::Advance { ms: 100 });
        }
        world.apply(&Op::Advance { ms: 15_000 });
    }
    assert!(world.coverage.retries >= 3, "the first stops were retried");
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// The oracle's I13 exemption, as a count per run: `PROPTEST_CASES` cases
/// (3000 if unset) from a fixed seed. Run with `--ignored --nocapture`.
#[test]
#[ignore = "a report, not a gate"]
fn report_exempted_sessions() {
    let count = std::env::var("PROPTEST_CASES")
        .ok()
        .and_then(|n| n.parse().ok())
        .unwrap_or(3_000);
    let (mut exempt, mut shots, mut broken) = (0, 0, 0);
    for case in sample_cases(count) {
        let world = run(&case);
        exempt += world.coverage.unseen_loads;
        shots += world.coverage.shots;
        broken += world
            .violations
            .iter()
            .filter(|v| v.invariant.status() == Status::Enforced)
            .count();
    }
    println!("{count} cases: {shots} shots, {exempt} exempted from I13, {broken} violations");
}

/// The returning check's bounds, each in the model: after the gate the scale
/// stalls for 2 s and comes back 20 g heavier than the cup could be (the
/// upper bound), or zeroed (the lower bound). Both are refused, and the
/// estimate stops the shot near target. Without the upper bound the 20 g is
/// adopted and I13 fails; without the lower bound the re-zero is adopted and
/// the shot runs on past target, which only this case's bound sees (I13 is an
/// early-stop invariant).
#[test]
fn a_returning_scale_outside_the_band_is_refused() {
    for back in [
        ScaleMode::StepUp { grams: 20.0 },
        ScaleMode::GapRezero { ms: 2_000 },
    ] {
        let mut world = plain_world();
        world.apply(&Op::GroupHead(MachineState::Espresso));
        while world.true_cup_g() < 20.0 {
            world.apply(&Op::Advance { ms: 100 });
        }
        if back == (ScaleMode::StepUp { grams: 20.0 }) {
            world.apply(&Op::Scale(ScaleMode::Stall));
            world.apply(&Op::Advance { ms: 2_000 });
        }
        world.apply(&Op::Scale(back));
        while world.coverage.stops == 0 {
            world.apply(&Op::Advance { ms: 100 });
            assert!(world.now_ms() < 120_000, "{back:?}: a stop");
        }
        assert!(
            world.violations.is_empty(),
            "{back:?}: {:?}",
            world.violations
        );
        assert_eq!(world.coverage.estimated_stops, 1, "{back:?}: on estimate");
        let cup = world.true_cup_g();
        assert!(
            (33.0..40.0).contains(&cup),
            "{back:?}: stopped at a true {cup} g"
        );
    }
}

/// §3.5's known limit (#389): a spoon set down during preheat, just before
/// flow, too late for the late-cup re-tare and before the step detector
/// watches, reads as coffee. The oracle skips I13 for exactly that: a load
/// that moved between the last zero and first flow, with no tare after it.
#[test]
fn a_load_set_down_just_before_flow_is_the_known_limit() {
    let mut world = World::new(De1Params::default(), 50.0, 5_000, false, false);
    world.apply(&Op::GroupHead(MachineState::Espresso));
    world.apply(&Op::Advance { ms: 1_100 });
    world.apply(&Op::Scale(ScaleMode::StepUp { grams: 5.0 }));
    world.apply(&Op::Advance { ms: 90_000 });
    assert_eq!(world.coverage.unseen_loads, 1);
    assert!(world.violations.is_empty(), "{:?}", world.violations);
}

/// The known limit stops at the untared-cup guard: a 60 g cup set down just
/// before flow, too late for the late-cup re-tare, is the guard's. It settles,
/// the guard latches a virtual zero, and the shot stops at a true 36 g. I13
/// judges it; with the guard off, the shot stops at once.
#[test]
fn a_heavy_cup_set_down_just_before_flow_is_the_untared_guards() {
    let mut world = World::new(De1Params::default(), 50.0, 5_000, false, false);
    world.apply(&Op::GroupHead(MachineState::Espresso));
    world.apply(&Op::Advance { ms: 1_100 });
    world.apply(&Op::Scale(ScaleMode::StepUp { grams: 60.0 }));
    while world.coverage.stops == 0 {
        world.apply(&Op::Advance { ms: 100 });
        assert!(world.now_ms() < 120_000, "a stop");
    }
    assert_eq!(world.coverage.unseen_loads, 0, "judged by I13");
    assert!(world.violations.is_empty(), "{:?}", world.violations);
    let cup = world.true_cup_g();
    assert!((33.0..40.0).contains(&cup), "stopped at a true {cup} g");
}
