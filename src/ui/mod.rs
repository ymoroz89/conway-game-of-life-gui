//! The application window: board, sidebar controls and the render loop that
//! drives them, powered by egui/eframe.
//!
//! The UI never computes anything. It translates user gestures into engine
//! commands and, once per frame, pulls the newest published snapshot for
//! display. The simulation is decoupled from the repaint rate: eframe repaints
//! on demand, and the engine publishes independently.

pub mod cell_geometry;
pub mod app;
pub mod board_render;
pub mod theme;

pub use app::LifeApp;