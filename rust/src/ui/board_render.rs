use egui::Color32;

use crate::model::Generation;
use crate::ui::theme::Theme;

/// Upper bound on the texture edge in texels. Boards larger than this are
/// downsampled; a 10,000-cell board would otherwise force a 400 MB RGBA
/// texture that no interactive canvas needs, and cells there are sub-pixel
/// anyway (1000 px window / 10,000 cells).
pub const MAX_TEXELS: usize = 1600;

/// The texture edge in texels for a board of `grid` cells. When the board is
/// downsampled, the edge shrinks to `ceil(grid / downsample)` so that every
/// texel covers at least one board cell — padding the edge out to
/// `MAX_TEXELS` would leave trailing texels that map past the board edge and
/// always render empty.
pub fn texels_for(grid: usize) -> usize {
    if grid <= MAX_TEXELS {
        grid
    } else {
        grid.div_ceil(downsample_for(grid))
    }
}

/// How many board cells one output texel covers (1 = 1:1).
pub fn downsample_for(grid: usize) -> usize {
    grid.div_ceil(MAX_TEXELS).max(1)
}

/// The colour of one board cell.
pub fn cell_color(alive: bool, ever_alive: bool) -> Color32 {
    if alive {
        Theme::BOARD_ALIVE
    } else if ever_alive {
        Theme::BOARD_TRAIL
    } else {
        Theme::BOARD_EMPTY
    }
}

/// Brings `pixels` (edge `texels`, so `texels * texels` entries) up to date
/// with `current`.
///
/// Three paths, cheapest first. When the previous generation is the same size,
/// only the cells whose colour differs are rewritten — the buffer must already
/// hold the previous full image (mirroring the Java `BoardView`), so this is
/// only correct when the caller keeps it between calls. Otherwise the whole
/// image is redrawn. Downsampled boards are always redrawn: the aggregation
/// makes a diff pointless.
pub fn render_into(
    downsample: usize,
    texels: usize,
    current: &Generation,
    previous: Option<&Generation>,
    pixels: &mut Vec<Color32>,
) {
    if downsample > 1 {
        pixels.clear();
        pixels.resize(texels * texels, Theme::BOARD_EMPTY);
        render_downsampled(current, downsample, texels, pixels);
        return;
    }

    if let Some(previous) = previous {
        if previous.width() == current.width() && previous.height() == current.height() {
            render_changes(previous, current, pixels);
            return;
        }
    }
    pixels.clear();
    pixels.resize(texels * texels, Theme::BOARD_EMPTY);
    render_all(current, pixels);
}

fn render_all(current: &Generation, pixels: &mut [Color32]) {
    let alive = current.alive_slice();
    let ever = current.ever_alive_slice();
    let width = current.width();
    let mut index = 0usize;
    for row in 0..current.height() {
        let row_base = row * width;
        for col in 0..current.width() {
            pixels[index] = cell_color(alive[row_base + col], ever[row_base + col]);
            index += 1;
        }
    }
    debug_assert_eq!(index, current.width() * current.height());
    debug_assert!(pixels.len() >= index);
}

/// Rewrites only the cells whose colour differs between the two generations.
/// Both generations must have the same dimensions; the caller guarantees that.
fn render_changes(
    previous: &Generation,
    current: &Generation,
    pixels: &mut [Color32],
) {
    let prev_alive = previous.alive_slice();
    let prev_ever = previous.ever_alive_slice();
    let alive = current.alive_slice();
    let ever = current.ever_alive_slice();
    let width = current.width();

    let mut index = 0usize;
    for row in 0..current.height() {
        let row_base = row * width;
        for col in 0..current.width() {
            let i = row_base + col;
            let rgb = cell_color(alive[i], ever[i]);
            if rgb != cell_color(prev_alive[i], prev_ever[i]) {
                pixels[index] = rgb;
            }
            index += 1;
        }
    }
    debug_assert_eq!(index, current.width() * current.height());
    debug_assert!(pixels.len() >= index);
}

