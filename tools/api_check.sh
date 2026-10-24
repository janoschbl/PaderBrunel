#!/usr/bin/env bash
# checks every srs endpoint the app uses
set -uo pipefail
B=https://service.ivanto.de/srs/api/v1
Q=(-d tenant=pader -d bundleIdentifier=de.ivanto.padersprinter --data-urlencode "appVersion=Android 17---8.7.8-9872")
NOW=$(TZ=Europe/Berlin date +%Y-%m-%dT%H:%M:%S)
fail=0
check() { # name, jq-ish python expr on parsed JSON, curl args...
  local name=$1 expr=$2; shift 2
  local body code
  body=$(curl -s -m 60 -G -w '\n%{http_code}' "${Q[@]}" "$@")
  code=${body##*$'\n'}; body=${body%$'\n'*}
  if [[ $code != 200 ]]; then echo "FAIL $name http $code"; fail=1; return; fi
  if out=$(python3 -c "import json,sys; d=json.loads(sys.stdin.read()); r=($expr); assert r, 'shape'; print(r)" <<<"$body" 2>&1); then
    echo "ok   $name -> $out"
  else echo "FAIL $name: $out"; fail=1; fi
}
check "stations (search)" "len(d)" "$B/stations" -d name=Kamp -d showStreets=true -d onlyKnown=false -d onlyStations=false
check "stations (nearby)" "len(d)" "$B/stations" -d latitude=51.7178 -d longitude=8.7544 -d distance=800
check "mapobjects"        "len(d)" "$B/mapobjects" -d mapObjectVersion=1 -d limit=200 -d latitude=51.7178 -d longitude=8.7544 -d distance=1500 -d services=STATION
check "departuresinfo"    "len(d['departures'])" "$B/departuresinfo" -d stationID=23207000 --data-urlencode "date=$NOW" -d sortBy=departureDate
# maxChanges=1 keeps the server at ~1.5 s, unlimited changes take 10-20 s
check "routes (stop to stop)" "len(d['routes'])" "$B/routes" -d originType=stop -d originID=23207000 --data-urlencode "originName=Paderborn Hbf" \
  -d destinationType=stop -d destinationID=23207063 -d destinationName=Kamp -d dateType=0 -d maxChanges=1 -d showWalkway=true -d interchange=true
check "infos"             "len(d)" "$B/infos"
check "lineplans/"        "len(d)" "$B/lineplans/"
check "trafficIncident/lines" "len(d)" "$B/trafficIncident/lines"
code=$(curl -s -o /dev/null -L -w '%{http_code} %{content_type}' "$B/lineplans/pader1?tenant=pader")
[[ $code == "200 application/pdf"* ]] && echo "ok   lineplans/{id} -> $code" || { echo "FAIL lineplans/{id} $code"; fail=1; }
exit $fail
