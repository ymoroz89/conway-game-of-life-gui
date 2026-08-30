/// Throughput measurement for the current run.
///
/// Timing uses `Instant`-derived nanoseconds rather than wall-clock
/// millisecond resolution, and the rate is a `f64`, so short runs report a
/// meaningful figure instead of being truncated to zero by integer division.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SimulationStats {
    /// Total generations computed since the last clear.
    pub generation: u64,
    /// Generations computed since the run started.
    pub generations_in_run: u64,
    /// Nanoseconds elapsed since the run started.
    pub elapsed_nanos: u64,
    /// Number of row bands requested for the most recent generation.
    pub partitions: usize,
}

impl SimulationStats {
    pub fn idle() -> SimulationStats {
        SimulationStats {
            generation: 0,
            generations_in_run: 0,
            elapsed_nanos: 0,
            partitions: 1,
        }
    }

    /// Generations per second over the current run, or zero if nothing has
    /// been timed yet.
    pub fn generations_per_second(&self) -> f64 {
        if self.elapsed_nanos == 0 || self.generations_in_run == 0 {
            return 0.0;
        }
        self.generations_in_run as f64 * 1e9 / self.elapsed_nanos as f64
    }

    /// Elapsed run time in seconds.
    pub fn elapsed_seconds(&self) -> f64 {
        self.elapsed_nanos as f64 / 1e9
    }
}