# Profiling issues — fixing progress

Tracks remediation of the findings in [profiling-findings.md](profiling-findings.md).
Updated as each fix lands.

## Status

| ID | Finding | Priority | Status |
| --- | --- | --- | --- |
| P1-1 | Renderer redraws the whole board every frame | 🔴 P1 | ✅ **Done** |
| P1-2 | Per-generation barrier caps parallelism | 🔴 P1 | ◐ **Partly** — see notes |
| P1-3 | Worker pool five times oversized | 🔴 P1 | ✅ **Done** |
| P2-4 | Re-profile with sampling rather than instrumentation | 🟡 P2 | ✅ **Done** |
| P2-5 | `Generation.population()` scans the board every frame | 🟡 P2 | ⬜ Won't fix — 0.1% of CPU |
| N1 | Two `boolean[]` allocated per generation | 🟡 P2 | ⬜ Won't fix — measured, see notes |
| — | **Bug:** ever-alive flags erased when the board dies out | 🔴 | ✅ **Fixed** |
| N2 | `ActiveRegion` per band per generation | 🟢 P3 | ⬜ Won't fix — 2.3 MB/s |
| P2-6 | Machine has substantial headroom (context only) | 🟡 P2 | ℹ️ No action |
| P3-7..10 | Informational | 🟢 P3 | ℹ️ No action |
| H-11..16 | Confirmed healthy | ✅ | ℹ️ No action |

---

## P1-1 — Incremental rendering ✅ Done

**Problem.** `BoardView.renderInto` cleared the entire pixel buffer and rewrote all 40,401
cells on *every* paint, including repaints caused by exposure or resize where nothing had
changed, while the engine was evaluating roughly 2% of the board per generation. The event
dispatch thread consumed as much CPU as the entire simulation control path.

### Change

`BoardView` now tracks `rendered` — the generation the backing image currently shows, which
is not always the generation being displayed. `renderInto` picks one of three paths, cheapest
first:

1. **Nothing to do** — the image already shows this generation. Covers every repaint from
   exposure, resize, focus change or the letterbox border redraw.
2. **Incremental** — the previous generation has the same dimensions, so only cells whose
   colour differs are rewritten.
3. **Full redraw** — first paint, or the board dimensions changed (the board-size control).

The full path retains the buffer clear, because it is what paints the cell separators. The
incremental path deliberately does *not* clear: separators never change, and clearing would
paint over them.

Files: [`BoardView.java`](../src/main/java/com/conway/ui/BoardView.java),
[`BoardViewTest.java`](../src/test/java/com/conway/ui/BoardViewTest.java).

### Measured effect

`RenderBench`: real generations from `LifeRules`, 400 warmup + 1200 measured frames, timing
`BoardView.paint` only.

| Board | Case | Before | After | Gain |
| --- | --- | --- | --- | --- |
| 201×201 @ 4px | paint with new generation | 0.716 ms | 0.347 ms | **2.06×** |
| 201×201 @ 4px | repaint, board unchanged | 0.691 ms | 0.286 ms | **2.42×** |
| 801×801 @ 1px | paint with new generation | 2.467 ms | 0.905 ms | **2.73×** |
| 801×801 @ 1px | repaint, board unchanged | 2.321 ms | 0.258 ms | **9.00×** |

Frame time is now dominated by Java2D's scaled blit, which the fix does not touch. Subtracting
that floor isolates the work actually changed:

| Board | Render work before | after | Gain |
| --- | --- | --- | --- |
| 201×201 | 0.430 ms | 0.061 ms | **7.0×** |
| 801×801 | 2.209 ms | 0.647 ms | **3.4×** |

### Tests

Four tests added (103 total, all passing). The load-bearing one is
`incrementalRenderMatchesFullRender`: the entire optimisation rests on the incremental and
full paths producing identical pixels, so that is asserted directly rather than inferred —
a sequence rendered step by step is compared pixel-for-pixel against the final board rendered
from scratch.

- `incrementalRenderMatchesFullRender` — covers alive → trail → empty, including the
  "backwards" transition a naive add-only renderer would miss.
- `incrementalRenderMatchesFullRenderOverManySteps` — 25 seeded-random boards, `alive` and
  `everAlive` varied independently so trail cells are exercised.
