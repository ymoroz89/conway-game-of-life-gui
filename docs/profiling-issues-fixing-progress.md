# Profiling issues — fixing progress

Tracks remediation of the findings in [profiling-findings.md](profiling-findings.md).
Updated as each fix lands.

## Status

| ID | Finding | Priority | Status |
| --- | --- | --- | --- |
| P1-1 | Renderer redraws the whole board every frame | 🔴 P1 | ✅ **Done** |
| P1-2 | Per-generation barrier caps parallelism | 🔴 P1 | ⬜ Not started |
| P1-3 | Worker pool five times oversized | 🔴 P1 | ⬜ Not started |
| P2-4 | Re-profile with sampling rather than instrumentation | 🟡 P2 | ⬜ Not started |
| P2-5 | `Generation.population()` scans the board every frame | 🟡 P2 | ⬜ Not started |
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

## Reproducing the benchmark

`RenderBench` lives outside the repository (it drives `BoardView` directly and is not a unit
test). To re-measure, time `BoardView.paint` over a run of real generations with the board
size and cell size as arguments, and compare against
`git show <ref>:src/main/java/com/conway/ui/BoardView.java`.

Note when scripting this: the project shell is zsh, which does **not** word-split unquoted
parameter expansions. `RenderBench $cfg` passes `"801 1"` as a single argument and the
benchmark silently falls back to its defaults. Pass arguments explicitly, or use `${=cfg}`.
