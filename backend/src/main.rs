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
struct PlanQuery { from: String, to: String, from_pos: Option<String>, to_pos: Option<String>, time: Option<i64>, n: Option<u32> }

struct Req { from: String, to: String, from_pos: Option<String>, to_pos: Option<String>, at: i64, n: u32 }

fn tid_place(p: &str) -> String { if upstream::coords(p).is_some() { p.to_string() } else { tid(p) } }

impl PlanQuery {
    /// The time is floored to 5 minutes so that nearby requests share one cache entry.
    fn req(self) -> Req {
        let now = chrono::Utc::now().timestamp();
        Req { from: tid_place(&self.from), to: tid_place(&self.to), from_pos: self.from_pos, to_pos: self.to_pos, at: self.time.unwrap_or(now) / 300 * 300, n: self.n.unwrap_or(12).clamp(1, 20) }
    }
}

impl Req {
    fn key(&self) -> String { format!("{}|{}|{}|{}", self.from, self.to, self.at, self.n) }
}

/// The search starts this long before the requested time, so the app also gets the last connection before it.
const LOOKBACK: i64 = 25 * 60;

/// Transitous returns one itinerary per train: the fastest onward bus only. People want a choice of buses, so for
/// each itinerary with a change everything after the second-to-last ride is replaced by the other options leaving
/// its arrival stop soon after.
async fn expand(s: &AppState, to: &str, mut p: plan::Plan, anchor: i64) -> plan::Plan {
    use std::collections::HashSet;
    let mut jobs = Vec::new();
    // Only the connection before the requested time and the two after it get variants. Transitous is a shared
    // community service, so a search costs at most three extra requests.
    let first_after = p.itins.iter().position(|i| i.dep >= anchor).unwrap_or(p.itins.len());
    let wanted = first_after.saturating_sub(1)..(first_after + 2).min(p.itins.len());
    for (i, it) in p.itins.iter().enumerate().filter(|(i, _)| wanted.contains(i)) {
        let rides: Vec<usize> = it.legs.iter().enumerate().filter(|(_, l)| l.transit()).map(|(k, _)| k).collect();
        if rides.len() < 2 { continue; }
        // the onward search starts where the previous ride arrives, so its own transfer walk replaces ours and the
        // joined connection never has two walks in a row
        let prev = rides[rides.len() - 2];
        jobs.push((i, prev + 1, it.legs[prev].arr + 180, it.legs[prev].to_id.clone()));
    }
    // variants are optional: when Transitous is slow the plain plan goes out instead of making the search wait
    let results = tokio::time::timeout(Duration::from_millis(2500), futures_util::future::join_all(jobs.iter().map(|(_, _, earliest, from)| {
        let (http, to, from, earliest) = (s.http.clone(), to.to_string(), from.clone(), *earliest);
        async move { if from.is_empty() { None } else { upstream::plan_static(&http, &from, &to, earliest, 6).await.ok() } }
    }))).await.unwrap_or_else(|_| jobs.iter().map(|_| None).collect());
    let chain = |legs: &[plan::Leg]| legs.iter().filter(|l| l.transit()).map(|l| format!("{}@{}", l.line, l.dep)).collect::<Vec<_>>().join(">");
    let mut seen: HashSet<String> = p.itins.iter().map(|i| chain(&i.legs)).collect();
    let mut extra = Vec::new();
    for ((i, k, earliest, _), onward) in jobs.iter().zip(results) {
        let Some(on) = onward else { continue };
        let base = &p.itins[*i];
        let mut taken = 0;
        for o in &on.itins {
            let rides = o.legs.iter().filter(|l| l.transit()).count();
            let Some(first) = o.legs.iter().find(|l| l.transit()) else { continue };
            if rides > 2 || first.dep < *earliest || o.arr > base.arr + 25 * 60 { continue }
            let legs: Vec<plan::Leg> = base.legs[..*k].iter().cloned().chain(o.legs.iter().cloned()).collect();
            if !seen.insert(chain(&legs)) { continue }
            let changes = legs.iter().filter(|l| l.transit()).count().saturating_sub(1) as u32;
            extra.push(plan::Itin { dep: base.dep, arr: o.arr, changes, legs });
            taken += 1;
            if taken >= 4 { break }
        }
    }
    p.itins.extend(extra);
    p.itins.sort_by_key(|i| (i.dep, i.arr));
    p
}

async fn plan_static(s: &AppState, r: &Req) -> Result<Arc<plan::Plan>, (StatusCode, String)> {
    s.plans.try_get_with(r.key(), async {
        let f = s.resolved.get(&r.from).await.unwrap_or_else(|| r.from.clone());
        let t = s.resolved.get(&r.to).await.unwrap_or_else(|| r.to.clone());
        let (base, to_used) = match upstream::plan_static(&s.http, &f, &t, r.at - LOOKBACK, r.n).await {
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
                (upstream::plan_static(&s.http, &out[0], &out[1], r.at - LOOKBACK, r.n).await?, out[1].clone())
            }
            other => (other?, t),
        };
        Ok::<_, String>(Arc::new(expand(s, &to_used, base, r.at).await))
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
