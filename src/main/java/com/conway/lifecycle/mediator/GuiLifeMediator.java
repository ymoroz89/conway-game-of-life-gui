package com.conway.lifecycle.mediator;

import com.conway.container.CellsStateMatrixContainerInf;
import com.conway.lifecycle.LifeCycleController;
import com.conway.ui.components.MatrixBoard;

/**
 * @author Yurii Moroz.
 *
 */
public class GuiLifeMediator implements GuiLifeMediatorInf {
	
	/** Matrix board GUI component. */
	private MatrixBoard board;
	
	/** Default time out. */
	private CellsStateMatrixContainerInf container;
	
	/** Life cycle controller. */
	private LifeCycleController controller;
		
	/** Number of threads. */
	private int threadsCount;

	/**
	 * Class constructor.
	 * 
	 * @param threadsCount
	 * @param gamePanel
	 * @param container
	 */
	public GuiLifeMediator(int threadsCount, MatrixBoard gamePanel,
			CellsStateMatrixContainerInf container) {
		this.threadsCount = threadsCount;
		this.board = gamePanel;
		this.container = container;
	}
	
	/**
	 * Start or notify application controller.
	 * 
	 * @param isOneCycle
	 * @param isStart
	 */
	public void runConroller(boolean isOneCycle, Boolean isStart) {

		if (controller == null) {
			controller = new LifeCycleController(container.getGridSize(),
					threadsCount, this);
		}

		if (!isOneCycle) {
			controller.setRunAllTime(isStart);
		}

		if (isStart != null && !isStart) {
			controller.terminate();
			controller = null;
			return;
		}

		switch (controller.getInternalState()) {
		case NEW:
			controller.start();
			break;
		case WAITING:
			controller.doNotify();
			break;
		default:
			break;
		}
	}

	/**
	 * Set number of threads to chain of
	 * application life cycle.
	 * 
	 * @param threadsCount
	 */
	public void setThreadsCount(int threadsCount) {
		if(controller != null && this.threadsCount != threadsCount) {
			controller.reInitThreadsParams(threadsCount);
		}
		this.threadsCount = threadsCount;
	}

	/**
	 * Update container with new matrix. 
	 * 
	 * @param matrix
	 */
	public void updateContainer(Boolean[][] matrix) {
		container.setMatrix(matrix);
	}

	/**
	 * Return matrix container structure copy.
	 * 
	 * @return matrix copy
	 */
	public Boolean[][] getMatrixCopy() {
		return container.getMatrixCopy();

	}
	
	public void repaintBoard() {
		board.repaint();
	}

	@Override
	public boolean isRunning() {
		return controller != null && controller.isAlive();
	}
}
