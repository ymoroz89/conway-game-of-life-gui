package com.conway.lifecycle.mediator;

public interface GuiLifeMediatorInf {
	
	/**
	 * Start or notify application controller.
	 * 
	 * @param isOnceCycle
	 * @param isStart
	 */
	public void runConroller(boolean isOnceCycle, Boolean isStart);
	
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
	public Object getMatrixCopy();
	
	/**
	 * Repaint board GUI component.
	 */
	public void repaintBoard();
	
}
