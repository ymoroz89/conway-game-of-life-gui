package com.conway.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import com.conway.config.BoardSizeOption
import com.conway.config.LifeConfig
import com.conway.engine.EngineListener
import com.conway.engine.EngineSnapshot
import com.conway.engine.PartitionPlanner
import com.conway.engine.SimulationEngine
import com.conway.engine.SimulationSeeder
import org.slf4j.LoggerFactory
import java.util.Locale

private val LOG = LoggerFactory.getLogger("com.conway.ui.App")

/** Chrome around the board: the sidebar plus the gaps either side of it. */
private const val SIDEBAR_AND_CHROME_DP = 250

/** Chrome below the board: the status bar plus the window padding. */
private const val STATUS_BAR_AND_CHROME_DP = 80

/**
 * The application window: controls, board and the render loop that drives them.
 *
 * Owns no simulation logic. It translates gestures into engine commands and, on the frame
 * clock, pulls the newest published snapshot for display.
 *
 * [initialEngine] is built by the caller rather than here. Creating an engine starts a
 * thread and a worker executor and then blocks while the board is seeded; none of that
 * belongs in composition, which Compose may skip, restart or abandon.
 */
@Composable
fun App(config: LifeConfig, initialEngine: SimulationEngine, onClose: () -> Unit) {
    val boardOptions = remember { BoardSizeOption.options(config.gridSize) }
    var gridSizeCells by remember { mutableStateOf(config.gridSize) }
    val engineRef = remember { mutableStateOf(initialEngine) }

    // One read of the engine per composition pass, so every effect and callback below binds
    // to the same instance instead of re-reading state that a board-size change may have
    // moved on. Swapping the engine recomposes App, which rebuilds all of them.
    val engine = engineRef.value

    DisposableEffect(Unit) {
        onDispose { engineRef.value.close() }
    }

    fun replaceEngine(newCells: Int) {
        // Rebuilding discards the simulation, so re-selecting the current size must be a
        // no-op rather than a silent reset of the user's board.
        if (newCells == gridSizeCells) return
        // Closed before the replacement is built, so a size change never has two simulation
        // threads and two worker executors live at once. Safe because the control that
        // triggers this is disabled while the simulation is running.
        engine.close()
        gridSizeCells = newCells
        engineRef.value = createEngine(newCells, config)
        LOG.info("Board switched to {}x{} cells", newCells, newCells)
    }

    // Engine lifecycle latches. The callbacks arrive on the simulation thread and write
    // straight into snapshot state, which is safe without marshalling: a state write takes
    // the global snapshot lock and is applied atomically, and Compose picks the new value
    // up on its next frame. This is the Compose equivalent of the Swing version's
    // invokeLater hop, not an omission of it.
    val engineLifecycle = remember(engine) { mutableStateOf(engine.state) }
    val errorMessage = remember(engine) { mutableStateOf<String?>(null) }
    DisposableEffect(engine) {
        val listener = object : EngineListener {
            override fun onStateChanged(state: SimulationEngine.State) {
                engineLifecycle.value = state
            }

            override fun onError(error: Throwable) {
                errorMessage.value = error.message ?: error.toString()
            }
        }
        engine.addListener(listener)
        // The listener outlives nothing: it is held by this engine alone, and a board-size
        // change closes and discards the engine along with it.
        onDispose { }
    }

    // The render loop. Pulls the newest snapshot on the display's frame clock, throttled to
    // the configured rate, so the simulation is not slowed by the repaint rate and the
    // repaint rate is not swamped by the simulation. Keyed on the engine so the loop
    // restarts when a new board is created.
    //
    // referentialEqualityPolicy, not the default structural one. The engine publishes a
    // fresh EngineSnapshot for every change and never mutates a published one, so identity
    // is an exact change test and says so directly -- the same test the Swing board used.
    //
    // Structural equality is not currently expensive here: it short-circuits on identity
    // while paused, and on the generation number while running, so it never reaches the
    // BooleanArray scan (measured at ~19ns per frame either way). But that cheapness is an
    // accident of the field order in Generation.equals rather than anything this code
    // enforces, and the scan behind it costs 28us on an 801x801 board.
    val snapshotState = remember(engine) {
        mutableStateOf(engine.snapshot(), referentialEqualityPolicy())
    }
    LaunchedEffect(engine, config.targetFps) {
        val pacer = FramePacer(config.targetFps)
        while (true) {
            withFrameNanos { frameNanos ->
                if (pacer.shouldRender(frameNanos)) {
                    snapshotState.value = engine.snapshot()
                }
            }
        }
    }

    var partitionSelection by remember(engine) {
        mutableStateOf(defaultPartitionsFor(gridSizeCells, config))
    }
    // Keyed on the engine as well as the value: a new board resets the selection to its own
    // recommendation, which can coincide with the previous one, and the effect must still
    // re-run against the engine that now needs telling.
    LaunchedEffect(engine, partitionSelection) { engine.setPartitions(partitionSelection) }

    val running = engineLifecycle.value == SimulationEngine.State.RUNNING

    // Sized from the configured board rather than left at the toolkit default, and centred,
    // the way the Swing frame's pack() plus setLocationRelativeTo(null) did. Sized once for
    // the board the application started on: a later size change letterboxes into whatever
    // space the window already has instead of resizing itself under the user.
    val boardEdgePx = config.cellSizePx * config.gridSize
    val windowWidth = boardEdgePx + SIDEBAR_AND_CHROME_DP
    val windowHeight = boardEdgePx + STATUS_BAR_AND_CHROME_DP
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(windowWidth.dp, windowHeight.dp),
    )

    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "Game of Life by Conway",
    ) {
        // Half the starting size, as the Swing frame did. Set once: it is an AWT property of
        // the window, not something to reassert on every recomposition.
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(windowWidth / 2, windowHeight / 2)
        }

        Column(modifier = Modifier.fillMaxSize().background(Theme.background)) {
            Row(modifier = Modifier.weight(1f).padding(10.dp)) {
                Sidebar(
                    boardOptions = boardOptions,
                    selectedCells = gridSizeCells,
                    partitionValue = partitionSelection,
                    partitionMax = engine.maxPartitions,
                    running = running,
                    onBoardSize = { cells -> replaceEngine(cells) },
                    onPartitionChange = { value ->
                        partitionSelection = value.coerceIn(1, engine.maxPartitions)
                    },
                    onStart = { engine.start() },
                    onStop = { engine.pause() },
                    onStep = { engine.step() },
                    onClear = { engine.clear() },
                    onQuit = onClose,
                )

                Box(modifier = Modifier.width(10.dp))

                // The snapshot is handed down as a lambda rather than read here. Passed as a
                // Generation it was an unstable parameter carrying a new instance every
                // frame, so BoardCanvas could never be skipped and its modifier chain was
                // rebuilt per frame; as a Function0 the parameter never changes, so the
                // board recomposes only when its extents do and a new generation costs one
                // draw. `./gradlew compileKotlin --rerun-tasks -PcomposeReports` shows this.
                BoardCanvas(
                    gridWidth = gridSizeCells,
                    gridHeight = gridSizeCells,
                    generation = { snapshotState.value.generation },
                    cellSize = config.cellSizePxFor(gridSizeCells),
                    onCellPress = { row, col -> engine.toggleCell(row, col) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            StatusBar(snapshot = { snapshotState.value }, state = engineLifecycle.value)
        }

        // Inside the window's content, not beside it. `Dialog` draws into the enclosing
        // window's Compose scene, so hosting it at application scope -- where there is no
        // scene -- failed the composition with "CompositionLocal LocalHostDefaultProvider
        // not present" the moment a generation failed, taking out the only path that
        // reports the failure.
        errorMessage.value?.let { message ->
            ErrorDialog(message, onDismiss = { errorMessage.value = null })
        }
    }
}

/** Reports a failed generation. The engine is already paused by the time this appears. */
@Composable
private fun ErrorDialog(message: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .background(Theme.surface, RoundedCornerShape(10.dp))
                    .padding(20.dp),
            ) {
                BasicText(
                    "Simulation error",
                    style = TextStyle(color = Theme.danger, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                BasicText(message, style = TextStyle(color = Theme.text, fontSize = 13.sp))
                Spacer(modifier = Modifier.height(16.dp))
                FlatButton(
                    "Dismiss",
                    enabled = true,
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

/** The left-hand control column. Disabled state mirrors the Swing-era frame. */
@Composable
private fun Sidebar(
    boardOptions: List<BoardSizeOption>,
    selectedCells: Int,
    partitionValue: Int,
    partitionMax: Int,
    running: Boolean,
    onBoardSize: (Int) -> Unit,
    onPartitionChange: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStep: () -> Unit,
    onClear: () -> Unit,
    onQuit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 220.dp)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Caption("Board size")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for (option in boardOptions) {
                // Resizing discards the simulation, so it is only offered while stopped.
                ChoiceChip(
                    text = option.label,
                    selected = option.cells == selectedCells,
                    enabled = !running,
                    onClick = { onBoardSize(option.cells) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Caption("Parallel partitions")
        Stepper(
            value = partitionValue,
            enabled = !running,
            onDecrement = { onPartitionChange(partitionValue - 1) },
            onIncrement = { onPartitionChange(partitionValue + 1) },
            modifier = Modifier.fillMaxWidth(),
        )
        BasicText(
            "1 to $partitionMax",
            style = TextStyle(color = Theme.textMuted, fontSize = 10.sp),
        )

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalRule()

        Spacer(modifier = Modifier.height(14.dp))
        SidebarButton("Start", enabled = !running, onClick = onStart, variant = ButtonVariant.PRIMARY)
        SidebarButton("Stop", enabled = running, onClick = onStop)
        SidebarButton("Next generation", enabled = !running, onClick = onStep)
        SidebarButton("Clear", enabled = !running, onClick = onClear)

        Spacer(modifier = Modifier.weight(1f))

        HorizontalRule()
        Spacer(modifier = Modifier.height(6.dp))
        SidebarButton("Quit", enabled = true, onClick = onQuit, variant = ButtonVariant.DANGER)
    }
}

/** A full-width one-pixel rule in the border colour. */
@Composable
private fun HorizontalRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Theme.border),
    )
}

/**
 * The caption-above-value status bar.
 *
 * [snapshot] is a lambda, not a value: reading the render-loop state inside this
 * composable confines the per-frame invalidation to the status bar instead of restarting
 * the whole window.
 */
@Composable
private fun StatusBar(snapshot: () -> EngineSnapshot, state: SimulationEngine.State) {
    val current = snapshot()
    val generation = current.generation
    val stats = current.stats
    val running = state == SimulationEngine.State.RUNNING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Theme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusSegment("Generation", String.format(Locale.ROOT, "%,d", stats.generation))
        StatusSegment("Gen/sec", String.format(Locale.ROOT, "%,.1f", stats.generationsPerSecond))
        StatusSegment("Population", String.format(Locale.ROOT, "%,d", generation.population))
        StatusSegment("Partitions", stats.partitions.toString())
        Spacer(modifier = Modifier.weight(1f))
        BasicText(
            if (running) "RUNNING" else "IDLE",
            style = TextStyle(
                color = if (running) Theme.accent else Theme.textMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.4.sp,
            ),
        )
    }
}

/**
 * The band ceiling for a board of [gridSize] cells. More bands than rows would produce
 * empty work units, so a smaller board lowers the ceiling with it rather than offering a
 * count the board cannot fill.
 */
internal fun maxPartitionsFor(gridSize: Int, config: LifeConfig): Int =
    minOf(config.maxPartitions, gridSize)

/**
 * The partition count a board of [gridSize] should start on. The configured board keeps
 * whatever was configured for it; any other size is sized by the planner.
 */
internal fun defaultPartitionsFor(gridSize: Int, config: LifeConfig): Int =
    if (gridSize == config.gridSize) config.defaultPartitions
    else PartitionPlanner.recommend(gridSize, maxPartitionsFor(gridSize, config))

/** Builds an engine for [gridSize], applying the recommended partition count and seeding
 *  the historical starting board (two Gosper glider guns). */
internal fun createEngine(gridSize: Int, config: LifeConfig): SimulationEngine {
    val engine = SimulationEngine(gridSize, gridSize, maxPartitionsFor(gridSize, config))
    engine.setPartitions(defaultPartitionsFor(gridSize, config))
    SimulationSeeder.seedBoard(engine, gridSize)
    return engine
}
