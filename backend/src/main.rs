//! Compact, cached front for German public transport data.
//! Timetable and search: Transitous (fast, nationwide). Live data: boards at each leg's stops, VRR/DELFI EFA in NRW
//! and Transitous elsewhere.
mod merge;
mod plan;
mod upstream;
mod watch;

use axum::{extract::{Path, Query, State}, http::{header, HeaderMap, StatusCode}, response::{sse::{Event, KeepAlive, Sse}, IntoResponse, Response}, routing::get, Json, Router};
use futures_util::{stream, Stream, StreamExt};
use std::convert::Infallible;
use tokio_stream::wrappers::BroadcastStream;
use moka::future::Cache;
use serde::Deserialize;
use std::{sync::Arc, time::Duration};
use tower_http::compression::CompressionLayer;

#[derive(Clone)]
pub struct AppState {
    http: reqwest::Client,
    stops: Cache<String, Arc<Vec<merge::Stop>>>,
    deps: Cache<String, Arc<merge::Board>>,
    plans: Cache<String, Arc<plan::Plan>>,
    /// router answers behind the variants of a search
    parts: Cache<String, Arc<plan::Plan>>,
    /// stop id -> platform id that Transitous accepts
    resolved: Cache<String, String>,
    lives: Cache<String, Arc<plan::Live>>,
    /// live-frame boards per (stop, dep or arr, 30-min slot)
    boards: Cache<plan::BoardReq, Arc<plan::Slot>>,
    watch: Arc<watch::Watch>,
}

/// Transitous ids for DELFI stops carry a prefix; callers may send the plain `de:05774:7000` form.
fn tid(s: &str) -> String {
    if s.starts_with("de:") { format!("de-DELFI_{s}") } else { s.to_string() }
}

/// Response with a weak ETag; a repeat request with the same state costs a 304 without a body.
fn etagged<T: serde::Serialize>(h: &HeaderMap, v: &T, max_age: u32) -> Response {
    use std::hash::{Hash, Hasher};
    let body = serde_json::to_vec(v).unwrap_or_default();
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    body.hash(&mut hasher);
    let tag = format!("W/\"{:x}\"", hasher.finish());
    let cc = format!("public, max-age={max_age}");
    if h.get(header::IF_NONE_MATCH).and_then(|v| v.to_str().ok()) == Some(tag.as_str()) {
        return (StatusCode::NOT_MODIFIED, [(header::ETAG, tag), (header::CACHE_CONTROL, cc)]).into_response();
    }
    ([(header::ETAG, tag), (header::CACHE_CONTROL, cc), (header::CONTENT_TYPE, "application/json".into())], body).into_response()
}

pub async fn board_cached(s: &AppState, stop: &str, n: u32) -> Result<Arc<merge::Board>, String> {
    s.deps.try_get_with(format!("{stop}|{n}"), async { upstream::board(&s.http, stop, n).await.map(Arc::new) })
        .await.map_err(|e| e.to_string())
}

#[derive(Deserialize)]
struct WatchQuery { stops: String }

/// SSE: a snapshot per stop on connect, then only changes. A comment line every 30 s keeps the connection alive.
async fn watch_handler(State(s): State<AppState>, Query(q): Query<WatchQuery>) -> ApiResult<Sse<impl Stream<Item = Result<Event, Infallible>>>> {
    let mut initial = Vec::new();
    let mut live = Vec::new();
    let mut last_err = None;
    for stop in q.stops.split(',').map(str::trim).filter(|x| !x.is_empty()).take(5) {
        match s.watch.subscribe(&s, &tid(stop)).await {
            Ok((snap, rx)) => { initial.push(snap); live.push(BroadcastStream::new(rx)); }
            Err(e) => { tracing::warn!("watch {stop}: {e}"); last_err = Some(e); }
        }
    }
    if initial.is_empty() { return Err(bad_gateway(last_err.unwrap_or_else(|| "no stops".into()))); }
    let ev = |d: &watch::Delta| Ok(Event::default().event("d").json_data(d).unwrap_or_else(|_| Event::default()));
    let init = stream::iter(initial.into_iter().map(|d| ev(&d)).collect::<Vec<_>>());
    let rest = stream::select_all(live).filter_map(move |r| async move { r.ok().map(|d| ev(&d)) });
    Ok(Sse::new(init.chain(rest)).keep_alive(KeepAlive::new().interval(Duration::from_secs(30))))
}

#[derive(Deserialize)]
struct StopsQuery { q: String }

#[derive(Deserialize)]
struct DepQuery { n: Option<u32> }

