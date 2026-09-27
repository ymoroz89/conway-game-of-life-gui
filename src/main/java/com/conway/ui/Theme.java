package com.conway.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single source of truth for the application's colors, fonts and component styling.
 *
 * <p>Everything here is plain Swing and AWT; no third-party look-and-feel is involved.
 * The approach is to install Nimbus &mdash; which ships with the JDK and, unlike the
 * platform look-and-feels, resolves its entire widget palette from a handful of
 * {@code UIManager} keys &mdash; and then hand-paint only the controls where that buys
 * real polish. Fighting a native look-and-feel to make a spinner or a tooltip dark is
 * not portable; re-seeding Nimbus is.
 *
 * <p>Board colors are held as packed RGB {@code int}s because {@link BoardView} writes
 * them straight into the {@code int[]} backing its image. The {@link Color} forms used
 * by the surrounding chrome are derived from the same values, so the board and the
 * window can never drift apart.
 */
public final class Theme {

    private static final Logger LOG = LoggerFactory.getLogger(Theme.class);

    /** A live cell. The brightest thing on screen. */
    public static final int BOARD_ALIVE = 0x5BE9B9;
    /** A cell that has died. Recedes, but still traces where the simulation has been. */
    public static final int BOARD_TRAIL = 0x7A3B5E;
    /** Never populated. */
    public static final int BOARD_EMPTY = 0x11141A;
    /** Separator between cells. Barely distinct from empty, so it reads as texture. */
    public static final int BOARD_GRID = 0x1A1E26;

    public static final Color BACKGROUND = new Color(0x16191F);
    public static final Color SURFACE = new Color(0x1E222A);
    public static final Color SURFACE_RAISED = new Color(0x272C36);
    public static final Color BORDER = new Color(0x2C323C);
    public static final Color TEXT = new Color(0xE4E8F0);
    public static final Color TEXT_MUTED = new Color(0x8A94A6);
    public static final Color ACCENT = new Color(BOARD_ALIVE);
    public static final Color ON_ACCENT = new Color(0x0B2A22);
    public static final Color DANGER = new Color(0xE05561);

    /**
     * Fill for a disabled control. Deliberately lighter than {@link #SURFACE}: a disabled
     * button painted in the panel's own color disappears into it, which reads as a
     * missing control rather than an inactive one.
     */
    public static final Color DISABLED = new Color(0x262B34);
    public static final Color TEXT_DISABLED = new Color(0x5F6978);

    public static final Font UI = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    public static final Font UI_BOLD = UI.deriveFont(Font.BOLD);
    public static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 15);
    public static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    /** Small, wide-tracked capitals for section headings and status captions. */
    public static final Font LABEL = new Font(Font.SANS_SERIF, Font.BOLD, 10)
            .deriveFont(Map.of(TextAttribute.TRACKING, 0.14));

    private Theme() {
    }

    /**
     * Installs the look and feel. Must be called on the event dispatch thread before any
     * component is created.
     *
     * <p>Nimbus derives its widget colors when it is installed, so the palette keys are
     * seeded first. If Nimbus is unavailable the system look and feel is used instead and
     * the hand-styled components below still apply.
     */
    public static void install() {
        UIManager.put("control", SURFACE);
        UIManager.put("info", SURFACE_RAISED);
        UIManager.put("nimbusBase", new Color(0x2A303A));
        UIManager.put("nimbusBlueGrey", new Color(0x3A414D));
        UIManager.put("nimbusLightBackground", SURFACE_RAISED);
        UIManager.put("nimbusFocus", ACCENT);
        UIManager.put("nimbusSelectionBackground", new Color(0x2F5F55));
        UIManager.put("nimbusSelectedText", TEXT);
        UIManager.put("nimbusDisabledText", new Color(0x5A6474));
        UIManager.put("text", TEXT);
        UIManager.put("background", BACKGROUND);

        try {
            for (UIManager.LookAndFeelInfo laf : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(laf.getName())) {
                    UIManager.setLookAndFeel(laf.getClassName());
                    applyOverrides();
                    return;
                }
            }
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | UnsupportedOperationException
                 | javax.swing.UnsupportedLookAndFeelException e) {
            LOG.debug("Falling back to the default look and feel", e);
        }
        applyOverrides();
    }

    /** Settings that Nimbus does not derive from the palette keys. */
    private static void applyOverrides() {
        UIManager.put("ToolTip.background", SURFACE_RAISED);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.font", UI);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Panel.background", SURFACE);
    }

    /** A heading for the window or a sidebar section. */
    public static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(TITLE);
        label.setForeground(TEXT);
        return label;
    }

    /** A small tracked-capitals caption. */
    public static JLabel caption(String text) {
        JLabel label = new JLabel(text.toUpperCase(java.util.Locale.ROOT));
        label.setFont(LABEL);
        label.setForeground(TEXT_MUTED);
        return label;
    }

    /** A value rendered in a monospaced face so digits do not jitter as they update. */
    public static JLabel value(String text) {
        JLabel label = new JLabel(text);
        label.setFont(MONO);
        label.setForeground(TEXT);
        return label;
    }

    /** A one-pixel horizontal rule. */
    public static JComponent divider() {
        JPanel rule = new JPanel();
        rule.setBackground(BORDER);
        rule.setPreferredSize(new Dimension(1, 1));
        rule.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return rule;
    }

    /** Padding, expressed as a border so it composes with the layout managers. */
    public static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    /**
     * Restyles a spinner's editor. The arrow buttons come from the look and feel; the
     * text field is ours, so it gets the theme's surface, a centered value and a caret
     * that is visible against a dark background.
     */
    public static void styleSpinner(JSpinner spinner) {
        spinner.setFont(UI);
        spinner.setBorder(BorderFactory.createLineBorder(BORDER));
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            JTextField field = editor.getTextField();
            field.setFont(UI_BOLD);
            field.setForeground(TEXT);
            field.setBackground(SURFACE_RAISED);
            field.setCaretColor(ACCENT);
            field.setHorizontalAlignment(JTextField.CENTER);
            field.setBorder(padding(4, 8, 4, 8));
            field.setOpaque(true);
        }
    }

    /** Pins a component's height so a vertical {@code BoxLayout} cannot stretch it. */
    public static void fixHeight(JComponent component, int height) {
        Dimension preferred = new Dimension(component.getPreferredSize().width, height);
        component.setPreferredSize(preferred);
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        component.setMinimumSize(new Dimension(0, height));
        component.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    }
}
