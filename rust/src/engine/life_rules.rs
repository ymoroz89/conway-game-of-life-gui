use rayon::prelude::*;

use crate::engine::partition_planner::PartitionPlanner;
use crate::model::{ActiveRegion, Generation};

/// Computes successive generations of Conway's Game of Life.
///
/// The board is split into contiguous bands of rows. Each band writes only to
/// its own disjoint slice of the destination buffers, so no synchronisation is
/// needed between workers; rayon's join produces the happens-before edge that
/// makes every write visible to the thread that assembles the result. When
/// only one band is produced, the work runs on the calling thread, giving an
/// honest single-threaded baseline.
///
/// Boundaries are dead: the grid does not wrap.
///
/// This type is stateless and safe to call from any thread. In practice it is
/// only ever called from the simulation thread.
#[derive(Debug, Clone, Copy, Default)]
pub struct LifeRules;

/// What one band computed: the active region of its slice of the destination
/// board, and the number of live cells it wrote. The population is summed
/// across bands and handed to `Generation::adopt_fast` so the snapshot carries
/// the count without a second scan.
#[derive(Debug, Clone, Copy)]
struct BandResult {
    region: ActiveRegion,
    population: usize,
}

impl LifeRules {
    /// Computes the generation following `current`.
    ///
    /// The result is identical for every value of `partitions`; the parameter
    /// only controls how the work is distributed. `partitions` is clamped to
    /// `[1, height]`; a value of one is computed inline.
    pub fn next(&self, current: &Generation, partitions: usize) -> Generation {
        let width = current.width();
        let height = current.height();
        let src = current.alive_slice();
        let src_ever = current.ever_alive_slice();
        let cells = width * height;
        let mut dst = vec![false; cells];
        let mut dst_ever = vec![false; cells];

        let active = current.active_region().pad(width, height);
        if active.is_empty() {
            // Nothing alive, so nothing to compute — but the ever-alive flags
            // are monotonic within a run and must be carried forward. Returning
            // a freshly zeroed board here would erase every trail the moment
            // the population reached zero.
            dst_ever.copy_from_slice(src_ever);
            return Generation::adopt_fast(
                width,
                height,
                dst,
                dst_ever,
                current.number() + 1,
                ActiveRegion::EMPTY,
                0,
            );
        }

        let bands = Self::bands_for(partitions, height, active);
        let base_size = height / bands;
        let remainder = height % bands;
        let mut ranges = Vec::with_capacity(bands);
        let mut start = 0usize;
        for band in 0..bands {
            let size = base_size + usize::from(band < remainder);
            ranges.push((start, start + size));
            start += size;
        }

        // Split the destination buffers into the disjoint row bands so each
        // worker owns its slice and no aliasing is possible.
        let mut band_slices = Vec::with_capacity(bands);
        {
            let mut dst_tail = dst.as_mut_slice();
            let mut ever_tail = dst_ever.as_mut_slice();
            let mut cursor = 0usize;
            for &(from, to) in &ranges {
                let skip = from * width - cursor;
                if skip > 0 {
                    let (_, rest) = dst_tail.split_at_mut(skip);
                    dst_tail = rest;
                    let (_, rest) = ever_tail.split_at_mut(skip);
                    ever_tail = rest;
                }
                let band_len = (to - from) * width;
                let (dst_band, rest) = dst_tail.split_at_mut(band_len);
                dst_tail = rest;
                let (ever_band, rest) = ever_tail.split_at_mut(band_len);
                ever_tail = rest;
                band_slices.push((dst_band, ever_band));
                cursor = to * width;
            }
        }

        let results: Vec<BandResult> = ranges
            .par_iter()
            .zip(band_slices.into_par_iter())
            .map(|(&(from, to), (dst_band, ever_band))| {
                compute_band(src, src_ever, dst_band, ever_band, width, height, from, to, active)
            })
            .collect();

        let mut next_active = ActiveRegion::EMPTY;
        let mut population = 0usize;
        for result in results {
            next_active = next_active.merge(result.region);
            population += result.population;
        }

        Generation::adopt_fast(
            width,
            height,
            dst,
            dst_ever,
            current.number() + 1,
            next_active,
            population,
        )
    }

    /// How many bands a generation is actually split across.
    ///
    /// Purely a work-distribution choice: the resulting board is identical
    /// whatever this returns, which is what the partition-equivalence tests
    /// pin down.
    pub(crate) fn bands_for(partitions: usize, height: usize, active: ActiveRegion) -> usize {
        let requested = partitions.clamp(1, height);
        if active.is_empty() {
            return 1;
        }
        let active_rows = (active.max_row - active.min_row) as usize + 1;
        let useful = active_rows / PartitionPlanner::DEFAULT_MIN_ROWS_PER_PARTITION;
        // A region too small to fill one band (useful == 0) must still be able
        // to run inline, so floor the cap at 1 before clamping.
        requested.clamp(1, useful.max(1))
    }
}

