#!/usr/bin/env bash
# Run the cross-JDK condition matrix. Project-agnostic: everything specific to an
# application lives in two config files, so this orchestration is written once.
#
#   jdks.conf   label  jenv-name  branch      (which JVMs, and which branch each uses)
#   bench.conf  how to build and launch THIS app, and how to read its output
#
# What this file owns, because it is the same everywhere and easy to get wrong:
#   - resolving jenv names and refusing non-OpenJDK builds
#   - neutralising .java-version, which silently overrides `jenv global`
#   - building each branch in its own worktree so a dirty tree cannot leak into an arm
#   - verifying `java -version` per arm, in the project directory, before measuring
#   - interleaving by repetition so drift hits every arm equally
#   - per-round JVM options, and appending one JSON row per run
#   - restoring jenv state on every exit path
#
# Usage: run-matrix.sh [--rounds C1,C3] [--reps 5] [--units 2000000]
#                      [--jdks jdks.conf] [--bench bench.conf] [--out profiling]

set -uo pipefail

ROUNDS="C1,C2,C3,C4"
REPS=5
UNITS=2000000
JDKS_CONF=tools/bench/jdks.conf
BENCH_CONF=tools/bench/bench.conf
OUT=profiling

while [ $# -gt 0 ]; do
    case "$1" in
        --rounds) ROUNDS="$2"; shift 2 ;;
        --reps)   REPS="$2"; shift 2 ;;
        --units)  UNITS="$2"; shift 2 ;;
        --jdks)   JDKS_CONF="$2"; shift 2 ;;
        --bench)  BENCH_CONF="$2"; shift 2 ;;
        --out)    OUT="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[ -f "$JDKS_CONF" ]  || { echo "missing $JDKS_CONF" >&2; exit 2; }
[ -f "$BENCH_CONF" ] || { echo "missing $BENCH_CONF" >&2; exit 2; }

# bench.conf is a shell fragment defining: ENV, BUILD_CMD, RUN_CMD, UNITS_RE,
# SECONDS_RE, RATE_RE, and optionally MARKER_START_RE / MARKER_END_RE.
# shellcheck disable=SC1090
. "$BENCH_CONF"
: "${ENV:?bench.conf must set ENV}"
: "${RUN_CMD:?bench.conf must set RUN_CMD}"
: "${BUILD_CMD:?bench.conf must set BUILD_CMD}"

mkdir -p "$OUT" || { echo "cannot create output directory '$OUT'" >&2; exit 2; }
[ -w "$OUT" ] || { echo "output directory '$OUT' is not writable" >&2; exit 2; }

# --- preflight -------------------------------------------------------------------
# .java-version takes precedence over `jenv global`, so leaving it in place would make
# every arm run the same JVM while carrying different labels. It has happened.
ORIG_GLOBAL="$(jenv global 2>/dev/null)"
STASHED=""
if [ -f .java-version ]; then
    mv .java-version .java-version.matrix-stash && STASHED=1
fi
restore() {
    [ -n "$ORIG_GLOBAL" ] && jenv global "$ORIG_GLOBAL" 2>/dev/null
    [ -n "$STASHED" ] && mv .java-version.matrix-stash .java-version 2>/dev/null
    echo "restored: jenv global=$ORIG_GLOBAL${STASHED:+, .java-version}"
}
trap restore EXIT
# Stop means stop: end the run in flight and exit, which fires the EXIT trap above. A
# handler that only restored state let the loop carry on and launch the next run.
trap 'pkill -TERM -P $$ 2>/dev/null; exit 130' INT TERM

# --- resolve arms ----------------------------------------------------------------
LABELS=(); NAMES=(); BRANCHES=(); WORKTREES=()
while read -r label name branch; do
    case "$label" in \#*|"") continue ;; esac
    prefix="$(jenv prefix "$name" 2>/dev/null)"
    [ -x "$prefix/bin/java" ] || { echo "cannot resolve jenv name '$name'" >&2; exit 1; }
    # GraalVM reports `java version`; OpenJDK builds report `openjdk version`. Comparing
    # a GraalVM arm against an OpenJDK arm compares JIT compilers, not JDK releases.
    if ! "$prefix/bin/java" -version 2>&1 | head -1 | grep -q '^openjdk'; then
        echo "label $label ($name) is not an OpenJDK build -- refusing" >&2; exit 1
    fi
    LABELS+=("$label"); NAMES+=("$name"); BRANCHES+=("$branch")
    WORKTREES+=("/tmp/jdkcmp-$label")
