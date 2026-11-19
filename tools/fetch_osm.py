#!/usr/bin/env python3
"""Builds the app's built-in OpenStreetMap maps (assets/osm/<ATOLL>.json.gz) from the daily Maldives
extract on Geofabrik (one file, no busy live servers). Same filters as the app's own Offline maps
download, so the app processes the data the same way. Map data (c) OpenStreetMap contributors, ODbL."""
import gzip, hashlib, json, os, re, sys, time, urllib.request
import osmium

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "osm")
PBF_URL = "https://download.geofabrik.de/asia/maldives-latest.osm.pbf"
ROAD_RE = re.compile(r"^(primary|secondary|tertiary|unclassified|residential|living_street|service|pedestrian|footway|path|track|road|steps|cycleway)$")
KEEP_ROAD_TAGS = {"name", "name:en", "ref", "highway", "oneway"}


def is_place(t):
    """The same filters as the app's Overpass query for places."""
    return ("amenity" in t or "shop" in t or "tourism" in t or "healthcare" in t or
            ("leisure" in t and "name" in t) or ("office" in t and "name" in t) or
            t.get("man_made") == "pier" or "public_transport" in t or t.get("highway") == "bus_stop" or t.get("building") == "mosque" or
            ("craft" in t and "name" in t) or t.get("aeroway") in ("aerodrome", "terminal"))


class Collector(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.places, self.roads = [], []

    def node(self, n):
        t = {tag.k: tag.v for tag in n.tags}
        if t and is_place(t) and n.location.valid():
            self.places.append({"lat": round(n.location.lat, 7), "lon": round(n.location.lon, 7), "tags": t})

    def way(self, w):
        t = {tag.k: tag.v for tag in w.tags}
        if not t:
            return
        pts = [(nd.location.lat, nd.location.lon) for nd in w.nodes if nd.location.valid()]
        if len(pts) < 2:
            return
        if is_place(t):
            # Like Overpass "out center": the middle of the way's box.
            lat = (min(p[0] for p in pts) + max(p[0] for p in pts)) / 2
            lon = (min(p[1] for p in pts) + max(p[1] for p in pts)) / 2
            self.places.append({"lat": round(lat, 7), "lon": round(lon, 7), "tags": t})
        if ROAD_RE.match(t.get("highway", "")):
            self.roads.append({"tags": {k: v for k, v in t.items() if k in KEEP_ROAD_TAGS},
                               "geometry": [{"lat": round(a, 7), "lon": round(b, 7)} for a, b in pts]})


def main():
    pbf = os.path.join(ROOT, "maldives-latest.osm.pbf")
    import subprocess
    ok = False
    for attempt in range(5):
        # curl over IPv4 first (some build machines have no IPv6 route), then Python's own download.
        r = subprocess.run(["curl", "-4", "-fsSL", "--retry", "3", "--connect-timeout", "30", "-o", pbf, PBF_URL])
        if r.returncode == 0 and os.path.exists(pbf) and os.path.getsize(pbf) > 100000:
            ok = True; break
        try:
            urllib.request.urlretrieve(PBF_URL, pbf)
            ok = True; break
        except Exception as e:
            print("download failed:", e, flush=True)
            time.sleep(30)
    if not ok:
        raise SystemExit("could not download the Maldives map file")
    print("map file", os.path.getsize(pbf), "bytes", flush=True)
    c = Collector()
    c.apply_file(pbf, locations=True)
    print(len(c.places), "places,", len(c.roads), "roads in the Maldives", flush=True)

    atolls = json.load(open(os.path.join(ASSETS, "maldives_islands.json"), encoding="utf-8"))
    shapes = json.load(open(os.path.join(ASSETS, "island_outlines_builtin.json"), encoding="utf-8"))
    rings, island_ring = shapes["rings"], shapes["islandRing"]
    os.makedirs(OUT, exist_ok=True)
    index = {"date": time.strftime("%Y-%m-%d"), "source": "Geofabrik Maldives extract", "atolls": {}}
    for a in atolls:
        code = a["code"]
        s, w, n, e = 90.0, 180.0, -90.0, -180.0
        for isl in a["islands"]:
            r = rings.get(island_ring.get(code + ":" + isl["n"], ""))
            pts = r if r else [[isl["lat"] - 0.03, isl["lon"] - 0.03], [isl["lat"] + 0.03, isl["lon"] + 0.03]]
            for p in pts:
                s, n, w, e = min(s, p[0]), max(n, p[0]), min(w, p[1]), max(e, p[1])
        pad = 0.004
        s, w, n, e = s - pad, w - pad, n + pad, e + pad
        inside = lambda lat, lon: s <= lat <= n and w <= lon <= e
        places = [p for p in c.places if inside(p["lat"], p["lon"])]
        roads = [r for r in c.roads if any(inside(g["lat"], g["lon"]) for g in r["geometry"])]
        path = os.path.join(OUT, code + ".json.gz")
        with gzip.open(path, "wt", encoding="utf-8") as f:
            json.dump({"date": index["date"], "places": places, "roads": roads}, f, separators=(",", ":"), ensure_ascii=False)
        sha = hashlib.sha256(open(path, "rb").read()).hexdigest()
        index["atolls"][code] = {"places": len(places), "roads": len(roads), "bytes": os.path.getsize(path), "sha256": sha}
        print(code, a["fullname"], len(places), "places", len(roads), "roads", os.path.getsize(path), "bytes", flush=True)
    # Bus routes (OpenStreetMap route relations) with their stops in order.
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import bus_routes
        routes = bus_routes.collect(pbf)
        for r in routes:
            for k in ("raw_stop_points", "road_ways", "road_ways_missing"): r.pop(k, None)
        json.dump({"date": index["date"], "routes": routes}, open(os.path.join(OUT, "bus_routes.json"), "w", encoding="utf-8"),
                  separators=(",", ":"), ensure_ascii=False)
        index["busRoutes"] = len(routes)
        print(len(routes), "bus routes", flush=True)
    except Exception as ex:
        print("bus routes failed:", ex, flush=True)
    # Changes whenever the maps are prepared again, so phones re-install even on the same date.
    index["stamp"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    json.dump(index, open(os.path.join(OUT, "index.json"), "w"), indent=1)
    os.remove(pbf)


if __name__ == "__main__":
    main()
