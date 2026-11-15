//! A Rust port of `tests/fixtures/gen.mjs`, the real-shaped row generator,
//! for any number of rows (PLAN B1). Same RNG (mulberry32), same seed, same
//! draws in the same order, so `rows(300)` is the fixture's
//! `rows.web.jsonl` (checked by `the_port_reproduces_the_fixture`).
//!
//! One change for larger histories: the gap between shots shrinks so the
//! rows span at most about four years, as a busy machine's would, and the
//! runs of equal times repeat every 300 rows. At 300 rows nothing changes.

use serde_json::{Value, json};

const NOW: i64 = 1_790_000_000_000;
const HOUR: f64 = 3_600_000.0;
/// The longest span a generated history covers, in hours: about four years.
const SPAN_HOURS: f64 = 4.0 * 365.0 * 24.0;
/// The fixture's mean gap between shots, in hours (9 to 14).
const MEAN_GAP_HOURS: f64 = 11.5;

/// mulberry32, as `gen.mjs` has it.
struct Rng(u32);

impl Rng {
    fn next(&mut self) -> f64 {
        self.0 = self.0.wrapping_add(0x6d2b_79f5);
        let s = self.0;
        let mut t = (s ^ (s >> 15)).wrapping_mul(1 | s);
        t = t.wrapping_add((t ^ (t >> 7)).wrapping_mul(61 | t)) ^ t;
        f64::from(t ^ (t >> 14)) / 4_294_967_296.0
    }

    /// `Math.floor(rnd() * n)`.
    fn below(&mut self, n: usize) -> usize {
        // The product is in [0, n), so the floor fits.
        #[allow(clippy::cast_possible_truncation, clippy::cast_precision_loss)]
        let i = (self.next() * n as f64).floor() as usize;
        i.min(n - 1)
    }

    fn pick<T: Copy>(&mut self, xs: &[T]) -> T {
        xs[self.below(xs.len())]
    }

    fn chance(&mut self, p: f64) -> bool {
        self.next() < p
    }

    /// `rhex(len)`: `len` random hex digits.
    fn hex(&mut self, len: usize) -> String {
        (0..len)
            .map(|_| {
                char::from_digit(u32::try_from(self.below(16)).unwrap_or(0), 16).unwrap_or('0')
            })
            .collect()
    }
}

fn round1(x: f64) -> f64 {
    (x * 10.0).round() / 10.0
}

/// `hex(n, 12)`: the last 12 hex digits of `n`, zero-padded.
fn hex12(n: i64) -> String {
    let s = format!("{n:012x}");
    s[s.len() - 12..].to_owned()
}

/// The bean snapshot a shot froze, by bag: `(name, roasterName, tags)`.
fn snapshot(bag: &str) -> (&'static str, &'static str, Option<&'static [&'static str]>) {
    match bag {
        "bean:a" => ("Gesha Village", "Sey", Some(&["fruity", "comp"])),
        "bean:b" => ("Etiopía Guji", "Onyx", Some(&["floral"])),
        "bean:c" => ("Etiopía Guji", "Onyx", None),
        "bean:d" => ("Colômbia El Paraíso", "Tim Wendelboe", Some(&["chocolate"])),
        "bean:e" => ("Red Brick", "Square Mile", Some(&["milk"])),
        "bean:f" => ("Kenya AA", "Sey", None),
        _ => ("Ruby", "Prodigal", None),
    }
}

const BAGS: [&str; 8] = [
    "bean:a", "bean:a", "bean:b", "bean:c", "bean:d", "bean:e", "bean:f", "bean:g",
];
const PROFILES: [&str; 7] = [
    "Adaptive v3",
    "Best Practice",
    "Turbo Bloom",
    "Londinium",
    "Café Crème",
    "blooming espresso",
    "Extractamundo Dos!",
];
const NOTES: [&str; 9] = [
    "Too sour, go finer",
    "Lovely, sweet finish",
    "Café-style milk drink",
    "Bitter end, pulled long",
    "Zuckersüß",
    "苦いが美味しい",
    "Naïve fruit bomb",
    "channeling?",
    "",
];
const TAGS: [&[&str]; 5] = [
    &["dial-in"],
    &["milk"],
    &["dial-in", "sour"],
    &["guest"],
    &["Café"],
];
const BREWS: [(&str, Option<&str>); 5] = [
    ("pourover", Some("Hoffmann V60")),
    ("pourover", None),
    ("aeropress", Some("Inverted")),
    ("french_press", None),
    ("karlsbad_kanne", None),
];

