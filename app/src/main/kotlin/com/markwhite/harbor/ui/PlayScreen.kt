package com.markwhite.harbor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.midi.KnobBinding
import com.markwhite.harbor.midi.MidiSource
import com.markwhite.harbor.midi.TriggerKind
import com.markwhite.harbor.ui.panel.LearnTarget
import com.markwhite.harbor.ui.panel.LocalMidiLearn
import com.markwhite.harbor.ui.panel.MidiLearnOverlayState
import com.markwhite.harbor.ui.screens.PanelAppearanceControls
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import com.markwhite.harbor.ui.panel.Finish
import com.markwhite.harbor.ui.panel.LcdBezel
import com.markwhite.harbor.ui.panel.PanelLeds
import com.markwhite.harbor.ui.panel.caseBody
import com.markwhite.harbor.ui.panel.tray
import com.markwhite.harbor.ui.theme.Display
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import com.markwhite.harbor.data.BufferMode
import com.markwhite.harbor.data.FirmwareNotes
import com.markwhite.harbor.data.KeybedMode
import com.markwhite.harbor.engine.Harbor
import com.markwhite.harbor.engine.Phase
import com.markwhite.harbor.engine.SaveState
import com.markwhite.harbor.ui.panel.Encoder
import com.markwhite.harbor.ui.panel.Fm1Panel
import com.markwhite.harbor.ui.panel.Keybed
import com.markwhite.harbor.ui.panel.LcdView
import com.markwhite.harbor.ui.panel.MasterPot
import com.markwhite.harbor.ui.panel.ONE_ROW
import com.markwhite.harbor.ui.panel.PanelButton
import com.markwhite.harbor.ui.panel.PanelInput
import com.markwhite.harbor.ui.panel.PanelPrefs
import com.markwhite.harbor.ui.panel.TWO_TIERS
import com.markwhite.harbor.ui.theme.Eyebrow
import com.markwhite.harbor.ui.theme.Tokens

/** The four layout modes (ui-spec §5.2), from the window's width and height. */
enum class LayoutMode { CompactPortrait, CompactLandscape, Medium, Expanded }

fun layoutModeOf(width: Dp, height: Dp): LayoutMode = when {
    height < 480.dp -> LayoutMode.CompactLandscape
    width < 600.dp -> LayoutMode.CompactPortrait
    width < 840.dp -> LayoutMode.Medium
    else -> LayoutMode.Expanded
}

/** Everything the panel needs, gathered once. */
@androidx.compose.runtime.Stable
class PanelState(val harbor: Harbor, val prefs: PanelPrefs, val leds: PanelLeds, val master: Int, val keybedMode: KeybedMode) {
    val input = object : PanelInput {
        override fun key(id: Int, down: Boolean) = harbor.key(id, down)
        override fun enc(id: Int, clicks: Int) = harbor.enc(id, clicks)
        override fun master(value: Int) = harbor.setMaster(value)
    }
}

/** The play chrome's status chips (ui-spec §6.0). */
@Composable
fun StatusChips(ui: UiState, go: (Screen) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SaveChip(ui.save) { go(Screen.Saves) }
        MidiChip(ui.midiActivity, ui.midiSources, ui.usbHint, compact = true) { go(Screen.Midi) }
        LoadChip(ui.statusFlow) { go(Screen.Audio) }
    }
}

@Composable
fun FirmwareTitle(ui: UiState, go: (Screen) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(8.dp)).clickable { go(Screen.Library) }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(ui.title, style = Display, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Filled.ArrowDropDown, "Choose firmware")
    }
}

