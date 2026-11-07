//! Connections: Transitous gives the timetable frame. The live frame comes from departure and arrival boards at
//! each leg's stops: VRR inside NRW, Transitous outside.
use crate::merge::{efa_id, is_nrw, key};
use serde::Serialize;
use serde_json::Value;
use std::collections::HashMap;

/// Intermediate stop of a leg: id, name, planned arrival and departure (unix seconds).
#[derive(Clone, Serialize, Debug, PartialEq)]
pub struct Via { pub id: String, pub name: String, pub arr: i64, pub dep: i64 }

#[derive(Clone, Serialize, Debug, PartialEq)]
pub struct Leg {
    pub mode: String,
    pub line: String,
    pub to: String,
    pub from_name: String,
    pub to_name: String,
    pub from_id: String,
    pub to_id: String,
    /// `[lat, lon]` of start and end, five decimals
    pub from_pos: [f64; 2],
    pub to_pos: [f64; 2],
    pub dep: i64,
    pub arr: i64,
    pub via: Vec<Via>,
}

impl Leg { pub fn transit(&self) -> bool { self.mode != "WALK" && self.mode != "BIKE" && self.mode != "CAR" } }

#[derive(Clone, Serialize, Debug, PartialEq)]
pub struct Itin { pub dep: i64, pub arr: i64, pub changes: u32, pub legs: Vec<Leg> }

