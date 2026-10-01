#!/usr/bin/env python3
"""Extract comparison metrics from a JFR recording.

Reduces each event family independently on its own values. It never filters by
wall-clock time, because event families in the same recording can sit on different
clock bases -- a naive time window silently returns nothing.

Missing or unreadable families produce a null metric carrying a `reason`, never a
zero or a substituted event. A missing row that looks like a present row is how a
wrong number reaches a comparison table.

Usage:
    parse_jfr.py RECORDING.jfr --units 2000000 [--jfr-bin /path/to/jfr] [-o out.json]

`--units` is the workload size the run completed (generations, requests, frames --
whatever the application counts), used for the per-unit metrics. Omit it and those
rows come back null with a reason rather than being guessed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import statistics
import subprocess
import sys
from pathlib import Path

CORE_EVENTS = [
    "JVMInformation",
    "GCHeapSummary",
    "G1HeapSummary",
    "GarbageCollection",
    "CPULoad",
    "ThreadAllocationStatistics",
    "VirtualThreadPinned",
]

_ISO_DUR = re.compile(
    r"^P(?:(?P<d>[\d.]+)D)?T(?:(?P<h>[\d.]+)H)?(?:(?P<m>[\d.]+)M)?(?:(?P<s>[\d.]+)S)?$"
)


def duration_seconds(value):
    """JFR emits durations as ISO-8601 strings such as 'PT0.001598083S'."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    match = _ISO_DUR.match(str(value))
    if not match:
        return None
    parts = match.groupdict()
    total = 0.0
    for key, scale in (("d", 86400), ("h", 3600), ("m", 60), ("s", 1)):
        if parts.get(key):
            total += float(parts[key]) * scale
    return total


def num(value):
    """Field types vary by event and JDK: some are JSON numbers, some strings."""
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = fraction * (len(ordered) - 1)
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    weight = position - low
    return ordered[low] * (1 - weight) + ordered[high] * weight