- `separatorsAreNotOverwrittenByIncrementalUpdates` — the incremental path must not paint
  over the grid.
- `differentDimensionsTriggerFullRedraw` — a smaller board must clear the area it no longer
  covers rather than leave the previous board visible.

**Mutation-checked.** The property tests were verified to have teeth by injecting a realistic
bug — comparing only `alive` and ignoring the `everAlive` trail flag. Both incremental tests
failed with an exact diagnostic (`Pixel (1, 1) differs: expected 7A3B5E but was 11141A`).
A property test that has never been seen to fail is not evidence.

### Honest limits

- **The comparison scan is still O(board).** Only the pixel *writes* and the buffer clear
  became proportional to what changed. This shows in the numbers: at 801×801 the incremental
  render costs 0.647 ms against 0.061 ms at 201×201, because the scan visits 641,601 cells
  instead of 40,401.
- **`ActiveRegion` was deliberately not used to narrow the scan.** `Generation.activeRegion()`
  is the bounding box of *live* cells. Restricting the diff to the union of the previous and
  current regions is probably sound, but it must hold for every way a `Generation` is
  produced — `LifeRules.next`, `withCell` (user toggles), `withPattern`, `empty` — and a
  missed user edit would be a visible correctness bug. Not worth the risk for a scan whose
  cost is already secondary; see follow-ups.
- **This was never the crisis the snapshot implied.** Instrumentation inflated the render
  cost dramatically (artifact A1). Uninstrumented, the old renderer took 0.72 ms/frame at
  201×201 against a 16.7 ms budget at 60 fps — about 4%. The fix is real and it matters at
  larger boards, but the EDT was not at risk on the default board.

### Follow-ups

| Item | Rationale |
| --- | --- |
| Narrow the diff scan using `ActiveRegion` | Would make the scan proportional to activity too. Needs the union-of-regions argument verified against every `Generation` factory, plus a test that a user toggle outside the live region still renders. |
| Revisit the scaled blit | Now the dominant per-frame cost at 201×201 (0.286 of 0.347 ms). Drawing at native size, or a `VolatileImage`, would attack it. |

---

## N1–N3 — New findings from the clean profile

Summary: **one correctness bug found and fixed; the performance items measured and left
alone.** The fix came out of investigating N1 rather than from the finding itself.

### Bug found: ever-alive flags erased when the board dies out ✅ Fixed

`next` allocates a zeroed `dstEver`, and the early return for an empty active region handed
that array straight back. So the moment the population reached zero, every "died here" flag
was wiped and the whole trail vanished from the board — despite `Generation` documenting the
flags as monotonic within a run and reset only by a clear.

One line: carry the flags forward before returning. Covered by
`everAliveSurvivesExtinction`, which fails on the previous revision with
*"its trail must outlive it"* and passes now, plus `everAliveAccumulates` for the ordinary
path.

File: [`LifeRules.java`](../src/main/java/com/conway/engine/LifeRules.java).

### N1 — Per-generation allocation: measured, not changed ⬜ Won't fix

The finding was ~1.5 GB/s of `boolean[]` allocation from two fresh arrays per generation.
Reusing those buffers means recycling arrays that live `Generation` snapshots still own, and
the immutable-snapshot contract is precisely what removed the original data race between the
simulation thread and the renderer. **The measured cost does not justify touching it:** GC is
167 ms across 60 s, 0.28%.

While in that code the redundant `everAlive` pass looked like an easy win — the tail sweeps
the full width of every row, though `dst` can only be true inside the active region. Three
variants were implemented and benchmarked against an interleaved A/B (two rounds, three JVMs
each, alternating builds to cancel thermal drift):

| Variant | Sparse board | Dense board |
| --- | --- | --- |
| A — clone `srcEver`, mark inside the rule loop | 1.3–1.6× faster | **4% slower** at 801/16 |
| B — per-band copy hoisted before the rule loop | 1.4× faster | **2–6% slower** |
| C — copy and OR as two passes, OR narrowed | 1.4–1.8× faster | **~3% slower** |
| D — copy and OR fused, rows and columns narrowed | 1.3–1.8× faster | **2–5% slower** |

