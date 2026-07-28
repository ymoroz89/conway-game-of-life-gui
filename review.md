## Comprehensive Code Review: Conway's Game of Life

### BUGS FOUND

#### 🐛 BUG 1 (CRITICAL): Wrong coordinate mapping in mouse listener
**File:** `GuiLife.java`, line 178
```java
int col = getGamePanel().yCoordToRowNumber(e.getX());
```
Uses `yCoordToRowNumber` for the X coordinate. Should use `xCoordToColumnNumber(e.getX())`. Clicking on cells will map to wrong grid positions.

#### 🐛 BUG 2 (CRITICAL): `boardPartSize` used before assignment in constructor
**File:** `CellStateGenerator.java`, lines 33-40
```java
if (startFromBoardPart < 0) {
    this.startFromBoardPart = gridSize + startFromBoardPart;
    this.endWithBoardPart = gridSize - this.boardPartSize - 1;  // boardPartSize is still 0 here!
} ...
this.boardPartSize = boardPartSize;  // assigned AFTER being used
```
`this.boardPartSize` is read on line 35 but assigned on line 40. For the last thread partition, `endWithBoardPart` is calculated as `gridSize - 0 - 1 = gridSize - 1` instead of the intended value. Works by accident only because the default int value (0) happens to produce a valid range.

#### 🐛 BUG 3 (CRITICAL): Swapped insets in `drawSquare`
**File:** `MatrixBoard.java`, lines 139-142
```java
double rowHeight = (double) (getHeight() - insets.left - insets.right) / rows;   // Wrong! Should be insets.top + insets.bottom
double colWidth = (double) (getWidth() - insets.top - insets.bottom) / columns;  // Wrong! Should be insets.left + insets.right
```
Vertical/horizontal insets are swapped, causing incorrect cell rendering when borders are present.

#### 🐛 BUG 4 (HIGH): Division by zero in TPS calculation
**File:** `LifeCycleController.java`, line 84
```java
long tps = iterationNumber / (workTime);
```
If the loop completes in under 1 second, `workTime = 0`, causing `ArithmeticException`. Also, `iterationNumber` is 0 at this point (decremented to 0 in the while loop), so TPS will always be 0 regardless.

#### 🐛 BUG 5 (HIGH): Stop button doesn't actually stop the thread
**File:** `GuiLifeMediator.java` + `LifeCycleController.java`
When stop is pressed, `runConroller(false, false)` sets `isRunAllTime = false` and calls `doNotify()`. The thread wakes up, runs one iteration, sees `isRunAllTime == false`, and calls `doWait()` again — it never actually terminates. The thread keeps running indefinitely in a wait-loop cycle.

#### 🐛 BUG 6 (MEDIUM): `UnsupportedOperationException` created but never thrown
**File:** `GuiLife.java`, lines 200, 204, 208
```java
public void mouseExited(MouseEvent e) {
    new UnsupportedOperationException();  // Missing 'throw' keyword
}
```
Three mouse event methods create exception objects but never throw them. These methods silently do nothing instead of indicating they're unsupported.

#### 🐛 BUG 7 (MEDIUM): Thread pool never shut down
**File:** `LifeCycleController.java`
`ExecutorService threadPool` is created in the constructor but never shut down, causing resource leak.

#### 🐛 BUG 8 (MEDIUM): `resultList` not re-initialized per iteration
**File:** `LifeCycleController.java`
`resultList` is an instance field cleared at line 128. If an exception occurs in the processing loop (lines 108-122), `resultList` may retain stale data that carries into the next iteration.

#### 🐛 BUG 9 (LOW): Duplicate assignment in pattern
**File:** `CellsStateContainer.java`, lines 79-80
`matrix[6][11]` is assigned twice (once on line 79, again on line 80). Redundant but harmless.

---

### SOLID PRINCIPLES VIOLATIONS

#### ❌ Single Responsibility Principle (SRP)
- **`LifeCycleController`**: Extends Thread + manages thread pool + runs game loop + handles synchronization. At least 4 responsibilities.
- **`CellsStateContainer`**: Matrix storage + pattern initialization (`setGliderGunPattern`). Pattern data should be externalized.
- **`GuiLife`**: UI component creation + event handling + application flow control. Too many concerns.

