"""Router reference for each case: Transitous asked directly with the case's minimum change time, kept on disk.

The reference is the yardstick for "a good connection was missed": what the router itself considers optimal."""
import json, os, sys, time, urllib.parse, urllib.request
from datetime import datetime, timezone

DIR = os.environ.get("BENCH_DIR", "/mnt/storage/t3/bench-cache")
os.makedirs(f"{DIR}/ref", exist_ok=True)
cases = json.load(open(f"{DIR}/cases.json"))

def fetch(c):
    path = f"{DIR}/ref/{c['id']}.json"
    if os.path.exists(path): return
    q = {"fromPlace": c["from"], "toPlace": c["to"], "numItineraries": 8, "maxTransfers": 2,
         "minTransferTime": c["transfer"],
         "time": datetime.fromtimestamp(c["time"], timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    if c["arrive"]: q["arriveBy"] = "true"
    url = "https://api.transitous.org/api/v5/plan?" + urllib.parse.urlencode(q)
    # Transitous is a shared service: one request per second, and a 429 is waited out
    wait = 10
    for attempt in range(6):
        time.sleep(1)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "brunel-bench/1"})
            body = urllib.request.urlopen(req, timeout=60).read()
            open(path, "wb").write(body); return
        except Exception as e:
            print("retry", c["id"], e, file=sys.stderr); time.sleep(wait); wait *= 2

for c in cases: fetch(c)
print("reference", len(os.listdir(f"{DIR}/ref")), "of", len(cases))
