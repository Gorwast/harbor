package com.markwhite.harbor.midi

import com.markwhite.harbor.ui.panel.Fm1Panel
import kotlinx.serialization.Serializable

@Serializable
enum class TriggerKind { Cc, Note }

/**
 * A MIDI message pattern bound to a front-panel control.
 * [channel]: -1 for any channel (default for CC knobs), or 0..15 (default for Note pads/buttons).
 */
@Serializable
data class MidiTrigger(
    val kind: TriggerKind,
    val number: Int,
    val channel: Int = -1,
) {
    val label: String
        get() {
            val base = when (kind) {
                TriggerKind.Cc -> "CC ${number.coerceIn(0, 127)}"
                TriggerKind.Note -> "Note ${number.coerceIn(0, 127)} (${midiNoteName(number.coerceIn(0, 127))})"
            }
            return if (channel in 0..15) "$base · Ch ${channel + 1}" else base
        }

    /** Compact badge text for the Play-screen MIDI Learn overlay (e.g. `CC21`, `N36`). */
    val shortLabel: String
        get() = when (kind) {
            TriggerKind.Cc -> "CC${number.coerceIn(0, 127)}"
            TriggerKind.Note -> "N${number.coerceIn(0, 127)}"
        }
}

private val NOTE_NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

fun midiNoteName(note: Int): String = "${NOTE_NAMES[note % 12]}${note / 12 - 1}"

@Serializable
enum class KnobMode(val label: String) {
    Absolute("Absolute (0–127)"),
    RelativeTwosComp("Relative (2's comp)"),
    RelativeBinaryOffset("Relative (offset 64)"),
}

@Serializable
enum class KnobSensitivity(val label: String) {
    Fine("Fine"),
    Normal("Normal"),
    Fast("Fast"),
    ExtraFine("Extra fine"),
    ;

    companion object {
        /** Ordered from slowest/finest to fastest for the UI segmented control. */
        val UI_ORDER = listOf(ExtraFine, Fine, Normal, Fast)
    }
}

/**
 * What drives one of the 8 FM-1 knobs: a continuous CC knob and/or Step − / Step + triggers.
 */
@Serializable
data class KnobBinding(
    val cc: MidiTrigger? = null,
    val stepDown: MidiTrigger? = null,
    val stepUp: MidiTrigger? = null,
) {
    val isEmpty: Boolean get() = cc == null && stepDown == null && stepUp == null
}

/**
 * Per-device MIDI control mappings (stored per [MidiSource.key], working across all firmwares).
 *
 * [knobs] is keyed by FM-1 knob id: `0..6` for the 7 encoders (`SELECT` = 0, `ALGORITHM` = 1,
 * `KNOB 1..4` = 2..5, `PRESETS` = 6), and `7` for the absolute `MASTER` pot.
 * [buttons] is keyed by FM-1 button id `0..13`.
 */
