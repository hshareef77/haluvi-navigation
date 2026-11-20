#!/usr/bin/env python3
"""Converts an RTL (Raajje Transport Link) public route export into the app's built-in file
assets/rtl/rtl_transport.json: bus routes with stops, positions and stop times, and ferry routes with
island stops. Usage: python3 tools/rtl_prepare.py <folder with bus_routes_original.json,
ferry_routes_original.json, vessel_routes_original.json> <date>
Bus and ferry data: RTL (Raajje Transport Link)."""
import difflib, json, math, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ATOLLS = json.load(open(os.path.join(ROOT, "app/src/main/assets/maldives_islands.json"), encoding="utf-8"))


def norm(s):
    return re.sub(r"[^a-z]", "", s.lower().replace("'", "").replace("’", ""))


def dist(a, b, c, d):
    return math.hypot((d - b) * 111320 * math.cos(math.radians(a)), (c - a) * 110540)


def island_key(name, lat, lon):
    """RTL ferry stop "Ha.Thuraakunu" -> app island key "HA:Thuraakunu" (by atoll and name, else nearest)."""
    pre, _, isl = name.partition(".")
    if not isl:
        pre, isl = "", name
    cands = []
    for a in ATOLLS:
        if pre and norm(a["code"]) != norm(pre) and not (norm(pre) == "hdh" and a["code"] == "HDh"):
            continue
        for i in a["islands"]:
            cands.append((a["code"], i))
    best, score = None, 0
    for code, i in cands:
        s = difflib.SequenceMatcher(None, norm(isl), norm(i["n"])).ratio()
        if s > score:
            best, score = (code, i), s
    if best and score >= 0.8 and dist(lat, lon, best[1]["lat"], best[1]["lon"]) < 8000:
        return best[0] + ":" + best[1]["n"], best[1]["n"]
    near = min(((dist(lat, lon, i["lat"], i["lon"]), a["code"], i) for a in ATOLLS for i in a["islands"]), key=lambda t: t[0])
    if near[0] < 2500:
        return near[1] + ":" + near[2]["n"], near[2]["n"]
    return "", isl.strip()


def times(lst):
    return sorted({t["timing"][:5] for t in (lst or []) if t.get("timing")})


def main(folder, date):
    bus = json.load(open(os.path.join(folder, "bus_routes_original.json"), encoding="utf-8"))
    ferry = json.load(open(os.path.join(folder, "ferry_routes_original.json"), encoding="utf-8"))
    vessel = json.load(open(os.path.join(folder, "vessel_routes_original.json"), encoding="utf-8"))
    routes = []
    groups = [("Greater Male", bus["routeResponse"])] + [(a["name"], a["routeResponse"]) for a in bus.get("atollRouteResponse") or []]
    for area, lst in groups:
      for r in lst:
        stops = [{"code": s["code"], "name": s["name"].strip(), "dv": s.get("dvname", ""),
                  "lat": round(float(s["latitude"]), 6), "lon": round(float(s["longitude"]), 6), "times": times(s.get("timings"))}
                 for s in sorted(r["busRouteStopList"], key=lambda s: s["order"])]
        routes.append({"kind": "bus", "code": r["code"], "ref": r.get("routeNumber", ""), "name": r["name"].strip(),
                       "area": area, "fare": r.get("fare"), "stops": stops})
    for r in vessel:
        stops = [{"code": s["code"], "name": s["name"].strip(), "dv": s.get("dvname", ""),
                  "lat": round(float(s["latitude"]), 6), "lon": round(float(s["longitude"]), 6), "times": times(s.get("timings"))}
                 for s in r["stopList"]]
        routes.append({"kind": "vessel", "code": r["code"], "ref": r.get("routeNumber", ""), "name": r["name"].strip(),
                       "area": "Greater Male", "stops": stops})
    skipped = []
    for z in ferry["zoneList"]:
        for r in z["routeList"]:
            name = r["routeName"]
            if "old" in name.lower() or "error" in name.lower():
                skipped.append(name); continue
            st = []
            for sch in r.get("scheduleList") or []:
                for s in sch.get("stopTiming") or []:
                    if st and st[-1]["code"] == s["stopCode"]:
                        continue
                    lat, lon = float(s["latitude"]), float(s["longitude"])
                    key, isl = island_key(s["stopName"], lat, lon)
                    st.append({"code": s["stopCode"], "name": s["stopName"].strip(), "dv": s.get("dvstopName", ""),
                               "lat": round(lat, 6), "lon": round(lon, 6), "island": key})
                break
            if len(st) >= 2:
                routes.append({"kind": "ferry", "code": r["routeCode"], "ref": name, "name": f"{st[0]['name']} to {st[-1]['name']}",
                               "area": z["zoneName"], "stops": st})
    out = {"date": date, "source": "RTL (Raajje Transport Link), public route data", "routes": routes}
    path = os.path.join(ROOT, "app/src/main/assets/rtl/rtl_transport.json")
    json.dump(out, open(path, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    kinds = {}
    for r in routes: kinds[r["kind"]] = kinds.get(r["kind"], 0) + 1
    unmatched = sorted({s["name"] for r in routes if r["kind"] == "ferry" for s in r["stops"] if not s["island"]})
    print(kinds, "skipped:", skipped, "ferry stops without island:", unmatched, os.path.getsize(path), "bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
