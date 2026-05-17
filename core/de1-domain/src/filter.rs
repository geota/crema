//! Median computation for smoothing a vibration-noisy signal.

/// The median of `values`, or `None` if empty. For an even count it is the
/// mean of the two central values. `NaN`s sort to one end (via `total_cmp`).
///
/// The median is robust to vibration: a knock on the scale or the impact of a
/// drop moves the *mean* but barely moves the *median* — which is why the flow
/// estimator and SAW logic smooth the scale stream through it.
pub fn median(values: &[f32]) -> Option<f32> {
    if values.is_empty() {
        return None;
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(f32::total_cmp);
    let mid = sorted.len() / 2;
    Some(if sorted.len() % 2 == 1 {
        sorted[mid]
    } else {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_empty_slice_has_no_median() {
        assert_eq!(median(&[]), None);
    }

    #[test]
    fn an_odd_count_returns_the_middle_value() {
        assert_eq!(median(&[3.0, 1.0, 2.0]), Some(2.0));
    }

    #[test]
    fn an_even_count_averages_the_two_middle_values() {
        assert_eq!(median(&[1.0, 2.0, 3.0, 4.0]), Some(2.5));
    }

    #[test]
    fn a_single_outlier_does_not_move_the_median() {
        // A 1000 g vibration spike among steady 10 g readings is rejected.
        assert_eq!(median(&[10.0, 10.0, 10.0, 1000.0, 10.0]), Some(10.0));
    }
}
