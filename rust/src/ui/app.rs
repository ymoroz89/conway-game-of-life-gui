use std::sync::Arc;

use eframe::egui;
use egui::{Color32, ColorImage, Context, Rect, RichText, Sense, Stroke, TextureHandle, TextureOptions};

use crate::config::{BoardSizeOption, LifeConfig};
use crate::engine::{PartitionPlanner, SimulationEngine, SimulationSeeder, State};
use crate::model::Generation;
use crate::ui::board_render::{self, downsample_for, texels_for};
use crate::ui::cell_geometry::CellGeometry;
use crate::ui::theme::{self, Theme};

const SIDEBAR_WIDTH: f32 = 208.0;
const MIN_CELL_SIZE_FOR_GRID: f32 = 3.0;

/// The application window: controls, board and the render loop that drives
/// them.
///
/// The app never computes anything. It translates user gestures into engine
/// commands and, once per frame, pulls the newest published snapshot for
/// display. Decoupling the two means the simulation is not throttled by the
/// repaint rate and the repaint rate is not swamped by the simulation.
pub struct LifeApp {
    engine: SimulationEngine,
    config: LifeConfig,
    grid_size: usize,
    options: Vec<BoardSizeOption>,
    partitions: usize,
    texels: usize,
    downsample: usize,
    texture: Option<TextureHandle>,
    pixels: Vec<Color32>,
    last_rendered: Option<Arc<Generation>>,
    running: bool,
    error_message: Option<String>,
}

impl LifeApp {
    pub fn new(
        cc: &eframe::CreationContext<'_>,
        config: LifeConfig,
        engine: SimulationEngine,
    ) -> LifeApp {
        Theme::install(&cc.egui_ctx);
        let grid = config.grid_size();
        LifeApp {
            engine,
            config,
            grid_size: grid,
            options: BoardSizeOption::options(grid),
            partitions: config.default_partitions(),
            texels: texels_for(grid),
            downsample: downsample_for(grid),
            texture: None,
            pixels: Vec::new(),
            last_rendered: None,
            running: false,
            error_message: None,
        }
    }

    /// The control column. Reacts to clicks and hands commands to the engine;
    /// the board is rendered separately.
    fn sidebar(&mut self, ui: &mut egui::Ui, ctx: &Context) {
        ui.add_space(16.0);
        ui.label(Theme::title("Game of Life"));
        ui.label(Theme::caption("Conway"));
        ui.add_space(18.0);
        ui.separator();
        ui.add_space(16.0);

        ui.label(Theme::caption("Board size"));
        ui.add_enabled_ui(!self.running, |ui| {
            let mut change: Option<usize> = None;
            for option in &self.options {
                let selected = option.cells() == self.grid_size;
                if ui
                    .selectable_label(selected, RichText::new(option.label()).size(13.0))
                    .clicked()
                {
                    change = Some(option.cells());
                }
            }
            if let Some(cells) = change {
                self.apply_board_size(ctx, cells);
            }
        });
        ui.add_space(8.0);

        ui.label(Theme::caption("Parallel partitions"));
        ui.add_enabled_ui(!self.running, |ui| {
            let mut partitions = self.partitions;
            let response =
                ui.add(egui::DragValue::new(&mut partitions).range(1..=self.engine.max_partitions()));
            if response.changed() {
                self.partitions = partitions;
                self.engine.set_partitions(partitions);
            }
        });
        ui.add_space(18.0);

        let start = control_button(ui, !self.running, "Start", Theme::BOARD_ALIVE, Theme::ON_ACCENT);
        if start {
            self.engine.set_partitions(self.partitions);
            self.engine.start();
        }
        let stop = control_button(ui, self.running, "Stop", Theme::SURFACE_RAISED, Theme::TEXT);
        if stop {
            self.engine.pause();
        }
        let step = control_button(ui, !self.running, "Next generation", Theme::SURFACE_RAISED, Theme::TEXT);
        if step {
            self.engine.set_partitions(self.partitions);
            self.engine.step();
        }
        let clear = control_button(ui, !self.running, "Clear", Theme::SURFACE_RAISED, Theme::TEXT);
        if clear {
            self.engine.clear();
        }
        ui.add_space(18.0);

        let quit = control_button(ui, true, "Quit", Theme::DANGER, Theme::TEXT);
        if quit {
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }
    }

