package mv.haluvi.navigation.ui

import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.BuildConfig
import mv.haluvi.navigation.data.TravelMode
import mv.haluvi.navigation.nav.Phrases
import java.util.Locale
import kotlin.math.roundToInt

private fun onOff(b: Boolean) = if (b) "On" else "Off"

/** A toggle button: "Label: On". */
private fun Screen.toggle(ui: UiBuilder, label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
    ui.button("$label: ${onOff(get())}") {
        set(!get())
        host.say("$label ${if (get()) "on" else "off"}")
        rebuild()
    }
}

/** Background island alerts switch (also on the Islands screen). */
fun islandAlertsToggle(screen: Screen, ui: UiBuilder) {
    val s = AppGraph.settings
    val host = screen.host
    ui.button("Island alerts in background: ${onOff(s.islandAlertsOn)}") {
        if (!s.islandAlertsOn && !host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return@button }
        s.islandAlertsOn = !s.islandAlertsOn
        if (s.islandAlertsOn) mv.haluvi.navigation.nav.NavigationService.ensureRunning(host)
        else mv.haluvi.navigation.nav.NavigationService.updateOrStop(host)
        host.say(if (s.islandAlertsOn) "Island alerts on" else "Island alerts off")
        screen.rebuild()
    }
    if (s.islandAlertsOn) ui.info("At sea, islands are announced and shown as notifications, even when the app is closed.")
}

