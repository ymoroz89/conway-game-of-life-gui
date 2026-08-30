use crate::engine::PartitionPlanner;

/// Externalised application settings.
///
/// Every value that used to be a compile-time constant is read from an
/// environment variable with a `CONWAY_` prefix, falling back to the defaults;
/// for example `CONWAY_GRID_SIZE=401`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LifeConfig {
    pub grid_size: usize,
    pub board_size_px: usize,
    pub target_fps: u32,
    pub max_partitions: usize,
    pub default_partitions: usize,
}

const DEFAULT_GRID_SIZE: usize = 201;
const DEFAULT_BOARD_SIZE_PX: usize = 1000;
const DEFAULT_TARGET_FPS: u32 = 60;

impl LifeConfig {
    /// Reads configuration from environment variables, falling back to the
    /// defaults used by the Java implementation.
    pub fn from_env() -> LifeConfig {
        let grid_size = usize_env("CONWAY_GRID_SIZE", DEFAULT_GRID_SIZE, 3, 10_000);
        let board_size_px = usize_env("CONWAY_BOARD_SIZE_PX", DEFAULT_BOARD_SIZE_PX, 50, 20_000);
        let target_fps = u32_env("CONWAY_TARGET_FPS", DEFAULT_TARGET_FPS, 1, 240);

        // More partitions than rows would produce empty work units, and more
        // than a small multiple of the core count adds scheduling overhead
        // without adding parallelism. Oversubscribing by 2x still lets the
        // user observe the effect of raising the count past the hardware limit.
        let max_partitions = usize_env(
            "CONWAY_MAX_PARTITIONS",
            PartitionPlanner::hardware_cap(grid_size),
            1,
            grid_size,
        );

        // Chosen from the board size and the machine rather than hard-coded to 1.
        let min_rows_per_partition = usize_env(
            "CONWAY_MIN_ROWS_PER_PARTITION",
            PartitionPlanner::DEFAULT_MIN_ROWS_PER_PARTITION,
            1,
            grid_size,
        );
        let recommended =
            PartitionPlanner::recommend_with_min(grid_size, max_partitions, min_rows_per_partition);
        let default_partitions =
            usize_env("CONWAY_PARTITIONS", recommended, 1, max_partitions);

        LifeConfig {
            grid_size,
            board_size_px,
            target_fps,
            max_partitions,
            default_partitions,
        }
    }

    pub fn grid_size(&self) -> usize {
        self.grid_size
    }

    /// Edge length of a single cell in pixels for the configured board, never
    /// less than one.
    pub fn cell_size_px(&self) -> usize {
        self.cell_size_px_for(self.grid_size)
    }

    /// Cell size for a board of `grid_size` cells, so the board can be resized
    /// at runtime without rebuilding the configuration.
    pub fn cell_size_px_for(&self, grid_size: usize) -> usize {
        (self.board_size_px / grid_size).max(1)
    }

    /// Board edge length in pixels for the configured board.
    pub fn board_size_px(&self) -> usize {
        self.cell_size_px() * self.grid_size
    }

    /// Repaint target. Rendering is deliberately decoupled from the simulation
    /// rate.
    pub fn target_fps(&self) -> u32 {
        self.target_fps
    }

    /// Upper bound the UI allows for the number of parallel row bands.
    pub fn max_partitions(&self) -> usize {
        self.max_partitions
    }

    /// Partition count the application starts with, sized for the configured
    /// board on this machine.
    pub fn default_partitions(&self) -> usize {
        self.default_partitions
    }
}

fn usize_env(name: &str, default: usize, min: usize, max: usize) -> usize {
    let Some(raw) = std::env::var(name).ok() else {
        return default;
    };
    match raw.trim().parse::<usize>() {
        Ok(value) if (min..=max).contains(&value) => value,
        Ok(value) => {
            eprintln!("conway-life: ignoring {name}={value}: outside [{min}, {max}], using {default}");
            default
        }
        Err(_) => {
            eprintln!("conway-life: ignoring {name}='{raw}': not an integer, using {default}");
            default
        }
    }
}

fn u32_env(name: &str, default: u32, min: u32, max: u32) -> u32 {
    let Some(raw) = std::env::var(name).ok() else {
        return default;
    };
    match raw.trim().parse::<u32>() {
        Ok(value) if (min..=max).contains(&value) => value,
        Ok(value) => {
            eprintln!("conway-life: ignoring {name}={value}: outside [{min}, {max}], using {default}");
            default
        }
        Err(_) => {
            eprintln!("conway-life: ignoring {name}='{raw}': not an integer, using {default}");
            default
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Runs `from_env` with any ambient `CONWAY_*` variables cleared, so the
    /// test is deterministic regardless of the developer's shell.
    fn defaults() -> LifeConfig {
        let preserved: Vec<(String, String)> = std::env::vars()
            .filter(|(name, _)| name.starts_with("CONWAY_"))
            .collect();
        for (name, _) in &preserved {
            std::env::remove_var(name);
        }
        let config = LifeConfig::from_env();
        for (name, value) in preserved {
            std::env::set_var(name, value);
        }
        config
    }

    #[test]
    fn defaults_match_the_java_application() {
        let config = defaults();

        assert_eq!(config.grid_size(), 201);
        assert_eq!(config.cell_size_px(), 4);
        assert_eq!(config.target_fps(), 60);
        assert_eq!(config.cell_size_px() * config.grid_size(), config.board_size_px());
        assert!(config.max_partitions() >= 1);
        assert!(config.default_partitions() >= 1);
        assert_eq!(config.default_partitions(), PartitionPlanner::recommend(201, config.max_partitions()));
    }

    #[test]
    fn cell_size_is_derived_per_board() {
        let config = defaults();
        assert_eq!(config.cell_size_px_for(401), 2);
        assert_eq!(config.cell_size_px_for(800), 1);
        assert_eq!(config.cell_size_px_for(801), 1);
    }
}