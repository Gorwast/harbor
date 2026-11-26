package com.markwhite.harbor.data

import android.content.Context
import androidx.core.content.edit
import com.markwhite.harbor.midi.DeviceMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class KnobGesture { Vertical, Circular, Both, Horizontal }
enum class KeybedMode { TwoTiers, SingleRow }
enum class BufferMode { LowLatency, Stable, Balanced }
enum class RecordingFormat(val label: String, val ext: String, val mime: String, val nativeCode: Int) {
    Wav16("WAV 16", "wav", "audio/x-wav", 0),
    Wav24("WAV 24", "wav", "audio/x-wav", 1),
    Aiff16("AIFF", "aiff", "audio/aiff", 2),
    M4a("M4A", "m4a", "audio/mp4", 0),
}

/** App preferences (ui-spec §6.6, §6.7) and per-firmware state, in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("harbor", Context.MODE_PRIVATE)

    init {
        // 0.1.0 remembered every automatic step-down at once, so one stall could keep a firmware
        // slow for good: forget those, keeping clocks the user set by hand
        if (prefs.getInt("clockMemory", 1) < 2) {
            prefs.edit {
                for (k in prefs.all.keys) if (k.startsWith("clock.") && prefs.getBoolean("auto." + k.removePrefix("clock."), true)) remove(k)
                putInt("clockMemory", 2)
            }
        }
    }

    private fun <T> flow(read: () -> T) = MutableStateFlow(read())

    private val _knobGesture = flow { KnobGesture.entries.getOrElse(prefs.getInt("knobGesture", 0)) { KnobGesture.Vertical } }
    val knobGesture: StateFlow<KnobGesture> = _knobGesture
    fun setKnobGesture(v: KnobGesture) { prefs.edit { putInt("knobGesture", v.ordinal) }; _knobGesture.value = v }

    /** dp of drag per encoder detent: 18 fine, 12 normal, 8 fast */
    private val _knobDpPerDetent = flow { prefs.getFloat("knobDp", 12f) }
    val knobDpPerDetent: StateFlow<Float> = _knobDpPerDetent
    fun setKnobDpPerDetent(v: Float) { prefs.edit { putFloat("knobDp", v) }; _knobDpPerDetent.value = v }

    private val _tapToStep = flow { prefs.getBoolean("tapToStep", true) }
    val tapToStep: StateFlow<Boolean> = _tapToStep
    fun setTapToStep(v: Boolean) { prefs.edit { putBoolean("tapToStep", v) }; _tapToStep.value = v }

    private val _keybedMode = flow { KeybedMode.entries.getOrElse(prefs.getInt("keybed", 0)) { KeybedMode.TwoTiers } }
    val keybedMode: StateFlow<KeybedMode> = _keybedMode
    fun setKeybedMode(v: KeybedMode) { prefs.edit { putInt("keybed", v.ordinal) }; _keybedMode.value = v }

    /** Active phone-portrait page: 0 full panel, 1 controls only (no keys), 2 screen on case, 3 dark screen. */
    private val _portraitPage = flow { prefs.getInt("portraitPage", 0).coerceIn(0, 3) }
    val portraitPage: StateFlow<Int> = _portraitPage
    fun setPortraitPage(v: Int) {
        val clamped = v.coerceIn(0, 3)
        prefs.edit { putInt("portraitPage", clamped) }
        _portraitPage.value = clamped
    }

    private val _hapticKnobs = flow { prefs.getBoolean("hapticKnobs", true) }
    val hapticKnobs: StateFlow<Boolean> = _hapticKnobs
    fun setHapticKnobs(v: Boolean) { prefs.edit { putBoolean("hapticKnobs", v) }; _hapticKnobs.value = v }

    private val _hapticButtons = flow { prefs.getBoolean("hapticButtons", true) }
    val hapticButtons: StateFlow<Boolean> = _hapticButtons
    fun setHapticButtons(v: Boolean) { prefs.edit { putBoolean("hapticButtons", v) }; _hapticButtons.value = v }

    private val _hapticKeys = flow { prefs.getBoolean("hapticKeys", false) }
    val hapticKeys: StateFlow<Boolean> = _hapticKeys
    fun setHapticKeys(v: Boolean) { prefs.edit { putBoolean("hapticKeys", v) }; _hapticKeys.value = v }

    private val _holdAssist = flow { prefs.getBoolean("holdAssist", false) }
    val holdAssist: StateFlow<Boolean> = _holdAssist
    fun setHoldAssist(v: Boolean) { prefs.edit { putBoolean("holdAssist", v) }; _holdAssist.value = v }

    private val _noteNames = flow { prefs.getInt("noteNames", 0) }
    /** 0 off, 1 C only, 2 all */
    val noteNames: StateFlow<Int> = _noteNames
    fun setNoteNames(v: Int) { prefs.edit { putInt("noteNames", v) }; _noteNames.value = v }

    private val _smoothLcd = flow { prefs.getBoolean("smoothLcd", false) }
    val smoothLcd: StateFlow<Boolean> = _smoothLcd
    fun setSmoothLcd(v: Boolean) { prefs.edit { putBoolean("smoothLcd", v) }; _smoothLcd.value = v }

    private val _flatPanel = flow { prefs.getBoolean("flatPanel", false) }
    val flatPanel: StateFlow<Boolean> = _flatPanel
    fun setFlatPanel(v: Boolean) { prefs.edit { putBoolean("flatPanel", v) }; _flatPanel.value = v }

    /** Tablet landscape: the pane beside the panel (off: the panel takes the width, with big keys). */
    private val _tabletPane = flow { prefs.getBoolean("tabletPane", true) }
    val tabletPane: StateFlow<Boolean> = _tabletPane
    fun setTabletPane(v: Boolean) { prefs.edit { putBoolean("tabletPane", v) }; _tabletPane.value = v }

    /** The panel's colour: a [com.markwhite.harbor.ui.panel.Finish] id. */
    private val _finish = flow { prefs.getString("finish", "dark") ?: "dark" }
    val finish: StateFlow<String> = _finish
    fun setFinish(v: String) { prefs.edit { putString("finish", v) }; _finish.value = v }

    /** Optional ARGB override for the panel case colour; null uses the preset's case. */
    private val _caseColor = flow { if (prefs.contains("caseColor")) prefs.getInt("caseColor", 0) else null }
    val caseColor: StateFlow<Int?> = _caseColor
    fun setCaseColor(argb: Int?) {
        prefs.edit { if (argb == null) remove("caseColor") else putInt("caseColor", argb) }
        _caseColor.value = argb
    }

    /** Optional ARGB override for the panel buttons colour; null uses the preset's buttons. */
    private val _buttonsColor = flow { if (prefs.contains("buttonsColor")) prefs.getInt("buttonsColor", 0) else null }
    val buttonsColor: StateFlow<Int?> = _buttonsColor
    fun setButtonsColor(argb: Int?) {
        prefs.edit { if (argb == null) remove("buttonsColor") else putInt("buttonsColor", argb) }
        _buttonsColor.value = argb
    }

    /** Optional ARGB override for the keybed's general colour; null uses the preset's keys. */
    private val _keysColor = flow { if (prefs.contains("keysColor")) prefs.getInt("keysColor", 0) else null }
    val keysColor: StateFlow<Int?> = _keysColor
    fun setKeysColor(argb: Int?) {
        prefs.edit { if (argb == null) remove("keysColor") else putInt("keysColor", argb) }
        _keysColor.value = argb
    }

    /** Optional per-knob ARGB colour overrides, keyed by `"master"` or `"enc<id>"`. */
    private val _knobColors = flow {
        val raw = prefs.getString("knobColors", null) ?: return@flow emptyMap()
        runCatching { json.decodeFromString<Map<String, Int>>(raw) }.getOrDefault(emptyMap())
    }
    val knobColors: StateFlow<Map<String, Int>> = _knobColors
    fun setKnobColor(key: String, argb: Int?) {
        val next = _knobColors.value.toMutableMap()
        if (argb == null) next.remove(key) else next[key] = argb
        prefs.edit { if (next.isEmpty()) remove("knobColors") else putString("knobColors", json.encodeToString(next)) }
        _knobColors.value = next
    }
    fun setAllKnobColors(argb: Int?, keys: List<String>) {
        val next = if (argb == null) emptyMap() else keys.associateWith { argb }
        prefs.edit { if (next.isEmpty()) remove("knobColors") else putString("knobColors", json.encodeToString(next)) }
        _knobColors.value = next
    }

    private val _immersive = flow { prefs.getBoolean("immersive", true) }
    val immersive: StateFlow<Boolean> = _immersive
    fun setImmersive(v: Boolean) { prefs.edit { putBoolean("immersive", v) }; _immersive.value = v }

    private val _bufferMode = flow { BufferMode.entries.getOrElse(prefs.getInt("buffer", 0)) { BufferMode.LowLatency } }
    val bufferMode: StateFlow<BufferMode> = _bufferMode
    fun setBufferMode(v: BufferMode) { prefs.edit { putInt("buffer", v.ordinal) }; _bufferMode.value = v }

    private val _primeCores = flow { prefs.getBoolean("primeCores", false) }
    val primeCores: StateFlow<Boolean> = _primeCores
    fun setPrimeCores(v: Boolean) { prefs.edit { putBoolean("primeCores", v) }; _primeCores.value = v }

    private val _recordingFormat = flow { RecordingFormat.entries.getOrElse(prefs.getInt("recordingFormat", 0)) { RecordingFormat.Wav16 } }
    val recordingFormat: StateFlow<RecordingFormat> = _recordingFormat
    fun setRecordingFormat(v: RecordingFormat) { prefs.edit { putInt("recordingFormat", v.ordinal) }; _recordingFormat.value = v }

    private val _m4aBitrateKbps = flow { prefs.getInt("m4aBitrateKbps", 192).coerceIn(128, 256) }
    val m4aBitrateKbps: StateFlow<Int> = _m4aBitrateKbps
    fun setM4aBitrateKbps(v: Int) { prefs.edit { putInt("m4aBitrateKbps", v) }; _m4aBitrateKbps.value = v }

    private val _keepPlayingInBackground = flow { prefs.getBoolean("background", false) }
    val keepPlayingInBackground: StateFlow<Boolean> = _keepPlayingInBackground
    fun setKeepPlayingInBackground(v: Boolean) { prefs.edit { putBoolean("background", v) }; _keepPlayingInBackground.value = v }

    private val _keepScreenOn = flow { prefs.getBoolean("screenOn", true) }
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn
    fun setKeepScreenOn(v: Boolean) { prefs.edit { putBoolean("screenOn", v) }; _keepScreenOn.value = v }

    private val _btWarning = flow { prefs.getBoolean("btWarning", true) }
    val btWarning: StateFlow<Boolean> = _btWarning
    fun setBtWarning(v: Boolean) { prefs.edit { putBoolean("btWarning", v) }; _btWarning.value = v }

    private val _visibleToApps = flow { prefs.getBoolean("visibleToApps", true) }
    val visibleToApps: StateFlow<Boolean> = _visibleToApps
    fun setVisibleToApps(v: Boolean) { prefs.edit { putBoolean("visibleToApps", v) }; _visibleToApps.value = v }

    private val _autoEnableMidi = flow { prefs.getBoolean("autoEnableMidi", true) }
    val autoEnableMidi: StateFlow<Boolean> = _autoEnableMidi
    fun setAutoEnableMidi(v: Boolean) { prefs.edit { putBoolean("autoEnableMidi", v) }; _autoEnableMidi.value = v }

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit { putBoolean("onboarded", v) }

    /** The package last played, to resume on launch. */
    var lastSha: String?
        get() = prefs.getString("lastSha", null)
        set(v) = prefs.edit { putString("lastSha", v) }

    // ---- per firmware build (package sha) on this device

    /**
     * The clock it boots at: the user's choice, or the automatic clock's result once it has been
     * seen in two sessions (see [clockSeen]).
     */
    fun clock(sha: String): Int = prefs.getInt("clock.$sha", 240)
    fun setClock(sha: String, mhz: Int) = prefs.edit { putInt("clock.$sha", mhz) }
    fun autoClock(sha: String): Boolean = prefs.getBoolean("auto.$sha", true)
    fun setAutoClock(sha: String, auto: Boolean) = prefs.edit { putBoolean("auto.$sha", auto) }
    /**
     * The clock the automatic clock stepped down to in an earlier session, not yet trusted: a
     * second session that needs it (or less) makes it the boot clock, and a long session that
     * never steps down forgets it. 0: none.
     */
    fun clockSeen(sha: String): Int = prefs.getInt("clockSeen.$sha", 0)
    fun setClockSeen(sha: String, mhz: Int) = prefs.edit { if (mhz == 0) remove("clockSeen.$sha") else putInt("clockSeen.$sha", mhz) }
    /** Whether the user has been told about this firmware's reduced clock. */
    fun clockNoticeShown(sha: String): Boolean = prefs.getBoolean("clockNotice.$sha", false)
    fun setClockNoticeShown(sha: String) = prefs.edit { putBoolean("clockNotice.$sha", true) }

    /** MASTER, 0..1023; the first boot of each firmware starts at 50%. */
    fun master(sha: String): Int = prefs.getInt("master.$sha", 512)
    fun setMaster(sha: String, v: Int) = prefs.edit { putInt("master.$sha", v) }

    // ---- per MIDI source (a stable key: type + name + serial)

    fun midiEnabled(key: String): Boolean? = if (prefs.contains("midi.on.$key")) prefs.getBoolean("midi.on.$key", true) else null
    fun setMidiEnabled(key: String, on: Boolean) = prefs.edit { putBoolean("midi.on.$key", on) }
    /** -1: as sent, else the channel 0..15 */
    fun midiRemap(key: String): Int = prefs.getInt("midi.ch.$key", -1)
    fun setMidiRemap(key: String, ch: Int) = prefs.edit { putInt("midi.ch.$key", ch) }

    /** Per-device control mappings; defaults to a built-in preset when a known controller is first seen. */
    fun midiMapping(key: String, deviceName: String = ""): DeviceMapping {
        val raw = prefs.getString("midi.map.$key", null)
            ?: return DeviceMapping.defaultForDevice(deviceName)
        return runCatching { json.decodeFromString(DeviceMapping.serializer(), raw) }
            .getOrElse { DeviceMapping.defaultForDevice(deviceName) }
    }

    fun setMidiMapping(key: String, mapping: DeviceMapping) =
        prefs.edit { putString("midi.map.$key", json.encodeToString(mapping)) }

    fun btWarned(device: String): Boolean = prefs.getBoolean("btWarned.$device", false)
    fun setBtWarned(device: String) = prefs.edit { putBoolean("btWarned.$device", true) }

    fun tipShown(tip: String): Boolean = prefs.getBoolean("tip.$tip", false)
    fun setTipShown(tip: String) = prefs.edit { putBoolean("tip.$tip", true) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
