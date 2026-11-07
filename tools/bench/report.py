"""Writes the benchmark report as one self-contained HTML page (dark theme, inline SVG, hover tooltips)."""
import collections, html, json, os, statistics, sys
from datetime import datetime
from zoneinfo import ZoneInfo
from metrics import DIR, evaluate, rides, trunk_key, waits

BERLIN = ZoneInfo("Europe/Berlin")
RUNS = ["v1.2.1", "neu · Varianten", "neu · Züge"]
COLOR = {"v1.2.1": "#c47f4a", "neu · Varianten": "#5b78ff", "neu · Züge": "#20a83a"}
KINDS = ["PB city", "PB region", "long", "Köln"]
KIND_LABEL = {"PB city": "Paderborn Stadt", "PB region": "Paderborn Umland", "long": "Fernstrecke", "Köln": "Köln/Bonn"}
e = html.escape


def hm(t): return datetime.fromtimestamp(t, BERLIN).strftime("%H:%M")


def agg(rows, cases, pick):
    out = collections.defaultdict(collections.Counter)
    for r in rows:
        c = cases[r["case"]]
        if not pick(c): continue
        a = out[r["run"]]
        a["cases"] += 1
        for k in ("rows", "violation", "extra_change", "dominated", "pointless", "rel", "hits", "empty", "trains"):
            a[k] += r[k]
        a["junk"] += r["extra_change"] + r["pointless"]
        a["err"] += r["code"] != 200
        a["late"] += bool(r.get("late_min"))
    return out


def tip(text): return f' data-tip="{e(text)}" tabindex="0"'


def hbars(title, items, unit="", note="", maxv=None, fmt=lambda v: f"{v:g}"):
    """items: [(row label, run, value, tooltip)] grouped by row label."""
    groups = collections.OrderedDict()
    for label, run, v, t in items: groups.setdefault(label, []).append((run, v, t))
    maxv = maxv or max([v for _, _, v, _ in items] + [1e-9])
    W, LW, BH, GAP = 820, 190, 14, 3
    y, parts = 24, []
    for x in (0, .25, .5, .75, 1):
        gx = LW + x * (W - LW - 60)
        parts.append(f'<line x1="{gx}" y1="16" x2="{gx}" y2="__H__" stroke="#2a2a2a"/>'
                     f'<text x="{gx}" y="11" class="ax" text-anchor="middle">{fmt(maxv * x)}{unit}</text>')
    for label, bars in groups.items():
        parts.append(f'<text x="0" y="{y + len(bars) * (BH + GAP) / 2 + 2}" class="lb">{e(label)}</text>')
        for run, v, t in bars:
            w = max((W - LW - 60) * v / maxv, 1.5)
            c = COLOR[run]
            parts.append(f'<g class="hit"{tip(t)}><rect x="{LW}" y="{y}" width="{W - LW}" height="{BH}" fill="transparent"/>'
                         f'<rect x="{LW}" y="{y}" width="{w:.1f}" height="{BH}" rx="3" fill="#161616" stroke="{c}"/>'
                         f'<text x="{LW + w + 6:.1f}" y="{y + 11}" class="val">{fmt(v)}{unit}</text></g>')
            y += BH + GAP
        y += 10
    svg = "".join(parts).replace("__H__", str(y - 8))
    legend = "".join(f'<span class="key"><i style="border-color:{COLOR[r]}"></i>{e(r)}</span>' for r in RUNS
                     if any(run == r for _, run, _, _ in items))
    return (f'<div class="panel"><div class="ptitle">{e(title)}</div><div class="legend">{legend}</div>'
            f'<svg viewBox="0 0 {W} {y}" width="100%">{svg}</svg></div>' + (f'<div class="foot">{e(note)}</div>' if note else ""))


