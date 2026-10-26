#!/usr/bin/env bash
# Samples the resident set size of every running drive.GuiDrive JVM once a second, until
# killed. Runs beside run-matrix.sh rather than inside it, so it adds nothing to the JVM
# under test. One line per JVM per sample:
#
#   <epoch s> <pid> <RSS KiB> <arm> <run tag or ->
#
# <arm> is read from the classpath (/tmp/jdkcmp-<arm>/bench-jar.jar); the run tag
# (gui-<round>-<arm>-rep<n>) appears when the round's options name a per-run file -- a JFR
# recording or a {RUN} log. A round with neither (C1) is matched to its runs by order: they
# run one at a time, so the k-th JVM of an arm in that round is its rep k.
#
# Usage: tools/bench/sample-rss.sh <output file> &
out="${1:?usage: sample-rss.sh <output file>}"
while :; do
    now="$(date +%s)"
    ps -Ao pid=,rss=,command= | awk -v t="$now" '$3 ~ /\/bin\/java$/ && /drive\.GuiDrive/ {
        arm = "?"; tag = "-"
        if (match($0, /jdkcmp-[A-Za-z0-9]+\//)) arm = substr($0, RSTART + 7, RLENGTH - 8)
        if (match($0, /gui-[A-Za-z0-9]+-[A-Za-z0-9]+-rep[0-9]+/)) tag = substr($0, RSTART, RLENGTH)
        print t, $1, $2, arm, tag }' >> "$out"
    sleep 1
done