@Composable
fun OverflowMenu(
    go: (Screen) -> Unit,
    onPerformanceMode: (() -> Unit)?,
    onPanelColors: (() -> Unit)? = null,
    onMidiLearn: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(16.dp), containerColor = Color(0xFF232427),
            border = BorderStroke(1.dp, Tokens.line.copy(alpha = 0.7f)),
        ) {
            for ((label, s) in listOf("Library" to Screen.Library, "Saves & backup" to Screen.Saves)) {
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; go(s) })
            }
            if (onPanelColors != null) DropdownMenuItem(text = { Text("Panel colours…") }, onClick = { open = false; onPanelColors() })
            if (onMidiLearn != null) DropdownMenuItem(text = { Text("MIDI learn") }, onClick = { open = false; onMidiLearn() })
            if (onPerformanceMode != null) DropdownMenuItem(text = { Text("Performance mode") }, onClick = { open = false; onPerformanceMode() })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; go(Screen.Settings) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(ui: UiState, panel: PanelState, mode: LayoutMode, go: (Screen) -> Unit, performance: Boolean, setPerformance: (Boolean) -> Unit) {
    val compact = mode == LayoutMode.CompactPortrait || mode == LayoutMode.CompactLandscape
    val app = LocalContext.current.applicationContext as HarborApp
    val sources by app.midi.sources.collectAsState()
    val connectedSources = remember(sources) { sources.filter { it.enabled && it.connected } }

    var showColorsSheet by remember { mutableStateOf(false) }
    var midiLearnActive by remember { mutableStateOf(false) }
    var selectedSourceKey by remember { mutableStateOf<String?>(null) }
    var armedTarget by remember { mutableStateOf<LearnTarget?>(null) }
    val activeSource = connectedSources.firstOrNull { it.key == selectedSourceKey } ?: connectedSources.firstOrNull()

    DisposableEffect(Unit) {
        onDispose {
            app.midi.stopLearn()
            app.midi.setLearnMute(false)
        }
    }

    LaunchedEffect(midiLearnActive) {
        app.midi.setLearnMute(midiLearnActive)
    }

    LaunchedEffect(midiLearnActive, activeSource?.id, armedTarget) {
        val src = activeSource
        val target = armedTarget
        if (!midiLearnActive || src == null || target == null) {
            app.midi.stopLearn()
            return@LaunchedEffect
        }
        app.midi.startLearn(src.id)
        try {
            while (isActive) {
                val trigger = app.midi.pollLearn()
                if (trigger != null) {
                    val valid = when (target) {
                        is LearnTarget.Knob -> trigger.kind == TriggerKind.Cc
                        is LearnTarget.Button -> true
                    }
                    if (valid) {
                        val cleaned = src.mapping.withoutTrigger(trigger)
                        val next = when (target) {
                            is LearnTarget.Knob -> {
                                val prev = cleaned.knobs[target.knobId] ?: KnobBinding()
                                cleaned.copy(knobs = cleaned.knobs + (target.knobId to prev.copy(cc = trigger)))
                            }
                            is LearnTarget.Button -> cleaned.copy(buttons = cleaned.buttons + (target.btnId to trigger))
                        }
                        app.midi.setMapping(src, next)
                        armedTarget = null
                        break
                    }
                }
                delay(60)
            }
        } finally {
            app.midi.stopLearn()
        }
    }

    val learnOverlay = if (midiLearnActive) {
        MidiLearnOverlayState(
            armed = armedTarget,
            bindingOf = { target ->
                val m = activeSource?.mapping
                when (target) {
                    is LearnTarget.Knob -> m?.knobs?.get(target.knobId)?.cc?.shortLabel
                    is LearnTarget.Button -> m?.buttons?.get(target.btnId)?.shortLabel
                }
            },
            onSelect = { target ->
                armedTarget = if (armedTarget == target) null else target
            },
        )
    } else null

    val enterMidiLearn = {
        midiLearnActive = !midiLearnActive
        armedTarget = null
    }
    val openColors = { showColorsSheet = true }

    val savedPortraitPage by app.settings.portraitPage.collectAsState()
    val pagerState = rememberPagerState(initialPage = savedPortraitPage.coerceIn(0, 3), pageCount = { 4 })
    val scope = rememberCoroutineScope()
    LaunchedEffect(savedPortraitPage) {
        val target = savedPortraitPage.coerceIn(0, 3)
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != savedPortraitPage) app.settings.setPortraitPage(pagerState.currentPage)
    }
    val darkScreenMode = mode == LayoutMode.CompactPortrait && pagerState.currentPage == 3

    // the case on a cached layer of its own, behind everything (as a parent's background it would
    // be redrawn with every frame of the LCD)
    Box(Modifier.fillMaxSize()) {
        if (darkScreenMode) {
            Spacer(Modifier.fillMaxSize().background(Color.Black))
        } else if (compact) {
            Spacer(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).caseBody(panel.prefs.finish, corner = 0.dp))
        }
        Column(Modifier.fillMaxSize()) {
            if (mode == LayoutMode.CompactPortrait && !performance && !darkScreenMode) {
                HeaderCard(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp)) {
                    FirmwareTitle(ui, go, Modifier.weight(1f))
                    Rec(ui, panel.harbor)
                    StatusChipsCompact(ui, go)
                    OverflowMenu(go, { setPerformance(true) }, openColors, enterMidiLearn)
                }
            }
            if ((mode == LayoutMode.Medium || mode == LayoutMode.Expanded) && !performance) {
                HeaderCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    FirmwareTitle(ui, go, Modifier.weight(1f))
                    Rec(ui, panel.harbor)
                    Spacer(Modifier.width(6.dp))
                    StatusChips(ui, go)
                    OverflowMenu(go, { setPerformance(true) }, openColors, enterMidiLearn)
                }
            }
            CompositionLocalProvider(LocalMidiLearn provides learnOverlay) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (mode) {
                        LayoutMode.CompactPortrait -> CompactPortraitPager(
                            pagerState = pagerState,
                            p = panel,
                            ui = ui,
                            darkMode = darkScreenMode,
                            onSelectPage = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                        )
                        LayoutMode.CompactLandscape -> CompactLandscapePanel(panel, ui, go, performance, setPerformance, openColors, enterMidiLearn)
                        LayoutMode.Medium -> MediumPanel(panel, ui, go)
                        LayoutMode.Expanded -> ExpandedPanel(panel, ui)
                    }
                    // over the panel, not above it: the panel keeps its size when it comes and goes
                    if (midiLearnActive) {
                        MidiLearnBanner(
                            connectedSources = connectedSources,
                            activeSource = activeSource,
                            armedTarget = armedTarget,
                            onSelectSource = { selectedSourceKey = it.key },
                            onClearArmed = {
                                val src = activeSource ?: return@MidiLearnBanner
                                val target = armedTarget ?: return@MidiLearnBanner
                                val next = when (target) {
                                    is LearnTarget.Knob -> {
                                        val prev = src.mapping.knobs[target.knobId] ?: KnobBinding()
                                        val updated = prev.copy(cc = null)
                                        if (updated.isEmpty) src.mapping.copy(knobs = src.mapping.knobs - target.knobId)
                                        else src.mapping.copy(knobs = src.mapping.knobs + (target.knobId to updated))
                                    }
                                    is LearnTarget.Button -> src.mapping.copy(buttons = src.mapping.buttons - target.btnId)
                                }
                                app.midi.setMapping(src, next)
                            },
                            onDone = { midiLearnActive = false; armedTarget = null },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    } else if (!performance) {
                        PerfBanner(ui, panel.harbor, go, Modifier.align(Alignment.TopCenter).padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    if (performance && mode != LayoutMode.CompactLandscape) {
                        TextButton(onClick = { setPerformance(false) }, Modifier.align(Alignment.TopEnd)) { Text("Exit performance mode") }
                    }
                    // performance mode has no bar: a take still running can be seen and stopped
                    if (performance && ui.recording) Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { Rec(ui, panel.harbor) }
                }
            }
        }
    }

    if (showColorsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showColorsSheet = false }, sheetState = sheetState) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Panel colours", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showColorsSheet = false }) { Text("Done") }
                }
                PanelAppearanceControls(app.settings, showPreview = false)
            }
        }
    }
}

