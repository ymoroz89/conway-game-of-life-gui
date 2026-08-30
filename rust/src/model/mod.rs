//! Board state and the shapes that are stamped onto it.

mod active_region;
mod generation;
mod pattern;
mod pattern_loader;

pub use active_region::ActiveRegion;
pub use generation::Generation;
pub use pattern::{Pattern, PatternError};
pub use pattern_loader::PatternLoader;