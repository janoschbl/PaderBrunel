use crate::merge::{self, Board, Dep, Stop};
use crate::plan::{self, BoardReq, Plan, Slot};
use serde_json::Value;
use std::{collections::HashMap, time::Duration};

const TRANSITOUS: &str = "https://api.transitous.org/api";
const VRR_DM: &str = "https://efa.vrr.de/standard/XML_DM_REQUEST";
const VRR_TIMEOUT: Duration = Duration::from_millis(1500);
/// A slot board lists up to 60 events and takes VRR longer than the live board.
const VRR_SLOT_TIMEOUT: Duration = Duration::from_millis(2800);

/// Transitous answers in 0.2 s or hangs for 15 to 30 s, at random. If the first request has not answered after
/// `HEDGE`, an identical second one goes out and whichever answers first wins. Costs one extra request only when slow.
const HEDGE: Duration = Duration::from_millis(1000);

async fn hedged<T, F, Fut>(f: F) -> Result<T, String>
where F: Fn() -> Fut, Fut: std::future::Future<Output = Result<T, String>> {
    let first = f();
    tokio::pin!(first);
    tokio::select! {
        r = &mut first => r,
        _ = tokio::time::sleep(HEDGE) => {
            let second = f();
            tokio::pin!(second);
            tokio::select! {
                r = &mut first => match r { Ok(v) => Ok(v), Err(_) => second.await },
                r = &mut second => match r { Ok(v) => Ok(v), Err(_) => first.await },
            }
        }
    }
}

fn ts(v: &Value) -> Option<i64> {
    chrono::DateTime::parse_from_rfc3339(v.as_str()?).ok().map(|t| t.timestamp())
}

pub async fn stops(http: &reqwest::Client, q: &str) -> Result<Vec<Stop>, String> {
    let v: Value = http.get(format!("{TRANSITOUS}/v1/geocode"))
        .query(&[("text", q), ("type", "STOP")]).send().await.map_err(|e| e.to_string())?
        .error_for_status().map_err(|e| e.to_string())?.json().await.map_err(|e| e.to_string())?;
    Ok(v.as_array().map(|a| a.iter().filter_map(|x| Some(Stop {
        id: x["id"].as_str()?.into(), name: x["name"].as_str()?.into(),
        lat: x["lat"].as_f64()?, lon: x["lon"].as_f64()?,
    })).take(10).collect()).unwrap_or_default())
}

async fn transitous(http: &reqwest::Client, stop: &str, n: u32) -> Result<Vec<Dep>, String> {
    let n = n.to_string();
    let v: Value = hedged(|| async {
        http.get(format!("{TRANSITOUS}/v5/stoptimes")).query(&[("stopId", stop), ("n", n.as_str())]).send().await.map_err(|e| e.to_string())?
            .error_for_status().map_err(|e| e.to_string())?.json::<Value>().await.map_err(|e| e.to_string())
    }).await?;
    let list = v["stopTimes"].as_array().ok_or("no stopTimes")?;
    Ok(list.iter().filter_map(|e| {
        let planned = ts(&e["place"]["scheduledDeparture"])?;
        let expected = ts(&e["place"]["departure"]).unwrap_or(planned);
        Some(Dep {
            planned, expected, live: e["realTime"].as_bool().unwrap_or(false),
            line: e["routeShortName"].as_str().unwrap_or("").into(),
            to: e["headsign"].as_str().unwrap_or("").into(),
            mode: e["mode"].as_str().unwrap_or("").into(),
            cancelled: e["cancelled"].as_bool().unwrap_or(false),
        })
    }).collect())
}

