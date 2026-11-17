package com.markwhite.harbor.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.data.FirmwareNotes
import com.markwhite.harbor.midi.DeviceMapping
import com.markwhite.harbor.midi.HarborMidiService
import com.markwhite.harbor.midi.KnobBinding
import com.markwhite.harbor.midi.KnobMode
import com.markwhite.harbor.midi.KnobSensitivity
import com.markwhite.harbor.midi.MidiHub
import com.markwhite.harbor.midi.MidiSource
import com.markwhite.harbor.midi.MidiTrigger
import com.markwhite.harbor.midi.SourceKind
import com.markwhite.harbor.midi.TriggerKind
import com.markwhite.harbor.midi.midiNoteName
import com.markwhite.harbor.ui.UiState
import com.markwhite.harbor.ui.panel.Fm1Panel
import com.markwhite.harbor.ui.theme.Tokens
import kotlinx.coroutines.delay

/** MIDI (ui-spec §6.5): what plays the FM-1's MIDI IN, and Harbor as a MIDI device. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MidiScreen(app: HarborApp, ui: UiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val midi = app.midi
    val sources by midi.sources.collectAsState()
    val usbHint by midi.usbHint.collectAsState()
    val clients by HarborMidiService.clients.collectAsState()
    val visible by app.settings.visibleToApps.collectAsState()
    val background by app.settings.keepPlayingInBackground.collectAsState()
    val autoEnable by app.settings.autoEnableMidi.collectAsState()
    var hintDismissed by remember { mutableStateOf(false) }
    var usbGuide by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf(false) }
    var mappingSourceKey by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    // the activity dots: refresh the sources' last-message times a few times a second
    LaunchedEffect(Unit) { while (true) { midi.publish(); now = System.nanoTime(); delay(120) } }

    SubScreen("MIDI", onBack) {
        if (usbHint && !hintDismissed) {
            InfoCard {
                Text("ⓘ Connected to a computer by USB", style = MaterialTheme.typography.titleSmall)
                Text("To play Harbor from a DAW on the computer, set this phone's USB mode to MIDI.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { usbGuide = true }) { Text("Show me how") }
                    OutlinedButton(onClick = { openUsbSettings(context) }) { Text("Open settings") }
                    TextButton(onClick = { hintDismissed = true }) { Text("Dismiss", maxLines = 1, softWrap = false) }
                }
            }
        }
        Section("Play from: the FM-1's MIDI IN") {
            sources.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = Tokens.line.copy(alpha = 0.6f))
                SourceRow(midi, s, now) { mappingSourceKey = s.key }
            }
            if (sources.isEmpty()) {
                Text("Nothing connected", style = MaterialTheme.typography.titleSmall)
                Text("Plug in a USB keyboard, pair a Bluetooth one, or connect a computer.", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
            }
            HorizontalDivider(color = Tokens.line.copy(alpha = 0.6f))
            SwitchRow("Enable new devices automatically", autoEnable) { app.settings.setAutoEnableMidi(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { scan = true }) { Text("Pair Bluetooth…") }
            OutlinedButton(onClick = { midi.panic() }) { Text("All notes off") }
        }

        Section("Harbor as a MIDI device") {
            SwitchRow("Visible to other apps", visible, "\"Harbor\" · input \"MIDI IN\"") { on ->
                app.settings.setVisibleToApps(on)
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, HarborMidiService::class.java),
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            Text(if (clients == 0) "No app connected" else "$clients app${if (clients > 1) "s" else ""} connected ⦿")
            SwitchRow("Keep playing in background", background, onChange = rememberBackgroundToggle(app.settings))
        }

        val id = ui.docked?.entry?.id
        if (id != null) {
            Section("For ${ui.shortName}") {
                Text(FirmwareNotes.midiHint(id) ?: "No special MIDI needs known.")
                FirmwareNotes.preferredChannel(id)?.let { ch ->
                    OutlinedButton(onClick = { sources.forEach { midi.setRemap(it, ch) } }) { Text("Use channel ${ch + 1} for every source") }
                }
            }
        }
        TextButton(onClick = { usbGuide = true }) { Text("Play Harbor from a computer (USB)…") }
    }

    if (usbGuide) UsbGuide { usbGuide = false }
    if (scan) BluetoothScanSheet(midi) { scan = false }
    val activeSource = mappingSourceKey?.let { key -> sources.firstOrNull { it.key == key } }
    if (activeSource != null) {
        DeviceMappingSheet(midi, activeSource) { mappingSourceKey = null }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SourceRow(midi: MidiHub, s: MidiSource, now: Long, onConfigure: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val lit = s.lastActivity != 0L && now - s.lastActivity < 150_000_000
    val mapped = s.mapping.mappedCount
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(10.dp)) { drawCircle(if (lit) Tokens.amber else Tokens.line) }
            Column(Modifier.weight(1f)) {
                Text(s.name)
                Text(
                    when (s.kind) {
                        SourceKind.Usb -> "USB"
                        SourceKind.Bluetooth -> "Bluetooth"
                        SourceKind.Computer -> "Computer (USB)"
                        SourceKind.App -> "App"
                    } + if (s.enabled && !s.connected) " · connecting…" else "",
                    style = MaterialTheme.typography.bodySmall, color = Tokens.dim,
                )
            }
            HarborSwitch(s.enabled, onChange = { midi.setEnabled(s, it) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
            Box {
                TextButton(onClick = { menu = true }) { Text("Channel: " + if (s.remap < 0) "as sent" else "${s.remap + 1}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("As sent") }, onClick = { midi.setRemap(s, -1); menu = false })
                    for (ch in 0 until 16) DropdownMenuItem(text = { Text("Channel ${ch + 1}") }, onClick = { midi.setRemap(s, ch); menu = false })
                }
            }
            TextButton(onClick = onConfigure) {
                Text("Controls: " + if (mapped == 0) "configure…" else "$mapped mapped")
            }
        }
    }
}

private fun openUsbSettings(context: Context) {
    // (there's no public intent for the USB preferences screen; the steps cover the rest)
    val intents = listOf(Intent("android.settings.USB_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
    for (i in intents) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

@Composable
private fun UsbGuide(onDone: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Play Harbor from a computer") },
        text = {
            Text(
                "1. Keep the phone plugged into the computer.\n" +
                    "2. Open the \"Charging this device via USB\" notification, or Settings ▸ Connected devices ▸ USB.\n" +
                    "3. Choose \"MIDI\".\n" +
                    "4. Back in Harbor, \"USB Peripheral Port\" appears under Play from.\n" +
                    "5. In your DAW, pick this phone as the MIDI output for a track.\n\n" +
                    "ⓘ Sound still plays from the phone: its speaker, headphones or audio interface, not over the USB cable.\n" +
                    "ⓘ While USB is in MIDI mode the phone can't use a USB MIDI keyboard on the same port.",
            )
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
        dismissButton = { TextButton(onClick = { openUsbSettings(context) }) { Text("Open settings") } },
    )
}

/** Scan for BLE-MIDI devices and connect one (ui-spec S4a). Asks for Nearby devices first. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
private fun BluetoothScanSheet(midi: MidiHub, onDone: () -> Unit) {
    val context = LocalContext.current
    val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    fun granted() = perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var allowed by remember { mutableStateOf(granted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = granted() }
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }

    if (allowed && adapter?.isEnabled == true) {
        DisposableEffect(Unit) {
            val scanner = adapter.bluetoothLeScanner
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    if (found.none { it.address == r.device.address }) found += r.device
                }
            }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MidiHub.BLE_MIDI_SERVICE)).build()
            runCatching { scanner?.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb) }
            scanning = true
            onDispose { runCatching { scanner?.stopScan(cb) }; scanning = false }
        }
        LaunchedEffect(Unit) { delay(20_000); scanning = false }
    }

    ModalBottomSheet(onDismissRequest = onDone) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pair a Bluetooth MIDI device", style = MaterialTheme.typography.titleMedium)
            when {
                !allowed -> {
                    Text("Harbor needs \"Nearby devices\" to find Bluetooth MIDI devices. USB, app and computer sources work without it.")
                    FilledTonalButton(onClick = { ask.launch(perms) }) { Text("Allow") }
                }
                adapter == null -> Text("This device has no Bluetooth.")
                !adapter.isEnabled -> {
                    Text("Bluetooth is off.")
                    FilledTonalButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) { Text("Turn on Bluetooth") }
                }
                else -> {
                    if (scanning) {
                        Text("Scanning… (stops after 20 s)")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    for (d in found) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(runCatching { d.name }.getOrNull() ?: d.address, Modifier.weight(1f))
                            TextButton(onClick = {
                                status = "Connecting…"
                                midi.connectBluetooth(d) { ok -> status = if (ok) "Connected." else "Couldn't connect. Put it in pairing mode and try again." }
                            }) { Text("Connect") }
                        }
                    }
                    if (found.isEmpty() && !scanning) Text("Not listed? Put it in pairing mode, and disconnect it from other apps.", color = Tokens.dim)
                    status?.let { Text(it) }
                }
            }
            Row { Box(Modifier.weight(1f)); TextButton(onClick = onDone) { Text("Done") } }
        }
    }
}

private sealed interface LearnSlot {
    val label: String
    val defaultKind: TriggerKind
    data class KnobCc(val knobId: Int, val name: String) : LearnSlot {
        override val label: String get() = "$name (knob CC)"
        override val defaultKind: TriggerKind get() = TriggerKind.Cc
    }
    data class KnobStepDown(val knobId: Int, val name: String) : LearnSlot {
        override val label: String get() = "$name (Step −)"
        override val defaultKind: TriggerKind get() = TriggerKind.Note
    }
    data class KnobStepUp(val knobId: Int, val name: String) : LearnSlot {
        override val label: String get() = "$name (Step +)"
        override val defaultKind: TriggerKind get() = TriggerKind.Note
    }
    data class PanelBtn(val btnId: Int, val name: String) : LearnSlot {
        override val label: String get() = "$name button"
        override val defaultKind: TriggerKind get() = TriggerKind.Note
    }
}

/**
 * Configure which physical knobs, pads or buttons on [source] control the FM-1's 8 knobs and 14
 * panel buttons. Supports interactive MIDI Learn, manual CC/Note selection, and controller presets.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DeviceMappingSheet(midi: MidiHub, source: MidiSource, onDone: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var learning by remember { mutableStateOf<LearnSlot?>(null) }
    var manualSlot by remember { mutableStateOf<LearnSlot?>(null) }
    val mapping = source.mapping

    fun update(next: DeviceMapping) = midi.setMapping(source, next)

    fun bind(slot: LearnSlot, trigger: MidiTrigger?) {
        val base = if (trigger != null) mapping.withoutTrigger(trigger) else mapping
        val next = when (slot) {
            is LearnSlot.KnobCc -> {
                val cur = base.knobs[slot.knobId] ?: KnobBinding()
                val updated = cur.copy(cc = trigger?.copy(kind = TriggerKind.Cc))
                base.copy(knobs = if (updated.isEmpty) base.knobs - slot.knobId else base.knobs + (slot.knobId to updated))
            }
            is LearnSlot.KnobStepDown -> {
                val cur = base.knobs[slot.knobId] ?: KnobBinding()
                val updated = cur.copy(stepDown = trigger)
                base.copy(knobs = if (updated.isEmpty) base.knobs - slot.knobId else base.knobs + (slot.knobId to updated))
            }
            is LearnSlot.KnobStepUp -> {
                val cur = base.knobs[slot.knobId] ?: KnobBinding()
                val updated = cur.copy(stepUp = trigger)
                base.copy(knobs = if (updated.isEmpty) base.knobs - slot.knobId else base.knobs + (slot.knobId to updated))
            }
            is LearnSlot.PanelBtn -> {
                base.copy(buttons = if (trigger == null) base.buttons - slot.btnId else base.buttons + (slot.btnId to trigger))
            }
        }
        update(next)
    }

    DisposableEffect(learning, source.id) {
        val active = learning
        if (active != null) midi.startLearn(source.id) else midi.stopLearn()
        onDispose { midi.stopLearn() }
    }

    LaunchedEffect(learning, source.id) {
        val slot = learning ?: return@LaunchedEffect
        while (true) {
            val got = midi.pollLearn()
            if (got != null) {
                if (slot is LearnSlot.KnobCc && got.kind != TriggerKind.Cc) {
                    // A button/pad Note received while learning a knob binds to Step + instead.
                    bind(LearnSlot.KnobStepUp(slot.knobId, slot.name), got)
                } else {
                    bind(slot, got)
                }
                learning = null
                break
            }
            delay(40)
        }
    }

    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Controls · ${source.name}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Map knobs, pads or buttons on this device to the FM-1's panel. Saved for this controller across all firmwares.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.dim,
                    )
                }
                TextButton(onClick = onDone) { Text("Done") }
            }

            Section("Quick presets") {
                Text(
                    "Knobs 1–4 map to KNOB 1–4; Knobs 5–8 map to SELECT, PRESETS, ALGORITHM and MASTER.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.dim,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = { learning = null; update(DeviceMapping.LAUNCHKEY_MINI_MK3) }) {
                        Text("Launchkey Mini MK3 (CC 21–28)")
                    }
                    FilledTonalButton(onClick = { learning = null; update(DeviceMapping.AKAI_MPK_MINI) }) {
                        Text("MPK Mini (0–127, CC 70–77)")
                    }
                    FilledTonalButton(onClick = { learning = null; update(DeviceMapping.AKAI_MPK_MINI_RELATIVE) }) {
                        Text("MPK Mini (Relative, CC 70–77)")
                    }
                    if (mapping.mappedCount > 0) {
                        OutlinedButton(onClick = { learning = null; update(DeviceMapping(knobMode = mapping.knobMode, sensitivity = mapping.sensitivity)) }) {
                            Text("Clear all")
                        }
                    }
                }
            }

            Section("Knob behaviour") {
                Choice(
                    "Knob type",
                    listOf(
                        KnobMode.Absolute to "0–127 pot",
                        KnobMode.RelativeTwosComp to "Rel (2's)",
                        KnobMode.RelativeBinaryOffset to "Rel (64)",
                    ),
                    mapping.knobMode,
                ) { mode ->
                    // Switching to Relative from Fast/Normal defaults to Fine if needed, or keeps user choice
                    update(mapping.copy(knobMode = mode))
                }

                Choice(
                    "Knob sensitivity (fine tuning)",
                    KnobSensitivity.UI_ORDER.map { it to it.label },
                    mapping.sensitivity,
                ) { update(mapping.copy(sensitivity = it)) }
                if (mapping.knobMode != KnobMode.Absolute) {
                    Text(
                        "Relative mode tames velocity acceleration. Use Fine or Extra fine if menus step too fast on endless encoders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.dim,
                    )
                }
            }

            learning?.let { slot ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Tokens.amber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, Tokens.amber),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Listening for ${slot.label}…", style = MaterialTheme.typography.titleSmall, color = Tokens.amberHi)
                        Text(
                            if (slot is LearnSlot.KnobCc) "Turn a physical knob on ${source.name}, or pick a CC number by hand."
                            else "Press a button or pad (or turn a knob) on ${source.name}.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { manualSlot = slot; learning = null }) { Text("Pick manually…") }
                            TextButton(onClick = { learning = null }) { Text("Cancel") }
                        }
                    }
                }
            }

            Section("FM-1 knobs (8)") {
                DeviceMapping.KNOB_TARGETS.forEachIndexed { i, target ->
                    if (i > 0) HorizontalDivider(color = Tokens.line.copy(alpha = 0.6f))
                    val b = mapping.knobs[target.id] ?: KnobBinding()
                    val ccSlot = LearnSlot.KnobCc(target.id, target.label)
                    val downSlot = LearnSlot.KnobStepDown(target.id, target.label)
                    val upSlot = LearnSlot.KnobStepUp(target.id, target.label)
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(target.label, style = MaterialTheme.typography.titleSmall)
                                Text(target.subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                            }
                            if (!b.isEmpty) {
                                TextButton(onClick = { update(mapping.copy(knobs = mapping.knobs - target.id)) }) {
                                    Text("Clear")
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SlotChip(
                                prefix = "Knob",
                                trigger = b.cc,
                                isLearning = learning == ccSlot,
                                onLearn = { learning = if (learning == ccSlot) null else ccSlot },
                                onManual = { learning = null; manualSlot = ccSlot },
                                onClear = { bind(ccSlot, null) },
                            )
                            SlotChip(
                                prefix = "Step −",
                                trigger = b.stepDown,
                                isLearning = learning == downSlot,
                                onLearn = { learning = if (learning == downSlot) null else downSlot },
                                onManual = { learning = null; manualSlot = downSlot },
                                onClear = { bind(downSlot, null) },
                            )
                            SlotChip(
                                prefix = "Step +",
                                trigger = b.stepUp,
                                isLearning = learning == upSlot,
                                onLearn = { learning = if (learning == upSlot) null else upSlot },
                                onManual = { learning = null; manualSlot = upSlot },
                                onClear = { bind(upSlot, null) },
                            )
                        }
                    }
                }
            }

            Section("FM-1 panel buttons (14)") {
                Fm1Panel.BUTTONS.forEachIndexed { i, btn ->
                    if (i > 0) HorizontalDivider(color = Tokens.line.copy(alpha = 0.6f))
                    val slot = LearnSlot.PanelBtn(btn.id, btn.label)
                    val trigger = mapping.buttons[btn.id]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(btn.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        SlotChip(
                            prefix = "Button",
                            trigger = trigger,
                            isLearning = learning == slot,
                            onLearn = { learning = if (learning == slot) null else slot },
                            onManual = { learning = null; manualSlot = slot },
                            onClear = { bind(slot, null) },
                        )
                    }
                }
            }
        }
    }

    manualSlot?.let { slot ->
        val existing = when (slot) {
            is LearnSlot.KnobCc -> mapping.knobs[slot.knobId]?.cc
            is LearnSlot.KnobStepDown -> mapping.knobs[slot.knobId]?.stepDown
            is LearnSlot.KnobStepUp -> mapping.knobs[slot.knobId]?.stepUp
            is LearnSlot.PanelBtn -> mapping.buttons[slot.btnId]
        }
        ManualTriggerDialog(
            title = slot.label,
            ccOnly = slot is LearnSlot.KnobCc,
            initial = existing ?: MidiTrigger(slot.defaultKind, if (slot is LearnSlot.KnobCc) 21 else 36, channel = -1),
            onDismiss = { manualSlot = null },
            onConfirm = { trigger ->
                bind(slot, trigger)
                manualSlot = null
            },
        )
    }
}

@Composable
private fun SlotChip(
    prefix: String,
    trigger: MidiTrigger?,
    isLearning: Boolean,
    onLearn: () -> Unit,
    onManual: () -> Unit,
    onClear: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        if (isLearning) {
            FilledTonalButton(
                onClick = onLearn,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("$prefix: move control…", color = Tokens.amberHi)
            }
        } else if (trigger != null) {
            FilledTonalButton(
                onClick = { menu = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("$prefix: ${trigger.label}")
            }
        } else {
            OutlinedButton(
                onClick = { menu = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("$prefix: map…")
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("MIDI learn (move control)") }, onClick = { menu = false; onLearn() })
            DropdownMenuItem(text = { Text("Pick CC / Note by hand…") }, onClick = { menu = false; onManual() })
            if (trigger != null) {
                DropdownMenuItem(text = { Text("Clear binding") }, onClick = { menu = false; onClear() })
            }
        }
    }
}

@Composable
private fun ManualTriggerDialog(
    title: String,
    ccOnly: Boolean,
    initial: MidiTrigger,
    onDismiss: () -> Unit,
    onConfirm: (MidiTrigger) -> Unit,
) {
    var kind by remember { mutableStateOf(if (ccOnly) TriggerKind.Cc else initial.kind) }
    var number by remember { mutableIntStateOf(initial.number.coerceIn(0, 127)) }
    var channel by remember { mutableIntStateOf(initial.channel.coerceIn(-1, 15)) }
    var chMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map $title") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!ccOnly) {
                    Segmented(
                        listOf(TriggerKind.Cc to "MIDI CC", TriggerKind.Note to "MIDI Note"),
                        kind,
                        onChange = { kind = it },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (kind == TriggerKind.Cc) "CC $number" else "Note $number (${midiNoteName(number)})",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = { number = (number - 1).coerceAtLeast(0) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("−") }
                    OutlinedButton(onClick = { number = (number + 1).coerceAtMost(127) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+") }
                }
                Slider(
                    value = number.toFloat(),
                    onValueChange = { number = it.toInt().coerceIn(0, 127) },
                    valueRange = 0f..127f,
                )
                Box {
                    OutlinedButton(onClick = { chMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("MIDI channel: " + if (channel < 0) "Any channel" else "Channel ${channel + 1}")
                    }
                    DropdownMenu(expanded = chMenu, onDismissRequest = { chMenu = false }) {
                        DropdownMenuItem(text = { Text("Any channel") }, onClick = { channel = -1; chMenu = false })
                        for (ch in 0 until 16) {
                            DropdownMenuItem(text = { Text("Channel ${ch + 1}") }, onClick = { channel = ch; chMenu = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(MidiTrigger(kind, number, channel)) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
