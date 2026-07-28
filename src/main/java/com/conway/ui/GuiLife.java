package com.conway.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import javax.swing.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conway.container.CellsStateMatrixContainerInf;
import com.conway.container.CellsStateContainer;
import com.conway.lifecycle.mediator.GuiLifeMediator;
import com.conway.lifecycle.mediator.GuiLifeMediatorInf;
import com.conway.ui.components.MatrixBoard;

/**
 * GuiLife class realized Conway's Game of Life.
 * 
 * @author Yurii Moroz.
 *
 */
public class GuiLife extends JPanel {

	/** Serial version UID. */
	private static final long serialVersionUID = -5351234400799390968L;
	
	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(GuiLife.class);

	/** Default cells grid size in PX. */
	private static final int DEFAULT_BOARD_SIZE_IN_PX = 1000;

	/** Default grid size. */
	private static final int DEFAULT_GRID_SIZE = 201;

	/** Number of input threads. */
	private int threadsCount;

	/** Grid board. */
	private MatrixBoard gamePanel;

	/** Control panel. */
	private JPanel controlPanel;

	/** Threads number label. */
	private JLabel threadsCountJLabel;

	/** Threads number text field. */
	private JTextField threadsCountJTextField;

	/** Start button. */
	private JButton startButton;

	/** Stop button. */
	private JButton stopButton;

	/** Next generation button. */
	private JButton nextGenerationButton;

	/** Quit button. */
	private JButton quitButton;

	/** Clear button. */
	private JButton clearButton;

	/** Cells state container. */
	private CellsStateMatrixContainerInf container = new CellsStateContainer(
			DEFAULT_GRID_SIZE);

	/** Application mediator object. */
	private GuiLifeMediatorInf mediator;

	/**
	 * Class constructor.
	 */
	public GuiLife() {
		this.mediator = new GuiLifeMediator(threadsCount, getGamePanel(),
				container);
//		container.setCellState(2, 0, true);
//		container.setCellState(2, 1, true);
//		container.setCellState(2, 2, true);
//		container.setCellState(1, 2, true);
//		container.setCellState(0, 1, true);
		container.setGliderGunPattern();

		add(getControlPanel());
		add(getGamePanel());

		setBorder(BorderFactory.createLineBorder(Color.WHITE, 3));
		setBackground(Color.LIGHT_GRAY);
	}

	/**
	 * Run start or next generation application logic
	 * 
	 * @param isStartButtonLogic
	 *            .
	 */
	private void appLogic(boolean isStartButtonLogic) {
		try {
			int numberOfThreadsValue = Integer.parseInt(threadsCountJTextField
					.getText());

			if (numberOfThreadsValue > 0
					&& numberOfThreadsValue <= DEFAULT_GRID_SIZE / 3) {
				mediator.setThreadsCount(numberOfThreadsValue);
				threadsCount = numberOfThreadsValue;
				if (isStartButtonLogic) {
					runEnabledLogic(true);
					mediator.runConroller(false, true);
				} else {
					mediator.runConroller(true, null);
				}
			} else {
				StringBuilder message = new StringBuilder(
						"Number of threads must be between ");
				message.append(1).append(" and ").append(DEFAULT_GRID_SIZE / 3);
				JOptionPane.showMessageDialog(this, message.toString(),
						"Number of threads warning",
						JOptionPane.WARNING_MESSAGE);
			}
			
		} catch (NumberFormatException nfe) {
			JOptionPane.showMessageDialog(this,
					"Count of threads must be number. Please try again",
					"Number format warning", JOptionPane.WARNING_MESSAGE);
		}
	}

