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
stub_dir="$(mktemp -d)"
cleanup() {
  rm -rf -- "$outside" "$stub_dir"
  [[ ! -L "$ROOT/a" ]] || rm -f -- "$ROOT/a"
  "$RUNNER" --verify >/dev/null
}
trap cleanup EXIT
printf 'must survive\n' >"$outside/sentinel"
rm -rf -- "$ROOT/a"
ln -s -- "$outside" "$ROOT/a"
if "$RUNNER" --reset >/dev/null 2>&1; then
  printf 'unsafe reset unexpectedly accepted a symbolic-link profile\n' >&2
  exit 1
fi
[[ -f "$outside/sentinel" ]]
rm -f -- "$ROOT/a"
"$RUNNER" --verify >/dev/null

if grep -Fq 'wait -n' "$RUNNER"; then
  printf 'launcher must remain compatible with macOS Bash 3.2 (no wait -n)\n' >&2
  exit 1
fi

# Exercise the OS boundary without building or launching: a stub Maven records whether
# execution passed the display precheck, then deliberately stops the launcher.
cat >"$stub_dir/uname" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "${CODEFIT_TEST_OS}"
EOF
cat >"$stub_dir/mvn" <<'EOF'
#!/usr/bin/env sh
: >"${CODEFIT_TEST_MAVEN_MARKER}"
exit 1
EOF
chmod +x "$stub_dir/uname" "$stub_dir/mvn"

darwin_marker="$stub_dir/darwin-reached-maven"
PATH="$stub_dir:$PATH" CODEFIT_TEST_OS=Darwin CODEFIT_TEST_MAVEN_MARKER="$darwin_marker" \
  env -u DISPLAY -u WAYLAND_DISPLAY "$RUNNER" >/dev/null 2>&1 || true
[[ -f "$darwin_marker" ]]

linux_marker="$stub_dir/linux-reached-maven"
PATH="$stub_dir:$PATH" CODEFIT_TEST_OS=Linux CODEFIT_TEST_MAVEN_MARKER="$linux_marker" \
  env -u DISPLAY -u WAYLAND_DISPLAY "$RUNNER" >/dev/null 2>&1 || true
[[ ! -e "$linux_marker" ]]

printf 'Dual-instance path and reset safety checks passed.\n'