@Serializable
data class DeviceMapping(
    val knobMode: KnobMode = KnobMode.Absolute,
    val sensitivity: KnobSensitivity = KnobSensitivity.Normal,
    val knobs: Map<Int, KnobBinding> = emptyMap(),
    val buttons: Map<Int, MidiTrigger> = emptyMap(),
) {
    /** Number of FM-1 controls that have at least one MIDI binding. */
    val mappedCount: Int
        get() = knobs.values.count { !it.isEmpty } + buttons.size

    /** Remove any other binding that already uses [trigger] before assigning it to a new slot. */
    fun withoutTrigger(trigger: MidiTrigger): DeviceMapping {
        fun clean(t: MidiTrigger?) = if (t == trigger) null else t
        val cleanedKnobs = knobs.mapValues { (_, b) ->
            b.copy(cc = clean(b.cc), stepDown = clean(b.stepDown), stepUp = clean(b.stepUp))
        }.filterValues { !it.isEmpty }
        val cleanedButtons = buttons.filterValues { it != trigger }
        return copy(knobs = cleanedKnobs, buttons = cleanedButtons)
    }

    /** Pack into 5-int tuples `[triggerKind, number, channel, targetKind, targetArg]` for JNI. */
    fun toNativeRules(): IntArray {
        val out = ArrayList<Int>()
        fun add(t: MidiTrigger, targetKind: Int, targetArg: Int) {
            out += t.kind.ordinal
            out += t.number.coerceIn(0, 127)
            out += if (t.channel in 0..15) t.channel else -1
            out += targetKind
            out += targetArg
        }
        for ((id, b) in knobs) {
            if (id == MASTER_KNOB_ID) {
                b.cc?.let { add(it, TARGET_POT, 0) }
                b.stepDown?.let { add(it, TARGET_POT_STEP, -32) }
                b.stepUp?.let { add(it, TARGET_POT_STEP, 32) }
            } else if (id in 0..6) {
                b.cc?.let { add(it, TARGET_ENC, id) }
                b.stepDown?.let { add(it, TARGET_ENC_STEP, (id and 0xff) or (-1 shl 8)) }
                b.stepUp?.let { add(it, TARGET_ENC_STEP, (id and 0xff) or (1 shl 8)) }
            }
        }
        for ((id, t) in buttons) {
            if (id in 0..13) add(t, TARGET_BUTTON, id)
        }
        return out.toIntArray()
    }

    companion object {
        const val MASTER_KNOB_ID = 7

        private const val TARGET_ENC = 0
        private const val TARGET_ENC_STEP = 1
        private const val TARGET_POT = 2
        private const val TARGET_POT_STEP = 3
        private const val TARGET_BUTTON = 4

        /** The 8 FM-1 knobs in preset order: KNOB 1–4 first, then SELECT, PRESETS, ALGORITHM, MASTER. */
        data class KnobTarget(val id: Int, val label: String, val subtitle: String)

        val KNOB_TARGETS: List<KnobTarget> = listOf(
            KnobTarget(2, "KNOB 1", "Parameter encoder 1"),
            KnobTarget(3, "KNOB 2", "Parameter encoder 2"),
            KnobTarget(4, "KNOB 3", "Parameter encoder 3"),
            KnobTarget(5, "KNOB 4", "Parameter encoder 4"),
            KnobTarget(Fm1Panel.SELECT.id, "SELECT", "Menu / parameter select"),
            KnobTarget(Fm1Panel.PRESETS.id, "PRESETS", "Patch / preset browser"),
            KnobTarget(Fm1Panel.ALGORITHM.id, "ALGORITHM", "FM algorithm encoder"),
            KnobTarget(MASTER_KNOB_ID, "MASTER", "Output volume pot"),
        )

        /** Map 8 consecutive MIDI CCs (`firstCc..firstCc+7`) to the 8 FM-1 knobs in [KNOB_TARGETS] order. */
        fun eightKnobPreset(
            firstCc: Int,
            knobMode: KnobMode = KnobMode.Absolute,
            sensitivity: KnobSensitivity = KnobSensitivity.Normal,
        ): DeviceMapping = DeviceMapping(
            knobMode = knobMode,
            sensitivity = sensitivity,
            knobs = KNOB_TARGETS.mapIndexed { i, target ->
                target.id to KnobBinding(cc = MidiTrigger(TriggerKind.Cc, firstCc + i, channel = -1))
            }.toMap(),
        )

        /** Novation Launchkey Mini MK3: pots 1–8 send CC 21–28 by default. */
        val LAUNCHKEY_MINI_MK3: DeviceMapping = eightKnobPreset(21)

        /** Akai MPK Mini (default absolute program): knobs K1–K8 send CC 70–77. */
        val AKAI_MPK_MINI: DeviceMapping = eightKnobPreset(70)

        /** Akai MPK Mini flashed with Relative knobs: CC 70–77 in Two's Complement with Fine sensitivity. */
        val AKAI_MPK_MINI_RELATIVE: DeviceMapping = eightKnobPreset(
            firstCc = 70,
            knobMode = KnobMode.RelativeTwosComp,
            sensitivity = KnobSensitivity.Fine,
        )

        /** Default mapping when a device is connected for the first time with no saved mapping. */
        fun defaultForDevice(name: String): DeviceMapping {
            val lower = name.lowercase()
            return when {
                "launchkey" in lower -> LAUNCHKEY_MINI_MK3
                "mpk" in lower -> AKAI_MPK_MINI
                else -> DeviceMapping()
            }
        }
    }
}
