package com.conway.container;

/**
 * @author Yurii Moroz.
 *
 */
public interface CellsStateMatrixContainerInf {

	/**
	 * Set matrix.
	 * 
	 * @param matrix
	 */
	public void setMatrix(Boolean[][] matrix);

	/**
	 * Return matrix.
	 * 
	 * @return matrix.
	 */
	public Boolean[][] getMatrix();

	/**
	 * Return matrix copy.
	 * 
	 * @return matrix copy.
	 */
	public Boolean[][] getMatrixCopy();
	
	/**
	 * Get cell state by x, y coordinates.
	 * 
	 * @param row
	 * @param col
	 * @return
	 */
	public Boolean getCellState(int row, int col);
	
	/**
	 * Set cell life state into input row and column.
	 * 
	 * @param row
	 * @param col
	 * @param state
	 */
	public void setCellState(int row, int col, Boolean state);
	
	/** 
	 * Get size of matrix cells grid.
	 * 
	 * @return gridSize.
	 */
	public int getGridSize();
	
	/**
	 * Clear container.
	 */
	public void clearContainer();
	
	public void setGliderGunPattern();
}
