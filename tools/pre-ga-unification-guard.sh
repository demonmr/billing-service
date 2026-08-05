#!/usr/bin/env bash
#
# pre-ga-unification-guard.sh
# ----------------------------
# Pre-GA API unification (2026-07-26) cancelled the V2-suffix pattern.
# Each service now exposes ONE unified OpenAPI contract under a single
# namespace, and the gateway routes ONE unified route per downstream.
#
# This guard fails CI if any of the following anti-patterns reappear in
# the production source tree:
#
#   1. *-v2.yaml contract files — there must be exactly ONE contract
#      per service (fin_app_api_unification_policy.md).
#   2. *V2Controller.java / *V2Service.java / *V2Handler.java —
#      controllers/services must be unified, not suffixed.
#   3. *.v2.* package paths in src/main/java — no V2 sub-package.
#   4. Historical references to deleted contracts (notification-v2.yaml,
#      calendar-api.yaml) in non-comment contexts.
#   5. V2/narrative leftovers in source comments such as "V2 contract-
#      first", "V2 surface", "V2 controller".
#
# Scope: src/main/java, src/main/resources/contracts, src/test/java,
# src/test/resources. Markdown / CHANGELOG docs are excluded (release
# notes legitimately reference v2.x.y contract versions).
#
# Usage:
#   ./tools/pre-ga-unification-guard.sh           # scan . (repo root)
#   ./tools/pre-ga-unification-guard.sh --self    # also scan this script
#                                                 # (used for self-test)
#
# Exit code:
#   0  — no V2 leftovers found
#   1  — at least one hit (anti-pattern reintroduced)
#   2  — script invocation error (missing grep, etc.)
#
# Wired into Maven via the `pre-ga-guard` profile in pom.xml (see
# exec-maven-plugin). Runs as part of `mvn verify -Ppre-ga-guard`.
#
# Portable: bash (with `command -v` + `grep -rE`); works on Linux,
# macOS, and Git Bash on Windows.

set -u  # NOT -e — we want to count hits and exit 1 at the end.

# --- Resolve repo root robustly (works on Linux/macOS/Git Bash) -------
# Use BASH_SOURCE first (preferred); fall back to $0; final fallback pwd.
if [ -n "${BASH_SOURCE[0]:-}" ]; then
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
elif [ -n "${0:-}" ] && [ "${0}" != "bash" ]; then
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
else
    SCRIPT_DIR="$(pwd)"
fi
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT" || {
    echo "[pre-ga-guard] FATAL: cannot cd to repo root: $REPO_ROOT" >&2
    exit 2
}

# Self-test (used by the developer to confirm the script is wired up).
SELF_TEST=0
for arg in "$@"; do
    case "$arg" in
        --self) SELF_TEST=1 ;;
        -h|--help)
            sed -n '2,40p' "$0"
            exit 0
            ;;
    esac
done

# --- Sanity checks ----------------------------------------------------
if ! command -v grep >/dev/null 2>&1; then
    echo "[pre-ga-guard] FATAL: grep not found in PATH" >&2
    exit 2
fi

# --- Scanner ----------------------------------------------------------
# Anti-patterns. Keep the regexes PRECISE — broad ones produce false
# positives on OpenAPI version strings (v2.4.0, v2.5.0, etc.).
#
# Each pattern lists:
#   - a short label
#   - a regex (extended)
#   - the directories to scan
#
# The regexes intentionally exclude:
#   * v2.x.y version strings (v2\.0\.0, v2\.1\.0, ..., v2\.9\.0)
#   * phase-X.Y.Z comments like (v2.3.0)
#   * doc/comments outside src/

HITS=0
SCAN_DIRS=("src/main/java" "src/main/resources/contracts" "src/test/java" "src/test/resources")

# Some repos put contracts under src/main/resources/api or src/main/openapi.
# Defensive: scan those too if they exist.
for extra in src/main/resources/api src/main/openapi src/main/api; do
    if [ -d "$extra" ]; then SCAN_DIRS+=("$extra"); fi
done

# Build a single grep invocation per pattern. We use `grep -rE` (POSIX)
# and post-filter via the negative-lookbehind trick that POSIX provides:
# `grep -vE` to exclude legitimate version strings.

declare -a FILENAME_PATTERNS=(
    # 1. V2-suffixed Java files (filename match, not content match).
    #    `*V2Controller.java` / `*V2Service.java` / etc. — these
    #    directly violate the unified-controller rule.
    #    Format: "LABEL ::: PATTERN"
    "V2-suffixed Java files ::: .*[Vv]2(Controller|Service|Handler|Repository|Api|RestController)\\.java$"

    # 2. V2 suffix in OpenAPI contract filenames — `*-v2.yaml`. The
    #    generator would otherwise pull these as a separate contract.
    "V2-suffixed OpenAPI YAML files ::: .*-v2\\.ya?ml$"
)

