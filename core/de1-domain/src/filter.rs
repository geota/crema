//! A sliding-window median filter for smoothing a noisy signal.

use std::collections::VecDeque;

/// A sliding-window median filter.
///
/// Push samples; it keeps the most recent `window` of them and reports their
/// median. Its purpose is to make a vibration-noisy scale-weight stream robust
/// before it drives SAW (stop-at-weight): a knock on the scale or the impact
/// of a drop moves the *mean* but barely moves the *median*.
#[derive(Debug, Clone)]
pub struct MedianFilter {
    window: usize,
    samples: VecDeque<f32>,
}

impl MedianFilter {
    /// Create a filter that keeps the most recent `window` samples. A `window`
    /// of 0 is treated as 1.
    pub fn new(window: usize) -> MedianFilter {
        MedianFilter {
            window: window.max(1),
            samples: VecDeque::new(),
        }
    }

    /// Push a sample and return the median of the current window.
    pub fn push(&mut self, value: f32) -> f32 {
        if self.samples.len() == self.window {
            self.samples.pop_front();
        }
        self.samples.push_back(value);
        self.median().expect("the window is non-empty after a push")
    }

    /// The median of the current window, or `None` if no samples have been
    /// pushed since the last [`clear`](Self::clear).
    pub fn median(&self) -> Option<f32> {
        let window: Vec<f32> = self.samples.iter().copied().collect();
        median(&window)
    }

    /// Whether no samples are currently buffered.
    pub fn is_empty(&self) -> bool {
        self.samples.is_empty()
    }

    /// Discard all buffered samples.
    pub fn clear(&mut self) {
        self.samples.clear();
    }
}

/// The median of `values`, or `None` if empty. For an even count it is the
/// mean of the two central values. `NaN`s sort to one end (via `total_cmp`).
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
    fn an_empty_filter_has_no_median() {
        assert_eq!(MedianFilter::new(5).median(), None);
    }

    #[test]
    fn an_odd_window_returns_the_middle_value() {
        let mut filter = MedianFilter::new(5);
        filter.push(3.0);
        filter.push(1.0);
        assert_eq!(filter.push(2.0), 2.0);
    }

    #[test]
    fn an_even_window_averages_the_two_middle_values() {
        let mut filter = MedianFilter::new(4);
        filter.push(1.0);
        filter.push(2.0);
        filter.push(3.0);
        assert_eq!(filter.push(4.0), 2.5);
    }

    #[test]
    fn old_samples_fall_out_of_the_window() {
        let mut filter = MedianFilter::new(3);
        filter.push(1.0);
        filter.push(2.0);
        assert_eq!(filter.push(3.0), 2.0);
        // Pushing a 4th value evicts the 1.0; window is now {2, 3, 100}.
        assert_eq!(filter.push(100.0), 3.0);
    }

    #[test]
    fn a_single_outlier_does_not_move_the_median() {
        let mut filter = MedianFilter::new(5);
        for value in [10.0, 10.0, 10.0, 1000.0, 10.0] {
            filter.push(value);
        }
        // The 1000 g vibration spike is rejected.
        assert_eq!(filter.median(), Some(10.0));
    }

    #[test]
    fn clear_empties_the_filter() {
        let mut filter = MedianFilter::new(3);
        filter.push(5.0);
        filter.clear();
        assert!(filter.is_empty());
        assert_eq!(filter.median(), None);
    }
}
