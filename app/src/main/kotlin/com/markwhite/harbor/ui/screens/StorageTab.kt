package com.markwhite.harbor.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.data.InstalledPackage
import com.markwhite.harbor.data.SaveRecord
import com.markwhite.harbor.data.StorageSummary
import com.markwhite.harbor.data.clearShareCache
import com.markwhite.harbor.data.computeStorageSummary
import com.markwhite.harbor.data.exportAllSavesZip
import com.markwhite.harbor.ui.theme.Mono
import com.markwhite.harbor.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ColorRecordings = Color(0xFF5DA9E9)
private val ColorCache = Color(0xFF8C8E96)

/** Storage & Space Manager tab inside Library. */
@Composable
fun StorageTab(
    app: HarborApp,
    dockedSha: String?,
    onOpenRecordingsLargest: () -> Unit,
    onOpenOnDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by app.store.installed.collectAsState()
    val saveRev by app.saves.revision.collectAsState()
    val recVersion by app.recordings.version.collectAsState()

    var summary by remember { mutableStateOf<StorageSummary?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var reviewPackages by remember { mutableStateOf<Pair<String, List<InstalledPackage>>?>(null) }
    var manageOrphans by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Flush live docked firmware before archiving all saves.
                    app.harbor.flushSave()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        exportAllSavesZip(app.store, app.saves, app.catalog, out)
                    } ?: 0
                }
            }
            message = result.fold(
                { n -> if (n > 0) "Exported $n firmware save${if (n == 1) "" else "s"} to the archive." else "No saved flash patches to export yet." },
                { "Couldn't write the backup archive: ${it.message}" },
            )
        }
    }

    LaunchedEffect(installed, saveRev, recVersion, refreshTick) {
        summary = computeStorageSummary(context, app.store, app.saves, app.screenshots, app.recordings)
    }

    val s = summary
    if (s == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
        return
    }

    val superseded = remember(installed) { app.store.supersededVersions() }
    val protectedShas = remember(dockedSha) { setOfNotNull(dockedSha) }
    val unused30d = remember(installed, protectedShas) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600_000L
        app.store.unusedSince(cutoff, protectedShas)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            InfoCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("Harbor storage", style = MaterialTheme.typography.titleMedium)
                        Text(formatBytes(s.totalHarborBytes), style = MaterialTheme.typography.headlineSmall, color = Tokens.amberHi)
                    }
                    if (s.deviceFreeBytes > 0L) {
                        Text(
                            "${formatBytes(s.deviceFreeBytes)} free on device",
                            style = MaterialTheme.typography.bodySmall,
                            color = Tokens.dim,
                        )
                    }
                }
                StorageBreakdownBar(s)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LegendRow(Tokens.amber, "Downloaded firmwares (${s.firmwaresCount})", formatBytes(s.firmwaresBytes))
                    LegendRow(Tokens.green, "Saved flash patches (${s.activeSavesCount + s.orphanSavesCount})", formatBytes(s.totalSavesBytes))
                    LegendRow(ColorRecordings, "Recordings in Music/Harbor (${s.recordingsCount})", formatBytes(s.recordingsBytes))
                    LegendRow(ColorCache, "Screenshots & temp cache", formatBytes(s.cacheBytes + s.catalogBytes))
                }
            }
        }

        item {
            Section("Clean up") {
                CleanupRow(
                    title = "Screenshot & temp cache",
                    sub = "${formatBytes(s.cacheBytes)} · Safe to clear; images re-download when online",
                    actionLabel = "Clear",
                    enabled = s.cacheBytes > 0L,
                    onAction = {
                        scope.launch(Dispatchers.IO) {
                            app.screenshots.clearCache()
                            clearShareCache(context)
                            refreshTick++
                        }
                    },
                )
                HorizontalDivider(color = Tokens.line.copy(alpha = 0.5f))
                CleanupRow(
                    title = "Older firmware versions",
                    sub = if (superseded.isEmpty()) "No superseded firmware versions installed"
                    else "${superseded.size} older version${if (superseded.size == 1) "" else "s"} · ${formatBytes(superseded.sumOf { it.size })}",
                    actionLabel = "Review",
                    enabled = superseded.isNotEmpty(),
                    onAction = { reviewPackages = "Remove older firmware versions" to superseded },
                )
                HorizontalDivider(color = Tokens.line.copy(alpha = 0.5f))
                CleanupRow(
                    title = "Unused firmware downloads",
                    sub = if (unused30d.isEmpty()) "All non-favourite firmwares played within 30 days"
                    else "${unused30d.size} not played in 30 days · ${formatBytes(unused30d.sumOf { it.size })} (favourites kept)",
                    actionLabel = "Review",
                    enabled = unused30d.isNotEmpty(),
                    onAction = { reviewPackages = "Remove unused downloads" to unused30d },
                )
                HorizontalDivider(color = Tokens.line.copy(alpha = 0.5f))
                CleanupRow(
                    title = "Orphaned flash saves",
                    sub = if (s.orphanSavesCount == 0) "No saves from removed firmwares"
                    else "${s.orphanSavesCount} save${if (s.orphanSavesCount == 1) "" else "s"} for removed firmwares · ${formatBytes(s.orphanSavesBytes)}",
                    actionLabel = "Manage",
                    enabled = s.orphanSavesCount > 0,
                    onAction = { manageOrphans = true },
                )
                HorizontalDivider(color = Tokens.line.copy(alpha = 0.5f))
                CleanupRow(
                    title = "Large recordings",
                    sub = if (s.uncompressedRecordingsCount == 0) "${s.recordingsCount} recording${if (s.recordingsCount == 1) "" else "s"} · ${formatBytes(s.recordingsBytes)}"
                    else "${s.uncompressedRecordingsCount} uncompressed WAV/AIFF (${formatBytes(s.uncompressedRecordingsBytes)}) — compress to M4A to save ~90%",
                    actionLabel = "Browse",
                    enabled = s.recordingsCount > 0,
                    onAction = onOpenRecordingsLargest,
                )
            }
        }

        item {
            Section("Full backup") {
                Text(
                    "Export every kept firmware's saved flash patches into a single .zip archive of .fm1save files before clearing space or moving phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.dim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        enabled = s.activeSavesCount > 0,
                        onClick = {
                            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                            exportZip.launch("Harbor_saves_$stamp.zip")
                        },
                    ) { Text("Export all saves (.zip)…") }
                    OutlinedButton(onClick = onOpenOnDevice) { Text("Manage firmwares") }
                }
            }
        }
    }

    reviewPackages?.let { (title, pkgs) ->
        var alsoDeleteSaves by remember(pkgs) { mutableStateOf(false) }
        val totalSize = pkgs.sumOf { it.size }
        val savesCount = pkgs.count { app.saves.sizeBytes(it.sha) > 0L }
        AlertDialog(
            onDismissRequest = { reviewPackages = null },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Remove ${pkgs.size} firmware download${if (pkgs.size == 1) "" else "s"} (${formatBytes(totalSize)})? " +
                            pkgs.take(6).joinToString(", ") { it.title } + if (pkgs.size > 6) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (savesCount > 0) {
                        SwitchRow(
                            "Also delete saved patches ($savesCount)",
                            alsoDeleteSaves,
                            "Off keeps your flash saves if you re-download later",
                        ) { alsoDeleteSaves = it }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val toRemove = pkgs
                    val delSaves = alsoDeleteSaves
                    reviewPackages = null
                    scope.launch {
                        for (p in toRemove) app.harbor.removePackage(p, deleteSave = delSaves)
                        refreshTick++
                    }
                }) { Text("Remove", color = Tokens.error) }
            },
            dismissButton = { TextButton(onClick = { reviewPackages = null }) { Text("Cancel") } },
        )
    }

    if (manageOrphans) {
        OrphanSavesDialog(
            orphans = s.orphanSaves,
            onDeleteOne = { rec ->
                app.saves.reset(rec.sha)
                refreshTick++
            },
            onDeleteAll = {
                s.orphanSaves.forEach { app.saves.reset(it.sha) }
                manageOrphans = false
                refreshTick++
            },
            onDismiss = { manageOrphans = false },
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
private fun StorageBreakdownBar(s: StorageSummary) {
    val total = s.totalHarborBytes.coerceAtLeast(1L).toFloat()
    val segments = listOf(
        Tokens.amber to (s.firmwaresBytes / total),
        Tokens.green to (s.totalSavesBytes / total),
        ColorRecordings to (s.recordingsBytes / total),
        ColorCache to ((s.cacheBytes + s.catalogBytes) / total),
    ).filter { it.second > 0f }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Tokens.well),
    ) {
        var x = 0f
        for ((color, frac) in segments) {
            val w = (size.width * frac).coerceAtLeast(4f).coerceAtMost(size.width - x)
            if (w > 0f) {
                drawRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height))
                x += w
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = Mono, color = Tokens.dim)
    }
}

@Composable
private fun CleanupRow(
    title: String,
    sub: String,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
        }
        OutlinedButton(
            onClick = onAction,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) { Text(actionLabel) }
    }
}

@Composable
private fun OrphanSavesDialog(
    orphans: List<SaveRecord>,
    onDeleteOne: (SaveRecord) -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Orphaned flash saves") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "These saves belong to firmware builds whose .fwsc download was removed. Keep them if you plan to re-download the exact same build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.dim,
                )
                for (o in orphans.take(8)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Build ${o.sha.take(8)}…", style = Mono)
                            Text("${o.sectors} sectors · ${formatBytes(o.bytes)}", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                        }
                        TextButton(onClick = { onDeleteOne(o) }) { Text("Delete", color = Tokens.error) }
                    }
                }
            }
        },
        confirmButton = {
            if (orphans.isNotEmpty()) {
                TextButton(onClick = onDeleteAll) { Text("Delete all", color = Tokens.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
