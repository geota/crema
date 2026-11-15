//! Stats as SQL (SPEC §7): one `GROUP BY family` pass sums each row's stat
//! inputs, and the core applies `brew_history_stats`'s scope rule to the
//! per-family sums here. Count, beans used and rating span every family;
//! weight, ratio and time cover the single family, or espresso when mixed.

use de1_domain::BrewHistoryStats;
use serde::{Deserialize, Serialize};

use crate::derive::Derived;

/// One family's sums: a row of the stats query.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FamilySums {
    /// The family; `None` is espresso.
    pub family: Option<String>,
    /// Rows in the family.
    pub count: u64,
    /// Σ `st_dose`, and how many rows had one.
    pub dose: (f64, u64),
    /// Σ `st_yield`, and how many.
    pub yield_g: (f64, u64),
    /// Σ `st_ratio`, and how many.
    pub ratio: (f64, u64),
    /// Σ `st_time_s`, and how many.
    pub time_s: (f64, u64),
    /// Σ `st_rating`, and how many.
    pub rating: (f64, u64),
}

impl FamilySums {
    fn add(&mut self, d: &Derived) {
        fn acc(slot: &mut (f64, u64), v: Option<f64>) {
            if let Some(v) = v {
                slot.0 += v;
                slot.1 += 1;
            }
        }
        self.count += 1;
        acc(&mut self.dose, d.st_dose);
        acc(&mut self.yield_g, d.st_yield);
        acc(&mut self.ratio, d.st_ratio);
        acc(&mut self.time_s, d.st_time_s);
        acc(&mut self.rating, d.st_rating);
    }
}

/// The stats query's answer, worked out in memory: one [`FamilySums`] per
/// family, in first-seen order.
pub fn family_sums<'a>(rows: impl IntoIterator<Item = &'a Derived>) -> Vec<FamilySums> {
    let mut out: Vec<FamilySums> = Vec::new();
    for d in rows {
        let i = match out.iter().position(|f| f.family == d.family) {
            Some(i) => i,
            None => {
                out.push(FamilySums {
                    family: d.family.clone(),
                    ..FamilySums::default()
                });
                out.len() - 1
            }
        };
        out[i].add(d);
    }
    out
}

/// The stat strip from per-family sums, under `brew_history_stats`'s scope
/// rule. Families with no rows are ignored.
// Results are display values and stay f32, as in `brew_history_stats`.
#[allow(clippy::cast_precision_loss, clippy::cast_possible_truncation)]
#[must_use]
pub fn combine(groups: &[FamilySums]) -> BrewHistoryStats {
    let groups: Vec<&FamilySums> = groups.iter().filter(|g| g.count > 0).collect();
    let mixed = groups.len() > 1;
    let scope_family: Option<String> = if mixed {
        None
    } else {
        groups.first().and_then(|g| g.family.clone())
    };
    let scoped: Vec<&&FamilySums> = groups.iter().filter(|g| g.family == scope_family).collect();

    let total = |pick: fn(&FamilySums) -> (f64, u64), over: &[&&FamilySums]| {
        over.iter()
            .map(|g| pick(g))
            .fold((0.0_f64, 0_u64), |(s, n), (gs, gn)| (s + gs, n + gn))
    };
    let mean = |(s, n): (f64, u64)| (n > 0).then(|| (s / n as f64) as f32);
    let all: Vec<&&FamilySums> = groups.iter().collect();
    let dose = total(|g| g.dose, &all);
    let count: u64 = groups.iter().map(|g| g.count).sum();

    BrewHistoryStats {
        count: u32::try_from(count).unwrap_or(u32::MAX),
        beans_used_g: (dose.1 > 0).then_some(dose.0 as f32),
        avg_weight_g: mean(total(|g| g.yield_g, &scoped)),
        avg_ratio: mean(total(|g| g.ratio, &scoped)),
        avg_time_s: mean(total(|g| g.time_s, &scoped)),
        avg_rating: mean(total(|g| g.rating, &all)),
        mixed_methods: mixed,
        scope_method: scope_family,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::derive::{derive_value, stat_input};
    use de1_domain::brew_history_stats;
    use serde_json::{Value, json};

    fn rows() -> Vec<Value> {
        vec![
            json!({ "metadata": { "dose": 18.0, "yieldOut": 36.0, "rating": 4 }, "record": { "duration": 28_000 } }),
            json!({ "metadata": { "dose": 18.5, "rating": 0 }, "record": { "duration": 31_000 },
                    "summary": { "peakWeight": 40.0 } }),
            json!({ "brewMethod": "pourover", "metadata": { "dose": 15.0, "waterG": 250.0, "rating": 5 },
                    "record": { "duration": 0 } }),
        ]
    }

    fn both(rows: &[Value]) -> (BrewHistoryStats, BrewHistoryStats) {
        let derived: Vec<Derived> = rows
            .iter()
            .map(|r| derive_value(r).expect("derives"))
            .collect();
        let inputs: Vec<_> = rows.iter().map(stat_input).collect();
        (combine(&family_sums(&derived)), brew_history_stats(&inputs))
    }

    #[test]
    fn a_mixed_set_scopes_to_espresso() {
        let (sql, core) = both(&rows());
        assert_eq!(sql, core);
        assert!(sql.mixed_methods);
        assert_eq!(sql.count, 3);
        assert_eq!(sql.avg_rating, Some(4.5));
        assert_eq!(sql.avg_weight_g, Some(38.0));
    }

    #[test]
    fn one_family_scopes_to_it() {
        let (sql, core) = both(&rows()[2..]);
        assert_eq!(sql, core);
        assert_eq!(sql.scope_method.as_deref(), Some("pourover"));
        assert_eq!(sql.avg_time_s, None);
    }

    #[test]
    fn no_rows_are_no_stats() {
        let (sql, core) = both(&[]);
        assert_eq!(sql, core);
        assert_eq!(sql.count, 0);
        assert_eq!(combine(&[FamilySums::default()]), core);
    }
}
