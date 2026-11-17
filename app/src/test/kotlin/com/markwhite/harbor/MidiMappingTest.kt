package com.markwhite.harbor

import com.markwhite.harbor.midi.DeviceMapping
import com.markwhite.harbor.midi.KnobBinding
import com.markwhite.harbor.midi.KnobMode
import com.markwhite.harbor.midi.KnobSensitivity
import com.markwhite.harbor.midi.MidiTrigger
import com.markwhite.harbor.midi.TriggerKind
import com.markwhite.harbor.ui.panel.Fm1Panel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MidiMappingTest {
    @Test
    fun launchkeyAndMpkPresetsMapAllEightFm1KnobsInExpectedOrder() {
        val lk = DeviceMapping. defaultForDevice("Launchkey Mini MK3 MIDI 1")
        assertEquals(8, lk.mappedCount)
        assertEquals(MidiTrigger(TriggerKind.Cc, 21), lk.knobs[2]?.cc) // KNOB 1
        assertEquals(MidiTrigger(TriggerKind.Cc, 22), lk.knobs[3]?.cc) // KNOB 2
        assertEquals(MidiTrigger(TriggerKind.Cc, 23), lk.knobs[4]?.cc) // KNOB 3
        assertEquals(MidiTrigger(TriggerKind.Cc, 24), lk.knobs[5]?.cc) // KNOB 4
        assertEquals(MidiTrigger(TriggerKind.Cc, 25), lk.knobs[Fm1Panel.SELECT.id]?.cc)
        assertEquals(MidiTrigger(TriggerKind.Cc, 26), lk.knobs[Fm1Panel.PRESETS.id]?.cc)
        assertEquals(MidiTrigger(TriggerKind.Cc, 27), lk.knobs[Fm1Panel.ALGORITHM.id]?.cc)
        assertEquals(MidiTrigger(TriggerKind.Cc, 28), lk.knobs[DeviceMapping.MASTER_KNOB_ID]?.cc)

        val mpk = DeviceMapping.defaultForDevice("MPK mini 3")
        assertEquals(8, mpk.mappedCount)
        assertEquals(MidiTrigger(TriggerKind.Cc, 70), mpk.knobs[2]?.cc)
        assertEquals(MidiTrigger(TriggerKind.Cc, 77), mpk.knobs[DeviceMapping.MASTER_KNOB_ID]?.cc)

        val mpkRel = DeviceMapping.AKAI_MPK_MINI_RELATIVE
        assertEquals(8, mpkRel.mappedCount)
        assertEquals(KnobMode.RelativeTwosComp, mpkRel.knobMode)
        assertEquals(KnobSensitivity.Fine, mpkRel.sensitivity)
        assertEquals(listOf(KnobSensitivity.ExtraFine, KnobSensitivity.Fine, KnobSensitivity.Normal, KnobSensitivity.Fast), KnobSensitivity.UI_ORDER)
    }

    @Test
    fun withoutTriggerDeduplicatesReassignedControls() {
        val t = MidiTrigger(TriggerKind.Cc, 21, channel = -1)
        val initial = DeviceMapping(
            knobs = mapOf(2 to KnobBinding(cc = t)),
            buttons = mapOf(12 to t),
        )
        val cleaned = initial.withoutTrigger(t)
        assertNull(cleaned.knobs[2])
        assertNull(cleaned.buttons[12])
        assertEquals(0, cleaned.mappedCount)
    }

    @Test
    fun jsonRoundTripAndNativeRulePackingPreserveBindings() {
        val m = DeviceMapping(
            knobMode = KnobMode.Absolute,
            sensitivity = KnobSensitivity.Fast,
            knobs = mapOf(
                2 to KnobBinding(
                    cc = MidiTrigger(TriggerKind.Cc, 21, -1),
                    stepDown = MidiTrigger(TriggerKind.Note, 36, 9),
                    stepUp = MidiTrigger(TriggerKind.Note, 37, 9),
                ),
                DeviceMapping.MASTER_KNOB_ID to KnobBinding(cc = MidiTrigger(TriggerKind.Cc, 28, -1)),
            ),
            buttons = mapOf(12 to MidiTrigger(TriggerKind.Note, 40, 9)),
        )
        val encoded = Json.encodeToString(m)
        val decoded = Json.decodeFromString<DeviceMapping>(encoded)
        assertEquals(m, decoded)
        assertArrayEquals(m.toNativeRules(), decoded.toNativeRules())
        // 5 bindings * 5 ints each = 25 ints
        assertEquals(25, decoded.toNativeRules().size)
    }
}
