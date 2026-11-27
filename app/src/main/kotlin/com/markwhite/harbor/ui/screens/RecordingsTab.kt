package com.markwhite.harbor.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.data.RecordingItem
import com.markwhite.harbor.data.RecordingSort
import com.markwhite.harbor.data.Recordings
import com.markwhite.harbor.engine.RecordingPlayback
import com.markwhite.harbor.engine.RecordingPlayer
import com.markwhite.harbor.ui.theme.Mono
import com.markwhite.harbor.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Recordings tab inside Library: browse, play, rename, compress, share, and delete takes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingsTab(
    app: HarborApp,
    query: String,
    initialSort: RecordingSort = RecordingSort.Newest,
    onOpenAudioSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recVersion by app.recordings.version.collectAsState()
    val defaultBitrate by app.settings.m4aBitrateKbps.collectAsState()

    val player = remember(app) { RecordingPlayer(context, app.harbor) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    val playback by player.state.collectAsState()

    var allItems by remember { mutableStateOf<List<RecordingItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sort by remember(initialSort) { mutableStateOf(initialSort) }
    var sortMenu by remember { mutableStateOf(false) }
    var firmwareFilter by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var renaming by remember { mutableStateOf<RecordingItem?>(null) }
    var deleting by remember { mutableStateOf<List<RecordingItem>?>(null) }
    var converting by remember { mutableStateOf<List<RecordingItem>?>(null) }
    var convertBusy by remember { mutableStateOf(false) }
    var convertDone by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(recVersion) {
        loading = allItems.isEmpty()
        allItems = withContext(Dispatchers.IO) { app.recordings.list() }
        loading = false
        // Prune stale selections if items were deleted externally.
        val present = allItems.map { it.id }.toSet()
        if (selectedIds.any { it !in present }) {
            selectedIds = selectedIds.intersect(present)
        }
    }

    val firmwares = remember(allItems) {
        allItems.map { it.firmware }.distinct().sortedBy { it.lowercase() }
    }
    val visible = remember(allItems, query, firmwareFilter, sort) {
        Recordings.filterAndSort(allItems, query, firmwareFilter, sort)
    }
    val totalBytes = remember(allItems) { allItems.sumOf { it.sizeBytes } }
    val multiSelect = selectedIds.isNotEmpty()

    Column(modifier.fillMaxSize()) {
        if (multiSelect) {
            val picked = visible.filter { it.id in selectedIds }
            val pickedWavs = picked.filter { it.isWav }
            Card(
                colors = CardDefaults.cardColors(containerColor = Tokens.well),
                border = BorderStroke(1.dp, Tokens.amber.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { selectedIds = emptySet() }) {
                        Icon(Icons.Filled.Close, "Cancel selection")
                    }
                    Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (pickedWavs.isNotEmpty()) {
                        TextButton(onClick = { converting = pickedWavs }) { Text("→ M4A") }
                    }
                    IconButton(onClick = { shareRecordings(context, picked) }) {
                        Icon(Icons.Filled.Share, "Share selected")
                    }
                    IconButton(onClick = { deleting = picked }) {
                        Icon(Icons.Filled.Delete, "Delete selected", tint = Tokens.error)
                    }
                }
            }
        }

        if (playback.active != null) {
            MiniPlayerCard(
                playback = playback,
                onToggle = { playback.active?.let { player.toggle(it) } },
                onSeek = { player.seekTo(it) },
                onShare = { playback.active?.let { shareRecordings(context, listOf(it)) } },
                onStop = { player.stop() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (allItems.isEmpty()) "No recordings in ${Recordings.FOLDER_NAME}"
                            else "${allItems.size} recording${if (allItems.size == 1) "" else "s"} · ${formatBytes(totalBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.dim,
                            modifier = Modifier.weight(1f),
                        )
                        if (allItems.isNotEmpty()) {
                            Box {
                                OutlinedButton(
                                    onClick = { sortMenu = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text("Sort: ${sort.label}", style = MaterialTheme.typography.labelMedium)
                                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp))
                                }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    for (s in RecordingSort.entries) {
                                        DropdownMenuItem(
                                            text = { Text(s.label, color = if (s == sort) Tokens.amberHi else Tokens.ink) },
                                            onClick = { sort = s; sortMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (firmwares.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = firmwareFilter == null,
                                onClick = { firmwareFilter = null },
                                label = { Text("All") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Tokens.amber.copy(alpha = 0.2f),
                                    selectedLabelColor = Tokens.amberHi,
                                ),
                            )
                            for (fw in firmwares) {
                                FilterChip(
                                    selected = firmwareFilter == fw,
                                    onClick = { firmwareFilter = if (firmwareFilter == fw) null else fw },
                                    label = { Text(fw) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Tokens.amber.copy(alpha = 0.2f),
                                        selectedLabelColor = Tokens.amberHi,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            } else if (allItems.isEmpty()) {
                item {
                    InfoCard {
                        Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap the red record dot at the top of the Play screen to capture what the firmware plays. " +
                                "Takes are saved to ${Recordings.FOLDER_NAME} and appear here automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Tokens.dim,
                        )
                        OutlinedButton(onClick = onOpenAudioSettings) { Text("Recording format settings") }
                    }
                }
            } else if (visible.isEmpty()) {
                item { Text("No recordings match your filter.", color = Tokens.dim) }
            } else {
                items(visible, key = { it.id }) { item ->
                    val isPlaying = playback.active?.id == item.id && playback.playing
                    val isActive = playback.active?.id == item.id
                    val isSelected = item.id in selectedIds
                    RecordingCard(
                        item = item,
                        active = isActive,
                        playing = isPlaying,
                        selected = isSelected,
                        multiSelect = multiSelect,
                        onClick = {
                            if (multiSelect) {
                                selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                            } else {
                                player.toggle(item)
                            }
                        },
                        onLongClick = {
                            selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                        },
                        onRename = { renaming = item },
                        onShare = { shareRecordings(context, listOf(item)) },
                        onConvert = { converting = listOf(item) },
                        onDelete = { deleting = listOf(item) },
                    )
                }
                item {
                    Text(
                        "Stored in ${Recordings.FOLDER_NAME}. Long-press any recording to select several at once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.dim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    renaming?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename recording") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = renaming ?: return@TextButton
                        renaming = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { app.recordings.rename(target, text) }
                            if (!ok) message = "Couldn't rename that recording."
                        }
                    },
                    enabled = Recordings.sanitizeName(text).isNotEmpty(),
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { items ->
        val total = items.sumOf { it.sizeBytes }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (items.size == 1) "Delete recording?" else "Delete ${items.size} recordings?") },
            text = {
                Text(
                    if (items.size == 1) "Remove \"${items.first().title}\" (${formatBytes(total)}) from ${Recordings.FOLDER_NAME}?"
                    else "Remove ${items.size} recordings (${formatBytes(total)}) from ${Recordings.FOLDER_NAME}? This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = items
                    deleting = null
                    scope.launch {
                        if (toDelete.any { it.id == playback.active?.id }) player.stop()
                        withContext(Dispatchers.IO) {
                            for (r in toDelete) app.recordings.delete(r.uri)
                        }
                        selectedIds = selectedIds - toDelete.map { it.id }.toSet()
                    }
                }) { Text("Delete", color = Tokens.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    converting?.let { wavs ->
        var bitrate by remember(wavs) { mutableIntStateOf(defaultBitrate) }
        var deleteOriginals by remember(wavs) { mutableStateOf(true) }
        val totalWavBytes = wavs.sumOf { it.sizeBytes }
        val estM4aBytes = wavs.sumOf { (it.durationMs * bitrate / 8L).coerceAtLeast(16_384L) }
        AlertDialog(
            onDismissRequest = { if (!convertBusy) converting = null },
            title = { Text(if (wavs.size == 1) "Compress to M4A" else "Compress ${wavs.size} WAVs to M4A") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Current size: ${formatBytes(totalWavBytes)} → about ${formatBytes(estM4aBytes)} as AAC M4A.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.dim,
                    )
                    Choice(
                        "Quality",
                        listOf(128 to "128k", 192 to "192k", 256 to "256k"),
                        bitrate,
                    ) { bitrate = it }
                    SwitchRow(
                        "Delete original WAV${if (wavs.size == 1) "" else "s"}",
                        deleteOriginals,
                        "Reclaim disk space once conversion succeeds",
                    ) { deleteOriginals = it }
                    if (convertBusy) {
                        LinearProgressIndicator(
                            progress = { if (wavs.isEmpty()) 0f else convertDone.toFloat() / wavs.size },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Converting $convertDone / ${wavs.size}…", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !convertBusy,
                    onClick = {
                        convertBusy = true
                        convertDone = 0
                        scope.launch {
                            if (wavs.any { it.id == playback.active?.id }) player.stop()
                            var ok = 0
                            for (w in wavs) {
                                if (app.recordings.convertWavToM4a(w, bitrate, deleteOriginals).isSuccess) ok++
                                convertDone++
                            }
                            convertBusy = false
                            converting = null
                            selectedIds = emptySet()
                            message = "Converted $ok of ${wavs.size} recording${if (wavs.size == 1) "" else "s"} to M4A."
                        }
                    },
                ) { Text("Convert") }
            },
            dismissButton = {
                if (!convertBusy) TextButton(onClick = { converting = null }) { Text("Cancel") }
            },
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun MiniPlayerCard(
    playback: RecordingPlayback,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onShare: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = playback.active ?: return
    val duration = playback.durationMs.coerceAtLeast(1L)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Tokens.well),
        border = BorderStroke(1.dp, Tokens.amber.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Tokens.amber.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = onToggle) {
                        if (playback.playing) PauseGlyph(Tokens.amberHi)
                        else Icon(Icons.Filled.PlayArrow, "Play", tint = Tokens.amberHi)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.firmware} · ${item.formatBadge}", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share", tint = Tokens.ink) }
                IconButton(onClick = onStop) { Icon(Icons.Filled.Close, "Stop", tint = Tokens.dim) }
            }
            if (playback.error != null) {
                Text(playback.error, style = MaterialTheme.typography.bodySmall, color = Tokens.error)
            } else {
                Slider(
                    value = playback.positionMs.coerceIn(0L, duration).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Tokens.amberHi,
                        activeTrackColor = Tokens.amber,
                        inactiveTrackColor = Tokens.line,
                    ),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatDurationMs(playback.positionMs), style = Mono, color = Tokens.dim)
                    Spacer(Modifier.weight(1f))
                    Text(formatDurationMs(playback.durationMs), style = Mono, color = Tokens.dim)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingCard(
    item: RecordingItem,
    active: Boolean,
    playing: Boolean,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onConvert: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val border = when {
        selected || active -> Tokens.amber.copy(alpha = 0.65f)
        else -> Tokens.line.copy(alpha = 0.6f)
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Tokens.well else Tokens.card),
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (selected || active) Tokens.amber.copy(alpha = 0.2f) else Tokens.well),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    selected -> Icon(Icons.Filled.Check, "Selected", tint = Tokens.amberHi)
                    playing -> PauseGlyph(Tokens.amberHi)
                    else -> Icon(Icons.Filled.PlayArrow, "Play", tint = if (active) Tokens.amberHi else Tokens.ink)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.formatBadge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (item.isWav) Tokens.amberHi else Tokens.green,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Tokens.well)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                    Text(
                        listOf(
                            item.firmware,
                            formatDurationMs(item.durationMs),
                            formatBytes(item.sizeBytes),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.dateAddedSec > 0L) {
                    Text(
                        formatShortDate(item.dateAddedSec * 1000L),
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.faint,
                    )
                }
            }
            if (!multiSelect) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Share…") }, onClick = { menu = false; onShare() })
                        if (item.isWav) {
                            DropdownMenuItem(text = { Text("Compress to M4A…") }, onClick = { menu = false; onConvert() })
                        }
                        DropdownMenuItem(
                            text = { Text("Delete…", color = Tokens.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PauseGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val barW = size.width * 0.26f
        val barH = size.height * 0.78f
        val top = (size.height - barH) / 2f
        val r = CornerRadius(barW * 0.35f)
        drawRoundRect(color, topLeft = Offset(size.width * 0.16f, top), size = Size(barW, barH), cornerRadius = r)
        drawRoundRect(color, topLeft = Offset(size.width * 0.58f, top), size = Size(barW, barH), cornerRadius = r)
    }
}

fun shareRecordings(context: Context, items: List<RecordingItem>) {
    if (items.isEmpty()) return
    if (items.size == 1) {
        val r = items.first()
        val send = Intent(Intent.ACTION_SEND)
            .setType(r.mimeType)
            .putExtra(Intent.EXTRA_STREAM, r.uri)
            .putExtra(Intent.EXTRA_TITLE, r.displayName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(context.contentResolver, r.displayName, r.uri)
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    } else {
        val uris = ArrayList(items.map { it.uri })
        val mime = if (items.map { it.mimeType }.distinct().size == 1) items.first().mimeType else "audio/*"
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(mime)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }
}

fun formatDurationMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val h = totalSec / 3600
    val m = (totalSec / 60) % 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatShortDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
