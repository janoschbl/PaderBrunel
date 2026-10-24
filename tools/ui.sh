#!/usr/bin/env bash
# adb helpers: shot NAME, tap-id ID, tap-text TEXT, dump
set -euo pipefail
OUT=/tmp/pm/shots; mkdir -p "$OUT"
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml; }
center() { python3 -c '
import re,sys
xml=sys.stdin.read(); key,val=sys.argv[1],sys.argv[2]
for n in re.finditer(r"<node [^>]*>", xml):
    a=n.group(0)
    v=re.search(key+r"=\"([^\"]*)\"", a)
    if v and val in v.group(1):
        x1,y1,x2,y2=map(int,re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",a).groups())
        print((x1+x2)//2,(y1+y2)//2); break
else: sys.exit("not found: "+val)
' "$1" "$2"; }
case "$1" in
  shot) adb exec-out screencap -p > "$OUT/$2.png"; echo "$OUT/$2.png" ;;
  tap-id) read -r x y < <(dump | center resource-id "$2"); adb shell input tap "$x" "$y" ;;
  tap-text) read -r x y < <(dump | center text "$2"); adb shell input tap "$x" "$y" ;;
  dump) dump | python3 -c '
import re,sys
for n in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    a=n.group(0); t=re.search(r"text=\"([^\"]*)\"",a).group(1); r=re.search(r"resource-id=\"([^\"]*)\"",a).group(1); d=re.search(r"content-desc=\"([^\"]*)\"",a).group(1)
    if t or r or d: print(repr(t), r, d, re.search(r"bounds=\"([^\"]*)\"",a).group(1))' ;;
esac
