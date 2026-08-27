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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
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

@Composable
fun App(config: LifeConfig, onClose: () -> Unit) {
    val boardOptions = remember { BoardSizeOption.options(config.gridSize) }
    var gridSizeCells by remember { mutableStateOf(config.gridSize) }
    var engine by remember { mutableStateOf(createEngine(config.gridSize, config)) }

    DisposableEffect(Unit) {
        onDispose { engine.close() }
    }

    fun replaceEngine(newCells: Int) {
        val old = engine
        gridSizeCells = newCells
        engine = createEngine(newCells, config)
        old.close()
        LOG.info("Board switched to {}x{} cells", newCells, newCells)
    }

    // Engine lifecycle latches, marshalled through mutable state (thread-safe writes).
    val engineState = remember(engine) { mutableStateOf(engine.state) }
    val errorMessage = remember(engine) { mutableStateOf<String?>(null) }
    DisposableEffect(engine) {
        val listener = object : EngineListener {
            override fun onStateChanged(state: SimulationEngine.State) {
                engineState.value = state
            }

            override fun onError(error: Throwable) {
                errorMessage.value = error.message ?: error.toString()
            }
        }
        engine.addListener(listener)
        onDispose {}
    }

    // Pull the newest snapshot once per frame; rendering is decoupled from the simulation.
    // Keyed on the engine so the frame loop restarts when a new board is created.
    val snapshot by produceState(initialValue = engine.snapshot(), engine) {
        while (true) {
            value = engine.snapshot()
            withFrameNanos { }
        }
    }

    val recommendedFor: (Int) -> Int = { cells ->
        if (cells == config.gridSize) {
            config.defaultPartitions
        } else {
            PartitionPlanner.recommend(cells, config.maxPartitions)
        }
    }
    var partitionSelection by remember(engine) { mutableStateOf(recommendedFor(gridSizeCells)) }
    LaunchedEffect(partitionSelection) { engine.setPartitions(partitionSelection) }

    val running = engineState.value == SimulationEngine.State.RUNNING

    Window(onCloseRequest = onClose, title = "Game of Life by Conway") {
        Column(modifier = Modifier.fillMaxSize().background(Theme.background)) {
            Row(modifier = Modifier.weight(1f).padding(10.dp)) {
                Sidebar(
                    boardOptions = boardOptions,
                    selectedCells = gridSizeCells,
                    partitionValue = partitionSelection,
                    partitionMax = engine.maxPartitions,
                    running = running,
                    boardEnabled = !running,
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

                BoardCanvas(
                    generation = snapshot.generation,
                    cellSize = config.cellSizePxFor(gridSizeCells),
                    onCellPress = { row, col -> engine.toggleCell(row, col) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            StatusBar(snapshot, engineState.value)
        }
    }

    errorMessage.value?.let { message ->
        Dialog(onDismissRequest = { errorMessage.value = null }) {
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
                        onClick = { errorMessage.value = null },
                        modifier = Modifier.align(Alignment.End),
                    )
                }
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
    boardEnabled: Boolean,
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
                ChoiceChip(
                    text = option.label,
                    selected = option.cells == selectedCells,
                    enabled = boardEnabled,
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

/** The caption-above-value status bar. */
@Composable
private fun StatusBar(snapshot: EngineSnapshot, state: SimulationEngine.State) {
    val generation = snapshot.generation
    val stats = snapshot.stats
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

/** Builds an engine for [gridSize], applying the recommended partition count and seeding
 *  the historical starting board (two Gosper glider guns). */
internal fun createEngine(gridSize: Int, config: LifeConfig): SimulationEngine {
    val engine = SimulationEngine(gridSize, gridSize, config.maxPartitions)
    engine.setPartitions(
        if (gridSize == config.gridSize) config.defaultPartitions
        else PartitionPlanner.recommend(gridSize, config.maxPartitions),
    )
    SimulationSeeder.seedBoard(engine, gridSize)
    return engine
}
