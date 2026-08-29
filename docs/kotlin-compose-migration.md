# Kotlin / Compose Multiplatform migration

The codebase was ported from Java 21 + Swing + Maven to Kotlin 2.4.10 + Compose
Multiplatform 1.12.0 + Gradle (Kotlin DSL), targeting JVM 25 bytecode.

## What changed

| | Before | After |
| --- | --- | --- |
| Language | Java 21 | Kotlin 2.4.10 (JVM 25 bytecode) |
| UI | Swing (`LifeFrame`, `BoardView`, `FlatButton`) | Compose Multiplatform (`App`, `BoardCanvas`, `Controls`) |
| Rendering | `BufferedImage` + `Graphics2D` blit | `ByteArray` of premultiplied RGBA written straight in Skia's layout, drawn via `drawImage` |
| Frame pacing | `javax.swing.Timer` | `withFrameNanos` render loop rate-limited by `FramePacer`, on the Compose UI thread |
| Build | Maven (`pom.xml`, shade plugin) | Gradle 9.7.1 Kotlin DSL, Compose desktop plugin |
| Layout | `src/main/java`, `src/test/java` | `src/main/kotlin`, `src/test/kotlin` |
| Tests | JUnit 5 | JUnit 5, ported to Kotlin (125 tests, headless) |

## What did not change

- The concurrency architecture: `SimulationEngine` remains a single-writer actor on a
  virtual thread with a command queue, and `Generation` snapshots are still published
  through one `@Volatile` field. The port was mechanical; the design doc in the README
  still applies verbatim.
- The parallel band rules engine (`LifeRules`, `PartitionPlanner`) and its active-region
 optimisation.
- Every `-Dconway.*` system property and the logback configuration. `conway.targetFps`
  now caps a `withFrameNanos` loop instead of setting a `javax.swing.Timer` period, but
  it means the same thing and defaults to the same 60.

## What the port had to be corrected on

Three things did not survive the first pass and were restored afterwards, because each
silently changed the cost model rather than breaking anything visibly:

- Change detection had moved from reference identity to structural equality, so every
  frame ran `Generation.equals` over two `BooleanArray`s to discover nothing had changed.
  The render-loop state now uses `referentialEqualityPolicy`, which is exact here: the
  engine publishes a fresh snapshot per change and never mutates a published one.
- `BoardRenderer.render` had lost `BoardView.renderInto`'s identity early-out, so any
  draw not caused by a new generation still ran a full board diff that wrote nothing.
- The render-loop state was read at the top of `App`, recomposing the whole window --
  sidebar included -- once per frame. It is now read in the draw phase and in the status
  bar, so a new generation invalidates neither.
- The benchmark and its results; it now runs via `./gradlew bench`.

## Porting notes

- Java records became `data class`es; `Pattern`/`Generation` keep private constructors
  and factory functions so their validation contracts are unchanged.
- The Swing `BoardView` was split: the pure pixel-diff logic moved to a headless
  `BoardRenderer` (unit-tested without a display), and the pointer handling became
  Compose `pointerInput` + `detectTapGestures` mapped through the `letterbox`/`cellAt`
  pair in `BoardCanvas`. Those two replace Swing's `CellGeometry`, which was dropped: it
  mapped `Int` pixels within an `Int` viewport, and Compose lays the board out in `Float`
  device-independent pixels.
- `BoardViewTest`'s synthetic `MouseEvent` dispatch was replaced by direct
  `letterbox`/`cellAt` hit-testing assertions; image-equality tests now compare
  `ByteArray` pixel buffers instead of `BufferedImage.getRGB`.
- `FlatButton`, `Theme` (Swing constants) and `LifeGuiLauncher` (EDT bootstrap) have no
  counterpart: Compose `Button` styling, a Compose `MaterialTheme`-independent palette
  object, and `application { Window { ... } }` replace them.

## Commands

```bash
./gradlew build               # compile + headless tests
./gradlew run                 # launch the desktop app
./gradlew bench               # partition-count throughput benchmark
./gradlew createDistributable # native app bundle
```
