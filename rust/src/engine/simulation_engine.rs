use std::panic::AssertUnwindSafe;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::engine::life_rules::LifeRules;
use crate::engine::simulation_stats::SimulationStats;
use crate::model::{Generation, Pattern};

/// Lifecycle states reported through [`SimulationEngine::state`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    /// Idle; the board only changes in response to explicit commands.
    Paused,
    /// Advancing generations continuously.
    Running,
    /// Shut down; no further commands are processed.
    Stopped,
    /// A generation could not be computed. The engine is paused and the error
    /// was reported through [`SimulationEngine::take_errors`].
    Failed,
}

impl State {
    const fn code(self) -> u8 {
        match self {
            State::Paused => 0,
            State::Running => 1,
            State::Stopped => 2,
            State::Failed => 3,
        }
    }

    const fn from_code(code: u8) -> State {
        match code {
            0 => State::Paused,
            1 => State::Running,
            2 => State::Stopped,
            _ => State::Failed,
        }
    }
}

/// The board together with the statistics that belong to it, as one immutable
/// bundle.
///
/// The generation is an [`Arc`], so readers can hold it cheaply, and its
/// contents are immutable: the simulation thread hands the GUI a new bundle on
/// every publication, so a reader either sees generation N or generation N+1
/// in full and never a partially-applied board.
#[derive(Debug, Clone)]
pub struct EngineSnapshot {
    generation: Arc<Generation>,
    stats: SimulationStats,
}

impl EngineSnapshot {
    fn initial(width: usize, height: usize) -> EngineSnapshot {
        EngineSnapshot {
            generation: Arc::new(Generation::empty(width, height)),
            stats: SimulationStats::idle(),
        }
    }

    fn new(generation: Arc<Generation>, stats: SimulationStats) -> EngineSnapshot {
        EngineSnapshot { generation, stats }
    }

    /// The board, as a cheap-to-hold immutable handle.
    pub fn generation_arc(&self) -> &Arc<Generation> {
        &self.generation
    }

    pub fn generation(&self) -> &Generation {
        &self.generation
    }

    /// The throughput numbers that belong with the board.
    pub fn stats(&self) -> SimulationStats {
        self.stats
    }
}

/// The complete set of operations the simulation thread understands.
enum Command {
    Start,
    Pause,
    Step,
    Clear,
    Shutdown,
    ToggleCell { row: i32, col: i32 },
    PlacePattern { pattern: Arc<Pattern>, row: i32, col: i32 },
    SetPartitions { count: usize },
    /// A one-shot acknowledgement used by [`SimulationEngine::sync`].
    Barrier { ack: mpsc::Sender<()> },
}

/// Everything the simulation thread and the callers share.
struct Shared {
    /// The single publication point: the board and its statistics are always
    /// observed together, so a reader can never see a new generation with
    /// stale counters. Written only by the simulation thread (and once by the
    /// constructor); read by any thread.
    snapshot: Mutex<EngineSnapshot>,
    state: AtomicU8,
    errors: Mutex<Vec<String>>,
    closed: AtomicBool,
    thread_alive: AtomicBool,
    join: Mutex<Option<JoinHandle<()>>>,
    width: usize,
    height: usize,
    max_partitions: usize,
}

/// Owns the board and runs the simulation.
///
/// The engine is a single-writer actor. Exactly one thread — the simulation
/// thread started in [`SimulationEngine::new`] — ever mutates simulation state,
/// and every caller-facing method does nothing but enqueue a [`Command`]. The
/// board is handed to readers as an immutable [`EngineSnapshot`] published
/// behind a lock, so a reader sees either generation N or generation N+1 in
/// full and needs no further synchronisation. Requests are queued, never
/// dropped, so a `Step` issued at any moment is always honoured.
///
/// All public methods are safe to call from any thread.
///
/// The simulation thread blocks on the command receiver while paused and
/// *drains* pending commands without blocking while running, then advances one
/// generation — the same loop the Java version used.
pub struct SimulationEngine {
    shared: Arc<Shared>,
    tx: mpsc::Sender<Command>,
}

impl SimulationEngine {
    /// Creates an engine with an empty board and starts its simulation thread
    /// paused.
    ///
    /// # Panics
    ///
    /// Panics if the board dimensions are not positive or `max_partitions` is
    /// less than one.
    pub fn new(width: usize, height: usize, max_partitions: usize) -> SimulationEngine {
        assert!(width > 0 && height > 0, "board dimensions must be positive but were {width}x{height}");
        assert!(max_partitions >= 1, "maxPartitions must be at least 1 but was {max_partitions}");
        let shared = Arc::new(Shared {
            snapshot: Mutex::new(EngineSnapshot::initial(width, height)),
            state: AtomicU8::new(State::Paused.code()),
            errors: Mutex::new(Vec::new()),
            closed: AtomicBool::new(false),
            thread_alive: AtomicBool::new(false),
            join: Mutex::new(None),
            width,
            height,
            max_partitions,
        });
        let (tx, rx) = mpsc::channel();
        let thread_shared = shared.clone();
        let handle = std::thread::Builder::new()
            .name("conway-simulation".to_string())
            .spawn(move || simulate(thread_shared, rx))
            .expect("failed to spawn the simulation thread");
        *shared.join.lock().unwrap() = Some(handle);
        SimulationEngine { shared, tx }
    }

