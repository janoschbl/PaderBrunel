use serde::Serialize;
use std::collections::HashMap;

#[derive(Clone, Serialize, Debug, PartialEq)]
pub struct Stop { pub id: String, pub name: String, pub lat: f64, pub lon: f64 }

/// Times are unix seconds. `src` says where `expected` comes from.
#[derive(Clone, Serialize, Debug, PartialEq)]
pub struct Dep {
    pub planned: i64,
    pub expected: i64,
    pub live: bool,
    pub line: String,
    pub to: String,
    pub mode: String,
    pub cancelled: bool,
}

#[derive(Clone, Serialize, Debug, PartialEq)]
pub struct Board {
    pub stop: String,
    /// "vrr" when VRR live data was applied, "transitous" otherwise
    pub src: &'static str,
    /// true when VRR was expected but unavailable
    pub degraded: bool,
    pub deps: Vec<Dep>,
}

/// DELFI ids of NRW start with de:05 (state key 05). Transitous prefixes them with `de-DELFI_`.
pub fn is_nrw(stop: &str) -> bool {
    stop.trim_start_matches("de-DELFI_").starts_with("de:05")
}

/// `de-DELFI_de:05774:7063:0:1` -> `de:05774:7063`, the id EFA understands.
pub fn efa_id(stop: &str) -> String {
    stop.trim_start_matches("de-DELFI_").split(':').take(3).collect::<Vec<_>>().join(":")
}

pub fn norm_line(s: &str) -> String { s.chars().filter(|c| !c.is_whitespace()).collect::<String>().to_lowercase() }

/// Key to match one departure across sources: planned minute plus line.
pub fn key(planned: i64, line: &str) -> (i64, String) { (planned / 60, norm_line(line)) }

/// Applies VRR estimates to the Transitous timetable. Transitous live data is dropped inside NRW because it
/// disagreed with the operator's EFA in 10 of 36 departures; a departure without a VRR estimate counts as on time.
pub fn apply_vrr(mut deps: Vec<Dep>, vrr: &HashMap<(i64, String), i64>) -> Vec<Dep> {
    for d in &mut deps {
        match vrr.get(&key(d.planned, &d.line)) {
            Some(&est) => { d.expected = est; d.live = true; }
            None => { d.expected = d.planned; d.live = false; }
        }
    }
    deps
}

#[cfg(test)]
mod tests {
    use super::*;
    fn dep(planned: i64, expected: i64, live: bool, line: &str) -> Dep {
        Dep { planned, expected, live, line: line.into(), to: "x".into(), mode: "BUS".into(), cancelled: false }
    }

    #[test] fn nrw_detection() {
        assert!(is_nrw("de-DELFI_de:05774:7000"));
        assert!(is_nrw("de:05974:11872"));
        assert!(!is_nrw("de-DELFI_de:03353:94605"));
        assert!(!is_nrw("at-Railway-Current-Reference-Data-2026_de:02000:10950:1:5"));
    }

    #[test] fn efa_id_strips_platform() {
        assert_eq!(efa_id("de-DELFI_de:05774:7063:0:1"), "de:05774:7063");
        assert_eq!(efa_id("de-DELFI_de:05774:7000"), "de:05774:7000");
    }

    #[test] fn vrr_overrides_transitous_live() {
        // transitous claims +8 min live on R51, VRR has no estimate -> on time
        let deps = vec![dep(6000, 6480, true, "R 51"), dep(6600, 6600, false, "4")];
        let mut vrr = HashMap::new();
        vrr.insert(key(6600, "4"), 6900);
        let out = apply_vrr(deps, &vrr);
        assert_eq!((out[0].expected, out[0].live), (6000, false));
        assert_eq!((out[1].expected, out[1].live), (6900, true));
    }

    #[test] fn line_key_ignores_spaces_and_case() {
        assert_eq!(key(120, "RB 89"), key(179, "rb89"));
        assert_ne!(key(120, "RB 89"), key(180, "RB89"));
    }
}
