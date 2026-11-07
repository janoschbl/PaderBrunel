"""Scores benchmark runs. Each run is an ndjson file from BenchSuiteTest; the router reference sits in ref/<id>.json.

Per shown connection:
  violation      a change shorter than the traveller's minimum
  extra_change   another option leaves no earlier, arrives no later, and changes less
  dominated      the best way to a train is beaten by one that leaves later and arrives no later (waiting for nothing)
  pointless      a ride of one stop, at most 4 min, followed by a wait of 10 min or more
Per case:
  recall         share of the router's best (Pareto) connections near the requested time that the list matches
  late_min       minutes the earliest shown arrival is behind the router's (departure searches)
  empty          no row although the router has a connection
"""
import json, os
from datetime import datetime

DIR = os.environ.get("BENCH_DIR", "/mnt/storage/t3/bench-cache")
MIN = 60


def ts(s):
    return int(datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()) if s else None


def load_run(label):
    path = f"{DIR}/{label}.ndjson"
    if not os.path.exists(path): return {}
    return {r["id"]: r for r in map(json.loads, filter(str.strip, open(path)))}


def ref_itins(cid):
    path = f"{DIR}/ref/{cid}.json"
    if not os.path.exists(path): return None
    out = []
    for it in json.load(open(path)).get("itineraries", []):
        legs = it["legs"]
        rides = [l for l in legs if l["mode"] not in ("WALK", "BIKE", "CAR")]
        out.append({
            "dep": ts(legs[0].get("scheduledStartTime") or legs[0]["startTime"]),
            "arr": ts(legs[-1].get("scheduledEndTime") or legs[-1]["endTime"]),
            "changes": max(len(rides) - 1, 0),
            "lines": [l.get("routeShortName", "") for l in rides],
        })
    return out


def rides(r):
    return [l for l in r["legs"] if not l["walk"]]


def waits(r):
    rs = rides(r)
    return [(b["dep"] - a["arr"]) / MIN for a, b in zip(rs, rs[1:])]


def trunk_key(r):
    rs = rides(r)
    if not rs: return "walk"
    trains = [l for l in rs if l["type"] == 1]
    t = max(trains or rs, key=lambda l: l["arr"] - l["dep"])
    return f"{t['line']}@{t['dep']}"


def pareto(its):
    """Options nobody beats on (later departure, earlier arrival, fewer changes)."""
    return [a for a in its if not any(
        b is not a and b["dep"] >= a["dep"] and b["arr"] <= a["arr"] and b["changes"] <= a["changes"]
        and (b["dep"], -b["arr"], -b["changes"]) != (a["dep"], -a["arr"], -a["changes"]) for b in its)]


def relevant(ref, case):
    at = case["time"]
    if case["arrive"]:
        near = [r for r in ref if at - 180 * MIN <= r["arr"] <= at]
        return sorted(pareto(near), key=lambda r: -r["arr"])[:3]
    near = [r for r in ref if at - MIN <= r["dep"] <= at + 180 * MIN]
    return sorted(pareto(near), key=lambda r: r["dep"])[:3]


