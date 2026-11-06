#!/usr/bin/env bash
# P0-P6 device benchmark. Usage: bench.sh RUNS "FROM|TO|HH:MM" ["FROM|TO|HH:MM" ...]
# PM_DAY=1 searches tomorrow. Runs each search RUNS times on the connected phone (debug build) and prints median and p95 for
# time to first connection and time to the complete result. Read from the PMTIME log tag.
set -euo pipefail
RUNS=$1; shift
SER=${ANDROID_SERIAL:-}
A=(adb); [ -n "$SER" ] && A=(adb -s "$SER")
seq=$(date +%s)
out=/tmp/pm/bench-$seq.txt; mkdir -p /tmp/pm; : > "$out"
for spec in "$@"; do
  IFS='|' read -r f t at <<<"$spec"
  for i in $(seq 1 "$RUNS"); do
    "${A[@]}" logcat -c
    # every run asks for another minute, so repeated runs are new searches and not cache hits (set SAME=1 to repeat)
    when=$at; [ -n "$at" ] && [ -z "${SAME:-}" ] && when=$(date -d "$at today + $((i*7)) minutes" +%H:%M)
    "${A[@]}" shell "am start -n dev.padermodern/.MainActivity -f 0x20000000 --es pm_from '$f' --es pm_to '$t' ${when:+--es pm_at '$when'} --es pm_seq '$seq-$i' ${PM_DAY:+--es pm_day '$PM_DAY'}" >/dev/null
    for _ in $(seq 1 120); do
      line=$("${A[@]}" logcat -d -s PMTIME | grep "BENCH $seq-$i " || true)
      [ -n "$line" ] && break; sleep 1
    done
    echo "$f -> $t $line" >> "$out"
  done
done
python3 - "$out" <<'P'
import re,sys,statistics,collections
rows=collections.defaultdict(list)
for l in open(sys.argv[1]):
    m=re.match(r"(.*?) D PMTIME.*BENCH \S+ first=(-?\d+) done=(-?\d+) rows=(\d+) local=(\w+) err=(\w+)",l) or re.match(r"(.*?)\s+\S+ \S+\s+\d+\s+\d+ D PMTIME\s*: BENCH \S+ first=(-?\d+) done=(-?\d+) rows=(\d+) local=(\w+) err=(\w+)",l)
    key=l.split(" 11-")[0].split(" D ")[0].strip()
    m=re.search(r"first=(-?\d+) done=(-?\d+) rows=(\d+) local=(\w+) err=(\w+)",l)
    key=l.split("  ")[0].split(" 0")[0][:60]
    key=l[:l.find(" 1")] if False else l.split(" 11-")[0]
    if m: rows[key].append(tuple(map(int,m.groups()[:3]))+(m.group(4)=="true",m.group(5)=="true"))
    else: rows[key].append(None)
def p(v,q): v=sorted(v); return v[min(len(v)-1,int(len(v)*q))]
for k,v in rows.items():
    ok=[x for x in v if x]; 
    print(f"{k}: runs={len(v)} failed={len(v)-len(ok)}")
    if ok:
        f=[x[0] for x in ok]; d=[x[1] for x in ok]
        print(f"   first   median={int(statistics.median(f))} ms  p95={p(f,.95)} ms  max={max(f)}")
        print(f"   done    median={int(statistics.median(d))} ms  p95={p(d,.95)} ms  max={max(d)}")
        print(f"   rows(min)={min(x[2] for x in ok)} errors={sum(x[4] for x in ok)} localPreview={sum(x[3] for x in ok)}/{len(ok)}")
P
