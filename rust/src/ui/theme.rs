use egui::{Color32, Context, FontId, Stroke, Style, Visuals, Vec2};

/// Single source of truth for the application's colours and typography -
/// a direct port of the Java `Theme`, so the egui rework looks identical to
/// the Swing original.
pub struct Theme;

impl Theme {
    /// A live cell. The brightest thing on screen.
    pub const BOARD_ALIVE: Color32 = Color32::from_rgb(0x5B, 0xE9, 0xB9);
    /// A cell that has died. Recedes, but still traces where the simulation
    /// has been.
    pub const BOARD_TRAIL: Color32 = Color32::from_rgb(0x7A, 0x3B, 0x5E);
    /// Never populated.
    pub const BOARD_EMPTY: Color32 = Color32::from_rgb(0x11, 0x14, 0x1A);
    /// Separator between cells. Barely distinct from empty, so it reads as
    /// texture.
    pub const BOARD_GRID: Color32 = Color32::from_rgb(0x1A, 0x1E, 0x26);

    pub const BACKGROUND: Color32 = Color32::from_rgb(0x16, 0x19, 0x1F);
    pub const SURFACE: Color32 = Color32::from_rgb(0x1E, 0x22, 0x2A);
    pub const SURFACE_RAISED: Color32 = Color32::from_rgb(0x27, 0x2C, 0x36);
    pub const BORDER: Color32 = Color32::from_rgb(0x2C, 0x32, 0x3C);
    pub const TEXT: Color32 = Color32::from_rgb(0xE4, 0xE8, 0xF0);
    pub const TEXT_MUTED: Color32 = Color32::from_rgb(0x8A, 0x94, 0xA6);
    pub const ON_ACCENT: Color32 = Color32::from_rgb(0x0B, 0x2A, 0x22);
    pub const DANGER: Color32 = Color32::from_rgb(0xE0, 0x55, 0x61);
    /// Fill for a disabled control. Deliberately lighter than `SURFACE`: a
    /// disabled button painted in the panel's own colour disappears into it,
    /// which reads as a missing control rather than an inactive one.
    pub const DISABLED: Color32 = Color32::from_rgb(0x26, 0x2B, 0x34);
    pub const TEXT_DISABLED: Color32 = Color32::from_rgb(0x5F, 0x69, 0x78);

    /// Installs the dark palette and spacing on an egui context.
    pub fn install(ctx: &Context) {
        let mut visuals = Visuals::dark();
        visuals.panel_fill = Theme::SURFACE;
        visuals.window_fill = Theme::BACKGROUND;
        visuals.extreme_bg_color = Theme::SURFACE_RAISED;
        visuals.override_text_color = Some(Theme::TEXT);
        visuals.selection.bg_fill = Theme::BOARD_ALIVE;
        visuals.selection.stroke = Stroke::new(1.0, Theme::ON_ACCENT);

        let widgets = &mut visuals.widgets;
        widgets.noninteractive.bg_fill = Theme::SURFACE;
        widgets.noninteractive.weak_bg_fill = Theme::SURFACE_RAISED;
        widgets.noninteractive.bg_stroke = Stroke::new(1.0, Theme::BORDER);
        widgets.noninteractive.fg_stroke = Stroke::new(1.0, Theme::TEXT);
        widgets.noninteractive.corner_radius = 0.0.into();

        widgets.inactive.bg_fill = Theme::SURFACE_RAISED;
        widgets.inactive.weak_bg_fill = Theme::SURFACE;
        widgets.inactive.bg_stroke = Stroke::new(1.0, Theme::BORDER);
        widgets.inactive.fg_stroke = Stroke::new(1.0, Theme::TEXT);
        widgets.inactive.corner_radius = 0.0.into();

        widgets.hovered.bg_fill = Theme::BORDER;
        widgets.hovered.weak_bg_fill = Theme::SURFACE_RAISED;
        widgets.hovered.bg_stroke = Stroke::new(1.0, Theme::BORDER);
        widgets.hovered.fg_stroke = Stroke::new(1.0, Theme::TEXT);
        widgets.hovered.corner_radius = 0.0.into();

        widgets.active.bg_fill = Theme::BOARD_ALIVE;
        widgets.active.weak_bg_fill = Theme::ON_ACCENT;
        widgets.active.bg_stroke = Stroke::new(1.0, Theme::BOARD_ALIVE);
        widgets.active.fg_stroke = Stroke::new(1.0, Theme::ON_ACCENT);
        widgets.active.corner_radius = 0.0.into();

        widgets.open.bg_fill = Theme::SURFACE_RAISED;
        widgets.open.weak_bg_fill = Theme::SURFACE;
        widgets.open.bg_stroke = Stroke::new(1.0, Theme::BORDER);
        widgets.open.fg_stroke = Stroke::new(1.0, Theme::TEXT);
        widgets.open.corner_radius = 0.0.into();

        ctx.set_visuals(visuals);

        ctx.all_styles_mut(|style| {
            style.spacing.item_spacing = Vec2::new(8.0, 8.0);
            style.spacing.button_padding = Vec2::new(12.0, 6.0);
            style.spacing.window_margin = egui::Margin::same(10);
        });
    }

    /// A heading rendered in the window title style.
    pub fn title(text: &str) -> egui::RichText {
        egui::RichText::new(text)
            .font(FontId::proportional(15.0))
            .strong()
            .color(Theme::TEXT)
    }

    /// A small tracked-capitals caption (egui has no letter-spacing, so the
    /// tracking is approximated by the small bold face).
    pub fn caption(text: &str) -> egui::RichText {
        egui::RichText::new(text.to_ascii_uppercase())
            .font(FontId::proportional(10.0))
            .strong()
            .color(Theme::TEXT_MUTED)
    }

    /// A value rendered in a monospaced face so digits do not jitter as they
    /// update.
    pub fn value(text: &str) -> egui::RichText {
        egui::RichText::new(text)
            .font(FontId::monospace(12.0))
            .color(Theme::TEXT)
    }
}

/// A small helper for a status-bar segment: caption above, value below.
pub fn stat_segment(ui: &mut egui::Ui, caption: &str, value: &str) {
    ui.vertical(|ui| {
        ui.label(Theme::caption(caption));
        ui.label(Theme::value(value));
    });
}

pub fn install_global(_style: &mut Style) {
    // Reserved for any future global font/spacing tweaks.
}