#!/usr/bin/env python3
"""Downloads OpenStreetMap places and roads for every atoll of the Maldives and saves them as
app assets (assets/osm/<ATOLL>.json.gz), so the app has offline maps built in.
The queries are the same as the app's own Offline maps download, so the app processes them the same way.
Map data (c) OpenStreetMap contributors, ODbL."""
import gzip, json, os, sys, time, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "osm")
SERVERS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
           "https://maps.mail.ru/osm/tools/overpass/api/interpreter", "https://overpass.private.coffee/api/interpreter"]

PLACE_FILTERS = ['["amenity"]', '["shop"]', '["tourism"]', '["healthcare"]', '["leisure"]["name"]',
                 '["office"]["name"]', '["man_made"="pier"]', '["public_transport"]', '["building"="mosque"]', '["craft"]["name"]']
ROAD_RE = "^(primary|secondary|tertiary|unclassified|residential|living_street|service|pedestrian|footway|path|track|road|steps|cycleway)$"
KEEP_PLACE_TAGS = None  # keep all tags of places (category and address need several)
KEEP_ROAD_TAGS = {"name", "name:en", "ref", "highway", "oneway"}


def overpass(q):
    last = None
    for attempt in range(4):
        for url in SERVERS:
            try:
                data = urllib.parse.urlencode({"data": q}).encode()
                req = urllib.request.Request(url, data=data, headers={"User-Agent": "HaluviNavigation-builtin-maps/1.0"})
                with urllib.request.urlopen(req, timeout=240) as r:
                    j = json.loads(r.read().decode("utf-8"))
                    if "elements" in j:
                        return j["elements"]
            except Exception as e:
                last = e
                print("  server failed:", url, e, flush=True)
                time.sleep(5)
        time.sleep(30)
    raise RuntimeError("all servers failed: %s" % last)


def main():
    atolls = json.load(open(os.path.join(ASSETS, "maldives_islands.json"), encoding="utf-8"))
    shapes = json.load(open(os.path.join(ASSETS, "island_outlines_builtin.json"), encoding="utf-8"))
    rings, island_ring = shapes["rings"], shapes["islandRing"]
    os.makedirs(OUT, exist_ok=True)
    index = {"date": time.strftime("%Y-%m-%d"), "atolls": {}}
    only = set(sys.argv[1:])
    for a in atolls:
        code = a["code"]
        if only and code not in only:
            continue
        s, w, n, e = 90.0, 180.0, -90.0, -180.0
        for isl in a["islands"]:
            r = rings.get(island_ring.get(code + ":" + isl["n"], ""))
            pts = r if r else [[isl["lat"] - 0.03, isl["lon"] - 0.03], [isl["lat"] + 0.03, isl["lon"] + 0.03]]
            for p in pts:
                s, n, w, e = min(s, p[0]), max(n, p[0]), min(w, p[1]), max(e, p[1])
        pad = 0.004
        b = "(%f,%f,%f,%f)" % (s - pad, w - pad, n + pad, e + pad)
        print(code, a["fullname"], b, flush=True)
        pq = "[out:json][timeout:180];(" + "".join("node%s%s;way%s%s;" % (b, f, b, f) for f in PLACE_FILTERS) + ");out center tags;"
        places = []
        for el in overpass(pq):
            t = el.get("tags") or {}
            lat = el.get("lat", (el.get("center") or {}).get("lat"))
            lon = el.get("lon", (el.get("center") or {}).get("lon"))
            if lat is None or lon is None or not t:
                continue
            places.append({"lat": round(lat, 7), "lon": round(lon, 7), "tags": t})
        time.sleep(3)
        rq = '[out:json][timeout:180];way%s["highway"~"%s"];out geom tags;' % (b, ROAD_RE)
        roads = []
        for el in overpass(rq):
            g = el.get("geometry") or []
            if len(g) < 2:
                continue
            t = {k: v for k, v in (el.get("tags") or {}).items() if k in KEEP_ROAD_TAGS}
            roads.append({"tags": t, "geometry": [{"lat": round(p["lat"], 7), "lon": round(p["lon"], 7)} for p in g]})
        with gzip.open(os.path.join(OUT, code + ".json.gz"), "wt", encoding="utf-8") as f:
            json.dump({"date": index["date"], "places": places, "roads": roads}, f, separators=(",", ":"), ensure_ascii=False)
        index["atolls"][code] = {"places": len(places), "roads": len(roads)}
        print("  ", len(places), "places", len(roads), "roads", flush=True)
        time.sleep(3)
    old = {}
    ip = os.path.join(OUT, "index.json")
    if os.path.exists(ip) and only:
        old = json.load(open(ip)).get("atolls", {})
    old.update(index["atolls"])
    index["atolls"] = old
    json.dump(index, open(ip, "w"), indent=1)


if __name__ == "__main__":
    main()
