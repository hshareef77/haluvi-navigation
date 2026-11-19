package mv.haluvi.navigation.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mv.haluvi.navigation.AppGraph

/**
 * Single activity. Holds a back stack of screens so Back always goes to the previous screen
 * (fixes the Lua "Back loops to the same screen" problem). Screens keep their state (search text,
 * filters) and rebuild their views when shown again.
 */
class MainActivity : Activity() {
    private val stack = ArrayList<Screen>()
    private var current: Screen? = null
    private lateinit var container: FrameLayout
    private val main = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var visible = false
    private var lastLiveMs = 0L
    private var pendingFileResult: ((Uri?) -> Unit)? = null

    private val liveListener: () -> Unit = {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLiveMs >= 400) {
            lastLiveMs = now
            runCatching { current?.onLive() }
        }
        updateKeepScreenOn()
    }

    private val second = object : Runnable {
        override fun run() {
            if (!visible) return
            runCatching { current?.onSecond() }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        container.setBackgroundColor(UiColors.BG)
        container.fitsSystemWindows = true
        setContentView(container)
        AppGraph.nav.addListener(liveListener)
        AppGraph.explore.addListener(liveListener)
        showHome()
        if (!AppGraph.settings.guideDone) show { GuideScreen() } else {
            requestPermissionsIfNeeded()
            installBuiltinMapsIfNew()
        }
        if (AppGraph.settings.islandAlertsOn) mv.haluvi.navigation.nav.NavigationService.ensureRunning(this)
        // Keep saved Google places within Google's terms (positions refreshed from place IDs).
        if (AppGraph.settings.googleReady) scope.launch { runCatching { mv.haluvi.navigation.nav.GooglePlaceRefresh.run() } }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        AppGraph.uiVisible = true
        AppGraph.location.setInterval(1000L)
        AppGraph.location.acquire()
        AppGraph.compass.acquire()
        main.post(second)
        updateKeepScreenOn()
    }

    override fun onStop() {
        visible = false
        AppGraph.uiVisible = false
        main.removeCallbacks(second)
        AppGraph.location.release()
        AppGraph.compass.release()
        super.onStop()
    }

    override fun onDestroy() {
        AppGraph.nav.removeListener(liveListener)
        AppGraph.explore.removeListener(liveListener)
        current?.onDispose()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ navigation between screens

    fun currentScreen(): Screen? = current

    fun show(factory: () -> Screen) {
        val screen = factory()
        stack.add(screen)
        display(screen)
    }

    /** Replaces the current screen without adding a Back step. */
    fun replace(factory: () -> Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        show(factory)
    }

    fun rebuild() {
        val s = stack.lastOrNull() ?: return
        display(s, keepFocus = true)
    }

    fun back() {
        if (stack.size <= 1) {
            moveTaskToBack(true)
            return
        }
        stack.removeAt(stack.size - 1)
        display(stack.last())
    }

    /** Goes back to [screen] if it is on the stack. */
    fun backTo(screen: Screen) {
        val idx = stack.indexOfLast { it === screen }
        if (idx < 0) { back(); return }
        while (stack.size > idx + 1) stack.removeAt(stack.size - 1)
        display(screen)
    }

    /** Clears the stack and shows the Home screen. */
    /** Installs the built-in maps of all islands when this app version has newer ones (no internet needed). */
    fun installBuiltinMapsIfNew(quiet: Boolean = true) {
        runCatching { mv.haluvi.navigation.nav.BuiltinMaps.loadBusRoutesIfNew() }
        val date = mv.haluvi.navigation.nav.BuiltinMaps.stamp() ?: return
        if (AppGraph.settings.builtinInstalledDate == date || OfflineDownload.active) return
        startOfflineDownload(this, AppGraph.islands.allIslands, "the whole Maldives", builtin = true, quiet = quiet) { ok ->
            if (ok) AppGraph.settings.builtinInstalledDate = date
        }
    }

    fun showHome() {
        stack.clear()
        show { HomeScreen() }
    }

    /** Clears the stack to Home and opens a trip screen (Places or Islands). */
    fun showTrip(factory: () -> Screen) {
        stack.clear()
        stack.add(HomeScreen())
        show(factory)
    }

    /** Clears the stack and opens the navigation screen (after Start). */
    fun showNavigation() {
        stack.clear()
        stack.add(HomeScreen())
        show { NavigationScreen() }
    }

    /**
     * Speaks a result once: with the app voice when Voice is on, otherwise through the screen reader.
     * Never both, so nothing is spoken twice.
     */
    fun speak(text: String) {
        if (AppGraph.settings.voiceOn) AppGraph.speaker.speak(text) else say(text)
    }

    private fun display(screen: Screen, keepFocus: Boolean = false) {
        // When a screen rebuilds itself (for example after a toggle), keep TalkBack on the same control.
        val focusIndex = if (keepFocus) currentViewsIndexOfFocus() else -1
        if (current !== screen) current?.onDispose()
        screen.host = this
        current = screen
        val v = screen.create()
        container.removeAllViews()
        container.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val title = screen.titleView
        main.postDelayed({
            if (current !== screen) return@postDelayed
            val views = textViews(v)
            val target: View? = if (focusIndex >= 0 && focusIndex < views.size) views[focusIndex] else title
            target?.let {
                if (it === title) { it.isFocusableInTouchMode = true; it.requestFocus() }
                it.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
                it.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        }, 150)
    }

    private fun textViews(v: View, out: ArrayList<View> = ArrayList()): ArrayList<View> {
        if (v is android.widget.TextView) out.add(v)
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) textViews(v.getChildAt(i), out)
        return out
    }

    private fun currentViewsIndexOfFocus(): Int {
        val root = container.getChildAt(0) ?: return -1
        val views = textViews(root)
        val idx = views.indexOfFirst { runCatching { it.createAccessibilityNodeInfo().isAccessibilityFocused }.getOrDefault(false) }
        if (idx >= 0) return idx
        return views.indexOfFirst { it.isFocused }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    // ------------------------------------------------------------------ feedback

    /** Short UI confirmation: spoken by TalkBack when it is on, otherwise shown as a toast. */
    fun say(message: String) {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (am.isEnabled && am.isTouchExplorationEnabled) {
            @Suppress("DEPRECATION")
            container.announceForAccessibility(message)
        } else {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateKeepScreenOn() {
        val on = AppGraph.settings.keepAwake && (AppGraph.nav.isActive || AppGraph.explore.active)
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------ permissions

    fun hasLocationPermission() = AppGraph.location.hasPermission()

    fun requestPermissionsIfNeeded() {
        val needed = ArrayList<String>()
        if (!hasLocationPermission()) { needed.add(Manifest.permission.ACCESS_FINE_LOCATION); needed.add(Manifest.permission.ACCESS_COARSE_LOCATION) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasLocationPermission()) {
            AppGraph.location.restart()
            rebuild()
        } else {
            say("Location permission is needed for navigation. You can allow it in Settings.")
        }
    }

    fun openAppSettings() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
    }

    fun openLocationSettings() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    }

    // ------------------------------------------------------------------ files (import / export)

    fun pickFile(mime: Array<String>, result: (Uri?) -> Unit) {
        pendingFileResult = result
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, mime)
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(i, 2001) }.onFailure { pendingFileResult = null; say("No file picker is available.") }
    }

    fun createFile(name: String, mime: String, result: (Uri?) -> Unit) {
        pendingFileResult = result
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name)
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(i, 2002) }.onFailure { pendingFileResult = null; say("No file picker is available.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val cb = pendingFileResult ?: return
        pendingFileResult = null
        cb(if (resultCode == RESULT_OK) data?.data else null)
    }
}