/// Output texel (`ty`, `tx`) covers the board block of `downsample` x
/// `downsample` cells starting at `(ty * downsample, tx * downsample)`; a texel
/// is alive if any cell in its block is alive.
fn render_downsampled(
    current: &Generation,
    downsample: usize,
    texels: usize,
    pixels: &mut [Color32],
) {
    let alive = current.alive_slice();
    let ever = current.ever_alive_slice();
    let width = current.width();
    for ty in 0..texels {
        for tx in 0..texels {
            let mut any_alive = false;
            let mut any_ever = false;
            for r in (ty * downsample)..((ty + 1) * downsample).min(current.height()) {
                let row_base = r * width;
                for c in (tx * downsample)..((tx + 1) * downsample).min(width) {
                    let i = row_base + c;
                    any_alive |= alive[i];
                    any_ever |= ever[i];
                }
            }
            pixels[ty * texels + tx] = cell_color(any_alive, any_ever);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_util::board;

    #[test]
    fn renders_the_alive_and_dead_colours() {
        let generation = board(&["O.", ".O"]);
        let mut pixels = Vec::new();
        render_into(1, 2, &generation, None, &mut pixels);

        assert_eq!(pixels[0], Theme::BOARD_ALIVE);
        assert_eq!(pixels[1], Theme::BOARD_EMPTY);
        assert_eq!(pixels[2], Theme::BOARD_EMPTY);
        assert_eq!(pixels[3], Theme::BOARD_ALIVE);
    }

    #[test]
    fn trails_are_drawn_in_the_trail_colour() {
        let mut generation = board(&["O.", ".."]);
        // Kill the lone cell with the rules engine, leaving its trail.
        generation = crate::engine::LifeRules.next(&generation, 1);
        assert_eq!(generation.population(), 0);

        let mut pixels = Vec::new();
        render_into(1, 2, &generation, None, &mut pixels);
        assert_eq!(pixels[0], Theme::BOARD_TRAIL);
        assert_eq!(pixels[1], Theme::BOARD_EMPTY);
    }

    #[test]
    fn incremental_rendering_rewrites_only_changed_cells() {
        let first = board(&["O.", ".O"]);
        let mut pixels = Vec::new();
        render_into(1, 2, &first, None, &mut pixels);

        // A single extra live cell.
        let second = first.with_cell(0, 1, true);
        render_into(1, 2, &second, Some(&first), &mut pixels);

        assert_eq!(pixels[1], Theme::BOARD_ALIVE);
        assert_eq!(pixels[0], Theme::BOARD_ALIVE);
        assert_eq!(pixels[2], Theme::BOARD_EMPTY);
        assert_eq!(pixels[3], Theme::BOARD_ALIVE);
    }

    #[test]
    fn downsampling_keeps_enough_to_tell_life_from_death() {
        assert_eq!(texels_for(10_000), 1429); // ceil(10_000 / 7)
        assert_eq!(downsample_for(10_000), 7);
        assert_eq!(downsample_for(801), 1);

        // A board just past the 1:1 range: 1601 cells downsample 2:1, and the
        // 801-texel edge covers the board exactly (the last texel holding one
        // cell) — nothing may fall back to the empty colour.
        let grid = 1601usize;
        let alive = vec![true; grid * grid];
        let ever = alive.clone();
        let generation = Generation::adopt(grid, grid, alive, ever, 0);

        let texels = texels_for(grid);
        let down = downsample_for(grid);
        assert_eq!(texels, 801);
        assert_eq!(down, 2);
        let mut pixels = Vec::new();
        render_into(down, texels, &generation, None, &mut pixels);
        assert_eq!(pixels.len(), texels * texels);
        assert!(pixels.iter().all(|&c| c == Theme::BOARD_ALIVE));
    }
}