def covers(s, r):
    """The shown s is as good as the router's r: leaves no earlier, arrives no later, changes no more; or it arrives
    clearly earlier for leaving at most a few minutes earlier (the app's own rule)."""
    if s["changes"] > r["changes"]: return False
    if s["arr"] <= r["arr"] and s["dep"] >= r["dep"] - MIN: return True
    gain = (r["arr"] - s["arr"]) / MIN
    return gain >= 3 and (r["dep"] - s["dep"]) / MIN <= min(5, gain // 3)


def pointless(r):
    """A ride of one stop, at most 4 min, then 10 min or more idle (walking excluded) before the next ride."""
    legs = r["legs"]
    idx = [k for k, l in enumerate(legs) if not l["walk"]]
    for i, j in zip(idx, idx[1:]):
        a, b = legs[i], legs[j]
        walked = sum(l["arr"] - l["dep"] for l in legs[i + 1:j])
        if a["stops"] <= 2 and a["arr"] - a["dep"] <= 4 * MIN and b["dep"] - a["arr"] - walked >= 10 * MIN: return True
    return False


def score(case, shown, ref, variety):
    t = case["transfer"]
    pool = shown + (ref or [])
    out = {"rows": len(shown), "violation": 0, "extra_change": 0, "dominated": 0, "pointless": 0, "waits": []}
    for r in shown:
        if any(w < t for w in waits(r)): out["violation"] += 1
        if any(s["dep"] >= r["dep"] - MIN and s["arr"] <= r["arr"] and s["changes"] < r["changes"] for s in pool):
            out["extra_change"] += 1
        if pointless(r): out["pointless"] += 1
    # the best way to each train: the one leaving last; earlier ones are the variety the traveller asked for
    groups = {}
    for r in shown: groups.setdefault(trunk_key(r), []).append(r)
    for g in groups.values():
        best = max(g, key=lambda r: (r["dep"], -r["arr"]))
        out["waits"] += waits(best)
        if any(s["dep"] >= best["dep"] + 2 * MIN and s["arr"] <= best["arr"] and s["changes"] <= best["changes"] for s in pool):
            out["dominated"] += 1
    out["trains"] = len(groups)
    out["per_train"] = [len(g) for g in groups.values()]
    out["spread"] = [(max(r["dep"] for r in g) - min(r["dep"] for r in g)) / MIN for g in groups.values() if len(g) > 1]
    rel = relevant(ref, case) if ref else []
    if shown:
        # only the span the list covers; later trains are one "Später" away
        if case["arrive"]: rel = [r for r in rel if r["arr"] >= min(s["arr"] for s in shown)]
        else: rel = [r for r in rel if r["dep"] <= max(s["dep"] for s in shown)]
    hits = sum(any(covers(s, r) for s in shown) for r in rel)
    out["rel"], out["hits"] = len(rel), hits
    out["empty"] = int(not shown and bool(rel))
    if rel and not case["arrive"]:
        first = [s["arr"] for s in shown if s["dep"] >= case["time"] - MIN]
        out["late_min"] = max(0, (min(first) - min(r["arr"] for r in rel)) / MIN) if first else None
    return out


def evaluate():
    cases = json.load(open(f"{DIR}/cases.json"))
    runs = {"v121": load_run("v121"), "new": load_run("new")}
    rows = []
    for c in cases:
        ref = ref_itins(c["id"])
        for label, mode in (("v121", "trains"), ("new", "variety"), ("new", "trains")):
            res = runs[label].get(c["id"])
            if res is None: continue
            shown = res[mode]
            for r in shown: r["changes"] = max(len(rides(r)) - 1, 0)
            s = score(c, shown, ref, mode == "variety")
            s.update(case=c["id"], run=("v1.2.1" if label == "v121" else f"neu · {'Varianten' if mode == 'variety' else 'Züge'}"),
                     ms=res["ms"], code=res["code"], shown=shown)
            rows.append(s)
    return cases, rows


if __name__ == "__main__":
    cases, rows = evaluate()
    import collections
    agg = collections.defaultdict(lambda: collections.Counter())
    for r in rows:
        c = cases[r["case"]]
        key = (r["run"], "Ankunft" if c["arrive"] else "Abfahrt")
        a = agg[key]
        a["cases"] += 1
        for k in ("rows", "violation", "extra_change", "dominated", "pointless", "rel", "hits", "empty", "trains"):
            a[k] += r[k]
        a["err"] += r["code"] != 200
        if r.get("late_min"): a["late_cases"] += r["late_min"] > 0
    for k, a in sorted(agg.items()):
        print(f"{k[0]:18} {k[1]:8} cases {a['cases']:3} err {a['err']:2} rows {a['rows']:4} trains {a['trains']:4} "
              f"viol {a['violation']} extra {a['extra_change']} dom {a['dominated']} pointless {a['pointless']} "
              f"recall {a['hits']}/{a['rel']} empty {a['empty']} late {a['late_cases']}")
