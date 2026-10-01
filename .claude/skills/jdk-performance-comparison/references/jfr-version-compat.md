# JFR schema and semantics across JDK versions

Read this **before comparing any metric across a version boundary.** JFR's event schema
and — more dangerously — event *meanings* change between releases. A metric can be present
on both sides of a boundary, parse cleanly on both, and still not be the same measurement.

## The failure this prevents

The quiet failure is not a crash. It is an event that still exists, still parses, still
produces a plausible number, and now counts something different. The delta looks real, the
parser looks healthy, and the conclusion is wrong. Nothing downstream can catch it —
which is why the check belongs here, before the comparison, rather than in review.

## The rule: dispatch *and* raise a task

When the parser meets a version difference, do **two** things:

1. **Dispatch correctly** — handle the difference in the compatibility module.
2. **Open a tracked task** for it.

A compatibility entry without a task goes invisible the moment its author moves on, and
the next person reads the metric as comparable. The task is what survives the handover.

## The register

Keep one register file in the project alongside the parser. Each entry:

| Field | Content |
|---|---|
| Event / field | e.g. `jdk.VirtualThreadPinned` |
| Affected versions | which JDKs in the set behave differently |
| Difference | added, removed, renamed, or **same name, different meaning** |
| Handling | what the parser does |
| Comparable? | whether the metric may be compared **across** this boundary — and if not, say so in the table's own caption |
| Task | link to the tracked task |

"Same name, different meaning" is the category that causes wrong conclusions. Flag it
harder than a missing field, which at least fails loudly.

## Known differences

**Verify each against the actual versions in your set** rather than trusting this list.
It is a starting point for the register, not a substitute for checking — and it will go
stale as new releases land.

- **Virtual-thread sampling rates changed between releases** (62/s versus 80/s measured
  across one 21→25 boundary). Execution-sample *shares* are therefore not comparable across
  it: a method's share can move several points with no change in where time actually goes.
  Hot-method identity still compares fine; percentages do not.

- **Virtual-thread pinning semantics changed** with the work that stopped `synchronized`
  from pinning carriers (JEP 491). `jdk.VirtualThreadPinned` counts before and after that
  release are not the same measurement — the later JVM legitimately pins far less for
  reasons unrelated to the application. Compare counts only within one side of the
  boundary; across it, report per version and note why.

- **JFR CPU-time profiling** (`jdk.CPUTimeSample`, JEP 509) is not available on every
  version or platform — notably absent on some OSes at introduction. Capability-check
  before building any metric on it, rather than discovering a null halfway through a study.

- **Clock bases differ between event families** in the same recording (a ~7.7 h offset
  between two heap-summary families has been observed). This is not strictly a version
  issue, but it is checked the same way and breaks analysis identically. Never window by
  wall clock; reduce each family on its own values.

- **Default collector and default JFR settings change between releases.** Always state the
  collector explicitly and always pass `settings=profile`; otherwise a release changing its
  own default silently becomes the variable under test.

## When a metric is not comparable

Report it **per version with the boundary marked** — never as a delta, and never averaged
across the boundary. A table cell that cannot honestly hold a delta should say why in one
short phrase rather than showing a number that invites the comparison anyway.

This is the same rule that applies to vendor boundaries (Homebrew OpenJDK versus Azul Zulu)
and to branch boundaries where the source changed. All three are cases of "the arms differ
by more than the thing you are attributing the delta to", and all three belong in the
`Comparable?` column of the results table.
