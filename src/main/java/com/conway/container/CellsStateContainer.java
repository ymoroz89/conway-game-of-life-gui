package com.conway.container;

import static com.conway.util.GuiLifeUtils.*;

/**
 * @author Yurii Moroz.
 *
 */
public class CellsStateContainer implements CellsStateMatrixContainerInf {

	/** The matrix structure contains values.  */
	private Boolean[][] matrix;

	/**
	 * Class constructor.
	 * 
	 * @param gridSize
	 */
	public CellsStateContainer(int gridSize) {
		this.matrix = new Boolean[gridSize][gridSize];
	}

	/**
	 * Return matrix copy.
	 * 
	 * @return matrix copy.
	 */
	public Boolean[][] getMatrixCopy() {
		Boolean[][] matrixCopy = new Boolean[matrix.length][matrix.length];
		makeMatrixCopy(matrix, matrixCopy);
		return matrixCopy;
	}
	
	/**
	 * Get state of cell.
	 * State of cell can be - alive(TRUE), dead(FALSE)
	 * and not birth(null).
	 * 
	 * @param row
	 * @param col
	 * @return state of cell
	 */
	public Boolean getCellState(int row, int col) {
		return matrix[row][col];
	}
	
	/**
	 * Set cell life state into input row and column.
	 * State of cell can be - alive(TRUE), dead(FALSE)
	 * and not birth(null). 
	 * 
	 * @param row
	 * @param col
	 * @param state
	 */
	public void setCellState(int row, int col, Boolean state) {
		matrix[row][col] = state;
	}
	
	/** 
	 * Get size of matrix cells grid.
	 * 
	 * @return gridSize.
	 */
	public int getGridSize() {
		return matrix.length;
	}

	/**
	 * Clear container.
	 */
	public void clearContainer() {
		clearMatrix(matrix);
	}
	
	public void setGliderGunPattern() {
		int c = 120;
		matrix[5][1] = matrix[5][2] = matrix[6][1] = matrix[6][2] = 
			matrix[5][11] = matrix[6][11] = matrix[7][11] = 
			matrix[4][12] = matrix[8][12] =
			matrix[3][13] = matrix[3][14] = matrix[9][13] = 
			matrix[9][14] = matrix[6][15] = matrix[4][16] =
			matrix[8][16] = matrix[7][17] = matrix[6][17] =
			matrix[5][17] = matrix[6][18] = matrix[5][21] =
			matrix[5][22] = matrix[4][21] = matrix[4][22] =
			matrix[3][21] = matrix[3][22] = matrix[2][23] =
			matrix[6][23] = matrix[1][25] = matrix[2][25] =
			matrix[6][25] = matrix[7][25] = matrix[3][35] =
			matrix[4][35] = matrix[3][36] = matrix[4][36] = 
		
			matrix[c+75][c+79] = matrix[c+75][c+78] = matrix[c+74][c+78] = 
			matrix[c+74][c+79] = matrix[c+75][c+69] = matrix[c+74][c+69] = 
			matrix[c+73][c+69] = matrix[c+72][c+68] = matrix[c+76][c+68] = 
			matrix[c+71][c+67] = matrix[c+71][c+66] = matrix[c+77][c+67] = 
			matrix[c+77][c+66] = matrix[c+74][c+65] = matrix[c+72][c+64] =
			matrix[c+76][c+64] = matrix[c+75][c+63] = matrix[c+74][c+63] =
			matrix[c+73][c+63] = matrix[c+74][c+62] = matrix[c+75][c+59] =
			matrix[c+75][c+58] = matrix[c+76][c+58] = matrix[c+76][c+59] =
			matrix[c+77][c+58] = matrix[c+77][c+59] = matrix[c+78][c+57] =
			matrix[c+74][c+57] = matrix[c+78][c+55] = matrix[c+79][c+55] =
			matrix[c+74][c+55] = matrix[c+73][c+55] = matrix[c+77][c+45] = 
			matrix[c+76][c+45] = matrix[c+76][c+44] = matrix[c+77][c+44] = true;
		
	}
}