Medians from the interleaved run for the best variant:

| Case | Before | After (D) | |
| --- | --- | --- | --- |
| 801, 16 bands, dense | 245.7 µs | 258.1 µs | 5% slower |
| 801, 1 band, dense | 857 µs | 878 µs | 2.5% slower |
| 201, 4 bands, dense | 58.9 µs | 59.9 µs | 2% slower |
| 801, 16 bands, sparse | 47.4 µs | 30.9 µs | 1.53× faster |
| 201, 16 bands, sparse | 4.85 µs | 2.75 µs | 1.76× faster |

**All four were reverted.** The trade is bad for this application: sparse boards were never
slow in absolute terms — every sparse figure is under 50 µs, over 20,000 generations a second
— while dense is the actual bottleneck at 246–880 µs, and dense is what the seeded board is.
Optimising the case nobody waits on, at the cost of the case they do, is the wrong direction.

The mechanism is consistent across all four attempts: the redundant OR buys sequential access
over a contiguous row. Skipping work costs more than the work is worth, whether the skip is
done by narrowing bounds, splitting passes, or moving the marks into the rule loop. Variant A
additionally moved a full-board copy off the parallel bands onto the serial prologue, which is
straightforward Amdahl.

A comment in `computeBand` records this so the next reader does not re-derive it — the loop
looks obviously improvable and is not.

### N2 — `ActiveRegion` per band per generation ⬜ Won't fix

Four small records per generation, roughly 2.3 MB/s. Three orders of magnitude below the
array allocation that itself does not justify action.

### N3 / P2-5 — `Generation.population()` per frame ⬜ Won't fix

Confirmed real but **0.1% of total CPU** (3 samples in 2,595). Caching it on `Generation` would
not help: the engine produces ~18,000 generations a second against 60 frames, so each frame
sees a fresh instance and would miss the cache every time. Genuinely removing the scan means
counting inside `computeBand` and threading the total out through the band API — a change to
the hot loop and the `Generation.adopt` signature for a tenth of a percent. Not worth it.

---

## P2-4 — Re-profiled with sampling ✅ Done

A 60 second JFR recording (`settings=profile`, ~1% overhead) of a driver that mirrors the
running application: the engine advancing continuously on its own thread plus a 60 fps render
loop doing exactly what `LifeFrame` does per tick. Headless, so no window is needed, but the
threading shape and per-tick work match.

```
java -XX:StartFlightRecording=duration=65s,filename=clean.jfr,settings=profile \
     -Djava.awt.headless=true -cp target/GuiLife.jar:<driver> ProfileDriver 60
```

### Headline

**18,204 generations/s**, against roughly 1,000/s under the instrumented profiler — and in
line with the uninstrumented benchmark. **4 live worker threads**, against 20.

CPU distribution over 2,595 execution samples. Note these are shares of CPU actually consumed
by Java code: JFR samples running threads, so a parked thread contributes nothing.

| Thread | Samples | Share | Top frames |
| --- | --- | --- | --- |
| `conway-worker-*` | 2,575 | **99.2%** | `computeBand` 88.3%, `ruleAt` 10.8% |
| `render-loop` | 19 | **0.7%** | `renderChanges` 47%, `writeCell` 21%, `population` 16% |
| `conway-simulation` | 1 | **0.04%** | `LifeRules.next` |

GC: 290 young collections, **167 ms total pause across 60 s (0.28%)**, max 2.5 ms.

### What this validates

| Claim from the instrumented snapshot | Clean profile | Verdict |
| --- | --- | --- |
| A1 — `ruleAt` 20% and `colorOf` 7.4% are instrumentation artifact | `colorOf` does not appear at all; `ruleAt` is 10.8% of worker time and is the real border-cell path | ✅ Confirmed artifact |
| A2 — throughput figures distorted | 18,204 gen/s vs ~1,000 | ✅ Confirmed, 18× |
| P1-1 — renderer redraws the whole board; EDT costs as much as the simulation | render loop is **0.7%** of CPU, and `renderChanges` is its top frame, so the incremental path is the one running | ✅ Fixed, validated |
| P1-3 — 20 workers for 4 bands | 4 live workers | ✅ Fixed, validated |

