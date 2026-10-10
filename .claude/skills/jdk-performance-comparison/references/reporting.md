# Comparison tables, acceptance criteria, and the written assessment

## Table format

One table per round, every version side by side, **generated from the parsed data**.
Hand-transcription is how wrong numbers get into results — one project's log acquired a
digit transposition in a GC count and a pause maximum that no event in the recording
supported, both by retyping.

Header every table with its round and environment, and **state the JVM arguments that
round actually ran with**, read back from `jdk.JVMInformation.jvmArguments` rather than
from the runner script. A heap number is uninterpretable without knowing whether a heap
flag was set, and it is the first thing a reviewer asks about:

> **C3 — default heap + JFR (GUI, 2M generations, n=5)**
> `-XX:+UseG1GC -XX:FlightRecorderOptions=stackdepth=128 -XX:StartFlightRecording=…`
> No `-Xmx`, `-Xms` or `-Xmn`: heap left to ergonomics, which is what this round measures.

When heap is left to ergonomics, include the ergonomic defaults for each JDK as a short
table (initial/max heap, region size, young-gen percentages, pause target). If they are
identical and the JVMs still diverge, say so explicitly — that is a change in adaptive
behaviour, a materially stronger claim than a change in shipped defaults, and it is only
available to you if you checked.

| Metric | 21 | 25 | 27 | Δ 21→25 | Δ 25→27 | Comparable? |
|---|---|---|---|---|---|---|
| Throughput (median, IQR) | | | | | | |
| CPU per unit | | | | | | |
| Committed heap p50 | | | | | | |
| Young-gen capacity p50 | | | | | | |
| Live set after GC | | | | | | |
| Bytes per unit (eden / threads) | | | | | | |
| GC collections | | | | | | |
| Total pause / p50 / p99 | | | | | | |

**The `Comparable?` column is load-bearing, not decoration.** It carries, per metric:

- the **source diff** between the two versions' refs (one comment line versus a rewrite),
- any **vendor boundary** crossed,
- any **JFR schema boundary** that makes this particular metric incomparable.

A cell that cannot honestly hold a delta says why in a short phrase instead of showing a
number. A delta printed next to a caveat still gets quoted without the caveat.

Use consistent units and say which — MiB and MB differ by 5%, and one project's log spent a
round discovering its figures were MiB labelled MB.

## Acceptance criteria

When a delta may be called a finding:

1. **n ≥ 5 per arm**, every rep passing the identity and readiness gates.
2. **Distributions must not overlap.** A delta inside the calibrated noise floor is noise
   — say so plainly rather than quoting it with a hedge. "25 was 3% faster but this is
   within variance" invites the reader to keep the 3% and drop the clause.
3. **Heap, young-gen and GC-count rows: report every per-run value**, not a central
   estimate. These are bimodal per JVM start; a mean across two modes describes neither.
4. **Significant figures follow n.** At n=5, two are honest. Four significant figures off
   n=2 is a claim the data cannot support.
5. **Cross-vendor, cross-schema and cross-source deltas** carry a stated, unquantified
   component until a control arm exists.
6. **Direction must be consistent across reps.** A mean that flips sign between reps is
   not a result.
7. **Never compare across environments.** One environment per study. A number from another
   environment is not corroboration, however convenient.
8. **State what is bundled.** Enumerate *every* way the arms differ — source diff between refs, launch
   flags, vendor, collector settings, driver version — before attributing a delta to any
   one of them. One project attributed a per-unit allocation difference to a single cause
   when at least four were in play.

## The written assessment

After the tables, in prose:

- **What is established** — with the evidence and the n.
- **What is suggested but not established** — and exactly which run would settle it.
- **What is not attributable, and why** — which columns bundle a code change with a JDK
  change, which cross a vendor boundary, which cross a schema boundary.
- **Whether any prior claim is now contradicted.** Mark it withdrawn in place rather than
  deleting it. In a long-running comparison the corrections are the most useful part of the
  record — they are what stops the same wrong conclusion being rediscovered.
- **The single highest-value next experiment**, and its cost in machine time.

**Do not round a finding up.** "No difference outside noise" is a result, and at n=5
against a measured noise floor it is a strong one. The pressure to produce a headline
number is exactly what produces withdrawn claims later.

**Produce the table.** The deliverable is measured results, not a plan for obtaining them.
When the scope is clear and you have a recommended default, run it and report — confirm
only what is genuinely blocking (destructive actions, or a fork where the wrong choice
wastes an hour). A user who asked for a comparison and received a third round of questions
has been given nothing, and "where is the table?" is the correct response to that.

Equally, do not bury a real finding in caveats. If a delta is large, replicated, and
survives the pinned-heap control, say so directly in the first sentence.

## Worked example of an honest verdict

> **Throughput: no difference outside noise.** Across 5 interleaved reps per arm the
> medians were 19,950 and 19,830 units/s — 0.6% apart against a measured noise floor of
> ~6%, with fully overlapping distributions. Neither JDK is faster at this workload.
>
> **Heap sizing: a large, replicated difference.** Committed heap p50 was 620 MiB against
> 300 MiB, with an identical ~13 MiB live set, so essentially all of the difference is
> young-generation headroom the newer JDK declines to commit. It pays for that with 1.9×
> the collections and 1.6× the total pause — still only 0.45% of wall clock. This
> replicated in all three rounds and survives the pinned-heap control as a policy
> difference rather than a runtime one.
>
> **Not attributable:** the 27 column also carries ~370 lines of engine changes and a
> different vendor build, so no delta in it belongs to the JDK version alone.