type ApiResult<T> = Result<T, (StatusCode, String)>;

fn bad_gateway(e: impl std::fmt::Display) -> (StatusCode, String) {
    let msg = e.to_string();
    // an unknown stop is the caller's problem, not an upstream outage
    let code = if msg.contains("404 Not Found") { StatusCode::NOT_FOUND } else { StatusCode::BAD_GATEWAY };
    (code, msg)
}

#[derive(Deserialize)]
struct PlanQuery {
    from: String, to: String, from_pos: Option<String>, to_pos: Option<String>, time: Option<i64>, n: Option<u32>,
    /// shortest change in minutes and the most changes the traveller accepts
    transfer: Option<u32>, changes: Option<u32>,
    /// `1`: `time` is the latest arrival
    arrive: Option<u8>,
    /// connections per main train: other buses to it and from it (1 = the router's best only, default 4)
    variants: Option<u32>,
}

struct Req {
    from: String, to: String, from_pos: Option<String>, to_pos: Option<String>, at: i64, n: u32, rules: upstream::Rules,
    arrive: bool, variants: usize,
}

fn tid_place(p: &str) -> String { if upstream::coords(p).is_some() { p.to_string() } else { tid(p) } }

impl PlanQuery {
    /// The time is floored to 5 minutes so that nearby requests share one cache entry.
    fn req(self) -> Req {
        let now = chrono::Utc::now().timestamp();
        Req { from: tid_place(&self.from), to: tid_place(&self.to), from_pos: self.from_pos, to_pos: self.to_pos, at: self.time.unwrap_or(now) / 300 * 300, n: self.n.unwrap_or(12).clamp(1, 20),
              rules: upstream::Rules { transfer_min: self.transfer.unwrap_or(0).min(60), max_changes: self.changes.map(|c| c.min(8)) },
              arrive: self.arrive == Some(1), variants: self.variants.unwrap_or(4).clamp(1, 6) as usize }
    }
}

impl Req {
    fn key(&self) -> String {
        format!("{}|{}|{}|{}|{}|{:?}|{}|{}", self.from, self.to, self.at, self.n, self.rules.transfer_min, self.rules.max_changes, self.arrive, self.variants)
    }
}

/// A departure search starts this long before the requested time, so the app also gets the last connection before it.
const LOOKBACK: i64 = 25 * 60;
/// An arrival search also returns connections arriving this long after the requested time.
const LOOKAHEAD: i64 = 30 * 60;
/// Feeders leaving this much earlier than the router's own choice only add waiting.
const FEEDER_SPREAD: i64 = 30 * 60;

/// Bus, tram, and underground rides are never the main ride when a train is part of the connection.
fn is_train(mode: &str) -> bool {
    !matches!(mode, "BUS" | "COACH" | "WALK" | "BIKE" | "CAR") && !mode.contains("TRAM") && !mode.contains("SUBWAY") && !mode.contains("METRO")
}

/// The ride a connection is built around: the longest train ride, else the longest ride. Same rule as the app.
fn trunk(legs: &[plan::Leg]) -> Option<usize> {
    let rides = || legs.iter().enumerate().filter(|(_, l)| l.transit());
    let longest = |it: &mut dyn Iterator<Item = (usize, &plan::Leg)>| it.max_by_key(|(_, l)| l.arr - l.dep).map(|(k, _)| k);
    longest(&mut rides().filter(|(_, l)| is_train(&l.mode))).or_else(|| longest(&mut rides()))
}

fn chain(legs: &[plan::Leg]) -> String {
    legs.iter().filter(|l| l.transit()).map(|l| format!("{}@{}", l.line, l.dep)).collect::<Vec<_>>().join(">")
}