### What this invalidates — P1-2's premise was wrong

P1-2 was filed as "the per-generation barrier caps parallelism", from the dump showing
`conway-simulation` at 17.3% CPU and parked in `invokeAll`. The clean profile says otherwise:

- The simulation thread accounts for **1 sample in 2,595**.
- `jdk.ThreadPark` recorded **one** event above its 10 ms threshold across the whole run. With
  1.09 million generations in 60 s, every barrier wait was microseconds.

A coordinator thread that submits four tasks and parks while workers do the work is not a
bottleneck — it is the design working. The 17.3% CPU in the original dump was largely
instrumentation overhead *on that thread*, since every `next`, `adopt` and `merge` call carried
a probe.

The real ceiling on parallel scaling at 201×201 is that `computeBand` stops scaling past about
four threads at that working-set size — a memory and compute characteristic, not a
coordination one. **The banding fix made under P1-2 remains valid**: it was justified by direct
measurement of sparse boards (6× slower with 16 bands), not by the barrier premise. But the
premise itself did not survive a clean measurement, and the findings document has been
annotated accordingly.

### New findings from the clean profile

| # | Finding | Priority |
| --- | --- | --- |
| N1 | **Two `boolean[40401]` arrays allocated per generation** in `LifeRules.next` — the top allocation site by a wide margin, ~1.5 GB/s at this rate. GC absorbs it (0.28% pause), so it is not urgent, but it is the obvious next step if throughput ever matters: reuse buffers across generations. | 🟡 P2 |
| N2 | **One `ActiveRegion` allocated per band per generation** — second-largest site, ~4.4 M objects over the run. A record, so escape analysis may already remove some. | 🟢 P3 |
| N3 | **`Generation.population()` is 16% of render-loop samples**, confirming P2-5 is real but tiny — 0.1% of total CPU. | 🟡 P2 (already filed) |
| N4 | **`ruleAt` is 10.8% of worker time** and is genuine, not artifact: it is the bounds-checked border path, while the fused interior loop in `computeBand` handles the rest. | ℹ️ No action |

### Honest limits

- The driver is headless and stands in for the EDT with a plain thread. Real Swing adds event
  dispatch and the native blit, which this does not capture. The render-loop share is therefore
  a floor, not the full UI cost — though the original dump measured the same work at parity
  with the simulation thread, so the direction of the result is not in doubt.
- One 60 second run on one machine, not best-of-N. The large ratios here (18×, 99.2% vs 0.7%)
  are far outside run-to-run noise; the small ones, such as N3's 16%, are three samples and
  should not be quoted precisely.

---

## P1-2 — Bands capped by the work that exists ◐ Partly done

**Read this first.** P1-2 as originally written — "remove the per-generation barrier" — was
**not** done, and is not what this change is. The barrier is still there and the simulation
thread still parks in `invokeAll`. Investigating it surfaced a different, concrete defect,
which is what was fixed. The ceiling P1-2 describes is unchanged for dense boards.

### What the investigation actually found

The band count came from the *board*: `bands = min(partitions, height)`. But the work in any
one generation is bounded by the active region, which can be far smaller. Splitting work that
is not there costs a task dispatch and a join per band and returns nothing.

Measured with a single blinker (3 cells) against two glider guns on the same grid:

| Grid | Partitions | Blinker (µs/gen) | Two guns (µs/gen) |
| --- | --- | --- | --- |
| 201 | 1 | 8.5 | 67.9 |
| 201 | 4 | 26.3 | 63.9 |
| 201 | 16 | **52.1** | 71.3 |
| 801 | 1 | 54.5 | 860.0 |
| 801 | 16 | 68.8 | 243.9 |

On a sparse board parallelism was **actively harmful** — 8.5 → 52.1 µs at 201, six times
slower, because sixteen bands each did almost nothing while paying full coordination cost.
This is not a corner case: it is the board immediately after Clear, any sparse pattern, and
any large grid holding a small population.

### Change

`LifeRules.bandsFor` caps the requested band count by how many bands the active rows can
usefully fill, using the same rows-per-band figure `PartitionPlanner` uses. The planner
measured that constant against full boards, where the active region spans nearly everything;
this applies the identical rule to the rows that actually carry work.