/// Frame 1. `at` is the query time as unix seconds.
#[derive(Clone, Serialize, Debug, PartialEq)]
pub struct Plan { pub t: &'static str, pub at: i64, pub itins: Vec<Itin> }

/// Live times of one leg. `src` names where `dep` came from: "vrr", "transitous", or "arr" when only the arrival
/// board knew the leg. A leg missing from the frame has no forecast.
#[derive(Clone, Serialize, Debug, PartialEq)]
pub struct LegLive {
    pub itin: usize,
    pub leg: usize,
    pub dep: i64,
    pub arr: i64,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub cancelled: bool,
    pub src: &'static str,
}

/// Frame 2: live times for the legs the boards know. `gone` stays empty and is kept for older apps; a cancelled
/// leg is flagged instead.
#[derive(Clone, Serialize, Debug, PartialEq)]
pub struct Live { pub t: &'static str, pub legs: Vec<LegLive>, pub gone: Vec<usize> }

fn ts(v: &Value) -> Option<i64> {
    chrono::DateTime::parse_from_rfc3339(v.as_str()?).ok().map(|t| t.timestamp())
}

fn s(v: &Value) -> String { v.as_str().unwrap_or("").to_string() }

fn pos(p: &Value) -> [f64; 2] {
    let r = |x: f64| (x * 1e5).round() / 1e5;
    [r(p["lat"].as_f64().unwrap_or(0.0)), r(p["lon"].as_f64().unwrap_or(0.0))]
}

pub fn parse_transitous(v: &Value, at: i64) -> Result<Plan, String> {
    let its = v["itineraries"].as_array().ok_or_else(|| v["error"].as_str().unwrap_or("no itineraries").to_string())?;
    let itins = its.iter().filter_map(|it| {
        let mut legs: Vec<Leg> = it["legs"].as_array()?.iter().filter_map(|l| Some(Leg {
            mode: s(&l["mode"]),
            line: s(&l["routeShortName"]),
            to: s(&l["headsign"]),
            from_name: s(&l["from"]["name"]),
            to_name: s(&l["to"]["name"]),
            from_id: s(&l["from"]["stopId"]),
            to_id: s(&l["to"]["stopId"]),
            from_pos: pos(&l["from"]),
            to_pos: pos(&l["to"]),
            dep: ts(&l["scheduledStartTime"]).or_else(|| ts(&l["startTime"]))?,
            arr: ts(&l["scheduledEndTime"]).or_else(|| ts(&l["endTime"]))?,
            via: l["intermediateStops"].as_array().map(|a| a.iter().filter_map(|x| Some(Via {
                id: s(&x["stopId"]), name: s(&x["name"]),
                arr: ts(&x["scheduledArrival"]).or_else(|| ts(&x["arrival"]))?,
                dep: ts(&x["scheduledDeparture"]).or_else(|| ts(&x["departure"])).unwrap_or(0),
            })).collect()).unwrap_or_default(),
        })).collect();
        anchor_walks(&mut legs);
        // the itinerary's own startTime and endTime include delays; the plan frame is timetable only
        let dep = legs.first().map(|l| l.dep).or_else(|| ts(&it["scheduledStartTime"])).or_else(|| ts(&it["startTime"]))?;
        let arr = legs.last().map(|l| l.arr).or_else(|| ts(&it["scheduledEndTime"])).or_else(|| ts(&it["endTime"]))?;
        Some(Itin { dep, arr, changes: it["transfers"].as_u64().unwrap_or(0) as u32, legs })
    }).collect();
    Ok(Plan { t: "plan", at, itins })
}

/// Transitous has no timetable for walks: their "scheduled" times follow the live times of the rides around them,
/// so a walk to a late train ended after the train's planned departure. Walks after a ride start at its planned
/// arrival, walks before the first ride end at its planned departure; the length stays.
fn anchor_walks(legs: &mut [Leg]) {
    let mut prev_arr = None;
    for i in 0..legs.len() {
        if legs[i].transit() { prev_arr = Some(legs[i].arr); continue; }
        let len = legs[i].arr - legs[i].dep;
        if let Some(t) = prev_arr { legs[i].dep = t; legs[i].arr = t + len; prev_arr = Some(t + len); }
    }
    let Some(first) = legs.iter().position(|l| l.transit()) else { return };
    let mut next_dep = legs[first].dep;
    for l in legs[..first].iter_mut().rev() {
        let len = l.arr - l.dep;
        l.arr = next_dep; l.dep = next_dep - len; next_dep = l.dep;
    }
}

/// A short ride is a change for nothing when the stop of the next ride is in walking reach in time: "walk to the stop,
/// one stop by bus, wait 17 minutes" becomes "walk to the next ride's stop", often leaving later. Walks are estimated
/// as the app does (straight line, 25 % detour, 80 m a minute). A walk up to `HOP_EASY_WALK` always qualifies; a longer
/// one only when it is no longer than the walking and riding it replaces, and never beyond `HOP_MAX_WALK`.
const HOP_MAX_RIDE: i64 = 5 * 60;
const HOP_EASY_WALK: i64 = 15 * 60;
const HOP_MAX_WALK: i64 = 30 * 60;

fn meters(a: [f64; 2], b: [f64; 2]) -> f64 {
    let (la1, la2) = (a[0].to_radians(), b[0].to_radians());
    let h = ((la2 - la1) / 2.0).sin().powi(2) + la1.cos() * la2.cos() * ((b[1] - a[1]).to_radians() / 2.0).sin().powi(2);
    6_371_000.0 * 2.0 * h.sqrt().clamp(0.0, 1.0).asin()
}

fn walk_secs(a: [f64; 2], b: [f64; 2]) -> i64 { ((meters(a, b) * 1.25 / 80.0).ceil() as i64).max(1) * 60 }

/// Replaces hops in `it` by walks. `transfer` is the minimum change time in seconds. Returns whether it changed.
pub fn drop_hops(it: &mut Itin, transfer: i64) -> bool {
    let mut changed = false;
    'again: loop {
        let rides: Vec<usize> = it.legs.iter().enumerate().filter(|(_, l)| l.transit()).map(|(k, _)| k).collect();
        for w in 0..rides.len().saturating_sub(1) {
            let (i, j) = (rides[w], rides[w + 1]);
            let hop = &it.legs[i];
            if hop.arr - hop.dep > HOP_MAX_RIDE || hop.via.len() > 1 { continue; }
            let prev = if w > 0 { Some(rides[w - 1]) } else { None };
            let (start, from_name, from_id, from_pos) = match prev {
                Some(p) => { let l = &it.legs[p]; (Some(l.arr), l.to_name.clone(), l.to_id.clone(), l.to_pos) }
                None => { let l = &it.legs[0]; (None, l.from_name.clone(), l.from_id.clone(), l.from_pos) }
            };
            let next = &it.legs[j];
            if from_pos == [0.0, 0.0] || next.from_pos == [0.0, 0.0] { continue; }
            let walk = walk_secs(from_pos, next.from_pos);
            let first = prev.map(|p| p + 1).unwrap_or(0);
            let moving: i64 = it.legs[first..j].iter().map(|l| l.arr - l.dep).sum();
            if walk > HOP_MAX_WALK || (walk > HOP_EASY_WALK && walk > moving + 120) { continue; }
            let (dep, arr) = match start {
                Some(t) if t + walk.max(transfer) <= next.dep => (t, t + walk),
                Some(_) => continue,
                None => (next.dep - walk, next.dep),
            };
            let leg = Leg {
                mode: "WALK".into(), line: String::new(), to: String::new(), from_name, to_name: next.from_name.clone(), from_id,
                to_id: next.from_id.clone(), from_pos, to_pos: next.from_pos, dep, arr, via: vec![],
            };
            it.legs.splice(first..j, [leg]);
            it.dep = it.legs[0].dep;
            it.changes = it.legs.iter().filter(|l| l.transit()).count().saturating_sub(1) as u32;
            changed = true;
            continue 'again;
        }
        return changed;
    }
}

/// A forecast from a board. `t` is the expected departure or arrival in unix seconds.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Est { pub t: i64, pub cancelled: bool, pub src: &'static str }

/// One board: (planned minute, line) -> forecast. Only entries with a forecast or a cancellation.
pub type Slot = HashMap<(i64, String), Est>;

/// VRR `XML_DM_REQUEST` answer. With `arr` the board lists arrivals (`itdDateTimeDepArr=arr`).
pub fn parse_vrr_board(v: &Value, arr: bool) -> Result<Slot, String> {
    let (planned, estimated) = if arr { ("arrivalTimePlanned", "arrivalTimeEstimated") } else { ("departureTimePlanned", "departureTimeEstimated") };
    let mut out = Slot::new();
    // a quiet stop has no stopEvents at all
    if v["stopEvents"].is_null() && v["locations"].is_array() { return Ok(out); }
    for e in v["stopEvents"].as_array().ok_or("no stopEvents")? {
        let Some(p) = ts(&e[planned]) else { continue };
        let cancelled = e["isCancelled"].as_bool().unwrap_or(false)
            || e["realtimeStatus"].as_array().is_some_and(|a| a.iter().any(|x| x.as_str() == Some("CANCELLED")));
        let est = ts(&e[estimated]);
        if est.is_none() && !cancelled { continue; }
        let t = &e["transportation"];
        // trains come as "RB 89" in `number` and "RB89" in `disassembledName`; norm_line makes both one key
        for line in [&t["number"], &t["disassembledName"]].into_iter().filter_map(|x| x.as_str()) {
            out.insert(key(p, line), Est { t: est.unwrap_or(p), cancelled, src: "vrr" });
        }
    }
    Ok(out)
}

/// Transitous `/v5/stoptimes` answer, entries with `realTime` only.
pub fn parse_transitous_board(v: &Value, arr: bool) -> Result<Slot, String> {
    let (planned, expected) = if arr { ("scheduledArrival", "arrival") } else { ("scheduledDeparture", "departure") };
    let mut out = Slot::new();
    for e in v["stopTimes"].as_array().ok_or("no stopTimes")? {
        let cancelled = e["cancelled"].as_bool().unwrap_or(false);
        if !e["realTime"].as_bool().unwrap_or(false) && !cancelled { continue; }
        let (Some(p), Some(x)) = (ts(&e["place"][planned]), ts(&e["place"][expected])) else { continue };
        out.insert(key(p, e["routeShortName"].as_str().unwrap_or("")), Est { t: x, cancelled, src: "transitous" });
    }
    Ok(out)
}

/// Board lookup for one stop: departures or arrivals from `slot` on, 30 minutes wide.
#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct BoardReq { pub stop: String, pub arr: bool, pub slot: i64 }

pub const SLOT: i64 = 30 * 60;
/// Legs are looked up from this long ago (still riding) to this far ahead. Further out nobody has a forecast yet.
pub const BEHIND: i64 = 30 * 60;
pub const AHEAD: i64 = 90 * 60;
/// Upper bound on board requests per live frame, nearest legs first.
pub const MAX_BOARDS: usize = 12;

impl BoardReq {
    fn at(stop: &str, arr: bool, t: i64) -> Self {
        // a board starts a few minutes before the slot so a leg at the slot's start is surely on it
        BoardReq { stop: efa_id(stop), arr, slot: (t - 300).div_euclid(SLOT) * SLOT }
    }
    pub fn nrw(&self) -> bool { is_nrw(&self.stop) }
}

/// The boards a live frame needs: for each ride in the time window, the departure board at its first stop and the
/// arrival board at its last stop.
pub fn wanted(plan: &Plan, now: i64) -> Vec<BoardReq> {
    let mut out: Vec<(i64, BoardReq)> = Vec::new();
    for l in plan.itins.iter().flat_map(|i| &i.legs).filter(|l| l.transit()) {
        if l.arr < now - BEHIND || l.dep > now + AHEAD { continue; }
        if !l.from_id.is_empty() { out.push(((l.dep - now).abs(), BoardReq::at(&l.from_id, false, l.dep))); }
        if !l.to_id.is_empty() { out.push(((l.arr - now).abs(), BoardReq::at(&l.to_id, true, l.arr))); }
    }
    out.sort();
    let mut seen = std::collections::HashSet::new();
    out.into_iter().map(|(_, r)| r).filter(|r| seen.insert(r.clone())).take(MAX_BOARDS).collect()
}

/// Boards that answered, merged per (stop, arrival?) over their slots.
#[derive(Default)]
pub struct Boards(pub HashMap<(String, bool), Slot>);

impl Boards {
    pub fn add(&mut self, r: &BoardReq, slot: &Slot) {
        let m = self.0.entry((r.stop.clone(), r.arr)).or_default();
        for (k, v) in slot { m.entry(k.clone()).or_insert(*v); }
    }
    fn get(&self, stop: &str, arr: bool, planned: i64, line: &str) -> Option<Est> {
        self.0.get(&(efa_id(stop), arr))?.get(&key(planned, line)).copied()
    }
}

/// Live frame. Departure from the departure board, arrival from the arrival board. When only the departure is known
/// the arrival moves by the same delay, the usual case for a late train.
pub fn live(plan: &Plan, boards: &Boards) -> Live {
    let mut legs = Vec::new();
    for (i, it) in plan.itins.iter().enumerate() {
        for (k, l) in it.legs.iter().enumerate().filter(|(_, l)| l.transit()) {
            let d = boards.get(&l.from_id, false, l.dep, &l.line);
            let a = boards.get(&l.to_id, true, l.arr, &l.line);
            if d.is_none() && a.is_none() { continue; }
            let dep = d.map_or(l.dep, |e| e.t);
            let arr = a.map_or(l.arr + (dep - l.dep), |e| e.t).max(dep);
            let cancelled = d.is_some_and(|e| e.cancelled) || a.is_some_and(|e| e.cancelled);
            legs.push(LegLive { itin: i, leg: k, dep, arr, cancelled, src: d.map_or("arr", |e| e.src) });
        }
    }
    Live { t: "live", legs, gone: vec![] }
}

/// Europe/Berlin UTC offset in seconds: CEST from the last Sunday of March 01:00 UTC to the last Sunday of October 01:00 UTC.
pub fn berlin_offset(unix: i64) -> i64 {
    use chrono::{Datelike, TimeZone, Utc};
    let year = Utc.timestamp_opt(unix, 0).unwrap().year();
    let last_sunday = |month: u32| {
        let mut d = Utc.with_ymd_and_hms(year, month, 31, 1, 0, 0).unwrap();
        while d.weekday().num_days_from_sunday() != 0 { d -= chrono::Duration::days(1); }
        d.timestamp()
    };
    if unix >= last_sunday(3) && unix < last_sunday(10) { 7200 } else { 3600 }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn load(name: &str) -> Value { serde_json::from_str(&std::fs::read_to_string(format!("tests/fixtures/{name}")).unwrap()).unwrap() }

    #[test] fn parses_transitous_plan() {
        let p = parse_transitous(&load("transitous-plan.json"), 0).unwrap();
        assert!(!p.itins.is_empty());
        assert!(p.itins[0].legs.iter().any(|l| l.transit() && !l.line.is_empty()));
        assert!(p.itins.iter().all(|i| i.arr > i.dep));
        let ride = p.itins[0].legs.iter().find(|l| l.transit()).unwrap();
        assert!(ride.from_pos[0] > 50.0 && ride.from_pos[0] < 53.0);
        assert!(p.itins.iter().flat_map(|i| &i.legs).any(|l| !l.via.is_empty()));
    }

