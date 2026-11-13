package mv.haluvi.navigation.feedback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import mv.haluvi.navigation.data.Settings
import mv.haluvi.navigation.data.VibrationPatterns

/**
 * Text to speech for navigation guidance.
 *  - Uses the phone's DEFAULT speech engine (the one chosen in Android settings), not Google TTS.
 *  - Uses the engine's own language and voice (nothing is forced).
 *  - Plays at ACCESSIBILITY volume, the same volume as the screen reader.
 * Works with the screen off (unlike screen reader announcements).
 */
class Speaker(context: Context, private val settings: Settings) {
    private val appContext = context.applicationContext
    private var ready = false
    private var pending: String? = null
    private var lastText = ""
    private var lastMs = 0L
    private lateinit var tts: TextToSpeech

    init { connect() }

    private fun defaultEngine(): String? = runCatching {
        android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.TTS_DEFAULT_SYNTH)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** (Re)connects to the current default engine, e.g. after the user changes it in Android settings. */
    fun connect() {
        ready = false
        if (this::tts.isInitialized) runCatching { tts.shutdown() }
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                configure()
                pending?.let { speakNow(it, true) }
                pending = null
            }
        }
        val engine = defaultEngine()
        tts = if (engine != null) TextToSpeech(appContext, listener, engine) else TextToSpeech(appContext, listener)
    }

    val engineName: String get() = runCatching { tts.defaultEngine }.getOrNull() ?: defaultEngine() ?: "default"

    private fun configure() {
        runCatching {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        applyRate()
    }

    fun applyRate() { runCatching { tts.setSpeechRate(settings.speechRate) } }

    /**
     * Speaks [text]. [flush] interrupts what is being said. Identical text within 1.5 s is dropped so
     * that fast repeated events do not stutter.
     */
    fun speak(text: String, flush: Boolean = true, force: Boolean = false) {
        if (!settings.voiceOn && !force) return
        val t = text.trim()
        if (t.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (t == lastText && now - lastMs < 1500) return
        lastText = t; lastMs = now
        if (!ready) { pending = t; return }
        speakNow(t, flush)
    }

    private fun speakNow(text: String, flush: Boolean) {
        val params = android.os.Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_ACCESSIBILITY)
        runCatching { tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "h" + SystemClock.elapsedRealtime()) }
    }

    fun stop() { runCatching { tts.stop() } }
}

/** Vibration: direction-focus vibration (continuous or a repeating pattern) and one-shot patterns. */
class Haptics(context: Context, private val settings: Settings) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Volatile var focusRunning = false
        private set

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()

    val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    /** Starts the non-stop focus vibration (Lua v32 "shared continuous focus vibration"). */
    fun startFocus() {
        if (!settings.vibrationOn || focusRunning || !hasVibrator) return
        focusRunning = true
        val name = settings.focusVibration
        val effect = if (name == VibrationPatterns.CONTINUOUS || VibrationPatterns.patterns[name] == null) {
            VibrationEffect.createWaveform(longArrayOf(0, 1000), intArrayOf(0, 255), 0)
        } else {
            VibrationEffect.createWaveform(VibrationPatterns.patterns[name]!!, 0)
        }
        @Suppress("DEPRECATION")
        runCatching { vibrator?.vibrate(effect, attrs) }
    }

    fun stopFocus() {
        if (!focusRunning) return
        focusRunning = false
        runCatching { vibrator?.cancel() }
    }

    fun playPattern(name: String) {
        if (!settings.vibrationOn || !hasVibrator) return
        focusRunning = false
        val p = VibrationPatterns.patterns[name] ?: VibrationPatterns.patterns["Double pulse"]!!
        @Suppress("DEPRECATION")
        runCatching { vibrator?.cancel(); vibrator?.vibrate(VibrationEffect.createWaveform(p, -1), attrs) }
    }

    /** Preview used by the pattern picker; plays even the "Continuous" choice briefly. */
    fun preview(name: String) {
        if (!hasVibrator) return
        focusRunning = false
        val effect = if (name == VibrationPatterns.CONTINUOUS) VibrationEffect.createOneShot(1200, 255)
        else VibrationEffect.createWaveform(VibrationPatterns.patterns[name] ?: return, -1)
        @Suppress("DEPRECATION")
        runCatching { vibrator?.cancel(); vibrator?.vibrate(effect, attrs) }
    }

    fun arrival() = playPattern(settings.arrivalVibration)

    fun tick() {
        if (!settings.vibrationOn || !hasVibrator) return
        @Suppress("DEPRECATION")
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(60, 200), attrs) }
    }
}