@Composable
private fun Rec(ui: UiState, harbor: Harbor) {
    val recFrames by if (ui.recording) {
        remember(ui.statusFlow) { ui.statusFlow.map { it.recFrames }.distinctUntilChanged() }.collectAsState(-1L)
    } else {
        remember { mutableLongStateOf(-1L) }
    }
    RecordButton(ui.recording, recFrames, ui.docked != null, harbor::startRecording, harbor::stopRecording)
}

/** The Play screen's bar: a dark card sitting on the case. */
@Composable
private fun HeaderCard(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .dropShadow(shape, Shadow(radius = 8.dp, color = Color.Black, offset = DpOffset(0.dp, 3.dp), alpha = 0.5f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF202124), Color(0xFF1A1B1D))))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0xFF3A3B40), Color(0xFF26272A))), shape)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** On a phone in portrait the chips shrink to their glyphs' essentials. */
@Composable
private fun StatusChipsCompact(ui: UiState, go: (Screen) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        MidiChip(ui.midiActivity, ui.midiSources, ui.usbHint, compact = true) { go(Screen.Midi) }
        LoadChip(ui.statusFlow, compact = true) { go(Screen.Audio) }
    }
}

@Composable
private fun PerfBanner(ui: UiState, harbor: Harbor, go: (Screen) -> Unit, modifier: Modifier) {
    val perf = remember { PerfWatch() }
    var problem by remember { mutableStateOf(PerfWatch.Problem.None) }
    LaunchedEffect(ui.statusFlow, ui.bufferMode) {
        ui.statusFlow.collect { s ->
            problem = perf.update(s, ui.bufferMode == BufferMode.LowLatency, System.currentTimeMillis())
        }
    }
    var dismissed by remember { mutableStateOf(PerfWatch.Problem.None) }
    if (problem == dismissed || problem == PerfWatch.Problem.None) return
    val status by ui.statusFlow.collectAsState()
    val name = ui.shortName
    val (text, action) = when (problem) {
        PerfWatch.Problem.None -> return
        PerfWatch.Problem.CantKeepUp ->
            if (!status.autoClock) "$name can't keep up at ${status.mhz} MHz on this phone." to ("Use automatic clock" to { harbor.setClock(240, true) })
            else "Even at 120 MHz this phone can't run $name in real time. The desktop version of the emulator will play it." to ("Details" to { go(Screen.Audio) })
        PerfWatch.Problem.Buffer -> "The sound drops out now and then. A larger audio buffer smooths it over but adds a little delay to the keys." to
            ("Use a larger buffer" to { harbor.setBufferMode(BufferMode.Stable) })
        PerfWatch.Problem.Device -> "The sound drops out on this device, even with the larger buffer." to ("Details" to { go(Screen.Audio) })
    }
    Surface(
        color = Color(0xF23A2C14), shape = RoundedCornerShape(14.dp), shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Tokens.amber.copy(alpha = 0.35f)), modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = action.second) { Text(action.first) }
            IconButton(onClick = { dismissed = problem }) { Icon(androidx.compose.material.icons.Icons.Filled.Close, "Dismiss", Modifier.size(18.dp)) }
        }
    }
}

// ------------------------------------------------------------------------------ panel parts

/** The case on a tablet, where there's room to show it as an object; phones run it full-bleed. */
@Composable
private fun PanelBody(finish: Finish, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.caseBody(finish).systemGestureExclusion()) { content() }
}

