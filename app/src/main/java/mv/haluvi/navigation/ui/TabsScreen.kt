package mv.haluvi.navigation.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.launch
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.BuildConfig
import mv.haluvi.navigation.core.Fmt
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.nav.FocusTarget
import mv.haluvi.navigation.nav.NavKind
import mv.haluvi.navigation.nav.WhereAmI
import java.util.Locale
import kotlin.math.roundToInt

/** Main screen with four tabs: Home, Places, Islands, Settings (same structure as Lua v97-v130). */
class TabsScreen(val tab: String) : Screen() {
    private val g = AppGraph
    private val nav get() = g.nav
    private val settings get() = g.settings

    private var compassView: TextView? = null
    private var islandView: TextView? = null
    private var previewView: TextView? = null
    private var statusView: TextView? = null
    private var distanceView: TextView? = null
    private var speedView: TextView? = null
    private var altitudeView: TextView? = null
    private var etaView: TextView? = null
    private var focusView: TextView? = null
    private var routeView: TextView? = null
    private var gpsView: TextView? = null
    private var builtActiveKind: NavKind? = null
    private var builtRoute = false

    override fun create(): View {
        compassView = null; islandView = null; previewView = null; statusView = null; distanceView = null
        speedView = null; altitudeView = null; etaView = null; focusView = null; routeView = null; gpsView = null
        val outer = LinearLayout(host)
        outer.orientation = LinearLayout.VERTICAL
        outer.setBackgroundColor(UiColors.BG)
        outer.setPadding(host.dp(10), host.dp(6), host.dp(10), host.dp(6))
        val head = UiBuilder(host, outer)
        titleView = head.title("Haluvi Navigation, $tab")
        addTabRow(outer)

        val top = UiBuilder.vertical(host, 0)
        outer.addView(top)
        val scroll = ScrollView(host)
        val middle = UiBuilder.vertical(host, 0)
        scroll.addView(middle)
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = UiBuilder.vertical(host, 0)
        outer.addView(bottom)

        val topUi = UiBuilder(host, top)
        val midUi = UiBuilder(host, middle)
        val botUi = UiBuilder(host, bottom)
        builtActiveKind = nav.target?.kind
        builtRoute = nav.route != null
        when (tab) {
            "Places" -> buildPlaces(topUi, midUi, botUi)
            "Islands" -> buildIslands(topUi, midUi, botUi)
            "Settings" -> buildSettings(midUi)
            else -> buildHome(midUi)
        }
        onLive()
        return outer
    }

    private fun addTabRow(outer: LinearLayout) {
        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        for (name in listOf("Home", "Places", "Islands", "Settings")) {
            val b = Button(host)
            b.text = name
            b.isAllCaps = false
            b.textSize = 15f
            b.setTextColor(UiColors.TEXT)
            b.setPadding(host.dp(2), host.dp(8), host.dp(2), host.dp(8))
            val selected = name == tab
            b.isSelected = selected
            b.contentDescription = "$name tab" + if (selected) ", selected" else ""
            b.background = roundedBg(host, if (selected) UiColors.BUTTON_SELECTED else UiColors.BUTTON, 12, if (selected) 0xFFFFC94A.toInt() else null)
            b.stateListAnimator = null
            b.setOnClickListener { if (!selected) host.showTabs(name) }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(host.dp(2), host.dp(2), host.dp(2), host.dp(6))
            row.addView(b, lp)
        }
        outer.addView(row)
    }

    // =================================================================== Home

    private fun buildHome(ui: UiBuilder) {
        addPermissionWarnings(ui)
        ui.row(
            "Where am I" to { whereAmI() },
            "Compass" to { g.speaker.speak(WhereAmI.compassText(), force = true) }
        )
        ui.button(if (g.explore.active) "Explore: On. Open Explore" else "Explore") { host.show { ExploreScreen() } }
        ui.button("Travel mode: ${settings.travelMode.label}. Tap to change") { host.show(Pickers.travelMode(host)) }
        if (nav.isActive) {
            ui.info("Navigating to ${nav.target?.name}.")
            ui.row(
                "Open navigation" to { host.showTabs(if (nav.isIslandNav) "Islands" else "Places") },
                "Stop navigation" to { nav.stop(); host.rebuild() }
            )
        }
        compassView = ui.info(WhereAmI.compassText())
        compassView?.gravity = android.view.Gravity.CENTER
        islandView = ui.info(currentIslandText())
        gpsView = ui.info(gpsText())
        credit(ui)
    }