Band count is purely a work-distribution choice — the resulting board is identical whatever
it returns, which `PartitionEquivalenceTest` already pins down.

File: [`LifeRules.java`](../src/main/java/com/conway/engine/LifeRules.java).

### Measured effect

| Grid | Partitions | Board | Before | After | Change |
| --- | --- | --- | --- | --- | --- |
| 201 | 4 | blinker | 26.3 µs | 6.6 µs | **4.0× faster** |
| 201 | 16 | blinker | 52.1 µs | 9.4 µs | **5.5× faster** |
| 801 | 16 | blinker | 68.8 µs | 53.3 µs | **1.3× faster** |
| 201 | 4 | two guns | 63.9 µs | 65.7 µs | unchanged |
| 801 | 16 | two guns | 243.9 µs | 278.6 µs | unchanged |

Dense boards are unchanged **by construction, not by measurement** — which matters, because
run-to-run variance on the dense cases is around 5% and swamps any real difference. Probing
the band count directly:

```
grid 201, two guns: activeRows=201 -> bands(req 4)=4  bands(req 8)=4  bands(req 16)=4
grid 801, two guns: activeRows=801 -> bands(req 4)=4  bands(req 8)=8  bands(req 16)=16
```

At 801 the requested count passes through untouched, so the code path is identical. At 201
everything above 4 is capped to 4 — which is the count `PartitionPlanner` recommends there
anyway, so the engine now enforces at runtime what the planner advises. **Over-requesting
partitions can no longer hurt.**

One consequence: in `GenerationBenchmark` at 201, the rows for 4, 8, 16 and 20 partitions now
all execute four bands. Any spread between them is measurement noise, not scaling.

### Tests

Twelve tests added in `LifeRulesBandingTest` (118 total, all passing), covering the
rows-to-bands mapping, the sparse case that regressed, dense boards using every requested
band, degenerate inputs (empty region, zero and negative partitions), and bounds across 200
board heights.

Correctness needs no new coverage: `PartitionEquivalenceTest` already asserts the resulting
board is independent of the band count, which is exactly the invariant this change relies on.

### This broke two P1-3 tests, correctly

`workerPoolGrowsWithPartitionCount` and `workerPoolIsBoundedByPartitionCount` used a 32×32
board with a blinker. Under the new rule that is one band, computed inline, so the worker pool
is never touched and the pool could not grow. The tests were silently depending on the engine
always splitting.

They now use a 420-row board with stable blocks at both ends, so the active region spans the
full height permanently and eight bands are genuinely used. Worth noting as a behaviour
change in its own right: **small boards no longer use the worker pool at all.**

### Honest limits

- **The barrier is untouched.** The simulation thread still parks in `invokeAll` and is still
  idle most of its life on a dense board. This change stops the engine paying coordination
  cost when there is nothing to coordinate; it does not raise the ceiling when there is.
- **A second O(board) cost was found and not fixed.** `computeBand`'s tail copies `everAlive`
  and ORs it with the new board across every row in the band and every column,
  ignoring the active region entirely. That is why a blinker on an 801 board still costs
  ~50 µs per generation when its actual work is a handful of cells. See follow-ups.
- **The rows-per-band constant is shared but not configurable here.** `LifeRules` uses
  `PartitionPlanner.DEFAULT_MIN_ROWS_PER_PARTITION` directly, so
  `-Dconway.minRowsPerPartition` retunes the planner's recommendation but not this cap. Worth
  unifying if that override is ever used in anger.

### Follow-ups

| Item | Rationale |
| --- | --- |
| Prune the `everAlive` pass to the active region | The measured floor for sparse boards. `dst` is false outside the active region, so `dstEver == srcEver` there: a bulk copy plus an OR restricted to the active region would be equivalent and much cheaper. |
| Reconsider the barrier itself | Only worth it if large-board throughput becomes a goal. Pipelining generation N+1 against rendering of N would change the single-writer model, which is what makes the current design safe. |

---

## P1-3 — Worker pool sized to the partition count ✅ Done

