# Haluvi Navigation

An accessible GPS navigation app for blind and low vision travellers in the Maldives, written in native Android Kotlin.
It replaces the earlier Android Lua "Navigating Assistant" app and keeps all of its final (v130) features.

## Features
- **Home**: Where am I, Places, Islands, Routes, Explore, My places, Settings.
- **Places** (walking or driving): From (my location or a saved place), To (my places, search, what's around me), Start.
- **Islands** (sea or air): From (GPS island or chosen), To (any of the 200 inhabited islands), Start.
  - Island stop announcements: "Approaching …", "Arrived at …" (ferry stop), "Passing …".
  - Exact with island outlines, downloaded once in the app from OpenStreetMap (continues where it stopped,
    lists missing islands, includes other named islands such as resorts, shareable report).
  - Island alerts in background: announcements and notifications at sea without opening the app.
- **Routes** (bus, ferry or both): stops in order, each a place or an island (islands use their outline).
  Make a route, or record it while riding (the app asks to save each stop when the vehicle stops).
  While travelling: next stop, approaching, stopping at, passed, "Your stop is next", "Your stop is coming",
  "Arrived at your stop". Works in the background.
- **Trip alerts**: arrived, passed, island and route stop announcements are also shown as notifications
  when the app is not on screen (places, islands and routes alike).
- **While navigating**: the next instruction, distance and time, Repeat, What's in this direction, Stop.
  - Google road routes (optional) with spoken turn instructions and automatic re-route.
  - Offline routes calculated on the phone from downloaded roads (no internet), with turn, count and
    landmark instructions ("take the second road on the right at the harbour").
  - Online with a Google key: Google routes first; the offline route is used when offline or when Google fails.
  - Without either: clock-position guidance ("2 o'clock, 300 meters").
  - The phone vibrates while it points at the destination.
  - "Where is it?" gives the destination's clock position from where the phone points. Without a route,
    "You are moving away" warns when the distance keeps growing.
  - Destination side decided about 60 m before the end (route side, else metres left or right of the
    road line you travel along, else walking direction) and kept: "In 30 meters, destination on your right", "Arrived at …, destination on your right".
- **Explore**: your road, intersections on your road, and places as you pass them. Pauses when GPS is weak.
- **My roads**: Check this road (on the map, named by you, recorded by you, or missing), Name this road
  (walk 30 m along it), Record a new road (walk along its middle; joined to roads at its ends and used in
  offline routes), and Share with OpenStreetMap as a public note (no account; asks every time).
  Your roads are kept when the offline map is updated, and are in the backup file.
- **Save a place with its side** (here, on my left, on my right) for exact "destination on your left/right".
- **What's around me**: nearest places with clock positions, by category.
- **My places**: all places (saved by you, offline map, Google), nearest first; show by island, source or
  category; rename, edit, delete; delete all Google or offline map places of an island.
- **Settings → Manage islands and places**: atolls and islands with offline place counts; atoll details
  (download OpenStreetMap map or Google places for the atoll); island details (places by source, roads,
  outline, places list, download or delete OpenStreetMap map, Google places, rename, change type and
  airport, restore original); download places; all places by source or category; My roads; backup.
- **Joined islands** (one outline for two islands, e.g. Hulhudhoo and Meedhoo): each place goes to the island
  named in its address (Google, OpenStreetMap); otherwise a dividing line between the villages, learned from
  the places with addresses or postcodes, decides; or the border you set yourself (standing there, or at a
  place such as a school at the joining point). Hulhulé (Velana International Airport) is its own airport
  island, joined to Hulhumalé. Move to another island on each place (kept in later re-checks).
- **Areas inside an island** (Hulhumalé Phase 1 and Phase 2, or your own): "You are on Hulhumalé, Phase 2".
- **Get Google places** retries failed searches, searches crowded spots down to 20 m and says whether the
  search is complete. Before searching it shows what is already saved, when each island was last searched,
  and an estimate of the Google searches; during the search, new and already saved places.
- **Backup** holds places, roads, routes, borders, areas and island changes.
- **Places / Islands**: one From button that follows you ("From: my location, at Daily Print, on Ameenee
  Magu, Faafu Feeali"; "From: my island, Faafu Feeali, by GPS" or "at sea, 2 km from …") and a To button
  with distance and direction.
- **My places** (backup): One backup file (export and
  import) holds places, your road names and routes.
- **Get Google places** (optional): all places Google Maps has on an island, of every kind. The island is
  covered square by square and busy squares are split until nothing is missed; you choose which to save.
- Uses your default speech engine at accessibility volume; keeps guiding with the screen off.

## Moving from the Lua app
In the Lua app, the saved places are in `nav_assistant_locations.txt`. Copy that file to the phone's storage, then in
Haluvi Navigation open **My places → Import** and choose it.

## Google Maps (optional)
Enable **Places API (New)**, **Routes API** and **Geocoding API** in Google Cloud with billing, create a key, and paste it in
**Settings → Google Maps API key**. The in-app "Google setup guide" explains each step.

## Building
GitHub Actions builds the APK (`Build Android APK` workflow). Locally: `gradle :app:assembleDebug` with JDK 17 and the Android SDK.

Map data © OpenStreetMap contributors (ODbL, openstreetmap.org/copyright).

Developed by Hussain Shareef.
