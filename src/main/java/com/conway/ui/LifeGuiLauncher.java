package com.conway.ui;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conway.config.LifeConfig;
import com.conway.engine.SimulationEngine;

/**
 * Handles the initialization and launching of the Graphical User Interface.
 */
public final class LifeGuiLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(LifeGuiLauncher.class);

    private LifeGuiLauncher() {
    }

    /**
     * Configures the look and feel and shows the main application window.
     * Must be called to start the GUI.
     */
    public static void launch(LifeConfig config, SimulationEngine engine) {
        // Swing components must be created and touched on the event dispatch thread.
        SwingUtilities.invokeLater(() -> {
            applySystemLookAndFeel();
            LifeFrame frame = new LifeFrame(config, engine);
            frame.setVisible(true);
            frame.startRendering();
            LOG.info("GUI started");
        });
    }

    private static void applySystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | UnsupportedOperationException
                 | javax.swing.UnsupportedLookAndFeelException e) {
            LOG.debug("Falling back to the default look and feel", e);
        }
    }
}
