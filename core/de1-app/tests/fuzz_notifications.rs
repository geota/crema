//! Deterministic mutation of notification bytes: no input, on any source, for
//! any scale, may panic the core. A fixed seed, so a failure reproduces.

use de1_app::{CremaCore, ScaleId, Source};

const ROUNDS: usize = 60;
const NOTIFICATIONS: usize = 200;

const SCALES: [&str; 19] = [
    "BOOKOO_SC",
    "Decent Scale",
    "ACAIA",
    "LUNAR-123",
    "PEARLS",
    "Skale",
    "FELICITA",
    "EUREKA",
    "CFS-9002",
    "ROASTSEE",
    "Hiroia Jimmy",
    "Difluid",
    "Mb1",
    "Timemore",
    "Varia AKU",
    "Atomheart Eclair",
    "SmartChef",
    "Solo Barista",
    "Pyxis",
];

const SOURCES: [Source; 13] = [
    Source::De1State,
    Source::De1ShotSample,
    Source::ScaleWeight,
    Source::ScaleCommand,
    Source::De1WaterLevels,
    Source::De1Version,
    Source::De1MmrRead,
    Source::De1Calibration,
    Source::De1ShotSettings,
    Source::De1ProfileHeader,
    Source::De1FrameAck,
    Source::De1HeaderAck,
    Source::ScaleButton,
];

/// xorshift64: enough to walk the input space, and it needs no dependency.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }

    fn below(&mut self, n: usize) -> usize {
        usize::try_from(self.next() % n.max(1) as u64).unwrap_or(0)
    }
}

/// Random bytes, biased towards the headers and states that reach the
/// decoders' interesting paths.
fn notification(r: &mut Rng) -> Vec<u8> {
    let len = match r.below(4) {
        0 => r.below(4),
        1 => r.below(12),
        2 => r.below(24),
        _ => r.below(64),
    };
    let mut data: Vec<u8> = (0..len).map(|_| r.next().to_le_bytes()[0]).collect();
    if r.below(3) == 0
        && let Some(first) = data.first_mut()
    {
        let headers = [0x03, 0xEF, 0x10, 0x01, 0x0A, 0xAA, 0x0B, 0, 4, 5, 2];
        *first = headers[r.below(headers.len())];
    }
    if r.below(4) == 0
        && let Some(second) = data.get_mut(1)
    {
        let bytes = [0x0B, 0xDD, 0x0C, 0, 5, 4, 2];
        *second = bytes[r.below(bytes.len())];
    }
    data
}

#[test]
fn no_notification_panics_the_core() {
    let mut r = Rng(0xDEAD_BEEF_CAFE_F00D);
    for name in SCALES {
        for _ in 0..ROUNDS {
            let mut core = CremaCore::new();
            let _ = core.connect_scale(name, &[]);
            let profile = de1_domain::CremaProfile {
                yield_out: 36.0,
                ..de1_domain::blank_profile(&de1_domain::BrewDefaults::default())
            };
            let _ = core.set_desired_profile(
                Some(&profile),
                &de1_domain::BrewOverrides::default(),
                std::time::Duration::ZERO,
            );
            let mut now_ms = 0u64;
            for _ in 0..NOTIFICATIONS {
                now_ms += r.below(300) as u64;
                let source = SOURCES[r.below(SOURCES.len())];
                let _ = core.on_notification(source, &notification(&mut r), now_ms);
                if r.below(10) == 0 {
                    let _ = core.on_tick(now_ms);
                }
            }
        }
    }
}

#[test]
fn the_fuzz_reaches_most_scale_codecs() {
    let known = SCALES
        .iter()
        .filter(|name| ScaleId::identify(name, &[]).is_some())
        .count();
    assert!(known >= 15, "only {known} of the names identify a scale");
}