/// The router returns one connection per main train, with the quickest bus to it and from it. People want to choose
/// when to leave and which bus to take, so the main trains near the requested time get the other buses that reach
/// them (feeders) and, for departures, the other buses leaving their arrival stop (onward). A search costs at most
/// six extra requests to Transitous, a shared community service, all in parallel and cached.
async fn expand(s: &AppState, from: &str, to: &str, mut p: plan::Plan, r: &Req) -> plan::Plan {
    use std::collections::HashSet;
    if r.variants <= 1 || p.itins.is_empty() { return p; }
    let transfer = (r.rules.transfer_min as i64 * 60).max(180);
    // three different main trains with a ride before them: that is where the traveller gets to choose when to leave
    let main = |i: usize| {
        let legs = &p.itins[i].legs;
        trunk(legs).filter(|&t| legs.iter().position(|l| l.transit()) != Some(t)).map(|t| format!("{}@{}", legs[t].line, legs[t].dep))
    };
    let pick = |order: Vec<usize>, n: usize| {
        let mut seen = HashSet::new();
        order.into_iter().filter(|&i| main(i).is_some_and(|k| seen.insert(k))).take(n).collect::<Vec<usize>>()
    };
    let wanted: Vec<usize> = if r.arrive {
        // the two latest arriving in time and the first one after
        let (mut before, mut after): (Vec<usize>, Vec<usize>) = (0..p.itins.len()).partition(|&i| p.itins[i].arr <= r.at);
        before.sort_by_key(|&i| std::cmp::Reverse((p.itins[i].arr, p.itins[i].dep)));
        after.sort_by_key(|&i| (p.itins[i].arr, std::cmp::Reverse(p.itins[i].dep)));
        pick(before, 2).into_iter().chain(pick(after, 1)).collect()
    } else {
        let mut later: Vec<usize> = (0..p.itins.len()).filter(|&i| p.itins[i].dep >= r.at).collect();
        later.sort_by_key(|&i| (p.itins[i].dep, p.itins[i].arr));
        pick(later, 3)
    };
    // (itinerary, trunk leg, is feeder, query from, query to, time)
    let mut jobs: Vec<(usize, usize, bool, String, String, i64)> = Vec::new();
    for &i in &wanted {
        let legs = &p.itins[i].legs;
        let Some(t) = trunk(legs) else { continue };
        let first_ride = legs.iter().position(|l| l.transit());
        let last_ride = legs.iter().rposition(|l| l.transit());
        if first_ride != Some(t) && !legs[t].from_id.is_empty() {
            jobs.push((i, t, true, from.to_string(), legs[t].from_id.clone(), legs[t].dep));
        }
        if !r.arrive && last_ride != Some(t) && !legs[t].to_id.is_empty() {
            jobs.push((i, t, false, legs[t].to_id.clone(), to.to_string(), legs[t].arr + transfer));
        }
    }
    let rules = r.rules;
    // variants are optional: when Transitous is slow the plain plan goes out instead of making the search wait
    let wait = if upstream::record_dir().is_some() { Duration::from_secs(3600) } else { Duration::from_millis(2500) };
    let results = tokio::time::timeout(wait, futures_util::future::join_all(jobs.iter().map(|(_, _, feeder, a, b, at)| {
        cached_plan(s, a.clone(), b.clone(), *at, *feeder, 8, rules)
    }))).await.unwrap_or_else(|_| jobs.iter().map(|_| None).collect());
    let mut seen: HashSet<String> = p.itins.iter().map(|i| chain(&i.legs)).collect();
    let mut extra = Vec::new();
    for ((i, t, feeder, ..), found) in jobs.iter().zip(results) {
        let Some(found) = found else { continue };
        let base = &p.itins[*i];
        let main = &base.legs[*t];
        let mut taken = 1;
        // feeders: the latest first, as the router lists arrive-by results
        let mut options: Vec<&plan::Itin> = found.itins.iter().collect();
        if *feeder { options.sort_by_key(|o| std::cmp::Reverse(o.dep)); }
        for o in options {
            let rides: Vec<&plan::Leg> = o.legs.iter().filter(|l| l.transit()).collect();
            let legs: Vec<plan::Leg> = if *feeder {
                // the change to the main train keeps the traveller's minimum, measured from the last ride's arrival
                let ready = rides.last().map(|l| l.arr + transfer).unwrap_or(o.arr).max(o.arr);
                if ready > main.dep || o.dep < base.dep - FEEDER_SPREAD || rides.len() > 2 { continue }
                o.legs.iter().cloned().chain(base.legs[*t..].iter().cloned()).collect()
            } else {
                let Some(first) = rides.first() else { continue };
                if rides.len() > 2 || first.dep < main.arr + transfer || o.arr > base.arr + 25 * 60 { continue }
                base.legs[..=*t].iter().cloned().chain(o.legs.iter().cloned()).collect()
            };
            if !seen.insert(chain(&legs)) { continue }
            let changes = legs.iter().filter(|l| l.transit()).count().saturating_sub(1) as u32;
            if rules.max_changes.is_some_and(|m| changes > m) { continue }
            let (dep, arr) = (legs.first().map(|l| l.dep).unwrap_or(base.dep), legs.last().map(|l| l.arr).unwrap_or(base.arr));
            extra.push(plan::Itin { dep, arr, changes, legs });
            taken += 1;
            if taken >= r.variants { break }
        }
    }
    p.itins.extend(extra);
    p.itins.sort_by_key(|i| (i.dep, i.arr));
    p
}

