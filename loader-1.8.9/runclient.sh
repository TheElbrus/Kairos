#!/usr/bin/env bash
# Launch the Kairos Forge 1.8.9 dev client (bash / Git Bash).
#
# Why this script exists:
#   Loom 0.10.0.5 + Gradle 8.8 reject `./gradlew runClient` during task validation
#   ("property 'main' is missing an input or output annotation"), because
#   net.fabricmc.loom.task.RunGameTask extends JavaExec but exposes an unannotated
#   getMain(). See docs/JOURNAL.md.
#
#   So instead we ask the build for exactly what Loom would run
#   (task `printRunClientCommand`), then launch it by hand on the Java 8 JDK that
#   Loom provisioned INSIDE the repo. Nothing outside the repo is touched.
#
# Usage:
#   ./runclient.sh              # launch the dev client
#
# On Windows, runclient.ps1 does the same thing.
#
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"
gradle_home="${GRADLE_USER_HOME:-$repo/.gradle-home}"

# JDK used to run Gradle itself (Gradle 8.8 needs 17+; the game needs Java 8).
# Try, in order: KAIROS_GRADLE_JDK, JAVA_HOME, the JDKs found in this machine's ~/.jdks.
has_java() { [ -n "$1" ] && { [ -x "$1/bin/java" ] || [ -x "$1/bin/java.exe" ]; }; }
gradle_jdk=""
for cand in "${KAIROS_GRADLE_JDK:-}" "${JAVA_HOME:-}" \
            "$HOME/.jdks/ms-21.0.12.1" "$HOME/.jdks/temurin-25.0.4.1"; do
  if has_java "$cand"; then gradle_jdk="$cand"; break; fi
done
if [ -z "$gradle_jdk" ]; then
  echo "[runclient] ERROR: no JDK found to run Gradle." >&2
  echo "[runclient] Set JAVA_HOME or KAIROS_GRADLE_JDK to a modern JDK." >&2
  exit 1
fi

# Java 8 JDK auto-provisioned by the foojay resolver (real JDK, has javac).
# Glob so a patch-version bump of the provisioned JDK does not break the script.
java8=""
for cand in "$repo"/.gradle-home/jdks/temurin-8-*/jdk8u*/bin/java.exe; do
  if [ -x "$cand" ]; then java8="$cand"; break; fi
done
if [ -z "$java8" ]; then
  echo "[runclient] ERROR: Java 8 JDK not found under:" >&2
  echo "           $repo/.gradle-home/jdks/temurin-8-*/" >&2
  echo "[runclient] Build the loader first (it auto-provisions Java 8):" >&2
  echo "           cd loader-1.8.9 && GRADLE_USER_HOME=$gradle_home ./gradlew build" >&2
  exit 1
fi

echo "[runclient] asking Gradle for the launch command..."
mapfile -t out < <(cd "$here" && JAVA_HOME="$gradle_jdk" GRADLE_USER_HOME="$gradle_home" \
  ./gradlew -q printRunClientCommand)

get() { printf '%s\n' "${out[@]}" | sed -n "s/^$1=//p" | head -n1; }

main="$(get KAIROS_MAIN)"
args_raw="$(get KAIROS_ARGS)"
jvmargs_raw="$(get KAIROS_JVMARGS)"
cp="$(get KAIROS_CP)"

if [ -z "$main" ] || [ -z "$cp" ]; then
  echo "[runclient] ERROR: Gradle did not print a launch command." >&2
  printf '%s\n' "${out[@]}" >&2
  exit 1
fi

# Loom separates args with byte 0x01.
game_args=()
if [ -n "$args_raw" ]; then IFS=$'\x01' read -r -a game_args <<<"$args_raw"; fi
jvm_args=()
if [ -n "$jvmargs_raw" ]; then IFS=$'\x01' read -r -a jvm_args <<<"$jvmargs_raw"; fi

# The game's working directory (config/, saves/, logs/ live here). Gitignored.
rundir="$here/run"
mkdir -p "$rundir"
log="$rundir/runclient.log"

echo "[runclient] launching $main on Java 8"
echo "[runclient] working dir: $rundir"
echo "[runclient] log:         $log"
echo "[runclient] (close the game window to stop)"
echo

# KAIROS_PRINT_ONLY=1 prints the exact command without starting the game (for debugging).
if [ "${KAIROS_PRINT_ONLY:-0}" = "1" ]; then
  printf 'cd %q\n' "$rundir"
  printf '%q ' "$java8" "${jvm_args[@]}" -cp "$cp" "$main" "${game_args[@]}"
  echo
  exit 0
fi

cd "$rundir"
"$java8" "${jvm_args[@]}" -cp "$cp" "$main" "${game_args[@]}" 2>&1 | tee "$log"
