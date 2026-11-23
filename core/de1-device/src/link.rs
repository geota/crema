//! Link health from timestamps: did the device answer after connect, and is
//! it still talking.

/// Why [`LinkWatch`] gave up on a link.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LinkVerdict {
    /// Nothing valid arrived within the first-reading deadline.
    NoFirstReading,
    /// The device went quiet after it had been answering.
    Silent,
}

/// Watches one connection: a first valid reading must arrive within
/// `first_within_ms` of [`start`](Self::start), and after that the gap between
/// readings must stay under `silent_after_ms`. Gives one verdict per
/// connection, then stays quiet; a new connection builds a new watch.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LinkWatch {
    first_within_ms: u64,
    silent_after_ms: u64,
    started_at: Option<u64>,
    last_seen: Option<u64>,
    finished: bool,
}

impl LinkWatch {
    /// A watch that has not started.
    #[must_use]
    pub const fn new(first_within_ms: u64, silent_after_ms: u64) -> Self {
        LinkWatch {
            first_within_ms,
            silent_after_ms,
            started_at: None,
            last_seen: None,
            finished: false,
        }
    }

    /// The link is up and the device has been asked to talk; the first-reading
    /// deadline runs from here. A second call does not move it.
    pub fn start(&mut self, now_ms: u64) {
        self.started_at.get_or_insert(now_ms);
    }

    /// Whether [`start`](Self::start) has been called.
    #[must_use]
    pub fn started(&self) -> bool {
        self.started_at.is_some()
    }

    /// A valid reading arrived. Returns `true` for the first one.
    pub fn seen(&mut self, now_ms: u64) -> bool {
        let first = self.last_seen.is_none();
        self.last_seen = Some(self.last_seen.map_or(now_ms, |last| last.max(now_ms)));
        first
    }

    /// Whether a valid reading has arrived.
    #[must_use]
    pub fn ready(&self) -> bool {
        self.last_seen.is_some()
    }

    /// Whether the watch has given its verdict (or was [`finish`](Self::finish)ed).
    #[must_use]
    pub fn finished(&self) -> bool {
        self.finished
    }

    /// Stop watching without a verdict (the driver gave up for its own reason).
    pub fn finish(&mut self) {
        self.finished = true;
    }

    /// The verdict at `now_ms`, once. `None` while the link looks healthy,
    /// before [`start`](Self::start), and after a verdict was given.
    pub fn poll(&mut self, now_ms: u64) -> Option<LinkVerdict> {
        if self.finished {
            return None;
        }
        let verdict = match (self.started_at, self.last_seen) {
            (_, Some(last)) if now_ms.saturating_sub(last) >= self.silent_after_ms => {
                Some(LinkVerdict::Silent)
            }
            (Some(start), None) if now_ms.saturating_sub(start) >= self.first_within_ms => {
                Some(LinkVerdict::NoFirstReading)
            }
            _ => None,
        };
        if verdict.is_some() {
            self.finished = true;
        }
        verdict
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nothing_happens_before_start() {
        let mut watch = LinkWatch::new(5_000, 5_000);
        assert_eq!(watch.poll(60_000), None);
        assert!(!watch.started());
    }

    #[test]
    fn no_first_reading_by_the_deadline_is_one_verdict() {
        let mut watch = LinkWatch::new(5_000, 5_000);
        watch.start(1_000);
        assert_eq!(watch.poll(5_999), None);
        assert_eq!(watch.poll(6_000), Some(LinkVerdict::NoFirstReading));
        assert_eq!(watch.poll(20_000), None);
        assert!(watch.finished());
    }

    #[test]
    fn a_reading_before_the_deadline_makes_it_ready_then_silence_ends_it() {
        let mut watch = LinkWatch::new(5_000, 5_000);
        watch.start(0);
        assert!(watch.seen(4_900));
        assert!(!watch.seen(5_100));
        assert!(watch.ready());
        assert_eq!(watch.poll(10_099), None);
        assert_eq!(watch.poll(10_100), Some(LinkVerdict::Silent));
        assert_eq!(watch.poll(10_200), None);
    }

    #[test]
    fn start_is_set_once_and_time_going_backwards_is_harmless() {
        let mut watch = LinkWatch::new(5_000, 5_000);
        watch.start(1_000);
        watch.start(4_000);
        assert_eq!(watch.poll(6_000), Some(LinkVerdict::NoFirstReading));
        let mut watch = LinkWatch::new(5_000, 5_000);
        watch.start(10_000);
        watch.seen(10_000);
        watch.seen(9_000);
        assert_eq!(watch.poll(0), None);
    }

    #[test]
    fn finish_silences_the_watch() {
        let mut watch = LinkWatch::new(5_000, 5_000);
        watch.start(0);
        watch.finish();
        assert_eq!(watch.poll(60_000), None);
    }
}
