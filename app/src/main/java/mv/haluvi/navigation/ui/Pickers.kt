package mv.haluvi.navigation.ui

import android.view.View
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Fmt
import mv.haluvi.navigation.data.VibrationPatterns

object Pickers {
    /** Generic number picker. */
    fun number(host: MainActivity, title: String, intro: String?, values: List<Int>, current: Int, label: (Int) -> String, set: (Int) -> Unit): () -> Screen = {
        ChoiceScreen(title, intro, values.map { v ->
            (label(v) + if (v == current) " - selected" else "") to {
                set(v)
                host.say(label(v) + " selected")
                host.back()
            }
        }, "Cancel")
    }

    fun meters(host: MainActivity, title: String, values: List<Int>, current: Int, set: (Int) -> Unit) =
        number(host, title, null, values, current, { Fmt.meters(it.toDouble()) }, set)
}

/** Vibration pattern picker. Tap selects, long press (or TalkBack action) previews. */
class VibrationPatternScreen(
    private val title: String,
    private val current: String,
    private val includeContinuous: Boolean,
    private val onPick: (String) -> Unit
) : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(title)
        ui.info("Tap to choose. Long press to feel it.")
        val names = (if (includeContinuous) listOf(VibrationPatterns.CONTINUOUS) else emptyList()) + VibrationPatterns.names
        names.forEach { name ->
            ui.button(name + if (name == current) " - selected" else "",
                stroke = if (name == current) UiColors.STROKE_ON else null,
                onLong = { AppGraph.haptics.preview(name) }, longLabel = "Preview") {
                onPick(name)
                AppGraph.haptics.preview(name)
                host.say("$name selected")
                host.back()
            }
        }
        ui.button("Cancel") { host.back() }
        return scroll
    }
}
