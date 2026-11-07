#!/usr/bin/env python3
"""Compares the backend's live frame with the operators' own boards.

For the next legs on a few routes it asks /v1/plan, then the VRR departure board and the PaderSprinter (SRS) board
at the same stop, and prints the expected departure from each. Exits 1 when a leg the backend calls live differs
from VRR by more than a minute.

    tools/live_check.py [backend-url]      default http://127.0.0.1:8098
"""
import datetime
import json
import sys
import time
import urllib.parse
import urllib.request

BACKEND = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8098").rstrip("/")
# from, to, extra query (positions let the backend find stops Transitous knows only by platform)
ROUTES = [
    ("de:05974:32585", "de:05774:7000", {}),                             # Geseke -> Paderborn Hbf, RB89
    ("de:05774:7000", "de:05774:7063", {"to_pos": "51.71687,8.75707"}),  # Paderborn Hbf -> Kamp, city buses
    ("51.64000,8.51500", "de:05774:7000", {}),                           # a position in Geseke -> Paderborn Hbf
]
VRR_DM = "https://efa.vrr.de/standard/XML_DM_REQUEST"
SRS = "https://service.ivanto.de/srs/api/v1/departuresinfo"
SRS_APP = {"tenant": "pader", "bundleIdentifier": "de.ivanto.padersprinter", "appVersion": "Android 17---8.7.8-9872"}
BERLIN = datetime.timezone(datetime.timedelta(hours=1))  # November


def get(url, params, timeout=40):
    with urllib.request.urlopen(url + "?" + urllib.parse.urlencode(params), timeout=timeout) as r:
        return r.read().decode()


def iso(s):
    return int(datetime.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()) if s else None


def norm(line):
    return "".join(line.split()).lower()


def hhmm(t):
    return datetime.datetime.fromtimestamp(t, BERLIN).strftime("%H:%M") if t else "  -  "


def vrr_board(stop, at):
    local = datetime.datetime.fromtimestamp(at - 300, BERLIN)
    v = json.loads(get(VRR_DM, {"outputFormat": "rapidJSON", "type_dm": "stop", "name_dm": stop, "mode": "direct",
                               "useRealtime": "1", "limit": "60", "itdDate": local.strftime("%Y%m%d"), "itdTime": local.strftime("%H%M")}))
    sid = (v.get("locations") or [{}])[0].get("properties", {}).get("stopId")
    out = {}
    for e in v.get("stopEvents", []):
        p = iso(e.get("departureTimePlanned"))
        for line in {e["transportation"].get("number", ""), e["transportation"].get("disassembledName", "")}:
            out[(p // 60, norm(line))] = iso(e.get("departureTimeEstimated"))
    return sid, out


def srs_board(sid, at):
    if not sid:
        return {}
    date = datetime.datetime.fromtimestamp(at - 300, BERLIN).strftime("%Y-%m-%dT%H:%M:%S")
    try:
        v = json.loads(get(SRS, {**SRS_APP, "stationID": sid, "date": date, "sortBy": "departureDate"}, timeout=60))
    except Exception as e:
        print(f"    srs {sid}: {e}")
        return {}
    to_unix = lambda s: int(datetime.datetime.strptime(s, "%Y-%m-%dT%H:%M:%S%z").timestamp()) if s else None
    return {(to_unix(d["departureDate"]) // 60, norm(d.get("shortName") or "")): to_unix(d.get("expectedDepartureDate"))
            for d in v.get("departures", []) if d.get("departureDate")}


def main():
    now = int(time.time())
    bad = checked = 0
    print(f"{'route leg':44} {'plan':>5} {'ours':>5} {'src':>10} {'vrr':>5} {'srs':>5}")
    for frm, to, extra in ROUTES:
        lines = get(BACKEND + "/v1/plan", {"from": frm, "to": to, "time": now, **extra}).splitlines()
        plan = json.loads(lines[0])
        live = {(x["itin"], x["leg"]): x for x in json.loads(lines[1])["legs"]} if len(lines) > 1 else {}
        seen = set()
        for i, it in enumerate(plan["itins"]):
            for k, leg in enumerate(it["legs"]):
                if leg["mode"] == "WALK" or not (now - 600 <= leg["dep"] <= now + 3600):
                    continue
                stop = ":".join(leg["from_id"].removeprefix("de-DELFI_").split(":")[:3])
                key = (stop, leg["dep"] // 60, norm(leg["line"]))
                if key in seen:
                    continue
                seen.add(key)
                sid, vrr = vrr_board(stop, leg["dep"])
                srs = srs_board(sid, leg["dep"])
                ours = live.get((i, k))
                v = vrr.get(key[1:])
                s = srs.get(key[1:])
                mark = ""
                if ours and v is not None:
                    checked += 1
                    if abs(ours["dep"] - v) > 60:
                        bad += 1
                        mark = "  <- differs"
                elif v is not None and v != leg["dep"]:
                    bad += 1
                    mark = "  <- VRR has a delay we lack"
                name = f"{leg['line']} {leg['from_name']}"[:44]
                print(f"{name:44} {hhmm(leg['dep'])} {hhmm(ours and ours['dep'])} {(ours or {}).get('src', '-'):>10} {hhmm(v)} {hhmm(s)}{mark}")
    print(f"\n{checked} legs compared with VRR, {bad} problems")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
