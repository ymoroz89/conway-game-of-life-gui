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
	 * Update container value by input x, y coordinates. 
	 * 
	 * @param row
	 * @param col
	 * @param value
	 */
	public void updateContainer(int row, int col, Boolean value);
	
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
