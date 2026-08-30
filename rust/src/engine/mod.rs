//! The simulation engine: board ownership, the parallel Life rules, and the
//! partition sizing that makes the parallelism worthwhile.

mod life_rules;
mod partition_planner;
mod simulation_engine;
mod simulation_seeder;
mod simulation_stats;

pub use life_rules::LifeRules;
pub use partition_planner::PartitionPlanner;
pub use simulation_engine::{EngineSnapshot, SimulationEngine, State};
pub use simulation_seeder::SimulationSeeder;
pub use simulation_stats::SimulationStats;

#[cfg(test)]
mod banding_tests;
#[cfg(test)]
mod life_rules_tests;
#[cfg(test)]
mod partition_equivalence_tests;
#[cfg(test)]
mod partition_planner_tests;
#[cfg(test)]
mod seeding_tests;
#[cfg(test)]
mod simulation_engine_tests;