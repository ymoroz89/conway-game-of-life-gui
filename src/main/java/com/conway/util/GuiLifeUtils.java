package com.conway.util;

public class GuiLifeUtils {
	
	public static void makeMatrixCopy(Boolean[][] matrix, Boolean[][] matrixCopy) {
		if (matrix != null) {
			for (int i = 0; i < matrix.length; i++) {
				for (int j = 0; j < matrix[i].length; j++) {
					Boolean cell = matrix[i][j] != null ? matrix[i][j]
							.booleanValue() : null;
					matrixCopy[i][j] = cell;
				}
			}
		}
	}
	
	public static void clearMatrix(Boolean[][] matrix) {
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix.length; j++) {
				matrix[i][j] = null;
			}
		}
	}
}