    /// The fixed-rate statistics strip below the board.
    fn status_bar(&mut self, ui: &mut egui::Ui) {
        let snapshot = self.engine.snapshot();
        let stats = snapshot.stats();
        let population = snapshot.generation().population();

        ui.add_space(6.0);
        ui.horizontal(|ui| {
            theme::stat_segment(ui, "GENERATION", &with_thousands(stats.generation));
            ui.add_space(18.0);
            theme::stat_segment(ui, "SPEED", &format!("{:.1}", stats.generations_per_second()));
            ui.add_space(18.0);
            theme::stat_segment(ui, "POPULATION", &with_thousands(population as u64));
            ui.add_space(18.0);
            theme::stat_segment(ui, "PARTITIONS", &format!("{}", stats.partitions));

            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let (label, color) = if self.running {
                    ("Running", Theme::BOARD_ALIVE)
                } else {
                    ("Idle", Theme::TEXT_MUTED)
                };
                ui.label(RichText::new(label).monospace().size(12.0).color(color));
            });
        });
        ui.add_space(6.0);
    }

/// Renders the board: uploads a fresh texture whenever the published
    /// generation changed and maps clicks onto cells.
    fn board_ui(&mut self, ui: &mut egui::Ui, ctx: &Context) {
        let (response, painter) = ui.allocate_painter(ui.available_size(), Sense::click());
        let rect = centered_square(response.rect.shrink(6.0));
        if rect.width() <= 0.0 || rect.height() <= 0.0 {
            return;
        }

        let snapshot = self.engine.snapshot();
        let generation = snapshot.generation_arc().clone();
        let changed = match &self.last_rendered {
            Some(previous) => !Arc::ptr_eq(previous, &generation),
            None => true,
        };

        if changed {
            let texture = self.texture.get_or_insert_with(|| {
                ctx.load_texture(
                    "board",
                    ColorImage::new(
                        [self.texels, self.texels],
                        vec![Theme::BOARD_EMPTY; self.texels * self.texels],
                    ),
                    TextureOptions::NEAREST,
                )
            });
            board_render::render_into(
                self.downsample,
                self.texels,
                &generation,
                self.last_rendered.as_deref(),
                &mut self.pixels,
            );
            // The diff pass rewrote only changed cells into the persistent
            // buffer; the texture gets a snapshot so the buffer survives for
            // the next diff.
            texture.set(
                ColorImage::new([self.texels, self.texels], self.pixels.clone()),
                TextureOptions::NEAREST,
            );
            self.last_rendered = Some(generation.clone());
        }
        let texture = self.texture.as_ref().expect("board texture set above");

        painter.image(
            texture.id(),
            rect,
            egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)),
            Color32::WHITE,
        );
        // Outline the board itself, so the frame hugs the board at any window
        // size instead of floating out at the window edges.
        painter.rect_stroke(
            rect,
            0.0,
            Stroke::new(1.0, Theme::BORDER),
            egui::StrokeKind::Inside,
        );

        // Separators between cells, only when a cell is large enough that a
        // line costs less than the cell's own colour.
        let cell_px = rect.width() / self.grid_size as f32;
        if cell_px >= MIN_CELL_SIZE_FOR_GRID {
            let separator = Stroke::new(1.0, Theme::BOARD_GRID);
            for i in 1..self.grid_size {
                let x = rect.left() + i as f32 * cell_px;
                painter.line_segment(
                    [egui::pos2(x, rect.top()), egui::pos2(x, rect.bottom())],
                    separator,
                );
            }
            for i in 1..self.grid_size {
                let y = rect.top() + i as f32 * cell_px;
                painter.line_segment(
                    [egui::pos2(rect.left(), y), egui::pos2(rect.right(), y)],
                    separator,
                );
            }
        }

        if response.clicked() {
            if let Some(pos) = response.interact_pointer_pos() {
                let geometry = CellGeometry::new(self.grid_size, self.grid_size);
                let row = geometry.row_at(pos.y - rect.top(), rect.height());
                let col = geometry.column_at(pos.x - rect.left(), rect.width());
                if let (Some(row), Some(col)) = (row, col) {
                    self.engine.toggle_cell(row as i32, col as i32);
                }
            }
        }
    }

    /// Replaces the board with one of `cells` squared, discarding the current
    /// simulation.
    ///
    /// Both the engine's grid and the texture are fixed in size, so this builds
    /// new instances and swaps them in rather than resizing in place. The old
    /// engine is closed first, which stops its thread; leaving it running would
    /// leak a thread per size change.
    fn apply_board_size(&mut self, ctx: &Context, cells: usize) {
        if cells == self.grid_size {
            return;
        }
        self.engine.close();

        self.grid_size = cells;
        let max_partitions = self.config.max_partitions().min(cells);
        self.engine = SimulationEngine::new(cells, cells, max_partitions);
        let recommended = PartitionPlanner::recommend(cells, max_partitions);
        self.partitions = recommended;
        self.engine.set_partitions(recommended);
        SimulationSeeder::seed_board(&self.engine, cells);

        self.texels = texels_for(cells);
        self.downsample = downsample_for(cells);
        self.texture = None;
        self.pixels.clear();
        self.last_rendered = None;
        self.running = false;
        ctx.request_repaint();
    }

