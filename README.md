# Haluvi Navigation

An accessible GPS navigation app for blind and low vision travellers in the Maldives, written in native Android Kotlin.
It replaces the earlier Android Lua "Navigating Assistant" app and keeps all of its final (v130) features.

## Features
- **Home**: Where am I, compass, Explore, travel mode.
- **Places**: navigate to saved places, downloaded OpenStreetMap places, or online search results.
  - Google route guidance (Routes API, Directions fallback) with **spoken turn instructions**, live distance, speed, altitude and arrival time.
  - Automatic re-route when you leave the route.
  - Direct compass guidance with clock positions ("destination at 2 o'clock") when Google is not set up.
- **Islands**: navigate between all 200 inhabited islands of the 20 atolls, with "you are near …" announcements.
- **Direction focus**: the phone vibrates and says the name when you point it at the destination, or at nearby places or islands you switch on.
- **Explore**: current road, intersections ahead and nearby places as you walk or drive.
- **Location Management**: save your own places, download island map data (places and roads), search Google Maps or OpenStreetMap, select several results and save them, import/export.
- Keeps guiding with the **screen off** (foreground service with a Stop button in the notification).
- Built for TalkBack: large buttons, headings, labels, focus stays in place when a screen updates.

## Moving from the Lua app
In the Lua app, the saved places are in `nav_assistant_locations.txt`. Copy that file to the phone's storage, then in
Haluvi Navigation open **Location Management → Import locations** and choose it.

## Google Maps (optional)
Enable **Places API (New)**, **Routes API** and **Geocoding API** in Google Cloud with billing, create a key, and paste it in
**Settings → Google Maps API key**. The in-app "Google setup guide" explains each step.

## Building
GitHub Actions builds the APK (`Build Android APK` workflow). Locally: `gradle :app:assembleDebug` with JDK 17 and the Android SDK.

Developed by Hussain Shareef.