/// `n` rows in the web's `ShotRow` shape, newest first (ties by id
/// descending), as `gen.mjs` writes `rows.web.jsonl`.
pub fn rows(n: usize) -> Vec<Value> {
    let mut rng = Rng(0x005e_edc1);
    // Shrink the gaps so `n` rows span at most `SPAN_HOURS`.
    #[allow(clippy::cast_precision_loss)]
    let squeeze = (SPAN_HOURS / (n as f64 * MEAN_GAP_HOURS)).min(1.0);
    let mut at = NOW - 2 * 3_600_000;
    let mut out: Vec<(i64, String, Value)> = Vec::with_capacity(n);
    for i in 0..n {
        // Two runs of equal times, as two shots logged in the same millisecond.
        if !matches!(i % 300, 21 | 101 | 102) {
            let mut gap = 9.0 * HOUR + rng.next() * 5.0 * HOUR;
            if squeeze < 1.0 {
                gap *= squeeze;
            }
            // Under a day in ms: fits.
            #[allow(clippy::cast_possible_truncation)]
            let gap = gap.floor() as i64;
            at -= gap;
        }
        let brew = if rng.chance(0.18) {
            Some(rng.pick(&BREWS))
        } else {
            None
        };
        let bag_roll = rng.next();
        let bag = if bag_roll < 0.1 {
            None
        } else if bag_roll < 0.14 {
            Some("legacy")
        } else {
            Some(rng.pick(&BAGS))
        };
        // (name, roasterName, the bean's JSON)
        let bean: Option<(String, Option<String>, Value)> = match bag {
            None => None,
            Some("legacy") => Some((
                "Market Blend".into(),
                None,
                json!({ "name": "Market Blend", "roasterName": null }),
            )),
            Some(bag) => {
                let (name, roaster, tags) = snapshot(bag);
                let mut b = json!({ "beanId": bag, "name": name, "roasterName": roaster });
                if let Some(tags) = tags {
                    b["tags"] = json!(tags);
                }
                if rng.chance(0.3) {
                    b["grinder"] = json!("Niche Zero");
                }
                Some((name.into(), Some(roaster.into()), b))
            }
        };
        let rating = if rng.chance(0.25) {
            Value::Null
        } else if rng.chance(0.06) {
            json!(0)
        } else {
            json!(1 + rng.below(5))
        };
        let dose = if brew.is_some() {
            json!(15)
        } else if rng.chance(0.08) {
            Value::Null
        } else {
            json!(round1(17.5 + rng.next() * 2.0))
        };
        let final_weight = if brew.is_some() {
            None
        } else {
            Some(round1(32.0 + rng.next() * 8.0))
        };
        let yield_out = if brew.is_some() {
            if rng.chance(0.5) {
                json!(round1(200.0 + rng.next() * 30.0))
            } else {
                Value::Null
            }
        } else if rng.chance(0.15) {
            Value::Null
        } else {
            json!(final_weight)
        };
        let peak_weight = final_weight.map(|f| round1(f + rng.next() * 1.5));
        let manual = brew.is_some() && rng.chance(0.4);
        // Under a few minutes in ms: fits.
        #[allow(clippy::cast_possible_truncation)]
        let duration = if brew.is_some() {
            if manual {
                0
            } else {
                (180_000.0 + rng.next() * 80_000.0).round() as i64
            }
        } else {
            (25_000.0 + rng.next() * 20_000.0).round() as i64
        };
        let sample_count = if brew.is_some() {
            0
        } else {
            100 + rng.below(120)
        };
        let h = hex12(at);
        let id = format!(
            "shot:{}-{}-7{}-8{}-{}",
            &h[..8],
            &h[8..],
            rng.hex(3),
            rng.hex(3),
            rng.hex(12)
        );
        let profile_name = if brew.is_some() || rng.chance(0.05) {
            Value::Null
        } else {
            json!(rng.pick(&PROFILES))
        };
        let notes = if rng.chance(0.6) {
            json!(rng.pick(&NOTES))
        } else {
            Value::Null
        };
        let next_plan = if rng.chance(0.2) {
            json!(rng.pick(&["go finer", "Dose 18.5", "Grind +2"]))
        } else {
            Value::Null
        };
        let barista = if rng.chance(0.7) {
            Some(rng.pick(&["Sam", "Zoë", "Ana"]))
        } else {
            None
        };
        let drinker = if rng.chance(0.2) { Some("Ben") } else { None };
        let legacy_beans = match &bean {
            Some((name, roaster, _)) if i > 240 => {
                let parts: Vec<&str> = [roaster.as_deref(), Some(name.as_str())]
                    .into_iter()
                    .flatten()
                    .filter(|s| !s.is_empty())
                    .collect();
                json!(parts.join(" · "))
            }
            _ => Value::Null,
        };
        let grinder_model = if rng.chance(0.75) {
            Some(rng.pick(&["Niche Zero", "EK43", "Lagom P64"]))
        } else {
            None
        };
        let tags: &[&str] = if rng.chance(0.35) {
            rng.pick(&TAGS)
        } else {
            &[]
        };
        let pinned = rng.chance(0.06);
        let visualizer_id = if brew.is_none() && rng.chance(0.5) {
            json!(format!(
                "{}-{}-{}-{}-{}",
                rng.hex(8),
                rng.hex(4),
                rng.hex(4),
                rng.hex(4),
                rng.hex(12)
            ))
        } else {
            Value::Null
        };
        let (peak_pressure, peak_temp) = if brew.is_some() {
            (json!(0), json!(0))
        } else {
            (
                json!(round1(8.0 + rng.next() * 2.0)),
                json!(round1(90.0 + rng.next() * 4.0)),
            )
        };

        // `webRow(s)`.
        let mut metadata = json!({
            "dose": dose,
            "yieldOut": yield_out,
            "beans": legacy_beans,
            "grinderSetting": null,
            "notes": notes,
            "nextPlan": next_plan,
            "rating": rating,
            "tds": null,
            "extractionYield": null,
            "waterG": if brew.is_some() { json!(250) } else { Value::Null },
        });
        if let Some(b) = barista {
            metadata["barista"] = json!(b);
        }
        if let Some(d) = drinker {
            metadata["drinker"] = json!(d);
        }
        let mut row = json!({
            "formatVersion": 3,
            "id": id,
            "completedAt": at,
            "profileName": profile_name,
            "stopReason": if brew.is_some() { Value::Null } else { json!("Weight") },
            "metadata": metadata,
            "record": { "duration": duration },
            "visualizerId": visualizer_id,
            "deletedAt": null,
            "summary": {
                "v": 1,
                "sampleCount": sample_count,
                "brewSampleCount": 0,
                "peakWeight": peak_weight,
                "finalWeight": final_weight,
                "peakPressure": peak_pressure,
                "peakTemp": peak_temp,
                "spark": null
            }
        });
        if let Some((_, _, b)) = &bean {
            row["bean"] = b.clone();
        }
        if let Some(g) = grinder_model {
            row["grinderModel"] = json!(g);
        }
        if !tags.is_empty() {
            row["tags"] = json!(tags);
        }
        if pinned {
            row["pinned"] = json!(true);
        }
        if let Some((method, recipe)) = brew {
            row["brewMethod"] = json!(method);
            if let Some(r) = recipe {
                row["recipeName"] = json!(r);
            }
        }
        out.push((at, row["id"].as_str().unwrap_or_default().to_owned(), row));
    }
    // The store's order: newest first, ties by id descending.
    out.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| b.1.cmp(&a.1)));
    out.into_iter().map(|(_, _, row)| row).collect()
}