fn error_dialog(&mut self, ui: &mut egui::Ui) {
        let Some(message) = &self.error_message else {
            return;
        };
        let message = message.clone();
        egui::Window::new("Simulation error")
            .collapsible(false)
            .resizable(false)
            .show(ui, |ui| {
                ui.label(
                    RichText::new(format!(
                        "The simulation stopped because a generation could not be computed:\n{message}"
                    ))
                    .color(Theme::TEXT),
                );
                ui.add_space(8.0);
                if ui.button("OK").clicked() {
                    self.error_message = None;
                }
            });
    }
}

impl eframe::App for LifeApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        self.running = self.engine.state() == State::Running;

        if self.error_message.is_none() {
            let mut errors = self.engine.take_errors();
            if let Some(error) = errors.pop() {
                self.error_message = Some(error);
            }
        }

        egui::Panel::bottom("status_dock")
            .resizable(false)
            .show(ui, |ui| {
                self.status_bar(ui);
            });
        egui::Panel::left("controls_panel")
            .exact_size(SIDEBAR_WIDTH)
            .resizable(false)
            .show(ui, |ui| {
                self.sidebar(ui, &ctx);
            });
        egui::CentralPanel::default().show(ui, |ui| {
            self.board_ui(ui, &ctx);
        });

        self.error_dialog(ui);

        // Keep repainting while the simulation runs, even with no input.
        if self.running {
            ctx.request_repaint();
        }
    }

    fn on_exit(&mut self) {
        self.engine.close();
    }
}

/// A sidebar button: full column width, fixed height.
fn control_button(
    ui: &mut egui::Ui,
    enabled: bool,
    label: &str,
    fill: Color32,
    text: Color32,
) -> bool {
    let button = egui::Button::new(RichText::new(label).color(text).strong().size(14.0))
        .fill(fill)
        .min_size(egui::vec2(176.0, 32.0));
    ui.add_enabled(enabled, button).clicked()
}

/// The largest square that fits `rect`, centred with any leftover as margin.
fn centered_square(rect: Rect) -> Rect {
    let side = rect.width().min(rect.height()).max(1.0);
    Rect::from_min_size(
        egui::pos2(
            rect.center().x - side / 2.0,
            rect.center().y - side / 2.0,
        ),
        egui::vec2(side, side),
    )
}

/// Formats a count with thousands separators for the status bar.
fn with_thousands(value: u64) -> String {
    let digits = value.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (i, ch) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(ch);
    }
    out
}