def matrix(cases, rows, run, arrive):
    """Pairs x times; each cell is one search, colored by its worst finding."""
    by = {r["case"]: r for r in rows if r["run"] == run}
    pairs = list(collections.OrderedDict(((c["from_name"], c["to_name"]), None) for c in cases))
    times = list(collections.OrderedDict(((c["day"], c["when"][11:]), None) for c in cases))
    CW, RH, LW = 62, 22, 250
    parts = [f'<text x="{LW + i * CW + CW / 2}" y="12" class="ax" text-anchor="middle">{d} {t}</text>' for i, (d, t) in enumerate(times)]
    for j, p in enumerate(pairs):
        y = 22 + j * RH
        parts.append(f'<text x="0" y="{y + 14}" class="lb">{e(p[0])} → {e(p[1])}</text>')
        for i, tm in enumerate(times):
            c = next(c for c in cases if (c["from_name"], c["to_name"]) == p and (c["day"], c["when"][11:]) == tm and c["arrive"] == arrive)
            r = by.get(c["id"])
            if r is None: color, label, text = "#555", "–", "nicht gelaufen"
            elif r["code"] != 200: color, label, text = "#ff5f57", "err", f"Backend {r['code']}"
            elif r["violation"] or r["extra_change"] or r["pointless"] or r["empty"]:
                color, label, text = "#ff5f57", "!", f"Verstoß {r['violation']}, Extra-Umstieg {r['extra_change']}, sinnlos {r['pointless']}, leer {r['empty']}"
            elif r["rel"] and r["hits"] < r["rel"] or r["dominated"]:
                color, label, text = "#ffbd2e", f"{r['hits']}/{r['rel']}", f"Router-Bestverbindungen {r['hits']} von {r['rel']}, überholt {r['dominated']}"
            else: color, label, text = "#28c840", f"{r['rows']}", f"ok, {r['rows']} Zeilen, {r['trains']} Züge"
            first = r and r["shown"] and min(s["dep"] for s in r["shown"])
            detail = f"{p[0]} → {p[1]}, {c['day']} {c['when'][11:]} {'Ankunft' if arrive else 'Abfahrt'}, {c['transfer']} min Umstieg: {text}"
            parts.append(f'<g class="hit"{tip(detail)}><rect x="{LW + i * CW + 2}" y="{y + 2}" width="{CW - 4}" height="{RH - 4}" rx="3" '
                         f'fill="#161616" stroke="{color}"/><text x="{LW + i * CW + CW / 2}" y="{y + 15}" class="cell" fill="{color}" '
                         f'text-anchor="middle">{e(label)}</text></g>')
    h = 22 + len(pairs) * RH + 4
    return f'<svg viewBox="0 0 {LW + len(times) * CW} {h}" width="100%">{"".join(parts)}</svg>'


