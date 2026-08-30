/// Chooses how many row bands a generation should be split across.
///
/// The obvious default, the core count, is wrong for this workload. A
/// generation is a barrier: every band must finish before the next generation
/// starts, so the cost of splitting is paid per band per generation while the
/// benefit only appears once a band carries enough rows to outweigh dispatch
/// and join overhead. On a small board that trade never pays off.
///
/// Measured with `GenerationBenchmark` on a 10-core machine (6 performance +
/// 4 efficiency cores), best of 5 runs, the optimum tracks the *board*, not
/// the core count: at every size the best result lands at approximately fifty
/// rows per band. That single invariant is the rule implemented here.
///
/// The cap deliberately permits more bands than cores. On asymmetric cores an
/// efficiency core running an equal share of the work delays the barrier for
/// everyone; smaller bands let the faster cores absorb proportionally more.
pub struct PartitionPlanner;

impl PartitionPlanner {
    /// Rows a band needs before splitting it again pays for itself.
    pub const DEFAULT_MIN_ROWS_PER_PARTITION: usize = 50;

    /// Recommends a partition count for a board of `rows` rows, using the
    /// default rows-per-band threshold.
    ///
    /// # Panics
    ///
    /// Panics unless every argument is positive.
    pub fn recommend(rows: usize, max_partitions: usize) -> usize {
        Self::recommend_with_min(rows, max_partitions, Self::DEFAULT_MIN_ROWS_PER_PARTITION)
    }

    /// Recommends a partition count using an explicit rows-per-band threshold.
    ///
    /// # Panics
    ///
    /// Panics unless every argument is positive.
    pub fn recommend_with_min(rows: usize, max_partitions: usize, min_rows_per_partition: usize) -> usize {
        assert!(rows > 0, "rows must be positive but was {rows}");
        assert!(
            max_partitions > 0,
            "maxPartitions must be positive but was {max_partitions}"
        );
        assert!(
            min_rows_per_partition > 0,
            "minRowsPerPartition must be positive but was {min_rows_per_partition}"
        );

        let by_workload = rows / min_rows_per_partition;
        by_workload.clamp(1, max_partitions)
    }

    /// The upper bound the machine supports: twice the core count, never more
    /// than the number of rows. Oversubscription is intentional, for the
    /// straggler reason above.
    ///
    /// # Panics
    ///
    /// Panics unless `rows` is positive.
    pub fn hardware_cap(rows: usize) -> usize {
        assert!(rows > 0, "rows must be positive but was {rows}");
        let cores = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(1);
        (cores * 2).max(2).min(rows)
    }
}