    private fun addPermissionWarnings(ui: UiBuilder) {
        if (!host.hasLocationPermission()) {
            ui.info("Location permission is needed for navigation.")
            ui.row("Allow location" to { host.requestPermissionsIfNeeded() }, "App settings" to { host.openAppSettings() })
        } else if (!g.location.isGpsEnabled()) {
            ui.info("Location (GPS) is turned off.")
            ui.button("Open location settings") { host.openLocationSettings() }
        }
    }

    private fun whereAmI() {
        host.say("Checking location")
        host.scope.launch {
            val text = WhereAmI.describe()
            g.speaker.speak(text, force = true)
            host.say(text)
        }
    }

    private fun currentIslandText(): String {
        val island = nav.currentIsland() ?: return "Current island: waiting for GPS."
        return "Current island: ${island.fullName}" + if (nav.currentIslandIsManual()) ", chosen manually." else "."
    }

    private fun gpsText(): String {
        val f = g.location.fix ?: return "GPS: waiting for signal."
        val age = (f.ageMs / 1000).toInt()
        return "GPS accuracy ${f.accuracy.roundToInt()} meters" + if (age > 10) ", last fix $age seconds ago." else "."
    }

    private fun credit(ui: UiBuilder) {
        val c = ui.info("Developed by Hussain Shareef")
        c.gravity = android.view.Gravity.CENTER
        c.textSize = 15f
    }

    // =================================================================== Places

    private fun buildPlaces(top: UiBuilder, mid: UiBuilder, bottom: UiBuilder) {
        val placeActive = nav.isPlaceNav
        top.row(
            nav.currentPlaceButtonText() to { host.show { ChooseCurrentPlaceScreen() } },
            (nav.placeDestination?.let { "Destination: ${it.name}. Tap to change" } ?: "Choose destination") to { host.show { ChooseDestinationScreen() } }
        )
        top.row(
            "Travel mode: ${settings.travelMode.label}" to { host.show(Pickers.travelMode(host)) },
            (if (placeActive) "Stop navigation" else "Start navigation") to {
                if (placeActive) { nav.stop(); host.rebuild() } else startPlaceNavigation()
            }
        )

        addPermissionWarnings(mid)
        if (placeActive) {
            statusView = mid.info("")
            distanceView = mid.info("")
            speedView = mid.info("")
            altitudeView = mid.info("")
            etaView = mid.info("")
            focusView = mid.info("")
            if (settings.googleReady) routeView = mid.info("")
            mid.button(exploreToggleText()) { g.explore.toggle(); host.rebuild() }
            if (nav.placesNearbyOn) addNearbyPlaces(mid)
            if (nav.stepsVisible && nav.route != null) {
                mid.heading("Route steps")
                nav.routeStepLines().forEach { mid.info(it) }
            }
        } else {
            if (nav.lastArrivalText.isNotBlank() && !nav.isActive) mid.info(nav.lastArrivalText)
            previewView = mid.info(nav.placePreviewText())
            mid.info(settings.travelMode.description + if (!settings.googleReady) " Google Maps is off, so direct compass guidance is used." else "")
            mid.button("Online search for a destination") { host.show { OnlineSearchScreen(OnlineSearchScreen.Mode.DESTINATION) } }
            mid.button("Location Management") { host.show { LocationManagementScreen() } }
            mid.button("Explore nearby places") { host.show { ExploreScreen() } }
        }

        if (placeActive) {
            bottom.row(
                (if (nav.placesNearbyOn) "Nearest locations: On" else "Nearest locations: Off") to {
                    nav.setPlacesNearby(!nav.placesNearbyOn)
                    host.say(if (nav.placesNearbyOn) "Nearest locations on" else "Nearest locations off")
                    host.rebuild()
                },
                "Refresh nearest" to {
                    if (!nav.placesNearbyOn) nav.setPlacesNearby(true) else nav.refreshNearbyPlaces()
                    host.say("Nearest locations refreshed"); host.rebuild()
                }
            )
            bottom.row(
                "Nearest distance: ${Fmt.meters(settings.nearestLocationDistance.toDouble())}" to {
                    host.show(Pickers.meters(host, "Nearest location distance", listOf(25, 50, 100, 200, 300, 500, 1000), settings.nearestLocationDistance) {
                        settings.nearestLocationDistance = it; if (nav.placesNearbyOn) nav.refreshNearbyPlaces()
                    })
                },
                if (nav.route != null) ((if (nav.stepsVisible) "Hide route steps" else "Show route steps") to { nav.stepsVisible = !nav.stepsVisible; host.rebuild() })
                else ("Arrival distance: ${Fmt.meters(settings.arrivalDistance.toDouble())}" to { arrivalPicker() })
            )
        }
    }