def timeline(title, shown, t0, t1, mark=None):
    """Gantt rows: walk dotted gray, bus blue, train green; one row per connection."""
    W, LW, RH = 820, 110, 24
    x = lambda t: LW + (t - t0) / (t1 - t0) * (W - LW - 10)
    parts, tick = [], (t0 // 900 + 1) * 900
    while tick < t1:
        parts.append(f'<line x1="{x(tick):.1f}" y1="16" x2="{x(tick):.1f}" y2="__H__" stroke="#2a2a2a"/>'
                     f'<text x="{x(tick):.1f}" y="11" class="ax" text-anchor="middle">{hm(tick)}</text>')
        tick += 900
    if mark:
        parts.append(f'<line x1="{x(mark):.1f}" y1="16" x2="{x(mark):.1f}" y2="__H__" stroke="#ffbd2e" stroke-dasharray="3 3"/>')
    y = 22
    for r in sorted(shown, key=lambda r: (r["arr"], r["dep"])):
        parts.append(f'<text x="0" y="{y + 15}" class="lb">{hm(r["dep"])} – {hm(r["arr"])}</text>')
        for l in r["legs"]:
            a, b = max(l["dep"], t0), min(l["arr"], t1)
            if b <= a: continue
            if l["walk"]:
                parts.append(f'<line x1="{x(a):.1f}" y1="{y + 11}" x2="{x(b):.1f}" y2="{y + 11}" stroke="#888" stroke-width="2" stroke-dasharray="2 3"/>')
                continue
            c = "#28c840" if l["type"] == 1 else "#4f6dff"
            label = f'{l["line"]} {hm(l["dep"])}–{hm(l["arr"])} {l["from"]} → {l["to"]}'
            parts.append(f'<g class="hit"{tip(label)}><rect x="{x(a):.1f}" y="{y + 3}" width="{max(x(b) - x(a), 2):.1f}" height="16" rx="3" '
                         f'fill="#161616" stroke="{c}"/>' + (f'<text x="{x(a) + 4:.1f}" y="{y + 15}" class="cell" fill="{c}">{e(l["line"])}</text>'
                                                            if x(b) - x(a) > 28 else "") + "</g>")
        y += RH
    svg = "".join(parts).replace("__H__", str(y))
    return (f'<div class="panel"><div class="ptitle">{e(title)}</div><div class="legend"><span class="key"><i style="border-color:#888;border-style:dashed"></i>zu Fuß</span>'
            f'<span class="key"><i style="border-color:#4f6dff"></i>Bus</span><span class="key"><i style="border-color:#28c840"></i>Zug</span>'
            + ('<span class="key"><i style="border-color:#ffbd2e;border-style:dashed"></i>gesuchte Zeit</span>' if mark else "")
            + f'</div><svg viewBox="0 0 {W} {y + 4}" width="100%">{svg}</svg></div>')


def hist(title, series, bins, labels, note):
    items = []
    for run, values in series.items():
        n = max(len(values), 1)
        for (lo, hi), lab in zip(bins, labels):
            k = sum(lo <= v < hi for v in values)
            items.append((lab, run, round(100 * k / n), f"{run}: {k} von {len(values)} ({lab})"))
    return hbars(title, items, "%", note, maxv=100, fmt=lambda v: f"{v:.0f}")


def main():
    cases, rows = evaluate()
    dep = lambda c: not c["arrive"]
    A = agg(rows, cases, dep)
    AA = agg(rows, cases, lambda c: c["arrive"])
    n_dep = A["neu · Varianten"]["cases"]
    snap = datetime.now(BERLIN).strftime("%Y-%m-%d")

    def per100(a, k): return 100 * a[k] / max(a["rows"], 1)
    score = []
    for run in RUNS:
        a = A[run]
        score += [("Schrott je 100 Zeilen", run, round(per100(a, "junk"), 1), f"{run}: {a['junk']} von {a['rows']} Zeilen mit Extra-Umstieg oder sinnloser Kurzfahrt"),
                  ("Zug überholt (warten umsonst)", run, a["dominated"], f"{run}: bei {a['dominated']} Zügen fährt eine andere Verbindung später los und kommt nicht später an"),
                  ("Umstieg unter Minimum", run, a["violation"], f"{run}: {a['violation']} Zeilen"),
                  ("Leere Liste", run, a["empty"], f"{run}: {a['empty']} Suchen ohne Ergebnis, obwohl der Router eine Verbindung hat"),
                  ("Später als Router-Bestzeit", run, a["late"], f"{run}: in {a['late']} Suchen kommt die früheste Zeile später an als die beste des Routers")]
    recall = []
    for kind in KINDS:
        k = agg(rows, cases, lambda c, kind=kind: dep(c) and c["kind"] == kind)
        for run in RUNS:
            a = k[run]
            if a["rel"]: recall.append((KIND_LABEL[kind], run, round(100 * a["hits"] / a["rel"]), f"{run}: {a['hits']} von {a['rel']} Router-Bestverbindungen gelistet"))
    recall_arr = []
    for kind in KINDS:
        k = agg(rows, cases, lambda c, kind=kind: c["arrive"] and c["kind"] == kind)
        for run in RUNS[1:]:
            a = k[run]
            if a["rel"]: recall_arr.append((KIND_LABEL[kind], run, round(100 * a["hits"] / a["rel"]), f"{run}: {a['hits']} von {a['rel']}"))

    wait_series = {run: [w for r in rows if r["run"] == run and dep(cases[r["case"]]) for w in r["waits"]] for run in RUNS}
    wait_hist = hist("Umstiegswartezeit auf dem besten Weg je Zug (Abfahrtssuche)", wait_series,
                     [(0, 5), (5, 10), (10, 15), (15, 20), (20, 30), (30, 999)], ["0–5 min", "5–10 min", "10–15 min", "15–20 min", "20–30 min", "über 30 min"],
                     "Anteil aller Umstiege. Kürzere Wartezeit bei gleicher Ankunft heißt: später losgehen.")
    var_rows = [r for r in rows if r["run"] == "neu · Varianten"]
    # only connections with a ride before the main one: a direct bus or a train from the doorstep has nothing to vary
    def feeder_groups(r):
        g = collections.defaultdict(list)
        for s in r["shown"]: g[trunk_key(s)].append(s)
        def has_feeder(s):
            rs = rides(s)
            main = trunk_key(s)
            return len(rs) >= 2 and f"{rs[0]['line']}@{rs[0]['dep']}" != main
        def by_train(s): return any(l["type"] == 1 and f"{l['line']}@{l['dep']}" == trunk_key(s) for l in rides(s))
        return [v for v in g.values() if has_feeder(max(v, key=lambda s: s["dep"])) and by_train(v[0])]
    def nearest_two(r):
        # the two trains at the requested time: the first two leaving after it, or the last two arriving before it
        c = cases[r["case"]]
        gs = feeder_groups(r)
        if c["arrive"]: return sorted([g for g in gs if g[0]["arr"] <= c["time"]], key=lambda g: -g[0]["arr"])[:2]
        return sorted([g for g in gs if min(s["dep"] for s in g) >= c["time"] - 60], key=lambda g: min(s["dep"] for s in g))[:2]
    per_train = collections.Counter(min(len(g), 4) for r in var_rows for g in nearest_two(r))
    spread = [s for r in var_rows for s in r["spread"]]
    variety = hbars("Wege zu den zwei Zügen an der gesuchten Zeit (Modus „Mehr Varianten“, nur Bahn mit Zubringer)", [(f"{n} Weg{'e' if n > 1 else ''}", "neu · Varianten", per_train[n], f"{per_train[n]} Züge mit {n} Weg{'en' if n > 1 else ''}") for n in (1, 2, 3, 4)],
                    note=f"Spanne der Losgehzeiten je Zug mit mehreren Wegen: Median {statistics.median(spread) if spread else 0:.0f} min, "
                         f"90. Perzentil {sorted(spread)[int(.9 * len(spread))] if spread else 0:.0f} min. Gezählt sind die zwei Züge an der gesuchten Zeit, zu denen ein Bus oder eine Bahn fährt. Ein einzelner Weg heißt in den einzeln geprüften Stichproben: Die übrigen Zubringer fahren vor der gesuchten Abfahrt, mehr als 20 Minuten früher, oder kaum später als eine andere Verbindung, die deutlich früher ankommt. Busfahrten in der Stadt fehlen hier, ihre Auswahl kommt über die vielen Direktbusse.")
    trains = hbars("Züge je Suche (Mittel)", [("Abfahrt", run, round(A[run]["trains"] / max(A[run]["cases"], 1), 1), f"{run}: {A[run]['trains']} Züge in {A[run]['cases']} Suchen") for run in RUNS]
                   + [("Ankunft", run, round(AA[run]["trains"] / max(AA[run]["cases"], 1), 1), f"{run}: {AA[run]['trains']} Züge") for run in RUNS[1:]])
    rows_per = hbars("Zeilen je Suche (Mittel)", [("Abfahrt", run, round(A[run]["rows"] / max(A[run]["cases"], 1), 1), f"{run}: {A[run]['rows']} Zeilen") for run in RUNS]
                     + [("Ankunft", run, round(AA[run]["rows"] / max(AA[run]["cases"], 1), 1), f"{run}: {AA[run]['rows']} Zeilen") for run in RUNS[1:]])

    # example: Marsenweg to Geseke, Monday 19:00, arrival and departure
    def find(arrive, run, when="2026-11-09 19:00"):
        c = next(c for c in cases if c["from_name"] == "PB Marsenweg" and c["to_name"] == "Geseke Bf" and c["when"] == when and c["arrive"] == arrive)
        r = next((r for r in rows if r["case"] == c["id"] and r["run"] == run), None)
        return c, (r["shown"] if r else [])
    c_arr, ex_arr = find(True, "neu · Varianten")
    c_dep_old, ex_old = find(False, "v1.2.1", "2026-11-11 16:50")
    _, ex_new = find(False, "neu · Varianten", "2026-11-11 16:50")
    _, ex_tr = find(False, "neu · Züge", "2026-11-11 16:50")
    def span(*ls):
        allr = [r for l in ls for r in l]
        return (min(r["dep"] for r in allr) // 900 * 900, (max(r["arr"] for r in allr) // 900 + 1) * 900) if allr else (0, 1)
    t0, t1 = span(ex_arr)
    d0, d1 = span(ex_old, ex_new, ex_tr)

    from metrics import pointless, covers
    junk_examples = []
    for r in rows:
        if r["run"] != "v1.2.1" or cases[r["case"]]["day"] in ("Fr",): continue
        pool = r["shown"]
        for s in pool:
            extra = any(o["dep"] >= s["dep"] - 60 and o["arr"] <= s["arr"] and o["changes"] < s["changes"] for o in pool)
            later = any(o["dep"] >= s["dep"] + 300 and o["arr"] <= s["arr"] and o["changes"] <= s["changes"] for o in pool)
            if (extra or later or pointless(s)) and len(junk_examples) < 6 and all(c["id"] != r["case"] for c, _ in junk_examples):
                junk_examples.append((cases[r["case"]], s))

    def ex_row(c, s):
        legs = " → ".join(f'{l["line"]} {hm(l["dep"])}' for l in rides(s))
        return (f'<div class="row"><div class="rl">{e(c["day"])} {e(c["when"][11:])}</div><div>{e(c["from_name"])} → {e(c["to_name"])}: '
                f'<span class="mono">{hm(s["dep"])}–{hm(s["arr"])}</span> · {e(legs)} · Wartezeiten {", ".join(f"{w:.0f}" for w in waits(s))} min</div></div>')

    a_new, a_old = A["neu · Varianten"], A["v1.2.1"]
    rec = lambda a: f"{100 * a['hits'] / max(a['rel'], 1):.0f} %"
    lat = [r["ms"] for r in rows if r["run"] == "neu · Varianten" and r["code"] == 200]
    page = f"""<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Brunel · Routing-Benchmark</title><style>
:root{{--bg:#0d0d0d;--hover:#161616;--line:#2a2a2a;--text:#e8e8e8;--muted:#888;--dim:#555;--accent:#293fc5;--green:#28c840;--blue:#4f6dff;--yellow:#ffbd2e;--red:#ff5f57;
--mono:"Fira Code","JetBrains Mono","Cascadia Code",ui-monospace,monospace}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}}
main{{width:min(900px,calc(100% - 48px));margin:32px auto 48px}}
.eyebrow{{font:11px var(--mono);letter-spacing:.12em;text-transform:uppercase;color:#6b7cff}}
h1{{font-size:22px;font-weight:600;margin:6px 0 4px}}h2{{font-size:16px;font-weight:600;margin:4px 0 12px}}
.ctx{{color:var(--muted);margin:0 0 28px}}section{{margin:36px 0}}p{{color:#cfcfcf;margin:10px 0}}
.panel{{border:1px solid var(--line);border-radius:8px;padding:16px;background:var(--bg);margin:12px 0 4px}}
.ptitle{{font:12px var(--mono);color:var(--muted);margin-bottom:6px}}.legend{{display:flex;gap:16px;margin-bottom:8px;font:11px var(--mono);color:var(--muted)}}
.key i{{display:inline-block;width:14px;height:8px;border:1px solid;border-radius:2px;margin-right:6px;vertical-align:middle}}
.foot{{font:11px var(--mono);color:var(--dim);margin:4px 0 0}}
svg text{{font-family:var(--mono)}}.ax{{font-size:10px;fill:#888}}.lb{{font-size:11px;fill:#888}}.val{{font-size:11px;fill:#e8e8e8}}.cell{{font-size:10px}}
.hit{{cursor:default;outline:none}}.hit:hover rect:not([fill=transparent]),.hit:focus rect:not([fill=transparent]){{fill:#222}}
.row{{display:grid;grid-template-columns:140px 1fr;gap:12px;padding:8px 0;border-top:1px solid var(--line);transition:background .12s}}.row:hover{{background:var(--hover)}}
.rl{{font:11px var(--mono);color:var(--muted);padding-top:2px}}.mono{{font-family:var(--mono);font-size:12px}}
.big{{font:600 20px var(--mono)}}.g{{color:var(--green)}}.r{{color:var(--red)}}.y{{color:var(--yellow)}}
#tip{{position:fixed;pointer-events:none;background:#161616;border:1px solid var(--line);border-radius:6px;padding:6px 8px;font:11px var(--mono);color:var(--text);max-width:360px;display:none;z-index:9}}
footer{{font:11px var(--mono);color:var(--dim);border-top:1px solid var(--line);padding-top:10px;margin-top:40px}}
@media (prefers-reduced-motion:reduce){{.row{{transition:none}}}}
</style></head><body><main>
<div class="eyebrow">padermodern · routing-benchmark · {snap}</div>
<h1>Verbindungssuche: alt gegen neu</h1>
<p class="ctx">{len(cases)} Suchen: {len(set((c['from_name'], c['to_name']) for c in cases))} Strecken in Paderborn, im Umland und in Köln, Werktag, Samstag und Sonntag von morgens bis nachts, Abfahrt und Ankunft, Mindest-Umstieg 3, 5 oder 8 Minuten.</p>

<section><div class="eyebrow">Kurzfassung</div><h2>Was sich ändert</h2>
{hbars("Fehler in Abfahrtssuchen (weniger ist besser)", score, note=f"{n_dep} Abfahrtssuchen je Variante. v1.2.1 kann keine Ankunftssuche über das Backend und fehlt dort.")}
<div class="row"><div class="rl">router-treffer</div><div><span class="big">{rec(a_old)}</span> → <span class="big g">{rec(a_new)}</span> der besten Verbindungen des Routers rund um die gesuchte Zeit stehen in der Liste (Abfahrt).</div></div>
<div class="row"><div class="rl">schrott</div><div><span class="big r">{a_old['junk']}</span> → <span class="big g">{a_new['junk']}</span> Zeilen mit einem Umstieg mehr ohne Gewinn oder mit sinnloser Kurzfahrt, <span class="big">{a_old['dominated']}</span> → <span class="big g">{a_new['dominated']}</span> Züge, bei denen man umsonst wartet.</div></div>
<div class="row"><div class="rl">antwortzeit</div><div>Backend lokal mit aufgezeichneten Router-Antworten, Median {statistics.median(lat) if lat else 0:.0f} ms. Live kommt die Router-Zeit dazu (0,2 bis 1 s je Anfrage, Varianten parallel, höchstens 2,5 s).</div></div>
</section>

<section><div class="eyebrow">Abdeckung</div><h2>Findet die Liste die besten Verbindungen?</h2>
{hbars("Router-Bestverbindungen in der Liste, Abfahrtssuche", recall, "%", "Je Suche die bis zu drei Pareto-besten Verbindungen des Routers (später los, früher an, weniger Umstiege) ab der gesuchten Zeit. Treffer: eine Zeile fährt nicht früher, kommt nicht später an und steigt nicht öfter um.", maxv=100, fmt=lambda v: f"{v:.0f}")}
{hbars("Router-Bestverbindungen in der Liste, Ankunftssuche", recall_arr, "%", "Die bis zu drei spätesten Pareto-Verbindungen, die bis zur gesuchten Zeit ankommen.", maxv=100, fmt=lambda v: f"{v:.0f}")}
</section>

<section><div class="eyebrow">Jede Suche</div><h2>Alle Strecken und Zeiten, neu, Modus „Mehr Varianten“</h2>
<div class="panel"><div class="ptitle">Abfahrtssuche</div><div class="legend"><span class="key"><i style="border-color:#28c840"></i>ok, Zahl = Zeilen</span><span class="key"><i style="border-color:#ffbd2e"></i>Router-Bestverbindung fehlt oder Zug überholt</span><span class="key"><i style="border-color:#ff5f57"></i>Verstoß, Schrott oder leer</span></div>{matrix(cases, rows, "neu · Varianten", False)}</div>
<div class="panel"><div class="ptitle">Ankunftssuche</div>{matrix(cases, rows, "neu · Varianten", True)}</div>
<div class="foot">Mo 09.11., Mi 11.11., Fr 13.11., Sa 14.11., So 15.11.2026. Fahrplanstand Transitous vom {snap}.</div>
</section>

<section><div class="eyebrow">Beispiel</div><h2>Marsenweg nach Geseke, Ankunft Montag 19:00, 8 Minuten Umstieg</h2>
{timeline("neu · Mehr Varianten", ex_arr, t0, t1, c_arr["time"])}
<p>Die Züge um 18:33 und 19:03 stehen oben, jeder mit mehreren Wegen dorthin. Die Losgehzeiten liegen versetzt, damit du wählen kannst, wann du losgehst.</p>
</section>

<section><div class="eyebrow">Beispiel</div><h2>Marsenweg nach Geseke, Abfahrt Mittwoch 16:50</h2>
{timeline("v1.2.1", ex_old, d0, d1, c_dep_old["time"])}
{timeline("neu · Mehr Varianten", ex_new, d0, d1, c_dep_old["time"])}
{timeline("neu · Mehr Züge", ex_tr, d0, d1, c_dep_old["time"])}
</section>

<section><div class="eyebrow">Umstiege</div><h2>Kurz umsteigen statt lange warten</h2>
{wait_hist}
<p>Kein Umstieg unterschreitet das eingestellte Minimum. Bei gleicher Ankunft gewinnt der Weg, der später losfährt: Mit 5 Minuten Minimum schlägt ein 5-Minuten-Umstieg einen mit 30 Minuten Wartezeit.</p>
{"".join(ex_row(c, s) for c, s in junk_examples) and '<div class="ptitle" style="margin-top:16px">Schrott aus v1.2.1: eine andere Zeile derselben Liste fährt später los oder steigt seltener um und kommt nicht später an</div>' + "".join(ex_row(c, s) for c, s in junk_examples)}
</section>

<section><div class="eyebrow">Vielfalt</div><h2>Mehr Varianten oder mehr Züge</h2>
{variety}{trains}{rows_per}
</section>
<footer>padermodern · Benchmark vom {snap} · Fahrplan Transitous (MOTIS, DELFI), Router-Referenz Transitous /v5/plan mit gleichem Mindest-Umstieg · v1.2.1 = Commit 151bf81 mit Koordinaten statt Haltestellenpaaren (für den alten Stand günstig)</footer>
</main><div id="tip"></div><script>
const tipEl=document.getElementById('tip');
function show(ev,el){{tipEl.textContent=el.dataset.tip;tipEl.style.display='block';const r=el.getBoundingClientRect();
const x=ev&&ev.clientX!=null?ev.clientX:r.left+r.width/2, y=ev&&ev.clientY!=null?ev.clientY:r.top;
tipEl.style.left=Math.min(x+12,innerWidth-tipEl.offsetWidth-8)+'px';tipEl.style.top=(y+14)+'px'}}
document.querySelectorAll('[data-tip]').forEach(el=>{{el.addEventListener('pointermove',ev=>show(ev,el));el.addEventListener('focus',()=>show(null,el));
el.addEventListener('pointerleave',()=>tipEl.style.display='none');el.addEventListener('blur',()=>tipEl.style.display='none')}});
</script></body></html>"""
    if "--embed" in sys.argv:
        # inline view in the chat: no page background or margins, no per-search matrix and no departure examples
        import re
        page = re.sub(r'<section><div class="eyebrow">Jede Suche</div>.*?</section>', "", page, flags=re.S)
        page = re.sub(r'<section><div class="eyebrow">Beispiel</div><h2>Marsenweg nach Geseke, Abfahrt.*?</section>', "", page, flags=re.S)
        page = page.replace("body{{margin:0;background:var(--bg);".replace("{{", "{"), "body{margin:0;background:transparent;")
        page = page.replace("main{width:min(900px,calc(100% - 48px));margin:32px auto 48px}", "main{width:100%;margin:0}")
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    out = args[0] if args else f"{DIR}/report.html"
    open(out, "w").write(page)
    print("wrote", out)


if __name__ == "__main__":
    main()