done < "$JDKS_CONF"

[ "${#LABELS[@]}" -ge 2 ] || { echo "need at least two arms" >&2; exit 1; }

echo "=== arms (env=$ENV, units=$UNITS, reps=$REPS, rounds=$ROUNDS) ==="
for i in "${!LABELS[@]}"; do
    printf '  %-4s %-20s branch=%-10s %s\n' "${LABELS[$i]}" "${NAMES[$i]}" "${BRANCHES[$i]}" \
        "$("$(jenv prefix "${NAMES[$i]}")/bin/java" -version 2>&1 | head -1)"
done

# Source divergence between arms is a confound; report it rather than assume it away.
echo "=== branch diff between adjacent arms ==="
for ((i=1; i<${#LABELS[@]}; i++)); do
    printf '  %s -> %s : %s\n' "${LABELS[$((i-1))]}" "${LABELS[$i]}" \
        "$(git diff --shortstat "${BRANCHES[$((i-1))]}" "${BRANCHES[$i]}" -- src/ 2>/dev/null | sed 's/^ *//' || echo '?')"
done

# --- build each branch with its own JDK ------------------------------------------
for i in "${!LABELS[@]}"; do
    wt="${WORKTREES[$i]}"
    if [ ! -d "$wt" ]; then
        git worktree prune
        # --detach: a branch that is checked out elsewhere (typically the current one)
        # cannot be checked out again, but its commit can.
        git worktree add -q --detach "$wt" "${BRANCHES[$i]}" || exit 1
    fi
    echo "building ${LABELS[$i]} from ${BRANCHES[$i]} ..."
    cmd="${BUILD_CMD//\{WORKTREE\}/$wt}"
    JAVA_HOME="$(jenv prefix "${NAMES[$i]}")" bash -c "$cmd" >/dev/null 2>&1 \
        || { echo "build failed for ${LABELS[$i]}" >&2; exit 1; }
done

# Round options. bench.conf may define ROUND_<name> for any round, including names of
# its own -- the built-ins below are only a default. Use {JFR} where the flight-recorder
# options should go. Examples:
#
#   ROUND_C2='-Xms1g -Xmx1g -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=40'
#   ROUND_tuned='-Xms2g -Xmx2g -XX:MaxGCPauseMillis=50 {JFR}'
#
# A project's own tuned configuration is often the interesting round: it answers "how do
# these JDKs compare under the settings we actually ship with", which is a different and
# frequently more useful question than "how do they compare at their own defaults".
round_opts() {
    local var="ROUND_$1"
    if [ -n "${!var+set}" ]; then
        printf '%s' "${!var}"
        return
    fi
    case "$1" in
        C1|C5) printf '' ;;
        C2)    printf -- '-Xms1g -Xmx1g -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=40' ;;
        C3)    printf -- '{JFR}' ;;
        C4)    printf -- '-Xms1g -Xmx1g -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=40 {JFR}' ;;
        *)     echo "no options defined for round '$1' -- set ROUND_$1 in bench.conf" >&2
               printf '__UNDEFINED__' ;;
    esac
}

# Every flag in a round is part of what you are comparing, so verify each arm accepts it
# and reports the same value before measuring. An experimental flag can be renamed,
# re-defaulted or dropped between releases; a round whose flags do not resolve identically
# on every arm is not a comparison.
verify_round_flags() {
    local round="$1" opts="$2" bad=0
    [ -z "${opts//[[:space:]]/}" ] && return 0
    for i in "${!LABELS[@]}"; do
        local jh out
        jh="$(jenv prefix "${NAMES[$i]}")"
        out="$("$jh/bin/java" -XX:+UseG1GC $opts -version 2>&1)"
        if echo "$out" | grep -qiE 'unrecognized (vm )?option|could not create the java virtual machine'; then
            echo "  $round: ${LABELS[$i]} REJECTS these options" >&2
            echo "$out" | grep -iE 'unrecognized|error' | head -2 >&2
            bad=1
        fi
    done
    return $bad
}

# --- the matrix ------------------------------------------------------------------
# Repetition is the OUTER loop. Running each JDK to completion in turn charges one arm
# for the machine warming up -- measured at +6.6% across five reps in one session.
IFS=',' read -ra ROUND_LIST <<< "$ROUNDS"

