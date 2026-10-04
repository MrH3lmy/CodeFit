#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
readonly HARNESS_ROOT="$REPO_ROOT/.build/dev-peers"
readonly CLASSPATH_FILE="$HARNESS_ROOT/runtime-classpath.txt"

usage() {
  cat <<'EOF'
Usage: ./scripts/dev/run-dual-codefit.sh [--reset | --verify]

  (no option)  Build once and launch two persistent, isolated CodeFit profiles.
  --reset      Safely delete only profiles A and B, then build and launch them.
  --verify     Create/check the profile layout and exit without building or launching.
EOF
}

die() {
  printf 'run-dual-codefit: %s\n' "$*" >&2
  exit 1
}

canonical_existing() {
  (cd -- "$1" && pwd -P)
}

prepare_profiles() {
  mkdir -p -- "$HARNESS_ROOT"
  local harness_real
  harness_real="$(canonical_existing "$HARNESS_ROOT")"
  [[ "$harness_real" == "$REPO_ROOT/.build/dev-peers" ]] ||
    die "unsafe harness path: $harness_real"
  [[ ! -L "$HARNESS_ROOT" ]] || die "harness root must not be a symbolic link"

  local peer
  for peer in a b; do
    [[ ! -L "$HARNESS_ROOT/$peer" ]] || die "profile $peer must not be a symbolic link"
    mkdir -p -- "$HARNESS_ROOT/$peer/home" "$HARNESS_ROOT/$peer/work" \
      "$HARNESS_ROOT/$peer/logs" "$HARNESS_ROOT/$peer/tmp" \
      "$HARNESS_ROOT/$peer/preferences"
  done

  local a_root b_root a_home b_home a_db b_db
  a_root="$(canonical_existing "$HARNESS_ROOT/a")"
  b_root="$(canonical_existing "$HARNESS_ROOT/b")"
  a_home="$(canonical_existing "$HARNESS_ROOT/a/home")"
  b_home="$(canonical_existing "$HARNESS_ROOT/b/home")"
  a_db="$(canonical_existing "$HARNESS_ROOT/a/work")/codefit.db"
  b_db="$(canonical_existing "$HARNESS_ROOT/b/work")/codefit.db"

  [[ "$a_root" == "$harness_real/a" && "$b_root" == "$harness_real/b" ]] ||
    die "a profile resolves outside the harness root"
  [[ "$a_root" != "$b_root" && "$a_home" != "$b_home" && "$a_db" != "$b_db" ]] ||
    die "profiles alias the same persistent location"
}

reset_profiles() {
  # Do not generalize this deletion: exact paths and a canonical parent are deliberate safeguards.
  mkdir -p -- "$HARNESS_ROOT"
  [[ ! -L "$HARNESS_ROOT" ]] || die "refusing reset through a symbolic link"
  [[ "$(canonical_existing "$HARNESS_ROOT")" == "$REPO_ROOT/.build/dev-peers" ]] ||
    die "refusing reset outside the repository-owned harness directory"

  local peer target
  for peer in a b; do
    target="$HARNESS_ROOT/$peer"
    if [[ -e "$target" || -L "$target" ]]; then
      [[ ! -L "$target" ]] || die "refusing to reset symbolic-link profile: $target"
      [[ "$(canonical_existing "$target")" == "$HARNESS_ROOT/$peer" ]] ||
        die "refusing to reset aliased profile: $target"
      rm -rf -- "$target"
    fi
  done
}

mode="launch"
case "${1:-}" in
  "") ;;
  --reset) mode="reset" ;;
  --verify) mode="verify" ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || { usage >&2; exit 2; }

if [[ "$mode" == "reset" ]]; then
  reset_profiles
  printf 'Reset development profiles A and B under %s\n' "$HARNESS_ROOT"
fi
prepare_profiles

if [[ "$mode" == "verify" ]]; then
  printf 'Dual-profile isolation verified under %s\n' "$HARNESS_ROOT"
  exit 0
fi

command -v java >/dev/null 2>&1 || die "Java 21 is required but java was not found on PATH"
command -v mvn >/dev/null 2>&1 || die "Maven is required but mvn was not found on PATH"
java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
[[ "$java_major" == "21" ]] || die "Java 21 is required (found ${java_major:-unknown})"
[[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]] ||
  die "JavaFX needs a graphical session (neither DISPLAY nor WAYLAND_DISPLAY is set)"

mkdir -p -- "$REPO_ROOT/target"
printf 'Building CodeFit and resolving its runtime classpath once...\n'
(cd -- "$REPO_ROOT" && mvn -q -DskipTests package dependency:build-classpath \
  -Dmdep.outputFile="$CLASSPATH_FILE" -Dmdep.includeScope=runtime)
[[ -s "$CLASSPATH_FILE" ]] || die "Maven did not produce $CLASSPATH_FILE"
readonly APP_CLASSPATH="$REPO_ROOT/target/classes:$(<"$CLASSPATH_FILE")"

declare -a child_pids=()
stopping=false
stop_children() {
  if [[ "$stopping" == false ]]; then
    stopping=true
    ((${#child_pids[@]} == 0)) || kill -TERM "${child_pids[@]}" 2>/dev/null || true
    wait "${child_pids[@]}" 2>/dev/null || true
  fi
}
trap stop_children EXIT INT TERM HUP

launch_peer() {
  local label="$1" peer="$2"
  local root="$HARNESS_ROOT/$peer"
  local home="$root/home" work="$root/work" logs="$root/logs" tmp="$root/tmp" prefs="$root/preferences"
  local log="$logs/codefit.log"

  printf '\nCodeFit %s\nRuntime directory: %s\nDatabase: %s\nLog: %s\n' \
    "$label" "$root" "$work/codefit.db" "$log"
  (
    cd -- "$work"
    exec java \
      -Duser.home="$home" \
      -Djava.util.prefs.userRoot="$prefs" \
      -Djava.io.tmpdir="$tmp" \
      -cp "$APP_CLASSPATH" com.codefit.CodeFitLauncher
  ) >"$log" 2>&1 &
  child_pids+=("$!")
}

launch_peer A a
launch_peer B b
sleep 2
for pid in "${child_pids[@]}"; do
  kill -0 "$pid" 2>/dev/null || die "a CodeFit instance exited during startup; inspect the profile logs"
done

printf '\nTwo isolated CodeFit instances are running. Press Ctrl+C to stop both.\n'
set +e
wait -n "${child_pids[@]}"
status=$?
set -e
stop_children
exit "$status"