@Composable
private fun LeftCluster(p: PanelState, knob: Dp, spacing: Dp = 8.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            MasterPot(p.master, knob, p.prefs, p.input)
            Encoder(Fm1Panel.SELECT, knob, p.prefs, p.input, externalClicks = p.leds.encClicks(Fm1Panel.SELECT.id))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            Encoder(Fm1Panel.PRESETS, knob, p.prefs, p.input, externalClicks = p.leds.encClicks(Fm1Panel.PRESETS.id))
            Encoder(Fm1Panel.ALGORITHM, knob, p.prefs, p.input, externalClicks = p.leds.encClicks(Fm1Panel.ALGORITHM.id))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            for (b in Fm1Panel.OCT) PanelButton(b, p.leds[b.id], knob, 40.dp, p.prefs, p.input)
        }
    }
}

@Composable
private fun Knobs(p: PanelState, knob: Dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly) {
        for (k in Fm1Panel.KNOBS) Encoder(k, knob, p.prefs, p.input, accent = true, externalClicks = p.leds.encClicks(k.id))
    }
}

/** The twelve LED buttons in rows of [columns] (6: the hardware's two rows). */
@Composable
private fun ButtonGrid(p: PanelState, btnH: Dp, modifier: Modifier = Modifier, spacing: Dp = 6.dp, columns: Int = 6) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        for (row in (Fm1Panel.ROW1 + Fm1Panel.ROW2).chunked(columns)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val w = (maxWidth - spacing * (columns - 1)) / columns
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (b in row) PanelButton(b, p.leds[b.id], w, btnH, p.prefs, p.input)
                }
            }
        }
    }
}

/** The LCD with what the Play screen says over it (ui-spec §6.2.5). */
@Composable
private fun LcdScreen(p: PanelState, ui: UiState, size: Dp) {
    LcdBezel(size) { inner ->
        LcdView(p.harbor, inner, p.prefs.smoothLcd)
        LcdOverlay(ui, p.harbor)
    }
}

@Composable
private fun LcdOverlay(ui: UiState, harbor: Harbor) {
    val phase = ui.phase
    if (phase == Phase.Running) return
    if (phase == Phase.Booting) {
        val running by remember(ui.statusFlow) { ui.statusFlow.map { it.state.ordinal == 1 }.distinctUntilChanged() }.collectAsState(false)
        if (running) return
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xE6101113)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val body = MaterialTheme.typography.bodySmall
        when (phase) {
            Phase.Empty -> {
                Text("Nothing docked yet", style = MaterialTheme.typography.titleSmall)
                FilledTonalButton(onClick = { ui.go(Screen.Library) }) { Text("Browse firmwares") }
            }
            is Phase.Downloading -> {
                Text("Bringing ${phase.name} into harbor…", style = body, textAlign = TextAlign.Center)
                if (phase.total > 0) LinearProgressIndicator(progress = { phase.got.toFloat() / phase.total }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("${phase.got / 1024} KB", style = body, color = Tokens.dim)
                TextButton(onClick = { harbor.cancelDownload() }) { Text("Cancel") }
            }
            is Phase.DownloadFailed -> {
                Text("⚠ ${phase.message}", style = body, textAlign = TextAlign.Center)
                FilledTonalButton(onClick = { harbor.play(phase.entry) }) { Text("Retry") }
            }
            is Phase.NeedsFile -> {
                Text("Bring your own FM-1.fwsc", style = MaterialTheme.typography.titleSmall)
                FilledTonalButton(onClick = { ui.go(Screen.Library) }) { Text("Open FM-1.fwsc…") }
            }
            Phase.Booting -> {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text("Booting…", style = body)
            }
            Phase.Halted -> {
                Text("The emulated CPU halted. Restart the firmware, or copy a report for its author.", style = body, textAlign = TextAlign.Center)
                FilledTonalButton(onClick = { harbor.restart() }) { Text("Restart firmware") }
            }
            is Phase.Failed -> {
                Text("⚠ This firmware didn't boot: ${phase.message}", style = body, textAlign = TextAlign.Center)
                FilledTonalButton(onClick = { harbor.restart() }) { Text("Try again") }
            }
            is Phase.Paused -> {
                Text(phase.reason, style = MaterialTheme.typography.titleSmall)
                Button(onClick = { harbor.resume() }) { Text("Resume") }
            }
            Phase.Running -> {}
        }
    }
}

// ------------------------------------------------------------------------------ the layouts

/** Phone portrait: 4 swipeable layouts (full panel, 8-knob controls without keys, LCD on case, full dark LCD). */
@Composable
private fun CompactPortraitPager(
    pagerState: androidx.compose.foundation.pager.PagerState,
    p: PanelState,
    ui: UiState,
    darkMode: Boolean,
    onSelectPage: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            when (page) {
                0 -> CompactPortraitPanel(p, ui)
                1 -> CompactPortraitControls(p, ui)
                2 -> CompactPortraitScreen(p, ui)
                else -> CompactPortraitDarkScreen(p, ui)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(4) { i ->
                val active = pagerState.currentPage == i
                val color = when {
                    darkMode && active -> Color.White.copy(alpha = 0.85f)
                    darkMode -> Color.White.copy(alpha = 0.25f)
                    active -> p.prefs.finish.silk
                    else -> p.prefs.finish.silkDim.copy(alpha = 0.45f)
                }
                Box(
                    Modifier
                        .height(6.dp)
                        .width(if (active) 18.dp else 6.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { onSelectPage(i) },
                )
            }
        }
    }
}