/// VRR departure monitor, reduced to (planned minute, line) -> expected unix seconds. Only entries with an estimate.
async fn vrr(http: &reqwest::Client, stop: &str) -> Result<HashMap<(i64, String), i64>, String> {
    let v: Value = http.get(VRR_DM).timeout(VRR_TIMEOUT)
        .query(&[("outputFormat", "rapidJSON"), ("type_dm", "stop"), ("name_dm", &merge::efa_id(stop)),
                 ("mode", "direct"), ("useRealtime", "1"), ("limit", "80")])
        .send().await.map_err(|e| e.to_string())?
        .error_for_status().map_err(|e| e.to_string())?.json().await.map_err(|e| e.to_string())?;
    let mut out = HashMap::new();
    for e in v["stopEvents"].as_array().ok_or("no stopEvents")? {
        let (Some(p), Some(est)) = (ts(&e["departureTimePlanned"]), ts(&e["departureTimeEstimated"])) else { continue };
        let line = e["transportation"]["number"].as_str().unwrap_or("");
        out.insert(merge::key(p, line), est);
    }
    Ok(out)
}

pub async fn board(http: &reqwest::Client, stop: &str, n: u32) -> Result<Board, String> {
    if !merge::is_nrw(stop) {
        let deps = transitous(http, stop, n).await?;
        return Ok(Board { stop: stop.into(), src: "transitous", degraded: false, deps });
    }
    // both upstreams run at the same time, so VRR adds no latency beyond its own
    let (t, r) = tokio::join!(transitous(http, stop, n), vrr(http, stop));
    let deps = t?;
    Ok(match r {
        Ok(map) => Board { stop: stop.into(), src: "vrr", degraded: false, deps: merge::apply_vrr(deps, &map) },
        Err(e) => {
            tracing::warn!("vrr failed for {stop}: {e}");
            Board { stop: stop.into(), src: "transitous", degraded: true, deps }
        }
    })
}

/// A place is `lat,lon` or a Transitous stop id.
pub fn coords(place: &str) -> Option<(f64, f64)> {
    let (a, b) = place.split_once(',')?;
    Some((a.trim().parse().ok()?, b.trim().parse().ok()?))
}

/// What the traveller accepts: the shortest change in minutes and the most changes.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Rules { pub transfer_min: u32, pub max_changes: Option<u32> }

pub async fn plan_static(http: &reqwest::Client, from: &str, to: &str, at: i64, arrive: bool, n: u32, rules: Rules) -> Result<Plan, String> {
    let time = chrono::DateTime::from_timestamp(at, 0).ok_or("bad time")?.to_rfc3339_opts(chrono::SecondsFormat::Secs, true);
    // without a minimum, Transitous plans a five minute walk to a train that leaves six minutes after the bus
    // arrives, and the app then drops the connection for breaking the traveller's own minimum
    let mut q: Vec<(&str, String)> = vec![("fromPlace", from.into()), ("toPlace", to.into()), ("time", time), ("numItineraries", n.to_string())];
    if arrive { q.push(("arriveBy", "true".into())); }
    if rules.transfer_min > 0 { q.push(("minTransferTime", rules.transfer_min.to_string())); }
    if let Some(m) = rules.max_changes { q.push(("maxTransfers", m.to_string())); }
    let send = || async {
        let r = http.get(format!("{TRANSITOUS}/v5/plan")).query(&q).send().await.map_err(|e| e.to_string())?;
        if r.status() == reqwest::StatusCode::TOO_MANY_REQUESTS { return Err("429".to_string()); }
        r.error_for_status().map_err(|e| e.to_string())?.json::<Value>().await.map_err(|e| e.to_string())
    };
    let v = match record_dir() { Some(dir) => recorded(&dir, &q, send).await?, None => hedged(send).await? };
    plan::parse_transitous(&v, at)
}

/// Benchmarks set `BRUNEL_RECORD_DIR`: router answers are then kept on disk per query and replayed, so tuning runs
/// compare the same timetable and do not load Transitous again.
pub fn record_dir() -> Option<String> { std::env::var("BRUNEL_RECORD_DIR").ok() }

