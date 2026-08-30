use crate::engine::partition_planner::PartitionPlanner;

/// The cap on the machine the constant was calibrated against: 10 cores, 2x
/// oversubscribed.
const CAP: usize = 20;

#[test]
fn matches_measured_optimum() {
    // These are the measured optima from GenerationBenchmark, not restatements
    // of the formula. If the heuristic is retuned it must still land on the
    // fastest count.
    assert_eq!(PartitionPlanner::recommend(201, CAP), 4);
    assert_eq!(PartitionPlanner::recommend(401, CAP), 8);
    assert_eq!(PartitionPlanner::recommend(801, CAP), 16);
    assert_eq!(PartitionPlanner::recommend(1601, CAP), 20);
}

#[test]
fn tiny_boards_run_on_one_thread() {
    for rows in [1usize, 2, 10, 49] {
        assert_eq!(
            PartitionPlanner::recommend(rows, CAP),
            1,
            "splitting a {rows}-row board costs more in dispatch than the work is worth"
        );
    }
}

#[test]
fn never_exceeds_the_cap() {
    assert_eq!(PartitionPlanner::recommend(10_000, 4), 4);
    assert_eq!(
        PartitionPlanner::recommend(10_000, 1),
        1,
        "a single-partition cap must still yield a usable value"
    );
}

#[test]
fn honours_explicit_threshold() {
    assert_eq!(PartitionPlanner::recommend_with_min(201, CAP, 10), 20);
    assert_eq!(PartitionPlanner::recommend_with_min(201, CAP, 100), 2);
    assert_eq!(PartitionPlanner::recommend_with_min(201, CAP, 500), 1);
}

#[test]
fn result_is_always_in_range() {
    for rows in 1..=2000usize {
        let partitions = PartitionPlanner::recommend(rows, CAP);
        assert!(
            (1..=CAP).contains(&partitions),
            "out of range for {rows} rows: {partitions}"
        );
        assert!(
            partitions <= rows,
            "more bands than rows for {rows} rows: {partitions}"
        );
    }
}

#[test]
fn is_monotonic_in_board_size() {
    let mut previous = 0usize;
    for rows in 1..=2000usize {
        let partitions = PartitionPlanner::recommend(rows, CAP);
        assert!(
            partitions >= previous,
            "recommendation fell from {previous} to {partitions} at {rows}"
        );
        previous = partitions;
    }
}

#[test]
#[should_panic(expected = "rows must be positive")]
fn rejects_zero_rows() {
    let _ = PartitionPlanner::recommend(0, CAP);
}

#[test]
#[should_panic(expected = "maxPartitions must be positive")]
fn rejects_zero_max_partitions() {
    let _ = PartitionPlanner::recommend(201, 0);
}

#[test]
#[should_panic(expected = "minRowsPerPartition must be positive")]
fn rejects_zero_threshold() {
    let _ = PartitionPlanner::recommend_with_min(201, CAP, 0);
}

#[test]
#[should_panic(expected = "rows must be positive")]
fn rejects_zero_rows_in_hardware_cap() {
    let _ = PartitionPlanner::hardware_cap(0);
}

#[test]
fn hardware_cap_is_sane() {
    let cap = PartitionPlanner::hardware_cap(201);
    assert!(cap >= 2, "cap should permit at least two bands: {cap}");
    assert!(cap <= 201, "cap should never exceed the row count: {cap}");
    assert_eq!(
        PartitionPlanner::hardware_cap(4),
        4,
        "a tiny board caps at its own row count"
    );
}