def read_events(recording: Path, events, jfr_bin: str):
    """Return {event_type: [values, ...]} for the requested families."""
    command = [jfr_bin, "print", "--json", "--events", ",".join(events), str(recording)]
    try:
        completed = subprocess.run(
            command, capture_output=True, text=True, check=True, timeout=600
        )
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(f"jfr print failed: {exc.stderr.strip()[:400]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("jfr print timed out") from exc

    payload = json.loads(completed.stdout)
    grouped: dict[str, list] = {}
    for event in payload.get("recording", {}).get("events", []):
        name = event.get("type", "").removeprefix("jdk.")
        grouped.setdefault(name, []).append(event.get("values", {}))
    return grouped


def top_classes(recording: Path, jfr_bin: str, limit: int = 5):
    """Allocation profile by sampled weight. Optional: absent on some settings."""
    try:
        grouped = read_events(recording, ["ObjectAllocationSample"], jfr_bin)
    except RuntimeError as exc:
        return None, str(exc)
    samples = grouped.get("ObjectAllocationSample", [])
    if not samples:
        return None, "no ObjectAllocationSample events (settings=profile required)"

    weights: dict[str, float] = {}
    for sample in samples:
        obj = sample.get("objectClass") or {}
        name = obj.get("name") if isinstance(obj, dict) else str(obj)
        weights[name or "unknown"] = weights.get(name or "unknown", 0.0) + (
            num(sample.get("weight")) or 0.0
        )
    total = sum(weights.values()) or 1.0
    ranked = sorted(weights.items(), key=lambda kv: kv[1], reverse=True)[:limit]
    return [
        {"class": name, "share_pct": round(100 * weight / total, 2)}
        for name, weight in ranked
    ], None


def metric(value, reason=None):
    return {"value": value, "reason": reason}


def extract(recording: Path, units, jfr_bin: str) -> dict:
    result: dict = {
        "recording": recording.name,
        "sha256": hashlib.sha256(recording.read_bytes()).hexdigest(),
        "size_bytes": recording.stat().st_size,
        "units": units,
        "metrics": {},
    }
    metrics = result["metrics"]

    try:
        grouped = read_events(recording, CORE_EVENTS, jfr_bin)
    except RuntimeError as exc:
        result["error"] = str(exc)
        return result

    # --- identity -------------------------------------------------------------
    info = grouped.get("JVMInformation", [])
    if info:
        first = info[0]
        result["identity"] = {
            "jvmName": first.get("jvmName"),
            "jvmVersion": first.get("jvmVersion"),
            "jvmArguments": first.get("jvmArguments"),
            "javaArguments": first.get("javaArguments"),
        }
    else:
        result["identity"] = None
        result["identity_reason"] = "no JVMInformation event -- arm is unverifiable"

    # --- CPU ------------------------------------------------------------------
    loads = grouped.get("CPULoad", [])
    if loads:
        busy = [
            (num(e.get("jvmUser")) or 0) + (num(e.get("jvmSystem")) or 0) for e in loads
        ]
        machine = [num(e.get("machineTotal")) or 0 for e in loads]
        mean_busy = statistics.fmean(busy)
        metrics["cpu_busy_fraction"] = metric(round(mean_busy, 5))
        metrics["cpu_machine_total_fraction"] = metric(
            round(statistics.fmean(machine), 5)
        )
        # Headroom between the JVM and the machine is how external load shows up.
        metrics["cpu_external_fraction"] = metric(
            round(max(0.0, statistics.fmean(machine) - mean_busy), 5)
        )
    else:
        for key in ("cpu_busy_fraction", "cpu_machine_total_fraction"):
            metrics[key] = metric(None, "no CPULoad events")

    # --- heap -----------------------------------------------------------------
    heap = grouped.get("GCHeapSummary", [])
    if heap:
        committed = [
            num((e.get("heapSpace") or {}).get("committedSize"))
            for e in heap
            if (e.get("heapSpace") or {}).get("committedSize") is not None
        ]
        after = [num(e.get("heapUsed")) for e in heap if e.get("when") == "After GC"]
        metrics["committed_heap_p50_bytes"] = metric(percentile(committed, 0.5))
        metrics["live_set_after_gc_p50_bytes"] = (
            metric(percentile(after, 0.5))
            if after
            else metric(None, "no 'After GC' GCHeapSummary events")
        )
    else:
        for key in ("committed_heap_p50_bytes", "live_set_after_gc_p50_bytes"):
            metrics[key] = metric(None, "no GCHeapSummary events")

    # --- young generation -----------------------------------------------------
    # G1-specific. A different collector needs its own summary event; report the
    # gap rather than substituting one, since the shapes are not equivalent.
    young = grouped.get("G1HeapSummary", [])
    if young:
        before = [e for e in young if e.get("when") == "Before GC"]
        capacity = [
            num(e.get("edenTotalSize"))
            for e in young
            if e.get("edenTotalSize") is not None
        ]
        eden_consumed = sum(num(e.get("edenUsedSize")) or 0 for e in before)
        metrics["young_capacity_p50_bytes"] = metric(percentile(capacity, 0.5))
        metrics["eden_consumed_bytes"] = metric(eden_consumed)
        metrics["bytes_per_unit_eden"] = (
            metric(round(eden_consumed / units, 1))
            if units
            else metric(None, "--units not supplied")
        )
    else:
        for key in (
            "young_capacity_p50_bytes",
            "eden_consumed_bytes",
            "bytes_per_unit_eden",
        ):
            metrics[key] = metric(
                None, "no G1HeapSummary events (non-G1 collector? see references)"
            )

    # --- allocation cross-check ----------------------------------------------
    # Independent of the eden derivation above. The two measure slightly different
    # things and have disagreed before, so both are reported rather than one chosen.
    alloc = grouped.get("ThreadAllocationStatistics", [])
    if alloc:
        high_water: dict[str, float] = {}
        for event in alloc:
            thread = (event.get("thread") or {}).get("javaName") or str(
                (event.get("thread") or {}).get("javaThreadId")
            )
            value = num(event.get("allocated")) or 0.0
            high_water[thread] = max(high_water.get(thread, 0.0), value)
        total = sum(high_water.values())
        metrics["allocated_total_bytes"] = metric(total)
        metrics["bytes_per_unit_threads"] = (
            metric(round(total / units, 1))
            if units
            else metric(None, "--units not supplied")
        )
    else:
        for key in ("allocated_total_bytes", "bytes_per_unit_threads"):
            metrics[key] = metric(None, "no ThreadAllocationStatistics events")

    # --- garbage collection ---------------------------------------------------
    collections = grouped.get("GarbageCollection", [])
    if collections:
        pauses = [
            duration_seconds(e.get("longestPause"))
            for e in collections
            if duration_seconds(e.get("longestPause")) is not None
        ]
        sums = [
            duration_seconds(e.get("sumOfPauses"))
            for e in collections
            if duration_seconds(e.get("sumOfPauses")) is not None
        ]
        by_name: dict[str, int] = {}
        for event in collections:
            by_name[event.get("name") or "unknown"] = (
                by_name.get(event.get("name") or "unknown", 0) + 1
            )
        metrics["gc_count"] = metric(len(collections))
        metrics["gc_count_by_name"] = metric(by_name)
        metrics["pause_total_s"] = metric(round(sum(sums), 6) if sums else None)
        metrics["pause_p50_ms"] = metric(
            round(percentile(pauses, 0.50) * 1000, 4) if pauses else None
        )
        metrics["pause_p99_ms"] = metric(
            round(percentile(pauses, 0.99) * 1000, 4) if pauses else None
        )
        metrics["pause_max_ms"] = metric(round(max(pauses) * 1000, 4) if pauses else None)
    else:
        for key in (
            "gc_count",
            "pause_total_s",
            "pause_p50_ms",
            "pause_p99_ms",
            "pause_max_ms",
        ):
            metrics[key] = metric(
                None, "no GarbageCollection events -- settings=profile required"
            )

    # --- virtual threads ------------------------------------------------------
    # Meaning changed across releases (JEP 491): a count is not comparable across
    # that boundary. Reported as a count with the caveat carried in references.
    metrics["virtual_thread_pinned_count"] = metric(
        len(grouped.get("VirtualThreadPinned", []))
    )

    classes, reason = top_classes(recording, jfr_bin)
    metrics["top_allocation_classes"] = metric(classes, reason)

    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=Path)
    parser.add_argument(
        "--units", type=int, default=None, help="workload units the run completed"
    )
    parser.add_argument(
        "--jfr-bin", default=shutil.which("jfr") or "jfr", help="path to the jfr tool"
    )
    parser.add_argument("-o", "--out", type=Path, help="write JSON here (default stdout)")
    args = parser.parse_args()

    if not args.recording.exists():
        print(f"no such recording: {args.recording}", file=sys.stderr)
        return 2

    result = extract(args.recording, args.units, args.jfr_bin)
    text = json.dumps(result, indent=2)
    if args.out:
        args.out.write_text(text)
        print(f"wrote {args.out}")
    else:
        print(text)

    # A parse failure must be loud: it means the recording still holds something
    # unread, and deleting it would foreclose fixing the parser against real data.
    return 1 if result.get("error") else 0


if __name__ == "__main__":
    sys.exit(main())
