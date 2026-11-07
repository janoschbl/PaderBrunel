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

pub async fn plan_static(http: &reqwest::Client, from: &str, to: &str, at: i64, n: u32) -> Result<Plan, String> {
    let time = chrono::DateTime::from_timestamp(at, 0).ok_or("bad time")?.to_rfc3339_opts(chrono::SecondsFormat::Secs, true);
    let n = n.to_string();
    let v: Value = hedged(|| async {
        http.get(format!("{TRANSITOUS}/v5/plan"))
            .query(&[("fromPlace", from), ("toPlace", to), ("time", time.as_str()), ("numItineraries", n.as_str())])
            .send().await.map_err(|e| e.to_string())?
            .error_for_status().map_err(|e| e.to_string())?.json::<Value>().await.map_err(|e| e.to_string())
    }).await?;
    plan::parse_transitous(&v, at)
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
