//! Push for live data. One shared poller per stop, however many phones watch it.
//! A phone gets one snapshot of the delayed departures, then only changes.
use crate::{merge::Board, AppState};
use serde::Serialize;
use std::{collections::{HashMap, HashSet}, sync::Arc, time::Duration};
use tokio::sync::{broadcast, Mutex};

/// Server-side poll interval per watched stop. Phones do not poll at all.
pub const POLL: Duration = Duration::from_secs(20);

type Key = (i64, String);

/// `set` entries are `[planned minute, line, expected unix seconds]`, `clear` entries `[planned minute, line]`.
#[derive(Clone, Serialize, Debug, PartialEq, Default)]
pub struct Delta {
    pub stop: String,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub snap: bool,
    pub set: Vec<(i64, String, i64)>,
    pub clear: Vec<Key>,
}

impl Delta { pub fn is_empty(&self) -> bool { self.set.is_empty() && self.clear.is_empty() } }

pub struct StopWatch { tx: broadcast::Sender<Arc<Delta>>, last: Mutex<HashMap<Key, i64>> }

#[derive(Default)]
pub struct Watch { map: Mutex<HashMap<String, Arc<StopWatch>>> }

/// Delayed departures of a board, keyed by planned minute and line.
fn delayed(b: &Board) -> HashMap<Key, i64> {
    b.deps.iter().filter(|d| d.expected != d.planned).map(|d| (crate::merge::key(d.planned, &d.line), d.expected)).collect()
}

/// Changes between the last pushed state and the new board. A cleared entry is only reported while the
/// departure is still on the board, a departed one disappears on the phone anyway.
pub fn diff(stop: &str, last: &HashMap<Key, i64>, board: &Board) -> Delta {
    let new = delayed(board);
    let on_board: HashSet<Key> = board.deps.iter().map(|d| crate::merge::key(d.planned, &d.line)).collect();
    let mut set: Vec<_> = new.iter().filter(|(k, v)| last.get(*k) != Some(*v)).map(|(k, v)| (k.0, k.1.clone(), *v)).collect();
    let mut clear: Vec<_> = last.keys().filter(|k| !new.contains_key(*k) && on_board.contains(*k)).cloned().collect();
    set.sort(); clear.sort();
    Delta { stop: stop.into(), snap: false, set, clear }
}

pub fn snapshot(stop: &str, state: &HashMap<Key, i64>) -> Delta {
    let mut set: Vec<_> = state.iter().map(|(k, v)| (k.0, k.1.clone(), *v)).collect();
    set.sort();
    Delta { stop: stop.into(), snap: true, set, clear: vec![] }
}

impl Watch {
    /// Registers a listener. The returned snapshot comes from a fresh (cached 15 s) board.
    pub async fn subscribe(self: &Arc<Self>, s: &AppState, stop: &str) -> Result<(Delta, broadcast::Receiver<Arc<Delta>>), String> {
        let (sw, created) = {
            let mut m = self.map.lock().await;
            match m.get(stop) {
                Some(x) => (x.clone(), false),
                None => {
                    let (tx, _) = broadcast::channel(16);
                    let x = Arc::new(StopWatch { tx, last: Mutex::new(HashMap::new()) });
                    m.insert(stop.to_string(), x.clone());
                    (x, true)
                }
            }
        };
        let board = match crate::board_cached(s, stop, 40).await {
            Ok(b) => b,
            Err(e) => { if created { self.map.lock().await.remove(stop); } return Err(e); }
        };
        // bring the shared state up to date, others hear about changes
        self.apply(&sw, stop, &board).await;
        if created { self.spawn_poller(s.clone(), stop.to_string(), sw.clone()); }
        // subscribing under the state lock means no change can slip between snapshot and stream
        let last = sw.last.lock().await;
        let rx = sw.tx.subscribe();
        Ok((snapshot(stop, &last), rx))
    }

    async fn apply(&self, sw: &StopWatch, stop: &str, board: &Board) {
        let mut last = sw.last.lock().await;
        let d = diff(stop, &last, board);
        *last = delayed(board);
        if !d.is_empty() { let _ = sw.tx.send(Arc::new(d)); }
    }

    fn spawn_poller(self: &Arc<Self>, s: AppState, stop: String, sw: Arc<StopWatch>) {
        let this = self.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(POLL).await;
                if sw.tx.receiver_count() == 0 {
                    this.map.lock().await.remove(&stop);
                    return;
                }
                match crate::board_cached(&s, &stop, 40).await {
                    Ok(b) => this.apply(&sw, &stop, &b).await,
                    Err(e) => tracing::warn!("watch poll {stop}: {e}"),
                }
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::merge::Dep;
    fn dep(p: i64, e: i64, line: &str) -> Dep { Dep { planned: p, expected: e, live: e != p, line: line.into(), to: "".into(), mode: "BUS".into(), cancelled: false } }
    fn board(deps: Vec<Dep>) -> Board { Board { stop: "s".into(), src: "vrr", degraded: false, deps } }

    #[test] fn snapshot_lists_only_delayed() {
        let s = snapshot("s", &delayed(&board(vec![dep(600, 600, "1"), dep(660, 780, "2")])));
        assert_eq!(s.set, vec![(11, "2".to_string(), 780)]);
        assert!(s.snap);
    }

    #[test] fn diff_reports_changes_only() {
        let b1 = board(vec![dep(600, 720, "1"), dep(660, 660, "2")]);
        let last = delayed(&b1);
        assert!(diff("s", &last, &b1).is_empty());
        // line 1 now later, line 2 newly delayed
        let b2 = board(vec![dep(600, 780, "1"), dep(660, 720, "2")]);
        let d = diff("s", &last, &b2);
        assert_eq!(d.set.len(), 2);
        assert!(d.clear.is_empty());
    }

    #[test] fn diff_clears_recovered_but_not_departed() {
        let last: HashMap<Key, i64> = [((10, "1".to_string()), 720), ((11, "2".to_string()), 780)].into_iter().collect();
        // line 1 is back on time and still listed, line 2 left the board
        let b = board(vec![dep(600, 600, "1")]);
        let d = diff("s", &last, &b);
        assert_eq!(d.clear, vec![(10, "1".to_string())]);
        assert!(d.set.is_empty());
    }
}
