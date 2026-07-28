package com.conway.lifecycle;

import java.util.ArrayList;
import java.util.concurrent.Callable;

import com.conway.pair.CellCoordPair;

public class CellStateGenerator implements
		Callable<ArrayList<CellCoordPair<Integer, Integer>>> {

	/** Grid size. */
	private int gridSize;

	/** The part of grid size. */
	private int boardPartSize;

	/** Start position in grid. */
	private int startFromBoardPart;

	/** End-position in grid. */
	private int endWithBoardPart;

	private Boolean[][] matrix;

	private ArrayList<CellCoordPair<Integer, Integer>> resultList = new ArrayList<>();

	public CellStateGenerator(int gridSize, int boardPartSize,
			int startFromBoardPart, Boolean[][] matrix) {
		super();
		this.gridSize = gridSize;
		this.matrix = matrix;
		
		if (startFromBoardPart < 0) {
			this.startFromBoardPart = gridSize + startFromBoardPart;
			this.endWithBoardPart = gridSize - this.boardPartSize - 1;
		} else {
			this.startFromBoardPart = startFromBoardPart;
			this.endWithBoardPart = startFromBoardPart + boardPartSize - 1;
		}
		this.boardPartSize = boardPartSize;
	}


	public ArrayList<CellCoordPair<Integer, Integer>> call() throws Exception {
		updateCellsState();
		return resultList;
	}
	
	private void updateCellsState() {
		
		for (int i = 0; i < gridSize; i++) {
			for (int j = startFromBoardPart; j <= endWithBoardPart; j++) {
				Boolean cellState = getCellsState(i, j);
				int aliveCellsCounter = countAliveCellsAround(i, j);
				if (Boolean.TRUE.equals(cellState)
						&& (aliveCellsCounter < 2 || aliveCellsCounter > 3)) {
					resultList.add(new CellCoordPair<Integer, Integer>(i, j, false));
				} else if (aliveCellsCounter == 3) {
					resultList.add(new CellCoordPair<Integer, Integer>(i, j, true));
				}
			}
		}
	}
	
	private int countAliveCellsAround(int y, int x) {
		int counter = 0;
		for (int i = y - 1; i <= y + 1; i++) {
			for (int j = x - 1; j <= x + 1; j++) {
				if ((i != y || j != x) && (i >= 0 && i < gridSize)
						&& (j >= 0 && j < gridSize)
						&& Boolean.TRUE.equals(getCellsState(i, j))) {
					counter += 1;
				}
			}
		}
		return counter;
	}
	
	private Boolean getCellsState(int row, int col) {
		return matrix[row][col];
	}

}