#### ❌ Open/Closed Principle (OCP)
- **`CellsStateContainer`**: Adding new patterns requires modifying the class. Should accept pattern providers via strategy pattern.
- **`LifeCycleController`**: Game loop logic is hardcoded. Cannot be extended with different lifecycle behaviors without modification.

#### ❌ Liskov Substitution Principle (LSP)
- **`CellsStateMatrixContainerInf.getMatrixCopy()`** returns `Object` instead of `Boolean[][]`. Forces unsafe cast at `LifeCycleController.java:94`: `(Boolean[][]) mediator.getMatrixCopy()`. The interface contract is too weak.

#### ❌ Interface Segregation Principle (ISP)
- **`CellsStateMatrixContainerInf`** includes `setGliderGunPattern()` — a specific pattern method that doesn't belong in a general container interface.
- **`GuiLifeMediatorInf`** includes `runConroller()` — a controller operation, not a mediator responsibility.

#### ❌ Dependency Inversion Principle (DIP)
- **`GuiLife`** (line 73-74) instantiates concrete `CellsStateContainer` directly instead of depending on abstraction.
- **`GuiLifeMediator`** depends on concrete `LifeCycleController` rather than an interface.

---

### GRASP PRINCIPLES

| Principle | Assessment |
|-----------|-----------|
| **Controller** | `GuiLifeMediator` acts as controller — ✅ Good |
| **Creator** | `GuiLife` creates UI components — ✅ Reasonable |
| **Low Coupling** | Mediator pattern reduces UI↔logic coupling — ✅ Good |
| **High Cohesion** | `LifeCycleController` has LOW cohesion (threads + game loop + sync) — ❌ |
| **Polymorphism** | `Callable` interface for generators, interfaces for mediator/container — ✅ |
| **Pure Fabrication** | `GuiLifeMediator`, `GuiLifeUtils` — ✅ Good |
| **Indirection** | Mediator pattern — ✅ Good |
| **Protected Variations** | Interface-based design — ✅ Good, but weak typing in `getMatrixCopy()` — ❌ |

---

### OOP PRINCIPLES

| Principle | Assessment |
|-----------|-----------|
| **Encapsulation** | `matrix` is private in `CellsStateContainer` ✅, but `doWait()/doNotify()` are public in `LifeCycleController` ❌ |
| **Inheritance** | `LifeCycleController extends Thread` — ❌ Should implement `Runnable` instead |
| **Polymorphism** | Used via interfaces ✅ |

---

### KISS & DRY

**KISS Violations:**
- Multi-threading adds significant complexity for a 201×201 grid. Thread overhead likely exceeds parallelization benefits.
- `setGliderGunPattern()` is 25+ lines of hardcoded coordinate assignments — fragile and unreadable.
- `CellCoordPair<L, R>` generic pair is over-engineered; a simple `int[]` or `java.awt.Point` would suffice.

**DRY Violations:**
- Coordinate/inset calculation logic is duplicated across `xCoordToColumnNumber`, `yCoordToRowNumber`, and `drawSquare`.
- Pattern coordinates in `setGliderGunPattern` are hardcoded with no reuse.

---

### SUMMARY

| Category | Count |
|----------|-------|
| **Critical bugs** | 3 |
| **High-severity bugs** | 2 |
| **Medium-severity bugs** | 3 |
| **Low-severity bugs** | 1 |
| **SOLID violations** | 5+ |
| **GRASP issues** | 2 |
| **KISS/DRY issues** | 4+ |

**Top 3 recommendations:**
1. Fix the mouse coordinate mapping bug (BUG 1) — this breaks core interaction
2. Fix the `boardPartSize` initialization order in `CellStateGenerator` (BUG 2) — incorrect grid partitioning
3. Refactor `LifeCycleController` to implement `Runnable` instead of extending `Thread`, and properly manage thread lifecycle with a termination flag