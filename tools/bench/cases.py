"""Benchmark cases: places in and around Paderborn and in Cologne, weekdays, weekend, morning to night.

Writes cases.json for the JVM harness (BenchSuiteTest) and the report. Places are coordinates (an address search)
unless they are a Transitous stop id."""
import itertools, json, sys
from datetime import datetime
from zoneinfo import ZoneInfo

P = {
    "PB Marsenweg": "51.72777,8.76695",
    "Geseke Bf": "de-DELFI_de:05974:32585",
    "PB Uni": "51.70690,8.77130",
    "PB Elsen": "51.73500,8.68800",
    "Bad Lippspringe": "51.78170,8.82000",
    "PB Schloß Neuhaus": "51.74500,8.71200",
    "PB Hbf": "de-DELFI_de:05774:7000",
    "Salzkotten": "51.67130,8.60470",
    "Delbrück": "51.76670,8.56670",
    "PB Marienplatz": "51.71890,8.75440",
    "Büren": "51.55140,8.56000",
    "PB Westerntor": "51.71620,8.74800",
    "Bielefeld Zentrum": "52.02900,8.53300",
    "K Ehrenfeld": "50.94970,6.91980",
    "K Porz": "50.88060,7.05600",
    "K Sülz": "50.92160,6.92860",
    "K Mülheim": "50.96340,7.00400",
    "K Chorweiler": "51.02160,6.89770",
    "K Deutz": "50.94070,6.97500",
    "K Nippes": "50.95900,6.95300",
    "Bonn Zentrum": "50.73320,7.09700",
}
PAIRS = [
    ("PB Marsenweg", "Geseke Bf", "PB region"), ("PB Marsenweg", "PB Uni", "PB city"), ("PB Elsen", "Bad Lippspringe", "PB region"),
    ("PB Schloß Neuhaus", "PB Hbf", "PB city"), ("Salzkotten", "PB Uni", "PB region"), ("Delbrück", "PB Marienplatz", "PB region"),
    ("Büren", "PB Westerntor", "PB region"), ("PB Marsenweg", "Bielefeld Zentrum", "long"), ("PB Marsenweg", "K Ehrenfeld", "long"),
    ("K Ehrenfeld", "K Porz", "Köln"), ("K Sülz", "K Mülheim", "Köln"), ("K Chorweiler", "K Deutz", "Köln"), ("K Nippes", "Bonn Zentrum", "Köln"),
]
TIMES = [("Mo", "2026-11-09 07:40"), ("Mi", "2026-11-11 12:10"), ("Mi", "2026-11-11 16:50"), ("Mo", "2026-11-09 19:00"),
         ("Fr", "2026-11-13 23:30"), ("Sa", "2026-11-14 10:00"), ("Sa", "2026-11-14 21:45"), ("So", "2026-11-15 15:00")]
TRANSFERS = [3, 5, 8]

berlin = ZoneInfo("Europe/Berlin")
cases = []
for (a, b, kind), (day, when), arrive in itertools.product(PAIRS, TIMES, [False, True]):
    i = len(cases)
    t = datetime.strptime(when, "%Y-%m-%d %H:%M").replace(tzinfo=berlin)
    cases.append({
        "id": i, "from_name": a, "to_name": b, "from": P[a], "to": P[b], "kind": kind, "day": day,
        "when": when, "time": int(t.timestamp()), "arrive": arrive,
        # the Marsenweg to Geseke trip is the one from the report, it runs with 8 minutes like the phone
        "transfer": 8 if (a, b) == ("PB Marsenweg", "Geseke Bf") else TRANSFERS[(i // 2) % 3],
    })
json.dump(cases, open(sys.argv[1] if len(sys.argv) > 1 else "cases.json", "w"), ensure_ascii=False, indent=0)
print(len(cases), "cases")
