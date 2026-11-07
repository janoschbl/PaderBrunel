#!/usr/bin/env bash
# Runs the routing benchmark: local backend with recorded router answers, the app's ranking in a JVM test, then the
# report. Usage: tools/bench/run.sh [label]   (label defaults to "new"; output in $BENCH_DIR/<label>.ndjson)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BENCH_DIR=${BENCH_DIR:-/mnt/storage/t3/bench-cache}
LABEL=${1:-new}
PORT=${PORT:-8199}
mkdir -p "$BENCH_DIR/record-$LABEL"
python3 "$ROOT/tools/bench/cases.py" "$BENCH_DIR/cases.json"
(cd "$ROOT/backend" && cargo build -q)
BIND=127.0.0.1:$PORT BRUNEL_RECORD_DIR="$BENCH_DIR/record-$LABEL" "$ROOT/backend/target/debug/brunel-backend" > "$BENCH_DIR/backend-$LABEL.log" 2>&1 &
PID=$!; trap 'kill $PID' EXIT
sleep 1
export JAVA_HOME=${JAVA_HOME:-$(ls -d ~/.local/share/mise/installs/java/temurin-21* | head -1)}
BENCH_URL=http://127.0.0.1:$PORT BENCH_CASES="$BENCH_DIR/cases.json" BENCH_OUT="$BENCH_DIR/$LABEL.ndjson" \
  "$ROOT/gradlew" -p "$ROOT" :app:testDebugUnitTest --tests dev.padermodern.data.BenchSuiteTest --rerun -q
echo "wrote $BENCH_DIR/$LABEL.ndjson"
