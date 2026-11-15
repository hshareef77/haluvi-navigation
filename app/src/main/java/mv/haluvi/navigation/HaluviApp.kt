package mv.haluvi.navigation

import android.app.Application
import mv.haluvi.navigation.data.IslandRepository
import mv.haluvi.navigation.data.IslandShapes
import mv.haluvi.navigation.data.LocationStore
import mv.haluvi.navigation.data.Settings
import mv.haluvi.navigation.feedback.Haptics
import mv.haluvi.navigation.feedback.Speaker
import mv.haluvi.navigation.nav.ExploreEngine
import mv.haluvi.navigation.nav.IslandWatcher
import mv.haluvi.navigation.nav.NavigationEngine
import mv.haluvi.navigation.net.GoogleMaps
import mv.haluvi.navigation.net.OpenStreetMap
import mv.haluvi.navigation.sensors.CompassTracker
import mv.haluvi.navigation.sensors.LocationTracker

class HaluviApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

/** Application-wide singletons. Navigation lives here so it survives screen changes and runs with the screen off. */
object AppGraph {
    lateinit var settings: Settings; private set
    lateinit var islands: IslandRepository; private set
    lateinit var shapes: IslandShapes; private set
    lateinit var store: LocationStore; private set
    lateinit var location: LocationTracker; private set
    lateinit var compass: CompassTracker; private set
    lateinit var speaker: Speaker; private set
    lateinit var haptics: Haptics; private set
    lateinit var google: GoogleMaps; private set
    lateinit var osm: OpenStreetMap; private set
    lateinit var nav: NavigationEngine; private set
    lateinit var explore: ExploreEngine; private set
    lateinit var islandWatcher: IslandWatcher; private set
    lateinit var routes: mv.haluvi.navigation.data.TripRoutes; private set
    lateinit var routeTrip: mv.haluvi.navigation.nav.RouteTrip; private set
    lateinit var routeRecorder: mv.haluvi.navigation.nav.RouteRecorder; private set

    /** True while the app's screen is visible (notifications are then not needed). */
    @Volatile var uiVisible = false

    lateinit var appContext: android.content.Context; private set

    fun init(app: Application) {
        appContext = app
        settings = Settings(app)
        islands = IslandRepository(app)
        shapes = IslandShapes(app, islands)
        store = LocationStore(app, islands)
        location = LocationTracker(app)
        compass = CompassTracker(app)
        speaker = Speaker(app, settings)
        haptics = Haptics(app, settings)
        google = GoogleMaps { settings.googleApiKey }
        osm = OpenStreetMap()
        nav = NavigationEngine(app, settings, islands, shapes, store, location, compass, speaker, haptics, google)
        explore = ExploreEngine(app, settings, islands, store, location, speaker, google, nav)
        islandWatcher = IslandWatcher(app, settings, shapes, location, speaker, haptics, nav) { explore.active }
        explore.atSea = { f -> islandWatcher.atSea(f) }
        routes = mv.haluvi.navigation.data.TripRoutes(java.io.File(app.filesDir, "routes.json"))
        routeTrip = mv.haluvi.navigation.nav.RouteTrip(app)
        routeRecorder = mv.haluvi.navigation.nav.RouteRecorder(app)
        location.addListener { f -> mv.haluvi.navigation.nav.RoadDetector.onFix(f) }
        compass.onCalibrationNeeded = {
            if (nav.isActive || explore.active) speaker.speak("Compass needs calibration. Move the phone in a figure eight.", flush = false)
        }
    }
}
