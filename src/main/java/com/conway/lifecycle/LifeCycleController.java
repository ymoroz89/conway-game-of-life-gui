package com.conway.lifecycle;

import java.util.ArrayList;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conway.lifecycle.mediator.GuiLifeMediatorInf;
import com.conway.pair.CellCoordPair;
import static com.conway.util.GuiLifeUtils.makeMatrixCopy;

/**
 * @author Yurii Moroz.
 *
 */
public class LifeCycleController implements Runnable {

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory
			.getLogger(LifeCycleController.class);

	/** Number of threads. */
	private int threadsCount;

	/** Grid size. */
	private int gridSize;

	/** The part of grid size. */
	private int boardPartSize;

	/** The last part of grid size. */
	private int boardLastPartSize;

	private ArrayList<CellCoordPair<Integer, Integer>> resultList = new ArrayList<>();

	/** Application mediator object. */
	private GuiLifeMediatorInf mediator;

	/** Is run all time without waiting. */
	private boolean isRunAllTime;

	private volatile boolean isTerminated = false;

	private ExecutorService threadPool;

	private CompletionService<ArrayList<CellCoordPair<Integer, Integer>>> pool;

	private Thread internalThread;

	/**
	 * Class constructor.
	 * 
	 * @param gridSize
	 * @param threadsCount
	 * @param mediator
	 */
	public LifeCycleController(int gridSize, int threadsCount,
			GuiLifeMediatorInf mediator) {
		this.threadsCount = threadsCount;
		this.gridSize = gridSize;
		this.mediator = mediator;
		this.boardPartSize = this.gridSize / threadsCount;
		this.boardLastPartSize = getBoardLastPartSize();
		this.threadPool = Executors.newCachedThreadPool();
		this.pool = new ExecutorCompletionService<>(threadPool);
	}

	@Override
	public void run() {
		int maxIterations = 10000;
		int iterationNumber = 0;
		long dataInMillis = System.currentTimeMillis();
		LOGGER.info("Starting simulation. Max iterations: {}", maxIterations);
		while (iterationNumber < maxIterations && !isTerminated) {

			runNextGenerationLogic();
			mediator.repaintBoard();
			iterationNumber++;

			if (!isRunAllTime) {
				doWait();
			}
		}
		long workTime = (System.currentTimeMillis() - dataInMillis) / 1000;
		long tps = workTime > 0 ? (long) iterationNumber / workTime : 0;
		LOGGER.info("Simulation ended. Number of threads {}, work time {}s, Total iterations {}, Tps {}.",
				threadsCount, workTime, iterationNumber, tps);
		threadPool.shutdown();
	}

	public synchronized void start() {
		if (internalThread == null) {
			internalThread = new Thread(this, "LifeCycleControllerThread");
			internalThread.start();
		}
	}

	public void terminate() {
		isTerminated = true;
		doNotify();
	}

	public synchronized Thread.State getInternalState() {
		return internalThread == null ? Thread.State.NEW : internalThread.getState();
	}

	public boolean isAlive() {
		return internalThread != null && internalThread.isAlive();
	}

	/**
	 * Create cells generator's threads and set mask(copy) of the matrix container
	 * structure.
	 */
	private void runNextGenerationLogic() {

		Boolean[][] matrix = mediator.getMatrixCopy();
		for (int i = 0; i < threadsCount; i++) {

			int boardSize = (i == threadsCount - 1) ? boardLastPartSize
					: boardPartSize;
			int startPosition = (i == threadsCount - 1) ? -1
					* boardLastPartSize : i * boardPartSize;

			pool.submit(new CellStateGenerator(gridSize, boardSize,
					startPosition, matrix));
		}

		Boolean[][] nextMatrix = new Boolean[gridSize][gridSize];
		makeMatrixCopy(matrix, nextMatrix);

		for (int i = 0; i < threadsCount; i++) {
			try {
				ArrayList<CellCoordPair<Integer, Integer>> futureGet = pool
						.take().get();
				if (futureGet != null && !futureGet.isEmpty()) {
						for (CellCoordPair<Integer, Integer> pair : futureGet) {
							nextMatrix[pair.getLeft()][pair.getRight()] = pair.isAlive() ? Boolean.TRUE : Boolean.FALSE;
						}
				}
			} catch (InterruptedException e) {
				LOGGER.error("InterruptedException happened.");
				Thread.currentThread().interrupt();
				return;
			} catch (ExecutionException e) {
				LOGGER.error("ExecutionException happened.", e);
			}
		}

		mediator.updateContainer(nextMatrix);
	}

	/**
	 * Get boardLastPartSize.
	 * 
	 * @return boardLastPartSize
	 */
	private int getBoardLastPartSize() {
		return boardPartSize + gridSize % threadsCount;
	}

	/**
	 * Reinstall parameters needed for recreation of cells generation threads by
	 * input threads count.
	 * 
	 * @param count
	 */
	public void reInitThreadsParams(int count) {
		LOGGER.info(
				"Number of threads changed! Start reinstall params with count: {}",
				count);
		threadsCount = count;
		boardPartSize = gridSize / threadsCount;
		boardLastPartSize = getBoardLastPartSize();
	}

	/**
	 * Set count of cells generation threads.
	 * 
	 * @param count
	 */
	public void setThreadsCount(int count) {
		threadsCount = count;
	}

	/**
	 * Set runAllTime.
	 * 
	 * @param allTime
	 */
	public void setRunAllTime(boolean allTime) {
		this.isRunAllTime = allTime;
	}
	
	/**
	 * Causes the current thread to wait until another thread 
	 * invokes the doNotify() method 
	 */
	public void doWait() {
		synchronized (this) {
			try {
				wait();
			} catch (InterruptedException e) {
				LOGGER.error("InterruptedException happend");
			}
		}
	}

	/**
	 * Wakes up a single life cycle thread that is waiting on this object's monitor.
	 */
	public void doNotify() {
		synchronized (this) {
			notify();
		}
	}
}
