#!/usr/bin/env python3
"""Bus route check: lists every OpenStreetMap bus route in the Maldives with its stops in order.

Reads the Geofabrik Maldives extract (same source as tools/fetch_osm.py) and writes
reports/bus_routes.txt (readable) and reports/bus_routes.json (data for the app later).
It changes nothing in the app.
"""
import json, math, os, subprocess, sys, urllib.request

URL = "https://download.geofabrik.de/asia/maldives-latest.osm.pbf"
PBF = "/tmp/maldives-latest.osm.pbf"
OUT = os.path.join(os.path.dirname(__file__), "..", "reports")

import osmium


def download():
    if os.path.exists(PBF):
        return
    try:
        subprocess.run(["curl", "-4", "-fsSL", "--retry", "3", "-o", PBF, URL], check=True)
    except Exception:
        urllib.request.urlretrieve(URL, PBF)


class Relations(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.routes, self.masters = [], []

    def relation(self, r):
        t = {x.k: x.v for x in r.tags}
        if t.get("type") == "route" and t.get("route") in ("bus", "minibus", "trolleybus"):
            self.routes.append({"id": r.id, "tags": t,
                                "members": [(m.type, m.ref, m.role) for m in r.members]})
        elif t.get("type") == "route_master" and t.get("route_master") in ("bus", "minibus"):
            self.masters.append({"id": r.id, "tags": t, "routes": [m.ref for m in r.members if m.type == "r"]})


class Members(osmium.SimpleHandler):
    def __init__(self, nodes, ways):
        super().__init__()
        self.want_n, self.want_w = nodes, ways
        self.nodes, self.ways = {}, {}

    def node(self, n):
        if n.id in self.want_n:
            self.nodes[n.id] = {"lat": n.location.lat, "lon": n.location.lon,
                                "tags": {x.k: x.v for x in n.tags}}

    def way(self, w):
        if w.id in self.want_w:
            pts = []
            for nd in w.nodes:
                try:
                    pts.append((nd.location.lat, nd.location.lon))
                except Exception:
                    pass
            self.ways[w.id] = {"tags": {x.k: x.v for x in w.tags}, "points": pts}


def dist(a, b):
    la = math.radians((a[0] + b[0]) / 2)
    dx = (b[1] - a[1]) * 111320 * math.cos(la)
    dy = (b[0] - a[0]) * 110540
    return math.hypot(dx, dy)


def stop_name(t, fallback):
    return t.get("name:en") or t.get("name") or t.get("ref") or fallback


def main():
    download()
    rel = Relations()
    rel.apply_file(PBF)
    want_n, want_w = set(), set()
    for r in rel.routes:
        for typ, ref, role in r["members"]:
            (want_n if typ == "n" else want_w if typ == "w" else set()).add(ref)
    mem = Members(want_n, want_w)
    mem.apply_file(PBF, locations=True)

    # Every bus stop in the data, to count stops that no route uses.
    out_routes = []
    for r in sorted(rel.routes, key=lambda r: (r["tags"].get("ref", "zz"), r["tags"].get("name", ""))):
        stops, length, ways_ok, ways_missing = [], 0.0, 0, 0
        for typ, ref, role in r["members"]:
            if typ == "n" and (role.startswith("stop") or role.startswith("platform") or role == ""):
                n = mem.nodes.get(ref)
                if not n:
                    continue
                name = stop_name(n["tags"], "Unnamed stop")
                # stop_position and platform of the same stop often both appear: keep one.
                if stops and (stops[-1]["name"] == name or dist((stops[-1]["lat"], stops[-1]["lon"]), (n["lat"], n["lon"])) < 25):
                    continue
                stops.append({"id": ref, "name": name, "lat": round(n["lat"], 6), "lon": round(n["lon"], 6),
                              "role": role})
            elif typ == "w" and role in ("", "forward", "backward"):
                w = mem.ways.get(ref)
                if w and len(w["points"]) > 1:
                    ways_ok += 1
                    length += sum(dist(w["points"][i], w["points"][i + 1]) for i in range(len(w["points"]) - 1))
                else:
                    ways_missing += 1
        out_routes.append({"id": r["id"], "ref": r["tags"].get("ref", ""), "name": r["tags"].get("name", ""),
                           "from": r["tags"].get("from", ""), "to": r["tags"].get("to", ""),
                           "operator": r["tags"].get("operator", ""), "network": r["tags"].get("network", ""),
                           "fee": r["tags"].get("charge", "") or r["tags"].get("fee", ""),
                           "stops": stops, "road_ways": ways_ok, "road_ways_missing": ways_missing,
                           "length_m": round(length)})

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "bus_routes.json"), "w") as f:
        json.dump({"source": "OpenStreetMap contributors (Geofabrik extract)", "routes": out_routes,
                   "masters": rel.masters}, f, ensure_ascii=False, indent=1)
    lines = [f"Bus routes in OpenStreetMap, Maldives: {len(out_routes)} routes, {len(rel.masters)} route groups.", ""]
    for r in out_routes:
        lines.append(f"{r['ref'] or '(no number)'}: {r['name']}")
        lines.append(f"  From: {r['from']}  To: {r['to']}  Operator: {r['operator']}  Network: {r['network']}")
        lines.append(f"  {len(r['stops'])} stops, road line {r['length_m']} m from {r['road_ways']} road pieces"
                     + (f", {r['road_ways_missing']} pieces missing" if r['road_ways_missing'] else ""))
        for i, s in enumerate(r["stops"], 1):
            lines.append(f"   {i}. {s['name']}  ({s['lat']}, {s['lon']})")
        lines.append("")
    with open(os.path.join(OUT, "bus_routes.txt"), "w") as f:
        f.write("\n".join(lines))
    print("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