# Fail before measuring, not three arms in: an option one JDK rejects would otherwise
# show up as a dead arm after the first round has already cost real time.
echo "=== round options ==="
for round in "${ROUND_LIST[@]}"; do
    opts="$(round_opts "$round")"
    [ "$opts" = "__UNDEFINED__" ] && exit 2
    printf '  %-6s %s\n' "$round" "${opts:-(JVM defaults)}"
    verify_round_flags "$round" "${opts//\{JFR\}/}" || {
        echo "aborting: round '$round' options are not accepted by every arm" >&2; exit 2; }
done

for round in "${ROUND_LIST[@]}"; do
    for rep in $(seq 1 "$REPS"); do
        for i in "${!LABELS[@]}"; do
            label="${LABELS[$i]}"; name="${NAMES[$i]}"
            tag="$ENV-$round-$label-rep$rep"
            jfr="$OUT/$tag.jfr"; console="$OUT/$tag.console.log"

            jenv global "$name"
            java_home="$(jenv prefix "$name")"
            # Verified in the project directory, because that is where a stray
            # .java-version or a stale alias would take effect.
            actual="$("$java_home/bin/java" -version 2>&1 | head -1)"

            opts="$(round_opts "$round")"
            if [[ "$opts" == *"{JFR}"* ]]; then
                opts="${opts//\{JFR\}/-XX:FlightRecorderOptions=stackdepth=128 -XX:StartFlightRecording=name=prof,settings=profile,filename=$jfr,dumponexit=true,maxsize=500m}"
            fi

            cmd="${RUN_CMD//\{JAVA\}/$java_home/bin/java}"
            cmd="${cmd//\{OPTS\}/$opts}"
            cmd="${cmd//\{UNITS\}/$UNITS}"
            # Lets an arm run the artifact its own branch built, rather than one shared jar.
            cmd="${cmd//\{WORKTREE\}/${WORKTREES[$i]}}"

            echo "[$(date +%H:%M:%S)] $tag :: $actual"
            bash -c "$cmd" > "$console" 2>&1
            exit_code=$?

            sha=""; [ -f "$jfr" ] && sha="$(shasum -a 256 "$jfr" | cut -d' ' -f1)"

            ENV="$ENV" ROUND="$round" LABEL="$label" REP="$rep" JENV_NAME="$name" \
            JAVA_VER="$actual" BRANCH="${BRANCHES[$i]}" UNITS_TARGET="$UNITS" \
            EXITC="$exit_code" JFR="$jfr" SHA="$sha" CONSOLE="$console" OPTS="$opts" \
            UNITS_RE="${UNITS_RE:-}" SECONDS_RE="${SECONDS_RE:-}" RATE_RE="${RATE_RE:-}" \
            MARKER_START_RE="${MARKER_START_RE:-}" MARKER_END_RE="${MARKER_END_RE:-}" \
            python3 - "$OUT/results.jsonl" <<'PY'
import json, os, re, sys

text = open(os.environ["CONSOLE"], errors="replace").read()

def grab(var, cast):
    """Missing values stay null with the pattern recorded -- never coerced to zero,
    because a zero that looks like a measurement is how a wrong row reaches a table."""
    pattern = os.environ.get(var) or ""
    if not pattern:
        return None
    m = re.search(pattern, text)
    if not m:
        return None
    try:
        return cast(m.group(1))
    except (ValueError, IndexError):
        return None

row = {
    "env": os.environ["ENV"], "round": os.environ["ROUND"],
    "jdk": os.environ["LABEL"], "rep": int(os.environ["REP"]),
    "jenv_name": os.environ["JENV_NAME"], "java_version": os.environ["JAVA_VER"],
    "branch": os.environ["BRANCH"], "jvm_opts": os.environ["OPTS"].strip(),
    "units_target": int(os.environ["UNITS_TARGET"]),
    "units": grab("UNITS_RE", int),
    "seconds": grab("SECONDS_RE", float),
    "rate": grab("RATE_RE", float),
    "marker_start": grab("MARKER_START_RE", str),
    "marker_end": grab("MARKER_END_RE", str),
    "exit": int(os.environ["EXITC"]),
    "jfr": os.environ["JFR"] if os.environ["SHA"] else None,
    "jfr_sha256": os.environ["SHA"] or None,
    "console": os.environ["CONSOLE"],
}
with open(sys.argv[1], "a") as fh:
    fh.write(json.dumps(row) + "\n")

print("            rate=%s units=%s%s" % (
    row["rate"], row["units"],
    ("  marker_end=[%s]" % row["marker_end"]) if row["marker_end"] else ""))
PY
        done
    done
done

echo "done -- $(wc -l < "$OUT/results.jsonl" 2>/dev/null || echo 0) rows in $OUT/results.jsonl"
