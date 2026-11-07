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
        1 => (1u8..20).prop_map(|g| ScaleMode::Bump { grams: f32::from(g) }),
        1 => (1u8..250).prop_map(|g| ScaleMode::StepUp { grams: f32::from(g) }),
        1 => Just(ScaleMode::TransientZero),
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
        1 => target().prop_map(Op::SetProfileTarget),
        1 => target().prop_map(Op::Dial),
        1 => prop_oneof![Just(None), Just(Some(36.0f32)), Just(Some(80.0f32))]
            .prop_map(Op::SetVolumeLimit),
        1 => prop_oneof![Just(None), Just(Some(30.0f32))].prop_map(Op::SetMaxTime),
        1 => prop_oneof![Just(0.0f32), Just(148.0f32)].prop_map(Op::SetSteamTarget),
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
        1 => prop_oneof![Just(Op::HangWrite), Just(Op::ReorderWrites)],
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
    )
        .prop_map(
            |(params, headspace_ml, lane_timeout_ms, read_only, pre_flush, ops)| Case {
                params,
                headspace_ml,
                lane_timeout_ms,
                read_only,
                pre_flush,
                ops,
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
    for case in sample_cases(200) {
        let c = run(&case).coverage;
        total.shots += c.shots;
        total.water += c.water;
        total.steam += c.steam;
        total.stops += c.stops;
        total.weight_stops += c.weight_stops;
        total.volume_stops += c.volume_stops;
        total.retries += c.retries;
        total.skips += c.skips;
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
        ("volume stops", total.volume_stops),
        ("retries", total.retries),
        ("skips", total.skips),
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