    // ------------------------------------------------------------------
    // Commands. Each simply enqueues; none blocks on the simulation thread.
    // ------------------------------------------------------------------

    /// Begins advancing generations continuously.
    pub fn start(&self) {
        self.submit(Command::Start);
    }

    /// Stops advancing generations. The board is retained.
    pub fn pause(&self) {
        self.submit(Command::Pause);
    }

    /// Advances exactly one generation. Requests are queued and never dropped.
    pub fn step(&self) {
        self.submit(Command::Step);
    }

    /// Empties the board and resets the generation counter.
    pub fn clear(&self) {
        self.submit(Command::Clear);
    }

    /// Flips a cell. Out-of-bounds coordinates are ignored rather than
    /// rejected, mirroring the Java engine.
    pub fn toggle_cell(&self, row: i32, col: i32) {
        self.submit(Command::ToggleCell { row, col });
    }

    /// Stamps a pattern in with its top-left corner at `(row, col)`; cells
    /// outside the board are clipped.
    pub fn place(&self, pattern: Arc<Pattern>, row: i32, col: i32) {
        self.submit(Command::PlacePattern { pattern, row, col });
    }

    /// Sets the number of row bands used per generation, clamped to
    /// `[1, max_partitions]`.
    pub fn set_partitions(&self, count: usize) {
        let capped = count.clamp(1, self.shared.max_partitions);
        self.submit(Command::SetPartitions { count: capped });
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /// The most recently published board together with its statistics. Every
    /// call may return a newer bundle; the result is safe to hold.
    pub fn snapshot(&self) -> EngineSnapshot {
        self.shared.snapshot.lock().unwrap().clone()
    }

    pub fn state(&self) -> State {
        State::from_code(self.shared.state.load(Ordering::Acquire))
    }

    pub fn max_partitions(&self) -> usize {
        self.shared.max_partitions
    }

    /// Whether the simulation thread is still alive (spawned and not yet
    /// exited). Used by tests to prove `close` stopped it.
    pub fn is_simulation_running(&self) -> bool {
        self.shared.thread_alive.load(Ordering::Acquire)
    }

    /// Errors produced by failed generations, drained in order. The GUI polls
    /// this each frame and shows the first one as a dialog.
    pub fn take_errors(&self) -> Vec<String> {
        std::mem::take(&mut *self.shared.errors.lock().unwrap())
    }

    /// Blocks until every command enqueued before this call has been processed.
    ///
    /// Intended for tests and for deterministic start-up sequencing.
    ///
    /// Returns `false` if the timeout expired or the engine shut down first.
    pub fn sync(&self, timeout: Duration) -> bool {
        if self.shared.closed.load(Ordering::Acquire) {
            return false;
        }
        let (ack, rx) = mpsc::channel();
        if self.tx.send(Command::Barrier { ack }).is_err() {
            return false;
        }
        rx.recv_timeout(timeout).is_ok()
    }

    /// Stops the simulation thread. Idempotent; blocks until the thread has
    /// exited.
    pub fn close(&self) {
        if self.shared.closed.swap(true, Ordering::AcqRel) {
            return;
        }
        let _ = self.tx.send(Command::Shutdown);
        if let Some(handle) = self.shared.join.lock().unwrap().take() {
            // The simulation thread exits promptly after processing Shutdown.
            let _ = handle.join();
        }
        self.shared.state.store(State::Stopped.code(), Ordering::Release);
    }

    fn submit(&self, command: Command) {
        if self.shared.closed.load(Ordering::Acquire) {
            return;
        }
        let _ = self.tx.send(command);
    }
}

/// The simulation thread: owns the board and processes commands.
///
/// While running, pending commands are drained without blocking and a
/// generation is advanced whenever the queue is empty; while paused, the
/// thread blocks on the receiver so an idle engine burns no CPU. Every mutation
/// goes through this thread; nothing else may touch simulation state.
fn simulate(shared: Arc<Shared>, rx: mpsc::Receiver<Command>) {
    shared.thread_alive.store(true, Ordering::Release);
    let rules = LifeRules;
    let mut board: Arc<Generation> = Arc::new(Generation::empty(shared.width, shared.height));
    let mut running = false;
    let mut shutdown = false;
    let mut partitions = 1usize;
    let mut generations_in_run = 0u64;
    let mut run_start: Option<Instant> = None;
    let mut elapsed_nanos = 0u64;

    macro_rules! publish {
        ($board:expr, $stats:expr) => {
            *shared.snapshot.lock().unwrap() = EngineSnapshot::new($board, $stats);
        };
    }
    macro_rules! stats {
        ($board:expr) => {
            SimulationStats {
                generation: $board.number(),
                generations_in_run,
                elapsed_nanos,
                partitions,
            }
        };
    }

    while !shutdown {
        let command = if running {
            match rx.try_recv() {
                Ok(command) => Some(command),
                Err(mpsc::TryRecvError::Empty) => None,
                Err(mpsc::TryRecvError::Disconnected) => break,
            }
        } else {
            match rx.recv() {
                Ok(command) => Some(command),
                Err(_) => break,
            }
        };

        match command {
            Some(Command::Start) => {
                if !running {
                    running = true;
                    generations_in_run = 0;
                    elapsed_nanos = 0;
                    run_start = Some(Instant::now());
                    shared.state.store(State::Running.code(), Ordering::Release);
                }
            }
            Some(Command::Pause) => {
                if running {
                    running = false;
                    log_run_summary(generations_in_run, elapsed_nanos);
                }
                shared.state.store(State::Paused.code(), Ordering::Release);
            }
            Some(Command::Step) => {
                advance(
                    &shared,
                    &rules,
                    &mut board,
                    &mut running,
                    &mut generations_in_run,
                    &mut elapsed_nanos,
                    &mut run_start,
                    partitions,
                    false,
                );
            }
            Some(Command::Clear) => {
                if running {
                    running = false;
                    log_run_summary(generations_in_run, elapsed_nanos);
                }
                generations_in_run = 0;
                elapsed_nanos = 0;
                board = Arc::new(Generation::empty(shared.width, shared.height));
                publish!(board.clone(), stats!(board));
                shared.state.store(State::Paused.code(), Ordering::Release);
            }
            Some(Command::ToggleCell { row, col }) => {
                let row_in = row >= 0 && (row as usize) < shared.height;
                let col_in = col >= 0 && (col as usize) < shared.width;
                if row_in && col_in {
                    let live = !board.is_alive(row as usize, col as usize);
                    board = Arc::new(board.with_cell(row as usize, col as usize, live));
                    let current = board.clone();
                    let stats = shared.snapshot.lock().unwrap().stats;
                    publish!(current, stats);
                }
            }
            Some(Command::PlacePattern { pattern, row, col }) => {
                board = Arc::new(board.with_pattern(&pattern, row, col));
                let current = board.clone();
                let stats = shared.snapshot.lock().unwrap().stats;
                publish!(current, stats);
            }
            Some(Command::SetPartitions { count }) => {
                if partitions != count {
                    partitions = count;
                    let current = board.clone();
                    publish!(current, stats!(board));
                }
            }
            Some(Command::Barrier { ack }) => {
                let _ = ack.send(());
            }
            Some(Command::Shutdown) => {
                if running {
                    running = false;
                    log_run_summary(generations_in_run, elapsed_nanos);
                }
                shutdown = true;
            }
            None => {
                // Running with an empty queue: advance.
                advance(
                    &shared,
                    &rules,
                    &mut board,
                    &mut running,
                    &mut generations_in_run,
                    &mut elapsed_nanos,
                    &mut run_start,
                    partitions,
                    true,
                );
            }
        }
    }

    shared.thread_alive.store(false, Ordering::Release);
}

/// Computes and publishes the next generation.
///
/// `timed` selects whether this advance counts toward the throughput
/// measurement: manual steps are excluded so human think time does not skew
/// the rate.
// The worker-loop state is passed explicitly to keep it off a struct that would
// otherwise exist only to bundle these few `&mut` handles.
#[allow(clippy::too_many_arguments)]
fn advance(
    shared: &Arc<Shared>,
    rules: &LifeRules,
    board: &mut Arc<Generation>,
    running: &mut bool,
    generations_in_run: &mut u64,
    elapsed_nanos: &mut u64,
    run_start: &mut Option<Instant>,
    partitions: usize,
    timed: bool,
) {
    let next = std::panic::catch_unwind(AssertUnwindSafe(|| {
        let current = board.clone();
        rules.next(&current, partitions)
    }));
    match next {
        Ok(next) => {
            if timed {
                *generations_in_run += 1;
                *elapsed_nanos = run_start
                    .map(|start| start.elapsed().as_nanos() as u64)
                    .unwrap_or(0);
            }
            let new_board = Arc::new(next);
            *board = new_board.clone();
            let stats = SimulationStats {
                generation: new_board.number(),
                generations_in_run: *generations_in_run,
                elapsed_nanos: *elapsed_nanos,
                partitions,
            };
            *shared.snapshot.lock().unwrap() = EngineSnapshot::new(new_board, stats);
        }
        Err(payload) => {
            *running = false;
            let message = payload
                .downcast_ref::<&str>()
                .copied()
                .or_else(|| payload.downcast_ref::<String>().map(String::as_str))
                .unwrap_or("a generation could not be computed");
            shared.errors.lock().unwrap().push(format!(
                "Generation {}: {message}",
                board.number() + 1
            ));
            shared.state.store(State::Failed.code(), Ordering::Release);
        }
    }
}

fn log_run_summary(generations_in_run: u64, elapsed_nanos: u64) {
    let seconds = elapsed_nanos as f64 / 1e9;
    let rate = if seconds > 0.0 {
        generations_in_run as f64 / seconds
    } else {
        0.0
    };
    eprintln!(
        "conway-life: run finished: {generations_in_run} generations in {seconds:.3} s, {rate:.1} generations/s"
    );
}