declare -a CONTENT_PATTERNS=(
    # 3. V2 sub-package path in src/main/java (e.g. ua.fin.X.v2.Y).
    #    We use BRE here (not ERE) for portability — BRE supports the
    #    `\<` word-boundary on every grep flavor (BSD, GNU, Git Bash
    #    for Windows), whereas ERE's `\b` and the closing `\.`
    #    interact unpredictably. The leading `\<` ensures we match
    #    the start of a package declaration, not a fragment.
    #    The trailing `\.` is dropped because BRE on some platforms
    #    refuses to match a `\.` right before the input end (e.g.
    #    "package ua.fin.probe.v2;" — the final `;` not being a
    #    word char). The pattern is still safe: "ua.fin.X.v2" is
    #    already unique enough.
    "V2 sub-package in src/main/java ::: \\<ua\\.fin\\.[a-z][a-z]*\\.v2 ::: "

    # 4. `notification-v2.yaml` / `calendar-api.yaml` /
    #    `transactions-v2.yaml` — historical filenames that should
    #    not appear in live code, comments, or tests.
    "Obsolete contract filenames (notification-v2 / calendar-api / transactions-v2) ::: \\b(notification-v2|calendar-api|transactions-v2|person-settings-v2|analytics-v2|auth-v2)\\b ::: -E"

    # 5. V2 narrative in code comments — "V2 contract-first",
    #    "V2 surface", "V2 controller", "V1/V2 split", "v2-only".
    #    The negative-lookahead "V2 not followed by digit" (which
    #    would catch Flyway labels like V20) is implemented as a
    #    post-filter `grep -vE 'V2[0-9]'` (see FLYWAY_EXCLUDE
    #    below). `grep -P` (Perl regex) is NOT used because it's
    #    not portable across all Git Bash locales.
    "V2 narrative in Java comments ::: \\b(V2 contract-first|V2 surface|V2 controller|V2 contract|V2 split|V1/V2|v2-only)\\b ::: -E"
)

# Post-filter: drop Flyway migration version labels (V20, V21, ...)
# from V2 narrative matches. The narrative regex matches "V2 surface"
# but ALSO "V20 surface" — the latter is a Flyway version, not V2.
FLYWAY_EXCLUDE='V2[0-9]'

# OpenAPI version strings to ALWAYS exclude (legitimate release labels).
VERSION_EXCLUDE='v2\.(0|1|2|3|4|5|6|7|8|9|10)\.'

# Allow self-test patterns (the script's own narration / docs).
SELF_EXCLUDE_FILE="$0"

echo "[pre-ga-guard] scanning repo root: $REPO_ROOT"
echo "[pre-ga-guard] scan dirs: ${SCAN_DIRS[*]}"

# --- Filename-based scan (find + grep on basename) --------------------
# Each entry uses ":::" as the field separator (the patterns may
# contain `|` for alternation, so we can't use that as a separator).
for entry in "${FILENAME_PATTERNS[@]}"; do
    LABEL="${entry%% ::: *}"
    PAT="${entry#* ::: }"
    for dir in "${SCAN_DIRS[@]}"; do
        if [ ! -d "$dir" ]; then continue; fi
        # `find` walks the dir; -regex matches the basename against $PAT
        # anchored with `.*/`. Excludes generated dirs up-front.
        MATCHES=$(find "$dir" \
            -type d \( -name target -o -name build \
                     -o -name generated-sources \
                     -o -name generated-test-sources \) -prune -o \
            -type f -print 2>/dev/null \
            | grep -E "$PAT" \
            | grep -vF "$SELF_EXCLUDE_FILE" \
            || true)
        if [ -n "$MATCHES" ]; then
            REL_MATCHES=$(echo "$MATCHES" | sed "s|^$REPO_ROOT/||")
            COUNT=$(echo "$MATCHES" | wc -l | tr -d ' ')
            echo ""
            echo "[pre-ga-guard] FAIL: $LABEL  ($COUNT hits in $dir)"
            echo "$REL_MATCHES"
            HITS=$((HITS + COUNT))
        fi
    done
done

# --- Content-based scan (grep -rn on the file contents) --------------
# Each entry uses ":::" as the field separator (regex alternation
# uses `|` which we cannot use as a separator). Each entry is
# "LABEL ::: REGEX ::: GREP_FLAGS" where GREP_FLAGS defaults to -E.
for entry in "${CONTENT_PATTERNS[@]}"; do
    LABEL="${entry%% ::: *}"
    REST="${entry#* ::: }"
    PAT="${REST%% ::: *}"
    GREP_FLAGS="${REST#* ::: }"
    # If the entry had only 2 fields, GREP_FLAGS becomes the same as
    # PAT — fall back to -E in that case.
    if [ "$GREP_FLAGS" = "$PAT" ]; then GREP_FLAGS="-E"; fi
    for dir in "${SCAN_DIRS[@]}"; do
        if [ ! -d "$dir" ]; then continue; fi
        # Build the grep command. `-n` is always present so we have
        # line numbers; `-r` is always present for recursion.
        MATCHES=$(grep -rn $GREP_FLAGS "$PAT" "$dir" \
            --exclude-dir=target \
            --exclude-dir=build \
            --exclude-dir=generated-sources \
            --exclude-dir=generated-test-sources \
            2>/dev/null \
            | grep -vE "$VERSION_EXCLUDE" \
            | grep -vE "$FLYWAY_EXCLUDE" \
            | grep -vF "$SELF_EXCLUDE_FILE" \
            || true)
        if [ -n "$MATCHES" ]; then
            REL_MATCHES=$(echo "$MATCHES" | sed "s|^$REPO_ROOT/||")
            COUNT=$(echo "$MATCHES" | wc -l | tr -d ' ')
            echo ""
            echo "[pre-ga-guard] FAIL: $LABEL  ($COUNT hits in $dir)"
            echo "$REL_MATCHES"
            HITS=$((HITS + COUNT))
        fi
    done
done

echo ""
if [ "$HITS" -gt 0 ]; then
    echo "[pre-ga-guard] =================================="
    echo "[pre-ga-guard] FAIL: $HITS residual V2 references found."
    echo "[pre-ga-guard] Pre-GA API unification (2026-07-26) cancelled the V2-suffix pattern."
    echo "[pre-ga-guard] See memory/fin_app_api_unification_policy.md and the"
    echo "[pre-ga-guard] Cross-Cutting Checklist in SERVICE_ROADMAP_ANALYSIS.md."
    echo "[pre-ga-guard] =================================="
    exit 1
fi

echo "[pre-ga-guard] OK: 0 V2 leftovers. Pre-GA unification is intact."
exit 0