    fn ride(line: &str, from: &str, to: &str, dep: i64, arr: i64) -> Leg {
        Leg { mode: "REGIONAL_RAIL".into(), line: line.into(), to: "".into(), from_name: "".into(), to_name: "".into(), from_id: from.into(), to_id: to.into(), from_pos: [0.0; 2], to_pos: [0.0; 2], dep, arr, via: vec![] }
    }
    fn t(s: &str) -> i64 { chrono::DateTime::parse_from_rfc3339(s).unwrap().timestamp() }

    #[test] fn itin_times_are_scheduled() {
        let p = parse_transitous(&load("transitous-plan.json"), 0).unwrap();
        for it in &p.itins {
            assert_eq!(it.dep, it.legs.first().unwrap().dep);
            assert_eq!(it.arr, it.legs.last().unwrap().arr);
        }
    }

    /// RB89 Geseke 10:25 was +7 on 2026-11-07. The old trip-request frame missed it.
    #[test] fn live_from_boards() {
        let geseke = "de-DELFI_de:05974:32585";
        let hbf = "de-DELFI_de:05774:7000:3:5";
        let leg = ride("RB89", geseke, hbf, t("2026-11-07T09:25:00Z"), t("2026-11-07T09:40:00Z"));
        let p = Plan { t: "plan", at: 0, itins: vec![Itin { dep: leg.dep, arr: leg.arr, changes: 0, legs: vec![leg] }] };
        let reqs = wanted(&p, t("2026-11-07T09:21:00Z"));
        assert!(reqs.contains(&BoardReq { stop: "de:05974:32585".into(), arr: false, slot: t("2026-11-07T09:00:00Z") }));
        assert!(reqs.contains(&BoardReq { stop: "de:05774:7000".into(), arr: true, slot: t("2026-11-07T09:30:00Z") }));
        let mut b = Boards::default();
        b.add(&reqs[0], &parse_vrr_board(&load("vrr-dm-geseke-2026-11-07.json"), false).unwrap());
        b.add(&BoardReq { stop: "de:05774:7000".into(), arr: true, slot: 0 }, &parse_vrr_board(&load("vrr-dm-arr-pbhbf-2026-11-07.json"), true).unwrap());
        let l = live(&p, &b);
        assert_eq!(l.legs, vec![LegLive { itin: 0, leg: 0, dep: t("2026-11-07T09:32:00Z"), arr: t("2026-11-07T09:48:00Z"), cancelled: false, src: "vrr" }]);
    }

    #[test] fn arrival_follows_departure_delay() {
        let leg = ride("RB89", "de-DELFI_de:05974:32585", "de-DELFI_de:05774:7000", 6000, 6900);
        let p = Plan { t: "plan", at: 0, itins: vec![Itin { dep: 6000, arr: 6900, changes: 0, legs: vec![leg] }] };
        let mut b = Boards::default();
        let mut s = Slot::new();
        s.insert(key(6000, "RB 89"), Est { t: 6420, cancelled: false, src: "vrr" });
        b.add(&BoardReq { stop: "de:05974:32585".into(), arr: false, slot: 0 }, &s);
        let l = live(&p, &b);
        assert_eq!((l.legs[0].dep, l.legs[0].arr), (6420, 7320));
    }

    #[test] fn cancelled_leg_flagged() {
        let v = load("vrr-dm-arr-pbhbf-2026-11-07.json");
        let e = v["stopEvents"].as_array().unwrap().iter().find(|e| e["isCancelled"].as_bool() == Some(true)).unwrap();
        let (p, line) = (ts(&e["arrivalTimePlanned"]).unwrap(), e["transportation"]["number"].as_str().unwrap().to_string());
        let leg = Leg { mode: "BUS".into(), ..ride(&line, "de-DELFI_de:05774:1", "de-DELFI_de:05774:7000", p - 600, p) };
        let plan = Plan { t: "plan", at: 0, itins: vec![Itin { dep: p - 600, arr: p, changes: 0, legs: vec![leg] }] };
        let mut b = Boards::default();
        b.add(&BoardReq { stop: "de:05774:7000".into(), arr: true, slot: 0 }, &parse_vrr_board(&v, true).unwrap());
        assert!(live(&plan, &b).legs[0].cancelled);
    }

    #[test] fn transitous_board_keeps_realtime_only() {
        let s = parse_transitous_board(&load("transitous-stoptimes-geseke-2026-11-07.json"), false).unwrap();
        assert_eq!(s.get(&key(t("2026-11-07T09:25:00Z"), "RB89")).map(|e| e.t), Some(t("2026-11-07T09:32:00Z")));
        assert!(s.get(&key(t("2026-11-07T09:55:00Z"), "RB89")).is_none());
    }

    #[test] fn walks_follow_the_timetable() {
        let walk = |dep, arr| Leg { mode: "WALK".into(), ..ride("", "", "", dep, arr) };
        // live: train 10:25 left at 10:33, Transitous put the walk at 10:23 to 10:33
        let mut legs = vec![walk(1000, 1600), ride("RB89", "a", "b", 1120, 2020), walk(2500, 2620)];
        anchor_walks(&mut legs);
        assert_eq!((legs[0].dep, legs[0].arr), (520, 1120));
        assert_eq!((legs[2].dep, legs[2].arr), (2020, 2140));
    }

    #[test] fn hop_becomes_a_walk() {
        let walk = |dep, arr, from: [f64; 2], to: [f64; 2]| Leg { mode: "WALK".into(), from_pos: from, to_pos: to, ..ride("", "", "", dep, arr) };
        let at = |lat, lon, l: Leg, end: bool| if end { Leg { to_pos: [lat, lon], ..l } } else { Leg { from_pos: [lat, lon], ..l } };
        let (home, cherusker, tor, hbf) = ([51.72777, 8.76695], [51.72714, 8.76187], [51.72300, 8.75800], [51.71298, 8.74050]);
        // Marsenweg: walk to Cheruskerstraße, bus 3 one stop to Detmolder Tor at 18:03, wait, bus 6 at 18:22
        let three = at(tor[0], tor[1], at(cherusker[0], cherusker[1], ride("3", "c", "d", 64_860, 64_980), false), true);
        let six = at(hbf[0], hbf[1], at(tor[0], tor[1], ride("6", "d", "h", 66_120, 66_780), false), true);
        let mut it = Itin { dep: 64_200, arr: 66_780, changes: 1, legs: vec![walk(64_200, 64_860, home, cherusker), three, six] };
        assert!(drop_hops(&mut it, 300));
        assert_eq!(it.changes, 0);
        assert_eq!(it.legs.len(), 2);
        assert_eq!(it.legs[0].mode, "WALK");
        // the walk to Detmolder Tor ends when the 6 leaves, so the traveller sets off later than before
        assert_eq!(it.legs[0].arr, 66_120);
        assert!(it.dep > 64_200);
    }

    #[test] fn ride_that_saves_the_change_stays() {
        let a = Leg { from_pos: [51.72, 8.76], to_pos: [51.70, 8.70], ..ride("3", "a", "b", 1000, 1200) };
        let b = Leg { from_pos: [51.70, 8.70], to_pos: [51.6, 8.6], ..ride("6", "b", "c", 1500, 2000) };
        let mut it = Itin { dep: 1000, arr: 2000, changes: 1, legs: vec![a, b] };
        // 4.5 km is no walk
        assert!(!drop_hops(&mut it, 180));
    }

    #[test] fn far_legs_need_no_board() {
        let leg = ride("RB89", "de-DELFI_de:05974:32585", "de-DELFI_de:05774:7000", 100_000, 101_000);
        let p = Plan { t: "plan", at: 0, itins: vec![Itin { dep: 100_000, arr: 101_000, changes: 0, legs: vec![leg] }] };
        assert!(wanted(&p, 0).is_empty());
    }

    #[test] fn berlin_offset_switches() {
        assert_eq!(berlin_offset(1_794_000_000), 3600); // 2026-11-06
        assert_eq!(berlin_offset(1_784_000_000), 7200); // 2026-07-14
    }
}
