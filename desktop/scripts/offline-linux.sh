#!/bin/sh
# Optional Linux verification. Requires unprivileged user/network namespaces and iproute2.
# Only loopback is enabled, for the test driver's debugger connection; there is no external route.
set -eu
gate0_uid=$(id -u)
gate0_gid=$(id -g)
if [ "$gate0_uid" = 0 ]; then echo 'Run as a regular user; the renderer sandbox must stay enabled.' >&2; exit 1; fi
exec unshare -Urn sh -c '
  ip link set lo up
  gate0_uid=$1; gate0_gid=$2; shift 2
  exec unshare -U --map-user="$gate0_uid" --map-group="$gate0_gid" "$@"
' sh "$gate0_uid" "$gate0_gid" "$@"