/// A plain router answer, shared between searches that ask for the same feeder or onward buses.
async fn cached_plan(s: &AppState, from: String, to: String, at: i64, arrive: bool, n: u32, rules: upstream::Rules) -> Option<Arc<plan::Plan>> {
    let key = format!("{from}|{to}|{at}|{arrive}|{n}|{}|{:?}", rules.transfer_min, rules.max_changes);
    s.parts.try_get_with(key, async {
        upstream::plan_static(&s.http, &from, &to, at, arrive, n, rules).await.map(Arc::new)
    }).await.ok()
}

async fn plan_static(s: &AppState, r: &Req) -> Result<Arc<plan::Plan>, (StatusCode, String)> {
    s.plans.try_get_with(r.key(), async {
        let f = s.resolved.get(&r.from).await.unwrap_or_else(|| r.from.clone());
        let t = s.resolved.get(&r.to).await.unwrap_or_else(|| r.to.clone());
        let at = if r.arrive { r.at + LOOKAHEAD } else { r.at - LOOKBACK };
        let (base, from_used, to_used) = match upstream::plan_static(&s.http, &f, &t, at, r.arrive, r.n, r.rules).await {
            Err(e) if e.contains("404") => {
                // unknown stop id: look for a platform of the same stop at the position the app sent
                let mut changed = false;
                let mut out = [f, t];
                for (i, (place, pos)) in [(&r.from, &r.from_pos), (&r.to, &r.to_pos)].into_iter().enumerate() {
                    if upstream::coords(place).is_some() { continue; }
                    if let Some(pos) = pos {
                        if let Some(id) = upstream::resolve_stop(&s.http, place, pos).await {
                            s.resolved.insert(place.clone(), id.clone()).await;
                            out[i] = id; changed = true;
                        }
                    }
                }
                if !changed { return Err(e); }
                let [f, t] = out;
                (upstream::plan_static(&s.http, &f, &t, at, r.arrive, r.n, r.rules).await?, f, t)
            }
            other => (other?, f, t),
        };
        let mut p = expand(s, &from_used, &to_used, base, r).await;
        let transfer = (r.rules.transfer_min as i64 * 60).max(120);
        let mut seen = std::collections::HashSet::new();
        for it in &mut p.itins { plan::drop_hops(it, transfer); }
        // a dropped hop can turn one connection into a copy of another
        p.itins.retain(|it| seen.insert((chain(&it.legs), it.dep, it.arr)));
        p.itins.sort_by_key(|i| (i.dep, i.arr));
        Ok::<_, String>(Arc::new(p))
    }).await.map_err(|e| bad_gateway(e))
}

/// Board lookups run in parallel; the frame goes out with whatever answered within `LIVE_WAIT`.
const LIVE_WAIT: Duration = Duration::from_millis(3000);

async fn plan_live(s: &AppState, r: &Req, p: &plan::Plan) -> Result<Arc<plan::Live>, (StatusCode, String)> {
    s.lives.try_get_with(r.key(), async {
        let reqs = plan::wanted(p, chrono::Utc::now().timestamp());
        let fetch = reqs.iter().map(|b| async move {
            let slot = s.boards.try_get_with(b.clone(), async { upstream::slot_board(&s.http, b).await.map(Arc::new) }).await;
            (b, slot)
        });
        let mut boards = plan::Boards::default();
        let (mut ok, mut last_err) = (0, None);
        let answered = tokio::time::timeout(LIVE_WAIT, futures_util::future::join_all(fetch)).await.unwrap_or_default();
        for (b, slot) in answered {
            match slot {
                Ok(slot) => { boards.add(b, &slot); ok += 1; }
                Err(e) => { tracing::warn!("board {} {}: {e}", b.stop, if b.arr { "arr" } else { "dep" }); last_err = Some(e.to_string()); }
            }
        }
        if ok == 0 && !reqs.is_empty() { return Err(last_err.unwrap_or_else(|| "boards timed out".into())); }
        Ok::<_, String>(Arc::new(plan::live(p, &boards)))
    }).await.map_err(|e| bad_gateway(e))
}

async fn plan_static_handler(State(s): State<AppState>, h: HeaderMap, Query(q): Query<PlanQuery>) -> ApiResult<Response> {
    let p = plan_static(&s, &q.req()).await?;
    Ok(etagged(&h, &*p, 600))
}

