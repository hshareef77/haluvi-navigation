# Haluvi Navigation

An accessible GPS navigation app for blind and low vision travellers in the Maldives, written in native Android Kotlin.
It replaces the earlier Android Lua "Navigating Assistant" app and keeps all of its final (v130) features.

## Features
- **Home**: Where am I, Places, Islands, Explore, My places, Settings.
- **Places** (walking or driving): From (my location or a saved place), To (my places, search, what's around me), Start.
- **Islands** (sea or air): From (GPS island or chosen), To (any of the 200 inhabited islands), Start.
  - Island stop announcements: "Approaching …", "Arrived at …" (ferry stop), "Passing …".
  - Exact with island outlines, downloaded once in the app from OpenStreetMap (continues where it stopped,
    lists missing islands, includes other named islands such as resorts, shareable report).
  - Island alerts in background: announcements and notifications at sea without opening the app.
- **While navigating**: the next instruction, distance and time, Repeat, What's in this direction, Stop.
  - Google road routes (optional) with spoken turn instructions and automatic re-route.
  - Offline routes calculated on the phone from downloaded roads (no internet), with turn, count and
    landmark instructions ("take the second road on the right at the harbour").
  - Online with a Google key: Google routes first; the offline route is used when offline or when Google fails.
  - Without either: clock-position guidance ("2 o'clock, 300 meters").
  - The phone vibrates while it points at the destination.
  - Destination side decided about 60 m before the end (route side, else road direction, else walking
    direction) and kept: "In 30 meters, destination on your right", "Arrived at …, destination on your right".
- **Explore**: your road, intersections on your road, and places as you pass them. Pauses when GPS is weak.
- **Name this road**: walk 30 m along an unnamed downloaded road (away from junctions) and give it a name.
  Your names are used in Explore and offline routes, and are kept when the offline map is updated.
- **What's around me**: nearest places with clock positions, by category.
- **My places**: save, edit, delete, download island map (places and roads), import, export.
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