/// Computes one contiguous band of rows `[from_row, to_row)`.
///
/// `dst` and `ever` are the band's own disjoint slices, indexed relative to
/// `from_row`; `src` and `src_ever` are the full previous-generation buffers.
///
/// Interior rows on a wide board use the unrolled neighbour sum and only fall
/// back to the bounds-checked `rule_at` at the board edges and at the active
/// region's clipping columns. The ever-alive flags are carried forward and the
/// population counted in a fused pass over every row of the band (not just the
/// active ones), because flags outside the active region still belong to the
/// band and must be preserved.
// The band parameters are the price of the fused, bounds-free inner loop; every
// argument has a distinct role, mirroring the Java engine's band computation.
#[allow(clippy::too_many_arguments)]
fn compute_band(
    src: &[bool],
    src_ever: &[bool],
    dst: &mut [bool],
    ever: &mut [bool],
    width: usize,
    height: usize,
    from_row: usize,
    to_row: usize,
    active_limit: ActiveRegion,
) -> BandResult {
    let mut min_row = i32::MAX;
    let mut max_row: i32 = -1;
    let mut min_col = i32::MAX;
    let mut max_col: i32 = -1;

    let row_start = from_row.max(active_limit.min_row as usize);
    let row_end = (to_row).min(active_limit.max_row as usize + 1);
    let col_start = active_limit.min_col as usize;
    let col_end = active_limit.max_col as usize + 1;

    for row in row_start..row_end {
        let row_base = row * width;
        let band_base = (row - from_row) * width;

        if row > 0 && row < height - 1 && width > 2 {
            let up = row_base - width;
            let down = row_base + width;

            if col_start == 0 {
                write_rule_at(
                    src,
                    dst,
                    width,
                    row_base,
                    band_base,
                    0,
                    &mut min_row,
                    &mut max_row,
                    &mut min_col,
                    &mut max_col,
                );
            }

            let interior_begin = if col_start == 0 { 1 } else { col_start };
            let interior_end = col_end.min(width - 1);

            for col in interior_begin..interior_end {
                let i = row_base + col;
                let neighbours = usize::from(src[up + col - 1])
                    + usize::from(src[up + col])
                    + usize::from(src[up + col + 1])
                    + usize::from(src[i - 1])
                    + usize::from(src[i + 1])
                    + usize::from(src[down + col - 1])
                    + usize::from(src[down + col])
                    + usize::from(src[down + col + 1]);
                dst[band_base + col] =
                    if src[i] { neighbours == 2 || neighbours == 3 } else { neighbours == 3 };
                if dst[band_base + col] {
                    min_row = min_row.min(row as i32);
                    max_row = max_row.max(row as i32);
                    min_col = min_col.min(col as i32);
                    max_col = max_col.max(col as i32);
                }
            }

            if col_end == width {
                write_rule_at(
                    src,
                    dst,
                    width,
                    row_base,
                    band_base,
                    width - 1,
                    &mut min_row,
                    &mut max_row,
                    &mut min_col,
                    &mut max_col,
                );
            }
        } else {
            for col in col_start..col_end {
                write_rule_at(
                    src,
                    dst,
                    width,
                    row_base,
                    band_base,
                    col,
                    &mut min_row,
                    &mut max_row,
                    &mut min_col,
                    &mut max_col,
                );
            }
        }
    }

    // Carry the previous generation's ever-alive flags forward and mark the
    // cells that newly qualify, fused into one pass per row so the row is
    // still in cache for the second half. The OR sweeps the full width even
    // though `dst` can only be true inside the active region; the redundancy
    // is deliberate — it buys sequential access over a contiguous row, which
    // the Java profiling found worth more than the work it skips. The
    // population count is folded into this same pass.
    let mut population = 0usize;
    for row in from_row..to_row {
        let row_base = row * width;
        let band_base = (row - from_row) * width;
        ever[band_base..band_base + width]
            .copy_from_slice(&src_ever[row_base..row_base + width]);
        for col in 0..width {
            let index = band_base + col;
            ever[index] |= dst[index];
            population += usize::from(dst[index]);
        }
    }

    BandResult {
        region: ActiveRegion::new(min_row, max_row, min_col, max_col),
        population,
    }
}

/// Evaluates the rule at a cell that needs the bounds-checked path and records
/// it in the band's region when it survives.
#[allow(clippy::too_many_arguments)]
fn write_rule_at(
    src: &[bool],
    dst: &mut [bool],
    width: usize,
    row_base: usize,
    band_base: usize,
    col: usize,
    min_row: &mut i32,
    max_row: &mut i32,
    min_col: &mut i32,
    max_col: &mut i32,
) {
    let i = row_base + col;
    if rule_at(src, width, i, col) {
        dst[band_base + col] = true;
        let row = i / width;
        *min_row = (*min_row).min(row as i32);
        *max_row = (*max_row).max(row as i32);
        *min_col = (*min_col).min(col as i32);
        *max_col = (*max_col).max(col as i32);
    }
}

/// Bounds-checked rule evaluation, used for cells on the border of the board
/// and at the active region's clipping columns.
fn rule_at(src: &[bool], width: usize, index: usize, col: usize) -> bool {
    let height = src.len() / width;
    let row = index / width;
    let row_start = row.saturating_sub(1);
    let row_end = (row + 1).min(height - 1);
    let col_start = col.saturating_sub(1);
    let col_end = (col + 1).min(width - 1);
    let mut neighbours = 0usize;
    for r in row_start..=row_end {
        let base = r * width;
        for c in col_start..=col_end {
            if (r != row || c != col) && src[base + c] {
                neighbours += 1;
            }
        }
    }
    let alive = src[index];
    if alive {
        neighbours == 2 || neighbours == 3
    } else {
        neighbours == 3
    }
}