async fn plan_live_handler(State(s): State<AppState>, h: HeaderMap, Query(q): Query<PlanQuery>) -> ApiResult<Response> {
    let r = q.req();
    let p = plan_static(&s, &r).await?;
    let l = plan_live(&s, &r, &p).await?;
    Ok(etagged(&h, &*l, 20))
}

/// NDJSON: frame 1 (timetable) as soon as Transitous answers, frame 2 (live) when the boards for its legs answer.
async fn plan_stream(State(s): State<AppState>, Query(q): Query<PlanQuery>) -> ApiResult<impl IntoResponse> {
    let r = q.req();
    let (tx, rx) = tokio::sync::mpsc::channel::<Result<bytes::Bytes, std::convert::Infallible>>(2);
    let first = plan_static(&s, &r).await?;
    tokio::spawn(async move {
        let send = |v: &dyn erased::Json| { let mut b = v.line(); b.push(b'\n'); bytes::Bytes::from(b) };
        let _ = tx.send(Ok(send(&*first))).await;
        match plan_live(&s, &r, &first).await {
            Ok(l) => { let _ = tx.send(Ok(send(&*l))).await; }
            Err(e) => tracing::warn!("live frame failed: {}", e.1),
        }
    });
    Ok(([(header::CONTENT_TYPE, "application/x-ndjson")], axum::body::Body::from_stream(tokio_stream::wrappers::ReceiverStream::new(rx))))
}

mod erased {
    pub trait Json { fn line(&self) -> Vec<u8>; }
    impl<T: serde::Serialize> Json for T { fn line(&self) -> Vec<u8> { serde_json::to_vec(self).unwrap_or_default() } }
}

async fn stops(State(s): State<AppState>, Query(q): Query<StopsQuery>) -> ApiResult<impl IntoResponse> {
    let key = q.q.trim().to_lowercase();
    if key.len() < 2 { return Ok((cache_header(60), Json(Vec::<merge::Stop>::new()))); }
    // try_get_with makes concurrent requests for one key share a single upstream call
    let list = s.stops.try_get_with(key.clone(), async { upstream::stops(&s.http, &key).await.map(Arc::new) })
        .await.map_err(|e| bad_gateway(e))?;
    Ok((cache_header(3600), Json((*list).clone())))
}

async fn departures(State(s): State<AppState>, h: HeaderMap, Path(stop): Path<String>, Query(q): Query<DepQuery>) -> ApiResult<Response> {
    let n = q.n.unwrap_or(40).clamp(1, 80);
    let board = board_cached(&s, &tid(&stop), n).await.map_err(bad_gateway)?;
    Ok(etagged(&h, &*board, 15))
}

fn cache_header(secs: u32) -> [(header::HeaderName, String); 1] {
    [(header::CACHE_CONTROL, format!("public, max-age={secs}"))]
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();
    let http = reqwest::Client::builder()
        .user_agent("brunel-backend/0.1")
        .timeout(Duration::from_secs(6))
        .pool_idle_timeout(Duration::from_secs(60))
        .tcp_nodelay(true)
        .build().expect("http client");
    let state = AppState {
        http,
        stops: Cache::builder().max_capacity(50_000).time_to_live(Duration::from_secs(3600)).build(),
        deps: Cache::builder().max_capacity(20_000).time_to_live(Duration::from_secs(15)).build(),
        resolved: Cache::builder().max_capacity(50_000).time_to_live(Duration::from_secs(86_400)).build(),
        plans: Cache::builder().max_capacity(20_000).time_to_live(Duration::from_secs(600)).build(),
        parts: Cache::builder().max_capacity(20_000).time_to_live(Duration::from_secs(600)).build(),
        lives: Cache::builder().max_capacity(20_000).time_to_live(Duration::from_secs(20)).build(),
        boards: Cache::builder().max_capacity(20_000).time_to_live(Duration::from_secs(20)).build(),
        watch: Arc::new(watch::Watch::default()),
    };
    let app = Router::new()
        .route("/health", get(|| async { "ok" }))
        .route("/v1/stops", get(stops))
        .route("/v1/departures/{stop}", get(departures))
        .route("/v1/watch", get(watch_handler))
        .route("/v1/plan", get(plan_stream))
        .route("/v1/plan/static", get(plan_static_handler))
        .route("/v1/plan/live", get(plan_live_handler))
        .layer(CompressionLayer::new())
        .with_state(state);
    let addr = std::env::var("BIND").unwrap_or_else(|_| "0.0.0.0:8080".into());
    tracing::info!("listening on {addr}");
    axum::serve(tokio::net::TcpListener::bind(&addr).await.expect("bind"), app).await.expect("serve");
}