/** Phone portrait page 0: the controls re-flowed in the hardware's groups, two-tier or sliding keybed (§6.2.1). */
@Composable
private fun CompactPortraitPanel(p: PanelState, ui: UiState) {
    Box(Modifier.fillMaxSize().systemGestureExclusion()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
            val h = maxHeight
            val knob = if (h > 700.dp) 66.dp else 56.dp
            val btnH = if (h > 700.dp) 50.dp else 44.dp
            val keybedMin = if (p.keybedMode == KeybedMode.TwoTiers) 300.dp else 170.dp
            val leftW = knob * 2 + 8.dp
            val lcd = (h - keybedMin - (knob + 20.dp) - (btnH * 2 + 8.dp) - 48.dp)
                .coerceIn(150.dp, min(maxWidth - leftW - 12.dp, 260.dp))
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    LeftCluster(p, knob)
                    LcdScreen(p, ui, lcd)
                }
                Knobs(p, knob, Modifier.fillMaxWidth())
                ButtonGrid(p, btnH, Modifier.fillMaxWidth(), spacing = 7.dp)
                KeybedArea(p, Modifier.weight(1f).fillMaxWidth(), portrait = true)
            }
        }
    }
}

/** Phone portrait page 1: larger LCD, all 8 knobs and all buttons (including OCT−/OCT+), no keys. */
@Composable
private fun CompactPortraitControls(p: PanelState, ui: UiState) {
    Box(Modifier.fillMaxSize().systemGestureExclusion()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val h = maxHeight
            val knob = if (h > 720.dp) 62.dp else 54.dp
            val btnH = if (h > 720.dp) 48.dp else 42.dp
            val octH = 42.dp
            val controlsH = (knob + 20.dp) * 2 + (btnH * 2 + 20.dp) + octH + 44.dp
            val lcd = (h - controlsH).coerceIn(170.dp, min(maxWidth - 24.dp, 310.dp))
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                LcdScreen(p, ui, lcd)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MasterPot(p.master, knob, p.prefs, p.input)
                    Encoder(Fm1Panel.SELECT, knob, p.prefs, p.input, externalClicks = p.leds.encClicks(Fm1Panel.SELECT.id))
                    Encoder(Fm1Panel.PRESETS, knob, p.prefs, p.input, externalClicks = p.leds.encClicks(Fm1Panel.PRESETS.id))
                    Encoder(Fm1Panel.ALGORITHM, knob, p.prefs, p.input, externalClicks = p.leds.encClicks(Fm1Panel.ALGORITHM.id))
                }
                Knobs(p, knob, Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().tray(p.prefs.finish).padding(6.dp)) {
                    ButtonGrid(p, btnH, Modifier.fillMaxWidth(), spacing = 6.dp)
                }
                Row(
                    Modifier.tray(p.prefs.finish).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (b in Fm1Panel.OCT) PanelButton(b, p.leds[b.id], 108.dp, octH, p.prefs, p.input)
                }
            }
        }
    }
}

/** Phone portrait page 2: enlarged LCD alone on the selected case background. */
@Composable
private fun CompactPortraitScreen(p: PanelState, ui: UiState) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        val lcd = min(maxWidth - 8.dp, maxHeight - 16.dp).coerceAtLeast(180.dp)
        LcdScreen(p, ui, lcd)
    }
}

/** Phone portrait page 3: full-screen pure black dark-mode LCD only for external MIDI play. */
@Composable
private fun CompactPortraitDarkScreen(p: PanelState, ui: UiState) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val lcd = min(maxWidth, maxHeight).coerceAtLeast(180.dp)
        Box(Modifier.size(lcd)) {
            LcdView(p.harbor, lcd, p.prefs.smoothLcd)
            LcdOverlay(ui, p.harbor)
        }
    }
}

