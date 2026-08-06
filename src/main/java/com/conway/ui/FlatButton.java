package com.conway.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JButton;

/**
 * A flat, rounded button painted by the component itself.
 *
 * <p>Look-and-feel button painting cannot be restyled portably: the platform delegates
 * draw a native bevel and ignore {@code setBackground}. Turning the content area and
 * border off and painting the shape here gives one consistent appearance everywhere,
 * with explicit hover, pressed, focused and disabled states.
 *
 * <p>Only the fill is custom. Text, mnemonic underlines and accessibility all come from
 * {@link JButton}, so keyboard traversal and screen readers behave normally.
 */
@SuppressWarnings("serial") // Swing components inherit Serializable; this application never serialises them.
public final class FlatButton extends JButton {

    private static final int CORNER_RADIUS = 8;

    /** Visual weight, which encodes how prominent the action is. */
    public enum Variant {
        /** The primary action. Filled with the accent colour. */
        PRIMARY,
        /** Everything routine. A quiet raised surface. */
        SECONDARY,
        /** Destructive or terminal actions. */
        DANGER
    }

    private final Variant variant;

    public FlatButton(String text, Variant variant) {
        super(text);
        this.variant = variant;

        setFont(Theme.UI_BOLD);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setBorder(Theme.padding(8, 14, 8, 14));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(fill());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), CORNER_RADIUS, CORNER_RADIUS);

            if (isFocusOwner() && isEnabled()) {
                g2.setColor(Theme.ACCENT);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, CORNER_RADIUS, CORNER_RADIUS);
            }
        } finally {
            g2.dispose();
        }

        setForeground(textColor());
        super.paintComponent(g);
    }

    private Color fill() {
        if (!isEnabled()) {
            return Theme.DISABLED;
        }
        Color base = switch (variant) {
            case PRIMARY -> Theme.ACCENT;
            case SECONDARY -> Theme.SURFACE_RAISED;
            case DANGER -> Theme.DANGER;
        };
        if (getModel().isPressed()) {
            return shade(base, 0.82f);
        }
        if (getModel().isRollover()) {
            return shade(base, 1.14f);
        }
        return base;
    }

    private Color textColor() {
        if (!isEnabled()) {
            return Theme.TEXT_DISABLED;
        }
        return variant == Variant.PRIMARY ? Theme.ON_ACCENT : Theme.TEXT;
    }

    /** Scales a colour's channels, clamped, so hover and press read as the same hue. */
    private static Color shade(Color color, float factor) {
        return new Color(
                clamp(color.getRed() * factor),
                clamp(color.getGreen() * factor),
                clamp(color.getBlue() * factor));
    }

    private static int clamp(float channel) {
        return Math.min(255, Math.max(0, Math.round(channel)));
    }
}
