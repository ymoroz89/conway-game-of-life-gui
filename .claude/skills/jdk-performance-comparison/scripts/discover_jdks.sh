#!/usr/bin/env bash
# Discover what jenv actually offers, and what each name really resolves to.
#
# jenv names are labels chosen by whoever installed the JDK. They go stale by a patch
# release, and bare numeric aliases can resolve to an entirely different vendor -- on one
# machine `jenv prefix 25.0.4` returns GraalVM while `25` returns OpenJDK. Selecting by
# name alone is how a comparison ends up measuring two builds of the same JVM, or two
# different JIT compilers.
#
# Usage: discover_jdks.sh [project-dir]

set -uo pipefail
cd "${1:-.}" || exit 1

if ! command -v jenv >/dev/null 2>&1; then
    echo "jenv not found on PATH." >&2
    exit 1
fi

echo "=== jenv version names, and what each actually is ==="
printf '%-24s %-10s %s\n' "JENV NAME" "VENDOR" "REPORTED VERSION"
jenv versions --bare 2>/dev/null | while read -r name; do
    prefix="$(jenv prefix "$name" 2>/dev/null)" || continue
    [ -x "$prefix/bin/java" ] || continue
    line="$("$prefix/bin/java" -version 2>&1 | head -1)"
    # GraalVM reports `java version`, OpenJDK builds report `openjdk version`.
    case "$line" in
        openjdk*) vendor="openjdk" ;;
        *)        vendor="NOT-OPENJDK" ;;
    esac
    printf '%-24s %-10s %s\n' "$name" "$vendor" "$line"
done

echo
echo "Prefer fully-qualified names (openjdk64-*, zulu64-*, temurin64-*)."
echo "Rows marked NOT-OPENJDK are usually GraalVM: never include one in a JDK-version"
echo "comparison, because it compares JIT compilers rather than JDK releases."

echo
echo "=== jenv resolution in this directory ==="
echo "global        : $(jenv global 2>/dev/null)"
if [ -f .java-version ]; then
    echo ".java-version : $(cat .java-version)   <-- OVERRIDES jenv global here"
    echo "                Move it aside for the study, or every arm silently runs the"
    echo "                same JVM under different labels. Restore it afterwards."
else
    echo ".java-version : (none)"
fi
echo "resolves to   : $(jenv version 2>/dev/null)"
echo "java on PATH  : $(java -version 2>&1 | head -1)"

echo
echo "=== repository state ==="
if git rev-parse --git-dir >/dev/null 2>&1; then
    echo "branch : $(git rev-parse --abbrev-ref HEAD)"
    dirty="$(git status --porcelain)"
    if [ -n "$dirty" ]; then
        echo "status : DIRTY -- switching refs will fail or carry changes between arms."
        echo "$dirty" | sed 's/^/         /'
    else
        echo "status : clean"
    fi
    echo
    # Tags first: a release tag pins an arm to one commit for good, where a branch can move
    # or be deleted between the study and its reproduction.
    echo "tags (preferred refs for arms):"
    # %(*objectname) is the commit an annotated tag points at; a lightweight tag has none,
    # and its own objectname already is the commit.
    tags="$(git tag -l --format='         %(refname:short)  commit %(if)%(*objectname)%(then)%(*objectname:short)%(else)%(objectname:short)%(end)  %(contents:subject)')"
    if [ -n "$tags" ]; then echo "$tags" | cut -c1-110; else echo "         (none)"; fi
    echo
    echo "branches:"
    git branch -a --format='         %(refname:short)'
else
    echo "(not a git repository -- the ref-per-version mapping will not apply)"
fi