/** Phone landscape: the hardware's arrangement in one band over a full-width keybed (§6.2.2). */
@Composable
private fun CompactLandscapePanel(
    p: PanelState,
    ui: UiState,
    go: (Screen) -> Unit,
    performance: Boolean,
    setPerformance: (Boolean) -> Unit,
    onPanelColors: () -> Unit,
    onMidiLearn: () -> Unit,
) {
    Box(Modifier.fillMaxSize().systemGestureExclusion()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
            val keysH = max(150.dp, maxHeight * 0.42f)
            val band = maxHeight - keysH - 8.dp
            val knob = (band / 3.2f).coerceIn(44.dp, 60.dp)
            val lcd = (band - 4.dp).coerceIn(140.dp, 220.dp)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(band), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LeftCluster(p, knob, spacing = 4.dp)
                    LcdScreen(p, ui, lcd)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Knobs(p, knob, Modifier.fillMaxWidth())
                        ButtonGrid(p, ((band - knob - 32.dp) / 2).coerceIn(36.dp, 48.dp), Modifier.fillMaxWidth(), spacing = 4.dp)
                    }
                    if (!performance) Spacer(Modifier.width(140.dp))
                }
                KeybedArea(p, Modifier.weight(1f).fillMaxWidth(), portrait = false)
            }
            if (!performance) {
                // the floating chrome pill (ui-spec §4.2)
                val pill = RoundedCornerShape(18.dp)
                Box(
                    Modifier.align(Alignment.TopEnd).width(148.dp)
                        .dropShadow(pill, Shadow(radius = 8.dp, color = Color.Black, offset = DpOffset(0.dp, 3.dp), alpha = 0.5f))
                        .clip(pill)
                        .background(Brush.verticalGradient(listOf(Color(0xFF202124), Color(0xFF1A1B1D))))
                        .border(1.dp, Brush.verticalGradient(listOf(Color(0xFF3A3B40), Color(0xFF26272A))), pill),
                ) {
                    Column(Modifier.padding(4.dp)) {
                        Text(ui.shortName, style = Display.copy(fontSize = 19.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { go(Screen.Library) }.padding(horizontal = 8.dp, vertical = 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!ui.recording) CompactClockLabel(ui.statusFlow) { go(Screen.Audio) }
                            Spacer(Modifier.weight(1f))
                            Rec(ui, p.harbor)
                            OverflowMenu(go, { setPerformance(true) }, onPanelColors, onMidiLearn)
                        }
                    }
                }
            } else {
                TextButton(onClick = { setPerformance(false) }, Modifier.align(Alignment.TopEnd)) { Text("Exit") }
                if (ui.recording) Box(Modifier.align(Alignment.TopStart)) { Rec(ui, p.harbor) }
            }
        }
    }
}

/** Tablet portrait: the faithful upper panel, a taller decoupled keybed and cards (§6.2.3). */
@Composable
private fun MediumPanel(p: PanelState, ui: UiState, go: (Screen) -> Unit) {
    // the keybed takes a share of a tall screen rather than leaving it empty under the cards
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val keysH = (maxHeight * 0.32f).coerceIn(220.dp, 420.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PanelBody(p.prefs.finish, Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column {
                    FittedUpper(p, ui)
                    KeybedArea(p, Modifier.fillMaxWidth().height(keysH).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), portrait = false)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NowDockedCard(ui, go, Modifier.weight(1f))
                YourSaveCard(ui, go, Modifier.weight(1f))
            }
        }
    }
}

/** Tablet landscape: the fully faithful panel and a supporting pane (§6.2.4). */
@Composable
private fun ExpandedPanel(p: PanelState, ui: UiState) {
    val settings = (LocalContext.current.applicationContext as HarborApp).settings
    val pane by settings.tabletPane.collectAsState()
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (pane) {
                PanelBody(p.prefs.finish, Modifier.fillMaxWidth().aspectRatio(Fm1Panel.W / Fm1Panel.H, matchHeightConstraintsFirst = false)) {
                    Column {
                        FaithfulUpper(p, ui)
                        KeybedArea(p, Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, bottom = 24.dp, top = 8.dp), portrait = false)
                    }
                }
            } else {
                BigKeysPanel(p, ui)
                // bring the pane back: a small tab on the panel's edge
                FilledTonalIconButton(
                    onClick = { settings.setTabletPane(true) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 10.dp),
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Show the pane") }
            }
        }
        if (pane) {
            Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { settings.setTabletPane(false) }) {
                    Text("Hide pane: bigger keys")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(start = 4.dp).size(18.dp))
                }
                NowDockedCard(ui, ui.go, Modifier.fillMaxWidth())
                YourSaveCard(ui, ui.go, Modifier.fillMaxWidth())
                PlayFromCard(ui, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Tablet landscape without the pane: the upper panel at the hardware's proportions but held under
 * half the height, so the keybed below takes the full width and the rest of the height.
 */
@Composable
private fun BigKeysPanel(p: PanelState, ui: UiState) {
    PanelBody(p.prefs.finish, Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val upperH = min(maxWidth * (540f / Fm1Panel.W), maxHeight * 0.46f)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(upperH), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(upperH * (Fm1Panel.W / 540f))) { FaithfulUpper(p, ui) }
                }
                KeybedArea(p, Modifier.fillMaxWidth().weight(1f).padding(start = 24.dp, end = 24.dp, bottom = 20.dp, top = 4.dp), portrait = false)
            }
        }
    }
}

/** Tablet portrait: the hardware's left / LCD / right grouping fitted to the width (buttons in fours). */
@Composable
private fun FittedUpper(p: PanelState, ui: UiState) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
        val knob = 60.dp
        val lcd = (maxWidth - (knob * 2 + 8.dp) - 280.dp - 32.dp).coerceIn(220.dp, 300.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LeftCluster(p, knob)
            LcdScreen(p, ui, lcd)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Knobs(p, 52.dp, Modifier.fillMaxWidth())
                ButtonGrid(p, 44.dp, Modifier.fillMaxWidth(), columns = 4)
            }
        }
    }
}