/// Deep equality, numbers compared as `f64` (JSON writes `18.0` as `18`).
fn same(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::Number(x), Value::Number(y)) => x.as_f64() == y.as_f64(),
        (Value::Array(x), Value::Array(y)) => {
            x.len() == y.len() && x.iter().zip(y).all(|(x, y)| same(x, y))
        }
        (Value::Object(x), Value::Object(y)) => {
            x.len() == y.len() && x.iter().all(|(k, v)| y.get(k).is_some_and(|w| same(v, w)))
        }
        _ => a == b,
    }
}

#[test]
fn the_port_reproduces_the_fixture() {
    let path =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/rows.web.jsonl");
    let fixture: Vec<Value> = std::fs::read_to_string(path)
        .expect("the fixture")
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(|l| serde_json::from_str(l).expect("JSON"))
        .collect();
    let ours = rows(300);
    assert_eq!(ours.len(), fixture.len());
    for (i, (a, b)) in ours.iter().zip(&fixture).enumerate() {
        assert!(same(a, b), "row {i}:\n ours    {a}\n fixture {b}");
    }
}

#[test]
fn larger_histories_have_unique_ids_and_a_bounded_span() {
    let rows = rows(20_000);
    let ids: std::collections::HashSet<&str> =
        rows.iter().filter_map(|r| r["id"].as_str()).collect();
    assert_eq!(ids.len(), rows.len());
    let newest = rows[0]["completedAt"].as_i64().expect("a time");
    let oldest = rows[rows.len() - 1]["completedAt"]
        .as_i64()
        .expect("a time");
    assert!(oldest > 0 && newest - oldest <= 5 * 365 * 24 * 3_600_000);
}