	/**
	 * Make needed components enabled or disabled when start button pressed.
	 */
	private void runEnabledLogic(boolean isStartButton) {
		getStartButton().setEnabled(!isStartButton);
		getStopButton().setEnabled(isStartButton);
		getNextGenarationButton().setEnabled(!isStartButton);
		getClearButton().setEnabled(!isStartButton);
		getThreadsCountJTextField().setEnabled(!isStartButton);
		getGamePanel().setCursor(isStartButton ? null : java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
	}

	/**
	 * @return cell size in PX.
	 */
	private int getCellSize() {
		int cellSize = DEFAULT_BOARD_SIZE_IN_PX / DEFAULT_GRID_SIZE;
		return cellSize < 1 ? 1 : cellSize;
	}

	/**
	 * @return gamePanel.
	 */
	private MatrixBoard getGamePanel() {
		if (null == gamePanel) {
			gamePanel = new MatrixBoard(DEFAULT_GRID_SIZE, DEFAULT_GRID_SIZE,
					getCellSize(), getCellSize(), container);
			gamePanel.addMouseListener(getGamePanelMouseListener());
			gamePanel.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		}
		return gamePanel;
	}

	/**
	 * @return gamePanel MouseListener.
	 */
	private MouseListener getGamePanelMouseListener() {
		return new MouseAdapter() {

			public void mousePressed(MouseEvent e) {
				if (mediator.isRunning()) {
					return;
				}
				int row = getGamePanel().yCoordToRowNumber(e.getY());
				int col = getGamePanel().xCoordToColumnNumber(e.getX());

				if (row >= 0 && row < DEFAULT_GRID_SIZE && col >= 0
						&& col < DEFAULT_GRID_SIZE) {

					if (!Boolean.TRUE.equals(container.getCellState(row, col))) {
						container.setCellState(row, col, true);
						getGamePanel().repaintCell(row, col);
						LOGGER.info(
								"Added cell with (y, x) coordinates - ({}, {}).",
								row, col);
					} else {
						container.setCellState(row, col, null);
						getGamePanel().repaintCell(row, col);
						LOGGER.info(
								"Removed cell with (y, x) coordinates - ({}, {}).",
								row, col);
					}
				}
			}
		};
	}

	/**
	 * @return controlPanel.
	 */
	private JPanel getControlPanel() {
		if (null == controlPanel) {
			controlPanel = new JPanel();

			controlPanel.setLayout(new GridLayout(7, 1));
			controlPanel.add(getThreadsCountJLabel());
			controlPanel.add(getThreadsCountJTextField());
			controlPanel.add(getStartButton());
			controlPanel.add(getStopButton());
			controlPanel.add(getNextGenarationButton());
			controlPanel.add(getClearButton());
			controlPanel.add(getQuitButton());

			controlPanel.setBackground(Color.LIGHT_GRAY);
		}
		return controlPanel;
	}

	/**
	 * @return threadsCountJLabel.
	 */
	private JLabel getThreadsCountJLabel() {
		if (null == threadsCountJLabel) {
			threadsCountJLabel = new JLabel("Number of threads");
		}
		return threadsCountJLabel;
	}

	/**
	 * @return threadsCountJTextField.
	 */
	private JTextField getThreadsCountJTextField() {
		if (null == threadsCountJTextField) {
			threadsCountJTextField = new JTextField("1");
		}
		return threadsCountJTextField;
	}

	/**
	 * @return startButton.
	 */
	private JButton getStartButton() {
		if (null == startButton) {
			startButton = new JButton("start");
			startButton.addActionListener(new ActionListener() {

				public void actionPerformed(ActionEvent e) {
					LOGGER.info("Game start running.");
					appLogic(true);
				}
			});
		}
		return startButton;
	}

	/**
	 * @return stopButton.
	 */
	private JButton getStopButton() {
		if (null == stopButton) {
			stopButton = new JButton("stop");
			stopButton.setEnabled(false);
			stopButton.addActionListener(getStopButtonActionListener());
		}
		return stopButton;
	}

	/**
	 * @return stopButton ActionListener.
	 */
	private ActionListener getStopButtonActionListener() {
		return new ActionListener() {

			public void actionPerformed(ActionEvent e) {
				LOGGER.info("Game stop running.");
				mediator.runConroller(false, false);
				runEnabledLogic(false);

			}
		};
	}

	/**
	 * @return nextGenarationButton.
	 */
	private JButton getNextGenarationButton() {
		if (null == nextGenerationButton) {
			nextGenerationButton = new JButton("next genaration");
			nextGenerationButton.setPreferredSize(new Dimension(150, 20));
			nextGenerationButton.addActionListener(new ActionListener() {

				public void actionPerformed(ActionEvent e) {
					LOGGER.info("Next Genaration start creating...");
					appLogic(false);
				}
			});
		}
		return nextGenerationButton;
	}

	/**
	 * @return clearButton.
	 */
	private JButton getClearButton() {
		if (null == clearButton) {
			clearButton = new JButton("clear");
			clearButton.addActionListener(new ActionListener() {

				public void actionPerformed(ActionEvent e) {
					container.clearContainer();
					getGamePanel().repaint();
					LOGGER.info("Game board was cleared.");
				}
			});
		}
		return clearButton;
	}

	/**
	 * @return quitButton.
	 */
	private JButton getQuitButton() {
		if (null == quitButton) {
			quitButton = new JButton("quit");
			quitButton.addActionListener(new ActionListener() {

				public void actionPerformed(ActionEvent e) {
					LOGGER.info("Quit app.");
					System.exit(0);
				}
			});
		}
		return quitButton;
	}

	public static void main(String[] args) {
		JFrame lifeFrame = new JFrame("Game of Life by Conway");
		GuiLife guiLifePanel = new GuiLife();

		lifeFrame.setContentPane(guiLifePanel);
		lifeFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		lifeFrame.setLocation(200, 50);
		lifeFrame.pack();
		lifeFrame.setResizable(false);
		lifeFrame.setVisible(true);
	}
}