/**
 * The upper panel at panel.rs's positions, scaled to the width. Encoders keep their minimum
 * sizes; buttons take their whole pitch so neighbours never overlap.
 */
@Composable
private fun FaithfulUpper(p: PanelState, ui: UiState) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(Fm1Panel.W / 540f)) {
        val s = maxWidth / Fm1Panel.W
        @Composable
        fun at(cx: Float, cy: Float, w: Dp, h: Dp, content: @Composable () -> Unit) {
            Box(Modifier.offset(s * cx - w / 2, s * cy - h / 2).size(w, h), contentAlignment = Alignment.Center) { content() }
        }
        val encSize = max(s * 88f, 52.dp)
        val knobSize = max(s * 72f, 48.dp)
        at(Fm1Panel.POT_X, Fm1Panel.POT_Y + 14f, encSize, encSize + 18.dp) { MasterPot(p.master, encSize, p.prefs, p.input) }
        for (e in listOf(Fm1Panel.SELECT, Fm1Panel.PRESETS, Fm1Panel.ALGORITHM)) {
            at(e.x, e.y + 14f, encSize, encSize + 18.dp) { Encoder(e, encSize, p.prefs, p.input, externalClicks = p.leds.encClicks(e.id)) }
        }
        for (k in Fm1Panel.KNOBS) at(k.x, k.y + 14f, max(s * 88f, knobSize), knobSize + 18.dp) {
            Encoder(k, knobSize, p.prefs, p.input, accent = true, externalClicks = p.leds.encClicks(k.id))
        }
        for (b in Fm1Panel.BUTTONS) {
            val pitch = if (b.id < 2) 124f else 58f
            val w = min(max(s * b.w, 40.dp), s * pitch - 3.dp)
            val h = max(s * b.h, 44.dp)
            at(b.x + b.w / 2, b.y + b.h / 2, w, h) { PanelButton(b, p.leds[b.id], w, h, p.prefs, p.input) }
        }
        val lcd = s * Fm1Panel.LCD_SIZE
        Box(Modifier.offset(s * Fm1Panel.LCD_X, s * Fm1Panel.LCD_Y)) { LcdScreen(p, ui, lcd) }
    }
}

/** The keybed per the setting: two tiers in phone portrait, else one row; a window when asked. */
@Composable
private fun KeybedArea(p: PanelState, modifier: Modifier, portrait: Boolean) {
    Box(modifier.tray(p.prefs.finish).padding(5.dp)) {
        if (portrait && p.keybedMode == KeybedMode.SingleRow) {
            WindowedKeybed(p, Modifier.fillMaxSize())
        } else {
            Keybed(if (portrait) TWO_TIERS else ONE_ROW, p.leds, p.prefs, p.input, Modifier.fillMaxSize())
        }
    }
}

/** Eight white keys at a time with a draggable full-range overview strip above (§6.2.1). */
@Composable
private fun WindowedKeybed(p: PanelState, modifier: Modifier) {
    val whites = remember { (0 until Fm1Panel.KEY_COUNT).filter { !Fm1Panel.isBlack(it) } }
    var first by remember { mutableIntStateOf(whites.indexOf(7).coerceAtLeast(0)) } // C4
    val maxFirst = (whites.size - 8).coerceAtLeast(0)
    val clampedFirst = first.coerceIn(0, maxFirst)
    val last = (clampedFirst + 7).coerceAtMost(whites.lastIndex)
    val range = whites[clampedFirst]..whites[last]
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KeybedOverviewSlider(
            whiteCount = whites.size,
            windowStart = clampedFirst,
            windowSize = 8,
            finish = p.prefs.finish,
            onMove = { next -> first = next.coerceIn(0, maxFirst) },
            modifier = Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 4.dp),
        )
        Keybed(listOf(range), p.leds, p.prefs, p.input, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun KeybedOverviewSlider(
    whiteCount: Int,
    windowStart: Int,
    windowSize: Int,
    finish: Finish,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .pointerInput(whiteCount, windowSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    fun pick(x: Float) {
                        val frac = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val target = (frac * whiteCount - windowSize / 2f).roundToInt()
                        onMove(target)
                    }
                    pick(down.position.x)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        ch.consume()
                        if (!ch.pressed) break
                        pick(ch.position.x)
                    }
                }
            },
    ) {
        val cellW = size.width / whiteCount
        for (i in 0 until whiteCount) {
            val x = i * cellW + 1.dp.toPx()
            val w = (cellW - 2.dp.toPx()).coerceAtLeast(2f)
            val inWindow = i in windowStart until (windowStart + windowSize)
            drawRoundRect(
                color = if (inWindow) finish.naturalTop.copy(alpha = 0.85f) else finish.naturalTop.copy(alpha = 0.30f),
                topLeft = Offset(x, 2.dp.toPx()),
                size = Size(w, size.height - 4.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
        val frameX = windowStart * cellW
        val frameW = (windowSize * cellW).coerceAtMost(size.width - frameX)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.92f),
            topLeft = Offset(frameX, 0.5.dp.toPx()),
            size = Size(frameW, size.height - 1.dp.toPx()),
            cornerRadius = CornerRadius(6.dp.toPx()),
            style = Stroke(1.8.dp.toPx()),
        )
    }
}