/// While recording, requests to Transitous go out one at a time, at most one per second, and wait out a 429.
async fn recorded<F, Fut>(dir: &str, q: &[(&str, String)], fetch: F) -> Result<Value, String>
where F: Fn() -> Fut, Fut: std::future::Future<Output = Result<Value, String>> {
    static GATE: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());
    let name: String = q.iter().map(|(k, v)| format!("{k}={v}")).collect::<Vec<_>>().join("&")
        .chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '.' { c } else { '_' }).collect();
    let path = std::path::Path::new(dir).join(format!("{name}.json"));
    if let Ok(text) = std::fs::read_to_string(&path) { return serde_json::from_str(&text).map_err(|e| e.to_string()); }
    let _turn = GATE.lock().await;
    let mut wait = Duration::from_secs(10);
    let v = loop {
        tokio::time::sleep(Duration::from_millis(1000)).await;
        match fetch().await {
            Err(e) if e == "429" && wait < Duration::from_secs(300) => { tracing::warn!("429, waiting {wait:?}"); tokio::time::sleep(wait).await; wait *= 2; }
            other => break other?,
        }
    };
    let _ = std::fs::write(&path, v.to_string());
    Ok(v)
}

fn rfc3339(t: i64) -> Result<String, String> {
    Ok(chrono::DateTime::from_timestamp(t, 0).ok_or("bad time")?.to_rfc3339_opts(chrono::SecondsFormat::Secs, true))
}

/// One board for the live frame. Inside NRW the VRR EFA, which agreed with the operator where Transitous did not;
/// outside NRW Transitous. Asks a few minutes before the slot so the slot's first legs are on it.
pub async fn slot_board(http: &reqwest::Client, r: &BoardReq) -> Result<Slot, String> {
    if r.nrw() {
        let local = chrono::DateTime::from_timestamp(r.slot + plan::berlin_offset(r.slot), 0).ok_or("bad time")?;
        let v: Value = http.get(VRR_DM).timeout(VRR_SLOT_TIMEOUT)
            .query(&[("outputFormat", "rapidJSON"), ("type_dm", "stop"), ("name_dm", r.stop.as_str()), ("mode", "direct"),
                     ("useRealtime", "1"), ("limit", "60"), ("itdDateTimeDepArr", if r.arr { "arr" } else { "dep" }),
                     ("itdDate", &local.format("%Y%m%d").to_string()), ("itdTime", &local.format("%H%M").to_string())])
            .send().await.map_err(|e| e.to_string())?
            .error_for_status().map_err(|e| e.to_string())?.json().await.map_err(|e| e.to_string())?;
        plan::parse_vrr_board(&v, r.arr)
    } else {
        let (stop, time) = (format!("de-DELFI_{}", r.stop), rfc3339(r.slot)?);
        let v: Value = hedged(|| async {
            http.get(format!("{TRANSITOUS}/v5/stoptimes"))
                .query(&[("stopId", stop.as_str()), ("time", time.as_str()), ("n", "60"), ("arriveBy", if r.arr { "true" } else { "false" })])
                .send().await.map_err(|e| e.to_string())?
                .error_for_status().map_err(|e| e.to_string())?.json::<Value>().await.map_err(|e| e.to_string())
        }).await?;
        plan::parse_transitous_board(&v, r.arr)
    }
}

/// Transitous knows some stops only by platform id (`de:05774:7063:0:1`). Finds a platform of the same stop near `pos`.
pub async fn resolve_stop(http: &reqwest::Client, place: &str, pos: &str) -> Option<String> {
    let want = place.trim_start_matches("de-DELFI_");
    let v: Value = http.get(format!("{TRANSITOUS}/v1/reverse-geocode"))
        .query(&[("place", pos), ("type", "STOP")]).send().await.ok()?.error_for_status().ok()?.json().await.ok()?;
    v.as_array()?.iter().filter_map(|x| x["id"].as_str()).find(|id| id.trim_start_matches("de-DELFI_").starts_with(want)).map(String::from)
}
