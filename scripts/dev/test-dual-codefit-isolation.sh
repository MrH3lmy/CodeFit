#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
readonly RUNNER="$SCRIPT_DIR/run-dual-codefit.sh"
readonly ROOT="$REPO_ROOT/.build/dev-peers"

"$RUNNER" --verify >/dev/null

[[ "$(cd -- "$ROOT/a" && pwd -P)" != "$(cd -- "$ROOT/b" && pwd -P)" ]]
[[ "$(cd -- "$ROOT/a/home" && pwd -P)" != "$(cd -- "$ROOT/b/home" && pwd -P)" ]]
[[ "$(cd -- "$ROOT/a/work" && pwd -P)/codefit.db" != \
   "$(cd -- "$ROOT/b/work" && pwd -P)/codefit.db" ]]

# A symlink at a deletion boundary must make reset fail, never follow it.
outside="$(mktemp -d)"
trap 'rm -rf -- "$outside"; rm -f -- "$ROOT/a"; "$RUNNER" --verify >/dev/null' EXIT
printf 'must survive\n' >"$outside/sentinel"
rm -rf -- "$ROOT/a"
ln -s -- "$outside" "$ROOT/a"
if "$RUNNER" --reset >/dev/null 2>&1; then
  printf 'unsafe reset unexpectedly accepted a symbolic-link profile\n' >&2
  exit 1
fi
[[ -f "$outside/sentinel" ]]

printf 'Dual-instance path and reset safety checks passed.\n'