// ------------------------------------------------------------------------------ cards

@Composable
private fun PaneCard(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Tokens.card)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title.uppercase(), style = Eyebrow)
            content()
        }
    }
}

@Composable
private fun CompactClockLabel(statusFlow: kotlinx.coroutines.flow.StateFlow<com.markwhite.harbor.engine.EngineStatus>, onClick: () -> Unit) {
    val mhz by remember(statusFlow) { statusFlow.map { it.mhz }.distinctUntilChanged() }.collectAsState(240)
    Text("$mhz MHz", style = MaterialTheme.typography.labelSmall, color = Tokens.dim,
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp))
}

@Composable
private fun NowDockedCard(ui: UiState, go: (Screen) -> Unit, modifier: Modifier) {
    PaneCard("Now docked", modifier) {
        val d = ui.docked
        if (d == null) {
            Text("Nothing docked yet")
            OutlinedButton(onClick = { go(Screen.Library) }) { Text("Browse firmwares") }
        } else {
            val clock by remember(ui.statusFlow) { ui.statusFlow.map { it.mhz to it.autoClock }.distinctUntilChanged() }.collectAsState(240 to true)
            Text(d.pkg.title, style = MaterialTheme.typography.titleMedium)
            d.entry?.let { Text("${it.author} · ${it.license}", style = MaterialTheme.typography.bodySmall, color = Tokens.dim) }
            d.entry?.about?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            d.entry?.id?.let { id -> FirmwareNotes.midiHint(id)?.let { Text("MIDI: $it", style = MaterialTheme.typography.bodySmall, color = Tokens.dim) } }
            Text("Clock ${clock.first} MHz" + if (clock.second) " (automatic)" else "", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { go(Screen.Library) }) { Text("Change firmware") }
        }
    }
}

@Composable
private fun YourSaveCard(ui: UiState, go: (Screen) -> Unit, modifier: Modifier) {
    PaneCard("Your save", modifier) {
        Text(saveText(ui.save))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { go(Screen.Saves) }) { Text("Saves & backup") }
        }
    }
}

@Composable
private fun PlayFromCard(ui: UiState, modifier: Modifier) {
    PaneCard("Play from", modifier) {
        if (ui.midiNames.isEmpty()) Text("No MIDI devices", color = Tokens.dim)
        for (n in ui.midiNames) Text("⦿ $n")
        TextButton(onClick = { ui.go(Screen.Midi) }) { Text("MIDI settings") }
    }
}

fun saveText(s: SaveState): String = when (s) {
    is SaveState.Saved -> "✓ Saved automatically · ${timeOf(s.at)} · ${s.sectors} flash sector${if (s.sectors == 1) "" else "s"}"
    SaveState.Saving -> "Saving…"
    is SaveState.Failed -> "⚠ Save failed: ${s.message}. Your changes are kept in memory. Export a copy now."
    SaveState.NotSaved -> "Not saved yet: nothing written to flash"
}

@Composable
private fun MidiLearnBanner(
    connectedSources: List<MidiSource>,
    activeSource: MidiSource?,
    armedTarget: LearnTarget?,
    onSelectSource: (MidiSource) -> Unit,
    onClearArmed: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deviceMenu by remember { mutableStateOf(false) }
    val hasBinding = when (armedTarget) {
        is LearnTarget.Knob -> activeSource?.mapping?.knobs?.get(armedTarget.knobId)?.cc != null
        is LearnTarget.Button -> activeSource?.mapping?.buttons?.get(armedTarget.btnId) != null
        null -> false
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF0202124),
        border = BorderStroke(1.dp, Tokens.amber),
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        activeSource == null -> "Connect a MIDI controller to link controls"
                        armedTarget != null -> "Listening for ${armedTarget.label} — move control on ${activeSource.name}"
                        else -> "MIDI Learn · Tap any knob or button to link"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.amberHi,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (connectedSources.size > 1 && activeSource != null) {
                    Box {
                        Text(
                            "Device: ${activeSource.name} ▾",
                            style = MaterialTheme.typography.labelSmall,
                            color = Tokens.dim,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { deviceMenu = true }.padding(vertical = 2.dp),
                        )
                        DropdownMenu(expanded = deviceMenu, onDismissRequest = { deviceMenu = false }) {
                            for (src in connectedSources) {
                                DropdownMenuItem(
                                    text = { Text(src.name) },
                                    onClick = { deviceMenu = false; onSelectSource(src) },
                                )
                            }
                        }
                    }
                }
            }
            if (hasBinding) {
                TextButton(onClick = onClearArmed) { Text("Clear") }
            }
            FilledTonalButton(onClick = onDone) { Text("Done") }
        }
    }
}