**Problem.** The thread dump showed **20 live `conway-worker` threads serving 4 concurrent
bands**. The pool was created with `corePoolSize == maximumPoolSize == maxPartitions` (20).

Two mechanisms combined:

1. `ThreadPoolExecutor.execute` starts a *new* thread for every submitted task while the pool
   is below its core size, even when idle threads are already available. Four bands per
   generation therefore kept minting threads until all 20 existed.
2. `allowCoreThreadTimeOut(true)` was already set, but never reclaimed anything: under
   continuous stepping every thread picked up work well inside the 60 second keepalive.

### Change

The core size now tracks the partition count. It starts at 1 — a single band runs inline
without touching the executor — and `setCorePoolSize(partitions)` is called when the count
changes, from the `SetPartitions` command handler on the simulation thread. That is the same
thread that submits bands, so the pool is always resized before the next generation uses the
new count.

The queue is unbounded, so `maximumPoolSize` is never consulted and the core size is what
actually bounds the pool. `maximumPoolSize` stays at `maxPartitions` purely so the core can be
raised later without violating `core <= maximum`.

File: [`SimulationEngine.java`](../src/main/java/com/conway/engine/SimulationEngine.java).

### Measured effect

Reproducing the profiled configuration (`maxPartitions=20`, `partitions=4`, 201×201, seeded,
run for 4 s) and counting live `conway-worker` threads:

| | Live worker threads |
| --- | --- |
| Before | **20** |
| After | **4** |

Roughly 16 thread stacks reclaimed. This is a footprint fix, not a throughput fix — the
threads were idle, so no CPU was being wasted.

### Tests

Three tests added (106 total, all passing).

- `workerPoolIsBoundedByPartitionCount` — with `maxPartitions=16` and 2 partitions, at most
  2 worker threads exist after 200 generations. This is the regression test for the finding.
- `workerPoolGrowsWithPartitionCount` — raising the count to 8 lets the pool reach 8.
- `corePoolSizeTracksPartitionCount` — core size follows 1 → 8 → 2 → clamped 16.

All three seed a blinker first: an empty board short-circuits on an empty `ActiveRegion` and
never submits bands, so the pool would stay empty and the tests would pass vacuously.

### Correction — surplus threads are not reclaimed promptly

An earlier version of this fix asserted that lowering the count retires surplus workers as
they next go idle. **That is wrong**, and the test asserting it failed.

`setCorePoolSize` does call `interruptIdleWorkers()`, but an interrupted worker parked in
`getTask()` catches the `InterruptedException`, clears its timed-out flag and polls again. A
worker only exits once a poll genuinely times out — so the surplus goes away within
`WORKER_KEEPALIVE_SECONDS` (60 s), not at once.

The test now asserts the core size, which is deterministic and is the part this engine
controls; reclamation timing belongs to the JDK. The behaviour is acceptable regardless: the
finding was unbounded accumulation, not slow reclamation.

### Honest limits

- **Footprint only.** Roughly 16 thread stacks, no measurable CPU or throughput change. It was
  filed as P1 because 20 threads for 4 bands is misleading in a dump, not because it was
  costing time.
- **Lowering the count leaves surplus threads for up to 60 s**, as above. Shortening
  `WORKER_KEEPALIVE_SECONDS` would tighten that, at the cost of recreating threads after any
  pause longer than the keepalive. Not changed, as no evidence suggests it matters.

---

## Reproducing the benchmark

`RenderBench` lives outside the repository (it drives `BoardView` directly and is not a unit
test). To re-measure, time `BoardView.paint` over a run of real generations with the board
size and cell size as arguments, and compare against
`git show <ref>:src/main/java/com/conway/ui/BoardView.java`.

Note when scripting this: the project shell is zsh, which does **not** word-split unquoted
parameter expansions. `RenderBench $cfg` passes `"801 1"` as a single argument and the
benchmark silently falls back to its defaults. Pass arguments explicitly, or use `${=cfg}`.

When building an older revision for comparison, use `-Dmaven.test.skip=true` rather than
`-DskipTests`. The latter still *compiles* test sources, so a revision predating a new
test-visible accessor fails to build — and the previous jar is left in place, silently
producing a "before" measurement that is really the "after" build.
