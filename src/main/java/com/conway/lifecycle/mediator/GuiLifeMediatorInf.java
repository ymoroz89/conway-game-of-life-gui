package com.conway.lifecycle.mediator;

public interface GuiLifeMediatorInf {
	
	/**
	 * Start or notify application controller.
	 * 
	 * @param isOneCycle
	 * @param isStart
	 */
	public void runConroller(boolean isOneCycle, Boolean isStart);
	
	/**
	 * Set number of threads.
	 * 
	 * @param threadsCount
	 */
	public void setThreadsCount(int threadsCount);
	
	/**
	 * Update container with new matrix.
	 * 
	 * @param matrix
	 */
	public void updateContainer(Boolean[][] matrix);

	/**
	 * Return matrix copy.
	 * 
	 * @return matrix copy
	 */
	public Boolean[][] getMatrixCopy();
	
	/**
	 * Repaint board GUI component.
	 */
	public void repaintBoard();
	
	public boolean isRunning();
	
}