class SettingsScreen : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Settings")
        ui.button("Voice and vibration") { host.show { VoiceSettingsScreen() } }
        ui.button("Navigation") { host.show { NavigationSettingsScreen() } }
        ui.button("Google Maps") { host.show { GoogleSettingsScreen() } }
        ui.button("Offline maps") { host.show { OfflineMapsScreen() } }
        ui.button("About") {
            host.show { TextScreen("About", listOf(
                "Haluvi Navigation ${BuildConfig.VERSION_NAME}.",
                "Accessible navigation for blind and low vision people in the Maldives.",
                "Developed by Hussain Shareef."
            )) }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

class VoiceSettingsScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val s = g.settings
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Voice and vibration")
        toggle(ui, "Voice", { s.voiceOn }) { s.voiceOn = it }
        ui.info("Uses your phone's default speech engine at accessibility volume.")
        ui.button("Speech rate: ${String.format(Locale.US, "%.2f", s.speechRate)}") {
            host.show(Pickers.number(host, "Speech rate", "1.00 is normal.", listOf(75, 90, 100, 125, 150, 175, 200), (s.speechRate * 100).roundToInt(),
                { String.format(Locale.US, "%.2f", it / 100f) }) { s.speechRate = it / 100f; g.speaker.applyRate() })
        }
        ui.button("Test voice") { g.speaker.speak("This is Haluvi Navigation.", force = true) }
        ui.button("Reconnect speech engine") { g.speaker.connect(); host.say("Speech engine reconnected") }
        toggle(ui, "Vibration", { s.vibrationOn }) { s.vibrationOn = it; if (!it) g.haptics.stopFocus() }
        ui.button("Pointing vibration: ${s.focusVibration}") {
            host.show { VibrationPatternScreen("Pointing vibration", s.focusVibration, true) { s.focusVibration = it } }
        }
        ui.button("Arrival vibration: ${s.arrivalVibration}") {
            host.show { VibrationPatternScreen("Arrival vibration", s.arrivalVibration, false) { s.arrivalVibration = it } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

class NavigationSettingsScreen : Screen() {
    override fun create(): View {
        val s = AppGraph.settings
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Navigation")
        ui.button("Travel mode: ${s.travelMode.label}") {
            s.travelMode = if (s.travelMode == TravelMode.WALKING) TravelMode.DRIVING else TravelMode.WALKING
            AppGraph.nav.refreshRoute()
            host.say("Travel mode: ${s.travelMode.label}")
            rebuild()
        }
        ui.button(if (s.clockDirections) "Directions: clock. Example: 2 o'clock is ahead to the right. Tap for compass."
            else "Directions: compass. Example: north east, 45 degrees. Tap for clock.") {
            s.clockDirections = !s.clockDirections
            host.say(if (s.clockDirections) "Directions as clock positions" else "Directions as compass degrees")
            rebuild()
        }
        toggle(ui, "Miles too", { s.milesToo }) { s.milesToo = it }
        toggle(ui, "Spoken turn instructions", { s.turnAnnouncementsOn }) { s.turnAnnouncementsOn = it }
        ui.button("Pointing accuracy: ${s.focusAccuracy} degrees") {
            host.show(Pickers.number(host, "Pointing accuracy", "The phone vibrates when it points at the destination within this many degrees.",
                listOf(1, 2, 3, 5, 10, 15), s.focusAccuracy, { "$it degree" + if (it == 1) "" else "s" }) { s.focusAccuracy = it })
        }
        toggle(ui, "Turn hints by voice", { s.directionVoiceHintsOn }) { s.directionVoiceHintsOn = it }
        ui.button("Arrival distance: ${Phrases.distance(s.arrivalDistance.toDouble())}") {
            host.show(Pickers.meters(host, "Arrival distance", listOf(5, 10, 15, 20, 30, 50), s.arrivalDistance) { s.arrivalDistance = it })
        }
        ui.button("Island arrival: ${Phrases.distance(s.islandArrivalDistance.toDouble())}") {
            host.show(Pickers.meters(host, "Island arrival distance", listOf(300, 500, 1000, 2000), s.islandArrivalDistance) { s.islandArrivalDistance = it })
        }
        toggle(ui, "Island stop announcements", { s.islandStopAnnouncementsOn }) { s.islandStopAnnouncementsOn = it }
        islandAlertsToggle(this, ui)
        toggle(ui, "Keep screen on", { s.keepAwake }) { s.keepAwake = it }
        ui.button("Refresh GPS") { AppGraph.location.restart(); host.say("GPS refreshed") }
        ui.button("Back") { host.back() }
        return scroll
    }
}

class GoogleSettingsScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val s = g.settings
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Google Maps")
        ui.info("Optional. Gives road routes, road names and more places.")
        toggle(ui, "Google Maps", { s.googleEnabled }) { s.googleEnabled = it }
        val key = ui.edit("API key", s.googleApiKey, password = true)
        ui.row(
            "Save key" to {
                s.googleApiKey = key.text.toString().trim()
                if (s.googleApiKey.isNotEmpty()) s.googleEnabled = true
                host.say(if (s.googleApiKey.isEmpty()) "Key cleared" else "Key saved")
                rebuild()
            },
            "Check key" to {
                s.googleApiKey = key.text.toString().trim()
                if (s.googleApiKey.isEmpty()) host.speak("Please paste the key first.")
                else {
                    host.speak("Checking.")
                    host.scope.launch {
                        val (_, msg) = withContext(Dispatchers.IO) { g.google.checkKey() }
                        s.googleLastStatus = msg
                        host.speak(msg)
                        if (host.currentScreen() === this@GoogleSettingsScreen) rebuild()
                    }
                }
            }
        )
        if (s.googleLastStatus.isNotBlank()) ui.info(s.googleLastStatus)
        ui.button("How to get a key") {
            host.show { TextScreen("How to get a key", listOf(
                "1. On a computer, open console.cloud.google.com and create a project.",
                "2. Add billing to the project.",
                "3. Enable: Places API (New), Routes API, Geocoding API.",
                "4. Create an API key in Credentials.",
                "5. Paste it here, press Save key, then Check key."
            )) }
        }
        ui.button("Clear key") { s.googleApiKey = ""; s.googleEnabled = false; s.googleLastStatus = ""; host.say("Key cleared"); rebuild() }
        ui.button("Back") { host.back() }
        return scroll
    }
}
