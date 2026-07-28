package com.conway.ui.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.image.BufferedImage;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import com.conway.container.CellsStateMatrixContainerInf;

/**
 * @author Yurii Moroz.
 *
 */
public class MatrixBoard extends JPanel {

	/** Serial version UID. */
	private static final long serialVersionUID = 9208300481247825327L;
	
	/** Rows count. */
	private int rows;
	
	/** Columns count. */
	private int columns;
	
	/** Cells state container. */
	private CellsStateMatrixContainerInf container;
	
	/** Background color. */
	private Color backgroundColor = Color.GRAY;
	
	/** Board buffered image. */
	private BufferedImage boardBufImage;

	/**
	 * Class constructor.
	 * 
	 * @param rows
	 * @param columns
	 * @param preferredBlockWidth
	 * @param preferredBlockHeight
	 * @param container
	 */
	public MatrixBoard(int rows, int columns, int preferredBlockWidth,
			int preferredBlockHeight, CellsStateMatrixContainerInf container) {
		this.container = container;
		this.rows = rows;
		this.columns = columns;
		setDoubleBuffered(false);
		setBorder(BorderFactory.createLineBorder(Color.WHITE, 0));
		if (preferredBlockWidth > 0 && preferredBlockHeight > 0) {
			setPreferredSize(new Dimension(preferredBlockWidth * columns,
					preferredBlockHeight * rows));
		}
	}

	/**
	 * Repaint cell by input row and column.
	 * 
	 * @param row
	 * @param col
	 */
	public void repaintCell(int row, int col) {
		drawSquare(row, col);
	}

	/**
	 * Get matrix column number by x coordinate
	 * on board.
	 * 
	 * @param x
	 * @return column number in matrix
	 */
	public int xCoordToColumnNumber(int x) {
		Insets insets = getInsets();
		if (x < insets.left) {
			return -1;
		}
		double colWidth = (double) (getWidth() - insets.left - insets.right)
				/ columns;
		int col = (int) ((x - insets.left) / colWidth);
		return col >= columns ? columns : col;
	}

	/**
	 * Get matrix row number by y coordinate
	 * on board.
	 * 
	 * @param y
	 * @return row number in matrix
	 */
	public int yCoordToRowNumber(int y) {
		Insets insets = getInsets();
		if (y < insets.top) {
			return -1;
		}
		double rowHeight = (double) (getHeight() - insets.top - insets.bottom)
				/ rows;
		int row = (int) ((y - insets.top) / rowHeight);
		return row >= rows ? rows : row;
	}

	@Override
	public void paintComponent(Graphics g) {
		super.paintComponent(g);
		Boolean[][] currentMatrix = container.getMatrix();
		Graphics graphics = getMosaicBufImage().getGraphics();
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < columns; c++) {
				drawSquare(graphics, r, c, currentMatrix, false);
			}
		}
		graphics.dispose();
		g.drawImage(getMosaicBufImage(), 0, 0, null);
	}

	private BufferedImage getMosaicBufImage() {
		if(boardBufImage == null ) {
			boardBufImage = new BufferedImage(getWidth(), getHeight(),
					BufferedImage.TYPE_INT_RGB);
		}
		return boardBufImage;
	}

	/**
	 * Draw one of the rectangles in a specified graphics
	 * context. g must be non-null and (row, column) must be
	 * in the grid.
	 * 
	 * @param g
	 * @param row
	 * @param col
	 * @param matrix
	 * @param callRepaint
	 */
	private void drawSquare(Graphics g, int row, int col, Boolean[][] matrix, boolean callRepaint) {
		Insets insets = getInsets();
		double rowHeight = (double) (getHeight() - insets.top - insets.bottom)
				/ rows;
		double colWidth = (double) (getWidth() - insets.left - insets.right)
				/ columns;
		int xOffset = insets.left;
		int yOffset = insets.top;
		int y = yOffset + (int) Math.round(rowHeight * row);
		int h = Math.max(1, (int) Math.round(rowHeight * (row + 1)) + yOffset
				- y);
		int x = xOffset + (int) Math.round(colWidth * col);
		int w = Math.max(1, (int) Math.round(colWidth * (col + 1)) + xOffset
				- x);
		Color c = null;
		Boolean cellsLifeState = matrix[row][col];
		if (cellsLifeState != null) {
			c = cellsLifeState == true ? Color.WHITE : Color.RED;
		}
		g.setColor((c == null) ? Color.GRAY : c);
		g.fillRect(x +1, y + 1, w - 2, h - 2);
		if (c != null) {
			g.setColor(backgroundColor);
		}
		if (callRepaint) {
			repaint(x, y, w, h);
		}
	}

	/**
	 * Draw one of the rectangles in a specified graphics
	 * context and (row, column) must be
	 * in the grid.
	 * 
	 * @param row
	 * @param col
	 */
	private void drawSquare(int row, int col) {
		Graphics g = getMosaicBufImage().getGraphics();
		drawSquare(g, row, col, container.getMatrix(), true);
		g.dispose();
	}
}
