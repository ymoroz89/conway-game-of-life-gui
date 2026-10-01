# JFR metrics, identity gates, and how to read them

Contents:
- [Identity gates](#identity-gates)
- [Metric set](#metric-set)
- [Reduction rules](#reduction-rules)
- [What the numbers mean](#what-the-numbers-mean)
- [Non-G1 collectors](#non-g1-collectors)

## Identity gates

A run failing any of these is **discarded, not analysed**. They exist because every
upstream label can be wrong — the jenv alias, the branch name, the filename, the IDE's run
configuration. Only the recording is evidence.

| Gate | Check |
|---|---|
| JVM version | `jdk.JVMInformation.jvmVersion` matches the intended JDK |
| Vendor | the same field's vendor string is the one you think you installed |
| Native library | `jdk.NativeLibrary` shows libjvm resolving under that arm's `jenv prefix`, not merely under *some* JDK |
| Arguments | `jdk.JVMInformation.javaArguments` and `jvmArguments` match the intended launch |
| Workload size | within 0.1% of target across every arm of the round |
| Exclusivity | `jdk.CPULoad` shows no competing load (`machineTotal` minus JVM busy) |
| Readiness | the driver's own markers confirm the app stayed in the intended state |

Quick identity check without the parser:

```bash
jfr print --events JVMInformation rec.jfr | grep -E 'jvmVersion|javaArguments'
jfr print --events NativeLibrary rec.jfr | grep -o '[^"]*libjvm[^"]*'
```

`parse_jfr.py` puts the same fields in `identity` on every derived file, so the gate can be
applied mechanically across a whole round.

## Metric set

What `parse_jfr.py` extracts, and the event each comes from.

| Metric | Event | Reduction |
|---|---|---|
| Throughput | **the app's own log line** | units/s — ground truth, never JFR inference |
| `cpu_busy_fraction` | `jdk.CPULoad` | mean of `jvmUser` + `jvmSystem`; multiply by cores for "busy of N" |
| `cpu_external_fraction` | `jdk.CPULoad` | `machineTotal` − JVM busy; the exclusivity gate |
| `committed_heap_p50_bytes` | `jdk.GCHeapSummary` | median `heapSpace.committedSize` |
| `live_set_after_gc_p50_bytes` | `jdk.GCHeapSummary` where `when == "After GC"` | median `heapUsed` |
| `young_capacity_p50_bytes` | `jdk.G1HeapSummary` | median `edenTotalSize` |
| `eden_consumed_bytes` | `jdk.G1HeapSummary` where `when == "Before GC"` | Σ `edenUsedSize` |
| `bytes_per_unit_eden` | derived | eden consumed ÷ units |
| `bytes_per_unit_threads` | `jdk.ThreadAllocationStatistics` | Σ per-thread high-water ÷ units |
| `gc_count`, `gc_count_by_name` | `jdk.GarbageCollection` | count, split by collection name |
| `pause_total_s` | `jdk.GarbageCollection` | Σ `sumOfPauses` |
| `pause_p50_ms`, `p99`, `max` | `jdk.GarbageCollection` | percentiles of `longestPause` |
| `virtual_thread_pinned_count` | `jdk.VirtualThreadPinned` | count — **see version-compat** |
| `top_allocation_classes` | `jdk.ObjectAllocationSample` | share by sampled weight |

Durations arrive as ISO-8601 strings (`PT0.001598083S`), and numeric fields are sometimes
JSON numbers and sometimes strings depending on the event — the parser coerces both.

**Report both bytes-per-unit derivations.** The eden sum and the thread high-water sum
measure slightly different things and have disagreed on the same recording (82,878 vs
83,289 B/unit in one case). If they differ by more than ~1%, report both and say which is
which rather than quietly picking one.

**Execution samples are indicative only.** Virtual-thread sampling rates have changed
between releases, so a shift in a method's *share* of samples may be a sampling artefact
rather than a change in where time goes. Use them to confirm the hot method is unchanged,
never to attribute a percentage of runtime.

## Reduction rules

1. **Reduce each event family on its own values. Never filter by wall clock.** Families in
   the same recording can sit on different clock bases — a ~7.7 h offset between two heap
   summary families has been observed — so a naive time window returns an empty set and
   looks like missing data.
2. **Never silently degrade.** A missing, empty, or semantically-shifted field produces
   `null` plus a `reason`. Never substitute a related event, emit a zero, or drop the row:
   a missing row that looks like a present row is how a wrong number reaches a table.
3. **Percentile method matters at small n.** Interpolated and nearest-rank percentiles
   differ by a few percent on a few hundred pauses. Pick one, use it everywhere, and do not
   compare a p99 computed one way against one computed the other.
4. **Validate parser changes against a known-good result** — re-derive an existing round
   and diff. This has caught real errors, including a pause maximum that no event family in
   the recording supported.

## What the numbers mean

- **Committed heap is mostly young-gen headroom.** A large committed-heap difference
  between JDKs, with an identical live set, is a GC *sizing policy* difference, not a
  memory leak or a footprint regression. Check the live set before reading anything into
  committed heap.
- **More collections is the price of a smaller heap.** A JDK running in half the heap
  doing roughly twice the collections and twice the promotion is behaving as designed, not
  regressing.
- **Total pause tracks collection count.** Report it as a percentage of wall clock as well
  as absolute; sub-1% totals rarely matter to anyone.
- **Pause tails are noisy.** p99 and max routinely reverse direction between rounds at
  these sample counts. Median pause is the durable figure; treat tail claims as
  provisional unless they replicate.
- **Bytes per unit is usually JDK-independent.** It is a property of the application's
  allocation behaviour. A change here between JDK arms is more likely a workload or code
  difference than a runtime one — check the branch diff before believing it.
- **A non-zero pinning count is a finding**, not noise — but see version-compat before
  comparing counts across releases.

## Non-G1 collectors

The young-generation rows come from `jdk.G1HeapSummary`, which does not exist under other
collectors. The parser reports the gap rather than substituting, because the equivalents
are not shaped the same:

- **Parallel/Serial**: use `jdk.PSHeapSummary` (eden and survivor spaces named differently).
- **ZGC / Shenandoah**: generational layout differs enough that "eden capacity" has no
  direct analogue; report the collector's own region statistics and say so in the table.

If the study compares across *collectors* rather than versions, that is a different
experiment — the heap rows stop being comparable at all, and the honest output is two
separate tables.
