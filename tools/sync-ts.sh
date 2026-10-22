#!/bin/sh
# Copies the original TypeScript explore sources from the React Native app (read-only source) into tools/ts/ for the golden generator,
# or with --check only reports whether the copies are still identical. Never writes into the source tree.
set -eu
SRC="${CLIENT_APP:-/Users/dmyeon/clientApp}"
HERE="$(cd "$(dirname "$0")" && pwd)"
FILES="src/services/run/explore/exploreGraph.ts src/services/run/explore/explorePipeline.ts src/services/run/explore/exploreTile.ts
src/services/run/explore/onFoot.ts src/services/run/runDistance.ts src/services/run/runTrack.ts src/services/run/runElevation.ts
src/services/run/geo.ts src/services/run/polyline.ts jest/testing/exploreTestPaths.ts"
status=0
for f in $FILES; do
  if [ "${1:-}" = "--check" ]; then
    if ! cmp -s "$SRC/$f" "$HERE/ts/$f"; then echo "differs: $f"; status=1; fi
  else
    mkdir -p "$(dirname "$HERE/ts/$f")"
    cp "$SRC/$f" "$HERE/ts/$f"
  fi
done
[ "${1:-}" = "--check" ] && [ $status -eq 0 ] && echo "tools/ts is identical to $SRC"
exit $status
