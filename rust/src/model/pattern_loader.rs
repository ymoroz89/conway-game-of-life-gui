use super::pattern::{Pattern, PatternError};

/// The Gosper glider gun shipped with the application, embedded into the
/// binary at compile time from `rust/resources/patterns`.
pub const GOSPER_GLIDER_GUN_CELLS: &str =
    include_str!("../../resources/patterns/gosper-glider-gun.cells");

/// Loads [`Pattern`]s from plaintext `.cells` sources.
pub struct PatternLoader;

impl PatternLoader {
    /// The bundled Gosper glider gun.
    pub fn gosper_glider_gun() -> Result<Pattern, PatternError> {
        let lines: Vec<&str> = GOSPER_GLIDER_GUN_CELLS.lines().collect();
        Pattern::parse("gosper-glider-gun", &lines)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loads_the_bundled_gosper_glider_gun() {
        let gun = PatternLoader::gosper_glider_gun().expect("bundled gun must parse");

        assert_eq!(gun.name(), "gosper-glider-gun");
        assert_eq!(gun.width(), 36);
        assert_eq!(gun.height(), 9);
        // The Gosper glider gun is defined by exactly 36 live cells.
        assert_eq!(gun.population(), 36);
    }
}