    private fun arrivalPicker() = host.show(Pickers.meters(host, "Saved location arrival distance", listOf(1, 3, 5, 10, 15, 30, 50), settings.arrivalDistance) { settings.arrivalDistance = it })

    private fun startPlaceNavigation() {
        if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return }
        val dest = nav.placeDestination
        if (dest == null) {
            g.speaker.speak("Please choose a destination first.", force = true)
            host.show { ChooseDestinationScreen() }
            return
        }
        nav.startPlace(dest)
        host.rebuild()
    }

    private fun exploreToggleText() = if (g.explore.active) "Explore: On. Tap to turn off" else "Explore: Off. Tap to turn on"

    private fun addNearbyPlaces(ui: UiBuilder) {
        val list = nav.nearbyPlaces
        ui.heading("Nearest locations within ${Fmt.meters(settings.nearestLocationDistance.toDouble())}: ${list.size}")
        if (list.isEmpty()) { ui.info("No saved locations around you inside this distance. Press Refresh or increase the distance."); return }
        val f = g.location.fix
        list.forEach { hit ->
            val key = "place:" + hit.loc.id
            val on = nav.extraTargets.containsKey(key)
            val clock = Geo.clockPosition(g.compass.heading, hit.bearing)
            val text = "${hit.loc.name}, ${Fmt.meters(hit.distance)}, ${Fmt.direction(hit.bearing)}, at $clock. Direction ${if (on) "On" else "Off"}"
            ui.button(text, color = if (on) UiColors.BUTTON_ON else UiColors.BUTTON, stroke = if (on) UiColors.STROKE_ON else null) {
                val nowOn = nav.toggleExtraTarget(FocusTarget(key, hit.loc.name, hit.loc.lat, hit.loc.lon))
                host.say("${hit.loc.name} direction ${if (nowOn) "on" else "off"}")
                host.rebuild()
            }
        }
        if (f == null) ui.info("Waiting for GPS.")
    }

    // =================================================================== Islands

    private fun buildIslands(top: UiBuilder, mid: UiBuilder, bottom: UiBuilder) {
        val islandActive = nav.isIslandNav
        val cur = nav.currentIsland()
        top.row(
            ("Current island: " + (cur?.name ?: "GPS") + (if (nav.currentIslandIsManual()) ", manual" else "") + ". Tap to change") to { host.show { CurrentIslandScreen() } },
            (nav.islandDestination?.let { "Destination: ${it.name}. Tap to change" } ?: "Choose destination island") to {
                host.show { AtollPickerScreen("Destination atoll") { island -> nav.islandDestination = island; host.say("Destination island: ${island.fullName}"); host.showTabs("Islands") } }
            }
        )
        top.row(
            "Travel mode: ${settings.travelMode.label}" to { host.show(Pickers.travelMode(host)) },
            (if (islandActive) "Stop navigation" else "Start navigation") to {
                if (islandActive) { nav.stop(); host.rebuild() } else startIslandNavigation()
            }
        )

        addPermissionWarnings(mid)
        previewView = mid.info(nav.islandPreviewText())
        if (islandActive) {
            statusView = mid.info("")
            distanceView = mid.info("")
            speedView = mid.info("")
            etaView = mid.info("")
            focusView = mid.info("")
            mid.button(exploreToggleText()) { g.explore.toggle(); host.rebuild() }
            if (nav.islandsNearbyOn) addNearbyIslands(mid) else mid.info("Nearest islands are Off. Only destination island direction is active.")
        } else {
            if (nav.lastArrivalText.isNotBlank()) mid.info(nav.lastArrivalText)
            mid.info("Set current island and destination island, then press Start navigation. When you face an active island within ${settings.focusAccuracy} degree, the phone vibrates and says its name.")
        }

        if (islandActive) {
            bottom.row(
                (if (nav.islandsNearbyOn) "Nearest islands: On" else "Nearest islands: Off") to {
                    nav.setIslandsNearby(!nav.islandsNearbyOn)
                    host.say(if (nav.islandsNearbyOn) "Nearest front islands on" else "Nearest islands off")
                    host.rebuild()
                },
                "Refresh nearest" to {
                    if (!nav.islandsNearbyOn) nav.setIslandsNearby(true) else nav.refreshNearbyIslands()
                    host.say("Nearest front islands refreshed"); host.rebuild()
                }
            )
        }
        bottom.row(
            "Nearest area: ${Fmt.meters(settings.nearestIslandArea.toDouble())}" to {
                host.show(Pickers.meters(host, "Nearest island area", listOf(2000, 5000, 10000, 20000, 30000, 50000), settings.nearestIslandArea) {
                    settings.nearestIslandArea = it; if (nav.islandsNearbyOn) nav.refreshNearbyIslands()
                })
            },
            "Island arrival: ${Fmt.meters(settings.islandArrivalDistance.toDouble())}" to {
                host.show(Pickers.meters(host, "Island arrival distance", listOf(200, 500, 1000, 2000, 3000), settings.islandArrivalDistance) { settings.islandArrivalDistance = it })
            }
        )
    }

    private fun startIslandNavigation() {
        if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return }
        val dest = nav.islandDestination
        if (dest == null) {
            g.speaker.speak("Please choose destination island first.", force = true)
            host.show { AtollPickerScreen("Destination atoll") { island -> nav.islandDestination = island; host.showTabs("Islands") } }
            return
        }
        nav.startIsland(dest)
        host.rebuild()
    }

    private fun addNearbyIslands(ui: UiBuilder) {
        val list = nav.nearbyIslands
        ui.heading("Nearest front islands within ${Fmt.meters(settings.nearestIslandArea.toDouble())}: ${list.size}")
        if (list.isEmpty()) { ui.info("No islands in front of you inside this area. Turn, then press Refresh nearest, or increase the area."); return }
        list.forEach { hit ->
            val key = "island:" + hit.island.key
            val on = nav.extraTargets.containsKey(key)
            val text = "${hit.island.fullName}, ${Fmt.meters(hit.distance)}, ${Fmt.direction(hit.bearing)}. Direction ${if (on) "On" else "Off"}"
            ui.button(text, color = if (on) UiColors.BUTTON_ON else UiColors.BUTTON, stroke = if (on) UiColors.STROKE_ON else null) {
                val nowOn = nav.toggleExtraTarget(FocusTarget(key, hit.island.fullName, hit.island.lat, hit.island.lon))
                host.say("${hit.island.name} direction ${if (nowOn) "on" else "off"}")
                host.rebuild()
            }
        }
    }

    // =================================================================== Settings

    private fun onOff(b: Boolean) = if (b) "On. Tap to turn Off" else "Off. Tap to turn On"

    private fun toggle(ui: UiBuilder, label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        ui.button("$label ${onOff(get())}") {
            set(!get())
            host.say("$label ${if (get()) "on" else "off"}")
            host.rebuild()
        }
    }

    private fun buildSettings(ui: UiBuilder) {
        ui.button("Travel mode: ${settings.travelMode.label}. Tap to change") { host.show(Pickers.travelMode(host)) }

        ui.heading("Google Maps")
        toggle(ui, "Google Maps API", { settings.googleEnabled }) { settings.googleEnabled = it }
        val keyEdit = ui.edit("Google Maps API key", settings.googleApiKey, password = true)
        ui.row(
            "Save API key" to {
                settings.googleApiKey = keyEdit.text.toString().trim()
                if (settings.googleApiKey.isNotEmpty()) settings.googleEnabled = true
                host.say(if (settings.googleApiKey.isEmpty()) "Google API key cleared" else "Google API key saved")
                host.rebuild()
            },
            "Check API and billing" to {
                settings.googleApiKey = keyEdit.text.toString().trim()
                if (settings.googleApiKey.isEmpty()) {
                    g.speaker.speak("Paste and save the Google Maps API key first.", force = true)
                } else {
                    settings.googleLastStatus = "Checking. Please wait."
                    host.say("Checking Google API")
                    host.scope.launch {
                        val (_, msg) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { g.google.checkKey() }
                        settings.googleLastStatus = msg
                        g.speaker.speak(msg, force = true)
                        host.rebuild()
                    }
                }
            }
        )
        if (settings.googleLastStatus.isNotBlank()) ui.info("Google status: " + settings.googleLastStatus)
        ui.row(
            "Google setup guide" to { host.show { googleGuide() } },
            "Clear API key" to {
                settings.googleApiKey = ""; settings.googleEnabled = false; settings.googleLastStatus = ""
                host.say("Google API key cleared"); host.rebuild()
            }
        )

        ui.heading("Navigation")
        ui.button("Direction focus accuracy: ${settings.focusAccuracy} degree${if (settings.focusAccuracy == 1) "" else "s"} each side. Tap to change") {
            host.show(Pickers.number(host, "Direction focus accuracy",
                "The phone vibrates when you face a target within this many degrees. 1 degree is the most exact; larger values are easier to find.",
                listOf(1, 2, 3, 5, 10, 15, 20), settings.focusAccuracy, { "$it degree" + if (it == 1) "" else "s" }) { settings.focusAccuracy = it })
        }
        ui.button("Saved location arrival distance: ${Fmt.meters(settings.arrivalDistance.toDouble())}. Tap to change") { arrivalPicker() }
        ui.button("Island arrival distance: ${Fmt.meters(settings.islandArrivalDistance.toDouble())}. Tap to change") {
            host.show(Pickers.meters(host, "Island arrival distance", listOf(200, 500, 1000, 2000, 3000), settings.islandArrivalDistance) { settings.islandArrivalDistance = it })
        }
        toggle(ui, "Announce nearby islands while travelling", { settings.islandArrivalIdentityOn }) { settings.islandArrivalIdentityOn = it }
        toggle(ui, "Spoken turn instructions", { settings.turnAnnouncementsOn }) { settings.turnAnnouncementsOn = it }
        toggle(ui, "Keep screen awake while navigating", { settings.keepAwake }) { settings.keepAwake = it }

        ui.heading("Feedback")
        toggle(ui, "Voice feedback", { settings.voiceOn }) { settings.voiceOn = it }
        ui.button("Speech rate: ${String.format(Locale.US, "%.2f", settings.speechRate)}. Tap to change") {
            val rates = listOf(75, 90, 100, 125, 150, 175, 200)
            host.show(Pickers.number(host, "Speech rate", "1.00 is normal speed.", rates, (settings.speechRate * 100).roundToInt(),
                { String.format(Locale.US, "%.2f", it / 100f) }) { settings.speechRate = it / 100f; g.speaker.applyRate(); g.speaker.speak("This is the new speech rate.", force = true) })
        }
        toggle(ui, "Direction voice hints", { settings.directionVoiceHintsOn }) { settings.directionVoiceHintsOn = it }
        if (settings.directionVoiceHintsOn) {
            ui.button("Voice hint interval: ${settings.correctionIntervalSec} seconds. Tap to change") {
                host.show(Pickers.number(host, "Voice hint interval", null, listOf(2, 3, 4, 6, 8, 10), settings.correctionIntervalSec, { "$it seconds" }) { settings.correctionIntervalSec = it })
            }
        }
        toggle(ui, "Vibration feedback", { settings.vibrationOn }) { settings.vibrationOn = it; if (!it) g.haptics.stopFocus() }
        ui.button("Direction focus vibration: ${settings.focusVibration}. Tap to change") {
            host.show { VibrationPatternScreen("Direction focus vibration", settings.focusVibration, true) { settings.focusVibration = it } }
        }
        ui.button("Arrival vibration: ${settings.arrivalVibration}. Tap to change") {
            host.show { VibrationPatternScreen("Arrival vibration", settings.arrivalVibration, false) { settings.arrivalVibration = it } }
        }

        ui.heading("GPS and data")
        gpsView = ui.info(gpsText())
        ui.button("Refresh GPS") { g.location.restart(); host.say("GPS refreshed") }
        if (!g.compass.available) ui.info("This phone has no compass sensor. Direction focus is not available.")
        ui.button("Location Management") { host.show { LocationManagementScreen() } }
        ui.button("About") { host.show { about() } }
        credit(ui)
    }

    private fun googleGuide() = TextScreen("Google setup guide", listOf(
        "Google Maps is optional. Without it, Haluvi Navigation still works with saved places, downloaded OpenStreetMap places, islands and compass guidance.",
        "Step 1. On a computer or browser, open console.cloud.google.com and sign in. Create a project.",
        "Step 2. Open Billing and link a billing account. Google gives a monthly free usage amount, but billing must be connected.",
        "Step 3. Open APIs and Services, Library. Enable these APIs: Places API (New), Routes API, and Geocoding API.",
        "Step 4. Open APIs and Services, Credentials. Create an API key. For safety, restrict the key to these three APIs.",
        "Step 5. Copy the key, paste it in Settings, Google Maps API key, press Save API key, then press Check API and billing.",
        "The key is stored only on this phone."
    ))

    private fun about() = TextScreen("About", listOf(
        "Haluvi Navigation, version ${BuildConfig.VERSION_NAME}.",
        "An accessible GPS navigation assistant for blind and low vision users in the Maldives: saved places, downloaded OpenStreetMap island places, island navigation with direction focus vibration, Google route guidance, Explore mode, voice guidance and arrival detection.",
        "Developed by Hussain Shareef."
    ))

    // =================================================================== live updates

    override fun onLive() {
        // Navigation started or stopped elsewhere (arrival, notification Stop): rebuild once.
        if (nav.target?.kind != builtActiveKind || (nav.route != null) != builtRoute) {
            builtActiveKind = nav.target?.kind
            builtRoute = nav.route != null
            if (tab == "Places" || tab == "Islands" || tab == "Home") { rebuild(); return }
        }
        if (!nav.isActive) return
        statusView?.text = nav.statusText.ifBlank { "Starting." }
        distanceView?.text = nav.distanceText.ifBlank { "Distance: calculating." }
        speedView?.text = nav.speedText.ifBlank { "Current speed: waiting for movement" }
        altitudeView?.text = nav.altitudeText.ifBlank { "Altitude: waiting for GPS" }
        etaView?.text = nav.etaText.ifBlank { "Estimated arrival: waiting for movement" }
        focusView?.text = nav.focusText.ifBlank { "Direction focus: waiting for compass." }
        routeView?.text = nav.routeMessage.ifBlank { "Road route: waiting." }
    }

    override fun onSecond() {
        compassView?.text = WhereAmI.compassText()
        islandView?.text = currentIslandText()
        gpsView?.text = gpsText()
        if (!nav.isActive || nav.isIslandNav) previewView?.text = if (tab == "Islands") nav.islandPreviewText() else nav.placePreviewText()
        if (nav.isActive) focusView?.text = nav.focusText.ifBlank { "Direction focus: waiting for compass." }
    }
}
