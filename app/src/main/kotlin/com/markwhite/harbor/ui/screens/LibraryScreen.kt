package com.markwhite.harbor.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.data.Catalog
import com.markwhite.harbor.data.DownloadProgress
import com.markwhite.harbor.data.FirmwareCategory
import com.markwhite.harbor.data.FirmwareEntry
import com.markwhite.harbor.data.FirmwareNotes
import com.markwhite.harbor.data.FirmwareSort
import com.markwhite.harbor.data.FirmwareStore
import com.markwhite.harbor.data.InstalledPackage
import com.markwhite.harbor.data.LocalSort
import com.markwhite.harbor.data.RecordingSort
import com.markwhite.harbor.data.isOnline
import com.markwhite.harbor.ui.LayoutMode
import com.markwhite.harbor.ui.Screen
import com.markwhite.harbor.ui.UiState
import com.markwhite.harbor.ui.theme.Eyebrow
import com.markwhite.harbor.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

enum class LibraryTab(val label: String) {
    OnDevice("On device"),
    Catalog("Catalog"),
    Recordings("Recordings"),
    Storage("Storage"),
}

/** Unified Library hub: offline-first On device firmwares, Catalog, Recordings, and Storage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    app: HarborApp,
    ui: UiState,
    mode: LayoutMode,
    initialTab: LibraryTab? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog by app.catalog.entries.collectAsState()
    val installed by app.store.installed.collectAsState()
    val downloading by app.store.downloading.collectAsState()
    val saveRev by app.saves.revision.collectAsState()

    var tab by remember(initialTab) {
        mutableStateOf(initialTab ?: if (app.store.installed.value.isNotEmpty()) LibraryTab.OnDevice else LibraryTab.Catalog)
    }
    var recordingsSort by remember { mutableStateOf(RecordingSort.Newest) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FirmwareEntry?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var byoFor by remember { mutableStateOf<FirmwareEntry?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(isOnline(context)) }

    // Catalog filters
    var sort by remember { mutableStateOf(FirmwareSort.Default) }
    var category by remember { mutableStateOf(FirmwareCategory.All) }
    var onlyKept by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    // On-device filters & dialogs
    var localSort by remember { mutableStateOf(LocalSort.Recent) }
    var localSortMenu by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<InstalledPackage?>(null) }
    var removingPkg by remember { mutableStateOf<InstalledPackage?>(null) }
    var pendingSaveBytes by remember { mutableStateOf<ByteArray?>(null) }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val forEntry = byoFor
        byoFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = readUri(context, uri, 8L shl 20) ?: return@launch run { error = "Couldn't read that file." }
            app.harbor.openPackage(bytes, displayName(context, uri), forEntry).fold(
                { ui.go(Screen.Play) },
                { error = it.message ?: "That file isn't a firmware package Harbor can open." },
            )
        }
    }
    val exportSaveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingSaveBytes
        pendingSaveBytes = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
            }
            error = if (ok) "Backup saved." else "Couldn't write the backup there."
        }
    }

    fun openFile(forEntry: FirmwareEntry? = null) {
        byoFor = forEntry
        open.launch(arrayOf("application/octet-stream", "*/*"))
    }
    fun play(e: FirmwareEntry) {
        if (e.byo && app.store.latestFor(e.id) == null) { selected = e; return }
        app.harbor.play(e)
        ui.go(Screen.Play)
    }
    fun keepOffline(e: FirmwareEntry) {
        scope.launch {
            app.store.downloadForOffline(e).onFailure {
                error = it.message ?: "Couldn't download ${e.name}."
            }
        }
    }
    fun exportBackup(pkg: InstalledPackage) {
        scope.launch {
            runCatching { app.harbor.exportSaveFor(pkg) }.fold(
                { (name, bytes) -> pendingSaveBytes = bytes; exportSaveDoc.launch(name) },
                { error = "Couldn't export: ${it.message}" },
            )
        }
    }

    val catalogList = remember(catalog.firmwares, installed, query, category, sort, onlyKept) {
        val base = Catalog.filterAndSort(catalog.firmwares, query, category, sort)
        if (!onlyKept) base else {
            val keptIds = installed.map { it.catalogId }.toSet()
            base.filter { it.id in keptIds }
        }
    }
    val onDeviceList = remember(installed, localSort, query, catalog.firmwares) {
        FirmwareStore.sortAndFilterOnDevice(installed, localSort, query) { id -> app.catalog.byId(id) }
    }
    val wide = mode == LayoutMode.Expanded

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (searching && tab != LibraryTab.Storage) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        singleLine = true,
                        placeholder = {
                            Text(
                                when (tab) {
                                    LibraryTab.Recordings -> "Search recordings"
                                    LibraryTab.OnDevice -> "Search downloaded firmwares"
                                    else -> "Search firmwares"
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Library")
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (tab != LibraryTab.Storage) {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(Icons.Filled.Search, "Search")
                    }
                }
                if (tab == LibraryTab.Catalog || tab == LibraryTab.OnDevice) {
                    IconButton(
                        onClick = {
                            online = isOnline(context)
                            refreshing = true
                            scope.launch {
                                app.catalog.refresh().onFailure {
                                    error = "Couldn't refresh the list; showing the one from ${if (app.catalog.refreshedAt == 0L) "this app version" else com.markwhite.harbor.ui.timeOf(app.catalog.refreshedAt)}."
                                }
                                online = isOnline(context)
                                refreshing = false
                            }
                        },
                        enabled = !refreshing,
                    ) { Icon(Icons.Filled.Refresh, "Refresh list") }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Tokens.room),
        )

        Segmented(
            options = LibraryTab.entries.map { it to it.label },
            value = tab,
            onChange = { next ->
                tab = next
                if (next == LibraryTab.Storage) {
                    searching = false
                    query = ""
                }
                if (next == LibraryTab.Catalog) online = isOnline(context)
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        when (tab) {
            LibraryTab.Recordings -> {
                RecordingsTab(
                    app = app,
                    query = query,
                    initialSort = recordingsSort,
                    onOpenAudioSettings = { ui.go(Screen.Audio) },
                    modifier = Modifier.weight(1f),
                )
            }
            LibraryTab.Storage -> {
                StorageTab(
                    app = app,
                    dockedSha = ui.docked?.pkg?.sha,
                    onOpenRecordingsLargest = {
                        recordingsSort = RecordingSort.Largest
                        tab = LibraryTab.Recordings
                    },
                    onOpenOnDevice = { tab = LibraryTab.OnDevice },
                    modifier = Modifier.weight(1f),
                )
            }
            LibraryTab.OnDevice -> {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val totalBytes = remember(installed) { installed.sumOf { it.size } }
                        val (favorites, others) = remember(onDeviceList) { onDeviceList.partition { it.favorite } }
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Card(
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = Tokens.well),
                                        border = BorderStroke(1.dp, Tokens.line.copy(alpha = 0.6f)),
                                        modifier = Modifier.fillMaxWidth().clickable { tab = LibraryTab.Storage },
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    "${installed.size} ready offline · ${formatBytes(totalBytes)}",
                                                    style = MaterialTheme.typography.titleSmall,
                                                )
                                                Text(
                                                    "Play any downloaded or local firmware without internet",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Tokens.dim,
                                                )
                                            }
                                            Text("Storage →", style = MaterialTheme.typography.labelMedium, color = Tokens.amberHi)
                                        }
                                    }
                                    if (installed.isNotEmpty()) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                if (onDeviceList.size == installed.size) "Saved on this device"
                                                else "${onDeviceList.size} of ${installed.size} matching",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Tokens.dim,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Box {
                                                OutlinedButton(
                                                    onClick = { localSortMenu = true },
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                ) {
                                                    Text("Sort: ${localSort.label}", style = MaterialTheme.typography.labelMedium)
                                                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp))
                                                }
                                                DropdownMenu(expanded = localSortMenu, onDismissRequest = { localSortMenu = false }) {
                                                    for (s in LocalSort.entries) {
                                                        DropdownMenuItem(
                                                            text = { Text(s.label, color = if (s == localSort) Tokens.amberHi else Tokens.ink) },
                                                            onClick = { localSort = s; localSortMenu = false },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (installed.isEmpty()) {
                                item {
                                    InfoCard {
                                        Text("No firmwares kept yet", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Any firmware you play or download from the Catalog stays saved here so you can launch it again offline.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Tokens.dim,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilledTonalButton(onClick = { tab = LibraryTab.Catalog }) { Text("Browse catalog") }
                                            OutlinedButton(onClick = { openFile() }) { Text("Open .fwsc…") }
                                        }
                                    }
                                }
                            } else if (onDeviceList.isEmpty()) {
                                item { Text("No downloaded firmwares match your search.", color = Tokens.dim) }
                            } else {
                                if (favorites.isNotEmpty()) {
                                    item { Section("Favourites") }
                                    items(favorites, key = { "fav-${it.sha}" }) { pkg ->
                                        val entry = app.catalog.byId(pkg.catalogId)
                                        val sectors = remember(pkg.sha, saveRev) { app.saves.sectorCount(pkg.sha) }
                                        val saveBytes = remember(pkg.sha, saveRev) { app.saves.sizeBytes(pkg.sha) }
                                        OnDeviceCard(
                                            app = app,
                                            pkg = pkg,
                                            entry = entry,
                                            docked = ui.docked?.pkg?.sha == pkg.sha,
                                            clock = app.settings.clock(pkg.sha),
                                            saveSectors = sectors,
                                            saveBytes = saveBytes,
                                            updateAvailable = isUpdateAvailable(entry, installed),
                                            onPlay = { app.harbor.boot(pkg); ui.go(Screen.Play) },
                                            onToggleFavorite = { app.store.setFavorite(pkg.sha, !pkg.favorite) },
                                            onEditNote = { editingNote = pkg },
                                            onExport = { exportBackup(pkg) },
                                            onRemove = { removingPkg = pkg },
                                            onDetails = { entry?.let { selected = it } },
                                        )
                                    }
                                }
                                if (others.isNotEmpty()) {
                                    if (favorites.isNotEmpty()) {
                                        item { Section("All on device") }
                                    }
                                    items(others, key = { it.sha }) { pkg ->
                                        val entry = app.catalog.byId(pkg.catalogId)
                                        val sectors = remember(pkg.sha, saveRev) { app.saves.sectorCount(pkg.sha) }
                                        val saveBytes = remember(pkg.sha, saveRev) { app.saves.sizeBytes(pkg.sha) }
                                        OnDeviceCard(
                                            app = app,
                                            pkg = pkg,
                                            entry = entry,
                                            docked = ui.docked?.pkg?.sha == pkg.sha,
                                            clock = app.settings.clock(pkg.sha),
                                            saveSectors = sectors,
                                            saveBytes = saveBytes,
                                            updateAvailable = isUpdateAvailable(entry, installed),
                                            onPlay = { app.harbor.boot(pkg); ui.go(Screen.Play) },
                                            onToggleFavorite = { app.store.setFavorite(pkg.sha, !pkg.favorite) },
                                            onEditNote = { editingNote = pkg },
                                            onExport = { exportBackup(pkg) },
                                            onRemove = { removingPkg = pkg },
                                            onDetails = { entry?.let { selected = it } },
                                        )
                                    }
                                }
                            }
                        }
                        ExtendedFloatingActionButton(
                            onClick = { openFile() },
                            icon = { Icon(Icons.Filled.Add, null) },
                            text = { Text("Open a .fwsc file…") },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        )
                    }
                    if (wide) {
                        Box(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            val e = selected ?: ui.docked?.entry
                            if (e != null) {
                                FirmwareDetails(
                                    app = app,
                                    e = e,
                                    onPlay = { play(e) },
                                    onOpenByo = { openFile(e) },
                                    onKeepOffline = { keepOffline(e) },
                                    onRequestRemove = { removingPkg = it },
                                    onEditNote = { editingNote = it },
                                )
                            } else {
                                Text("Pick a firmware to see its details.", color = Tokens.dim)
                            }
                        }
                    }
                }
            }
            LibraryTab.Catalog -> {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (!online) {
                                item {
                                    Card(
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xF23A2C14)),
                                        border = BorderStroke(1.dp, Tokens.amber.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                "Offline — firmwares kept on this device still play.",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f),
                                            )
                                            TextButton(onClick = { tab = LibraryTab.OnDevice }) { Text("On device") }
                                        }
                                    }
                                }
                            }
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (catalogList.size == catalog.firmwares.size) "${catalog.firmwares.size} firmwares for the FM-1"
                                            else "${catalogList.size} of ${catalog.firmwares.size} firmwares",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Tokens.dim,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Box {
                                            OutlinedButton(
                                                onClick = { sortMenu = true },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            ) {
                                                Text("Sort: ${sort.label}", style = MaterialTheme.typography.labelMedium)
                                                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp))
                                            }
                                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                                for (s in FirmwareSort.entries) {
                                                    DropdownMenuItem(
                                                        text = { Text(s.label, color = if (s == sort) Tokens.amberHi else Tokens.ink) },
                                                        onClick = { sort = s; sortMenu = false },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        FilterChip(
                                            selected = onlyKept,
                                            onClick = { onlyKept = !onlyKept },
                                            label = { Text("Kept (${installed.count { it.catalogId != "local" }})") },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Tokens.green.copy(alpha = 0.2f),
                                                selectedLabelColor = Tokens.green,
                                            ),
                                        )
                                        for (c in FirmwareCategory.entries) {
                                            FilterChip(
                                                selected = category == c,
                                                onClick = { category = c },
                                                label = { Text(c.label) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = Tokens.amber.copy(alpha = 0.2f),
                                                    selectedLabelColor = Tokens.amberHi,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            if (catalogList.isEmpty()) item { Text("No firmwares match your filter.") }
                            if (sort == FirmwareSort.Default) {
                                val (checked, hubOnly) = catalogList.partition { !it.hubOnly }
                                items(checked, key = { it.id }) { e ->
                                    val kept = installed.filter { it.catalogId == e.id }.maxByOrNull { it.addedAt }
                                    FirmwareCard(
                                        app = app,
                                        e = e,
                                        kept = kept != null,
                                        docked = ui.docked?.entry?.id == e.id,
                                        clock = kept?.let { app.settings.clock(it.sha) },
                                        size = kept?.size,
                                        progress = downloading[e.id],
                                        online = online,
                                        showStats = false,
                                        onPlay = { play(e) },
                                        onKeepOffline = { keepOffline(e) },
                                        onDetails = { selected = e },
                                        onOpenByo = { openFile(e) },
                                    )
                                }
                                if (hubOnly.isNotEmpty()) {
                                    item {
                                        Column(Modifier.padding(top = 8.dp)) {
                                            Section("More from the FM-1 Firmware Hub")
                                            Text(
                                                "Newer firmwares the emulator's authors haven't checked yet. Most run; some may not.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Tokens.dim,
                                                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                                            )
                                        }
                                    }
                                    items(hubOnly, key = { it.id }) { e ->
                                        val kept = installed.filter { it.catalogId == e.id }.maxByOrNull { it.addedAt }
                                        FirmwareCard(
                                            app = app,
                                            e = e,
                                            kept = kept != null,
                                            docked = ui.docked?.entry?.id == e.id,
                                            clock = kept?.let { app.settings.clock(it.sha) },
                                            size = kept?.size,
                                            progress = downloading[e.id],
                                            online = online,
                                            showStats = false,
                                            onPlay = { play(e) },
                                            onKeepOffline = { keepOffline(e) },
                                            onDetails = { selected = e },
                                            onOpenByo = { openFile(e) },
                                        )
                                    }
                                }
                            } else {
                                items(catalogList, key = { it.id }) { e ->
                                    val kept = installed.filter { it.catalogId == e.id }.maxByOrNull { it.addedAt }
                                    FirmwareCard(
                                        app = app,
                                        e = e,
                                        kept = kept != null,
                                        docked = ui.docked?.entry?.id == e.id,
                                        clock = kept?.let { app.settings.clock(it.sha) },
                                        size = kept?.size,
                                        progress = downloading[e.id],
                                        online = online,
                                        showStats = true,
                                        onPlay = { play(e) },
                                        onKeepOffline = { keepOffline(e) },
                                        onDetails = { selected = e },
                                        onOpenByo = { openFile(e) },
                                    )
                                }
                            }
                            item {
                                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "Firmware list: The Firmware Hub – thefirmwarehub.com",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Tokens.amberHi,
                                        modifier = Modifier.clickable { openUrl(context, "https://thefirmwarehub.com") },
                                    )
                                    Text(
                                        "Compilation licensed under CC BY 4.0 (merged with fm1-emu's checked list; makers' texts and pictures remain theirs).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Tokens.dim,
                                    )
                                }
                            }
                        }
                        ExtendedFloatingActionButton(
                            onClick = { openFile() },
                            icon = { Icon(Icons.Filled.Add, null) },
                            text = { Text("Open a .fwsc file…") },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        )
                    }
                    if (wide) {
                        Box(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            val e = selected ?: ui.docked?.entry
                            if (e != null) {
                                FirmwareDetails(
                                    app = app,
                                    e = e,
                                    onPlay = { play(e) },
                                    onOpenByo = { openFile(e) },
                                    onKeepOffline = { keepOffline(e) },
                                    onRequestRemove = { removingPkg = it },
                                    onEditNote = { editingNote = it },
                                )
                            } else {
                                Text("Pick a firmware to see its details.", color = Tokens.dim)
                            }
                        }
                    }
                }
            }
        }
    }

    val sel = selected
    if (!wide && sel != null) {
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                FirmwareDetails(
                    app = app,
                    e = sel,
                    onPlay = { selected = null; play(sel) },
                    onOpenByo = { selected = null; openFile(sel) },
                    onKeepOffline = { keepOffline(sel) },
                    onRequestRemove = { selected = null; removingPkg = it },
                    onEditNote = { editingNote = it },
                )
            }
        }
    }

    editingNote?.let { pkg ->
        var text by remember(pkg.sha) { mutableStateOf(pkg.note) }
        AlertDialog(
            onDismissRequest = { editingNote = null },
            title = { Text("Note for ${pkg.name}") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 240) text = it },
                    label = { Text("Personal note (e.g. favourite bank, clock)") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.store.setNote(pkg.sha, text)
                    editingNote = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingNote = null }) { Text("Cancel") } },
        )
    }

    removingPkg?.let { pkg ->
        val hasSave = app.saves.sizeBytes(pkg.sha) > 0L
        var deleteSave by remember(pkg.sha) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { removingPkg = null },
            title = { Text("Remove ${pkg.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Removes the downloaded .fwsc (${formatBytes(pkg.size)}) from this device. " +
                            if (ui.docked?.pkg?.sha == pkg.sha) "This firmware is currently docked and will be unloaded."
                            else "You can download it again later when online.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (hasSave) {
                        val sectors = app.saves.sectorCount(pkg.sha)
                        SwitchRow(
                            "Also delete saved patches",
                            deleteSave,
                            "$sectors flash sector${if (sectors == 1) "" else "s"} (${formatBytes(app.saves.sizeBytes(pkg.sha))}). Off keeps your patches.",
                        ) { deleteSave = it }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = pkg
                    val del = deleteSave
                    removingPkg = null
                    scope.launch { app.harbor.removePackage(target, deleteSave = del) }
                }) { Text("Remove", color = Tokens.error) }
            },
            dismissButton = { TextButton(onClick = { removingPkg = null }) { Text("Cancel") } },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

private fun isUpdateAvailable(entry: FirmwareEntry?, installed: List<InstalledPackage>): Boolean {
    if (entry == null || entry.`package` == null) return false
    val versions = installed.filter { it.catalogId == entry.id }.sortedByDescending { it.addedAt }
    if (versions.isEmpty()) return false
    val newest = versions.first()
    val current = entry.sha256?.let { sha -> versions.any { it.sha.equals(sha, ignoreCase = true) } }
        ?: (newest.url == null || newest.url == entry.`package`)
    return !current
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnDeviceCard(
    app: HarborApp,
    pkg: InstalledPackage,
    entry: FirmwareEntry?,
    docked: Boolean,
    clock: Int,
    saveSectors: Int,
    saveBytes: Long,
    updateAvailable: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEditNote: () -> Unit,
    onExport: () -> Unit,
    onRemove: () -> Unit,
    onDetails: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Tokens.card),
        border = BorderStroke(1.dp, if (docked) Tokens.amber.copy(alpha = 0.65f) else Tokens.line.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().then(if (entry != null) Modifier.clickable(onClick = onDetails) else Modifier),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Monogram(pkg.catalogId.takeIf { it != "local" } ?: pkg.sha, pkg.name)
                Column(Modifier.weight(1f)) {
                    Text(pkg.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (entry != null) listOf(entry.author, entry.license).filter { it.isNotBlank() }.joinToString(" · ")
                        else "Local .fwsc · ${pkg.sha.take(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = if (pkg.favorite) "Remove from favourites" else "Add to favourites",
                        tint = if (pkg.favorite) Tokens.amber else Tokens.faint,
                    )
                }
            }
            if (pkg.note.isNotBlank()) {
                Text(
                    pkg.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.amberHi,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (docked) {
                    Text("● DOCKED", style = Eyebrow.copy(color = Tokens.amber, fontSize = 11.sp))
                } else {
                    Text("Ready offline", style = MaterialTheme.typography.labelSmall, color = Tokens.green)
                }
                Text(formatBytes(pkg.size), style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                if (saveSectors > 0) {
                    Text("✓ $saveSectors saved sector${if (saveSectors == 1) "" else "s"} (${formatBytes(saveBytes)})", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                }
                if (clock < 240) {
                    Text("$clock MHz here", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                }
                if (pkg.lastPlayedAt > 0L) {
                    Text("Played ${shortRelativeDay(pkg.lastPlayedAt)}", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                }
                if (updateAvailable) {
                    Text("⟳ Update available", style = MaterialTheme.typography.labelSmall, color = Tokens.amberHi)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (docked) {
                    FilledTonalButton(onClick = onPlay) { Text("Docked") }
                } else {
                    Button(onClick = onPlay, contentPadding = PaddingValues(start = 14.dp, end = 20.dp)) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Text("Play", Modifier.padding(start = 6.dp))
                    }
                }
                TextButton(onClick = onEditNote) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(15.dp))
                    Text(if (pkg.note.isBlank()) "Note" else "Edit note", Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = onExport) { Text("Backup…") }
                TextButton(onClick = onRemove) { Text("Remove", color = Tokens.dim) }
            }
        }
    }
}

private fun shortRelativeDay(epochMs: Long): String {
    val diffMs = System.currentTimeMillis() - epochMs
    val days = diffMs / (24L * 3600_000L)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days < 7L -> "$days days ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FirmwareCard(
    app: HarborApp,
    e: FirmwareEntry,
    kept: Boolean,
    docked: Boolean,
    clock: Int?,
    size: Long?,
    progress: DownloadProgress?,
    online: Boolean,
    showStats: Boolean,
    onPlay: () -> Unit,
    onKeepOffline: () -> Unit,
    onDetails: () -> Unit,
    onOpenByo: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Tokens.card),
        border = BorderStroke(1.dp, if (docked) Tokens.amber.copy(alpha = 0.6f) else Tokens.line.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FirmwareImage(app, e.image, null, 160.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Monogram(e.id, e.name)
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(e.author, e.license).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (docked) Text("DOCKED", color = Tokens.amber, style = Eyebrow.copy(color = Tokens.amber, fontSize = 12.sp))
            }
            Text(e.about, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                e.kinds.firstOrNull()?.let { k -> Text(k.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = Tokens.dim) }
                if (showStats && e.installs > 0) Text("${e.installs} installs", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                if (showStats && e.stars > 0) Text("★ ${e.stars}", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                if (showStats && e.hubOnly) Text("Hub", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                if (kept && size != null) Text("↓ kept · ${formatBytes(size)}", style = MaterialTheme.typography.labelSmall, color = Tokens.green)
                if (clock != null && clock < 240) Text("$clock MHz here", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                if (e.byo) Text("Bring your own", style = MaterialTheme.typography.labelSmall, color = Tokens.amber)
                if (e.beta) Text("Beta", style = MaterialTheme.typography.labelSmall, color = Tokens.amber)
                if (FirmwareNotes.makesNoSound(e.id)) Text("MIDI controller: no sound of its own", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
            }
            if (progress != null) {
                if (progress.total > 0) {
                    LinearProgressIndicator(progress = { (progress.got.toFloat() / progress.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text("Downloading for offline use (${formatBytes(progress.got)})…", style = MaterialTheme.typography.labelSmall, color = Tokens.amberHi)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                when {
                    e.byo && !kept -> {
                        Button(onClick = onOpenByo) { Text("Open FM-1.fwsc…") }
                        e.download?.let { url -> LinkButton("M-VAVE", url) }
                    }
                    e.playable && docked -> FilledTonalButton(onClick = onPlay) { Text("Docked") }
                    e.playable && (kept || online) -> {
                        Button(onClick = onPlay, contentPadding = PaddingValues(start = 14.dp, end = 20.dp)) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Text("Play", Modifier.padding(start = 6.dp))
                        }
                        if (!kept && !e.byo && progress == null) {
                            OutlinedButton(onClick = onKeepOffline) { Text("Keep offline") }
                        }
                    }
                    e.playable -> Text("Needs internet to download", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                    else -> Text("Not available in Harbor yet", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                }
                e.repo?.let { url -> LinkButton("Source", url) }
            }
        }
    }
}

/**
 * A firmware's screenshot at its own shape, no taller than [maxHeight], centred on the dark of a
 * screen when it is narrower than the space. Nothing at all while it loads, or when the Hub has no
 * usable one: the card reads the same without it.
 */
@Composable
private fun FirmwareImage(app: HarborApp, url: String?, description: String?, maxHeight: Dp) {
    if (url == null) return
    val image by produceState(app.screenshots.cached(url), url) { if (value == null) value = app.screenshots.load(url) }
    val img = image ?: return
    val ratio = (img.width.toFloat() / img.height).coerceIn(0.75f, 3f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(minOf(maxWidth / ratio, maxHeight)).clip(RoundedCornerShape(12.dp)).background(Tokens.well),
            contentAlignment = Alignment.Center,
        ) {
            Image(img, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

/**
 * A firmware's tile: its initial on a colour of its own (from its id, so it never changes), for
 * telling 49+ entries apart at a glance.
 */
@Composable
private fun Monogram(id: String, name: String) {
    val hue = ((id.hashCode() % 360) + 360) % 360
    val bg = Color.hsl(hue.toFloat(), 0.35f, 0.24f)
    val fg = Color.hsl(hue.toFloat(), 0.70f, 0.76f)
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = fg, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun FirmwareDetails(
    app: HarborApp,
    e: FirmwareEntry,
    onPlay: () -> Unit,
    onOpenByo: () -> Unit,
    onKeepOffline: () -> Unit = {},
    onRequestRemove: (InstalledPackage) -> Unit = { app.store.remove(it.sha) },
    onEditNote: (InstalledPackage) -> Unit = {},
) {
    val context = LocalContext.current
    val installed by app.store.installed.collectAsState()
    val downloading by app.store.downloading.collectAsState()
    val versions = installed.filter { it.catalogId == e.id }.sortedByDescending { it.addedAt }
    var confirmReset by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FirmwareImage(app, e.image, "Screenshot of ${e.name}", 280.dp)
        Text(e.title, style = MaterialTheme.typography.headlineSmall)
        Text("by " + listOf(e.author, e.license).filter { it.isNotBlank() }.joinToString(" · "), color = Tokens.dim)
        if (e.hubOnly) {
            Text(
                "From the FM-1 Firmware Hub; not yet checked in the emulator. Its licence is in its source.",
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.dim,
            )
        }
        Text(e.about)
        FirmwareNotes.midiHint(e.id)?.let { Text("MIDI: $it", style = MaterialTheme.typography.bodySmall) }
        if (FirmwareNotes.makesNoSound(e.id)) {
            Text("${e.name} is a MIDI controller: it makes no sound of its own in Harbor.", style = MaterialTheme.typography.bodySmall)
        }
        Text("A shared save includes the firmware, so its licence applies.", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
        if (e.byo) {
            Text("Harbor can't include M-VAVE's firmware. Download FM-1.fwsc from M-VAVE, then open it here. Harbor keeps it for next time.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                e.download?.let { url -> OutlinedButton(onClick = { openUrl(context, url) }) { Text("Download from M-VAVE ↗") } }
                FilledTonalButton(onClick = onOpenByo) { Text("Open FM-1.fwsc…") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.playable && (!e.byo || versions.isNotEmpty())) {
                FilledTonalButton(onClick = onPlay) { Text("Play") }
            }
            if (e.playable && !e.byo && versions.isEmpty() && downloading[e.id] == null) {
                OutlinedButton(onClick = onKeepOffline) { Text("Keep offline") }
            }
        }
        if (e.embedded != null) Text("This firmware lives inside its installer page; Harbor can't fetch it yet.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            e.repo?.let { url -> TextButton(onClick = { openUrl(context, url) }) { Text("Source ↗") } }
            e.hub?.let { id -> TextButton(onClick = { openUrl(context, "https://thefirmwarehub.com/#fw-$id") }) { Text("On the Hub ↗") } }
            e.installer?.let { url -> TextButton(onClick = { openUrl(context, url) }) { Text("Install on a real FM-1 ↗") } }
        }
        if (versions.isNotEmpty()) {
            Section("Kept on this device")
            for (v in versions) {
                val clock = app.settings.clock(v.sha)
                val sectors = app.saves.sectorCount(v.sha)
                Text(
                    "${v.title} · ${formatBytes(v.size)} · ${v.sha.take(8)}" +
                        (if (sectors > 0) " · $sectors saved sectors" else "") +
                        (if (clock < 240) " · $clock MHz here" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val newest = versions.first()
            if (newest.note.isNotBlank()) {
                Text("Note: ${newest.note}", style = MaterialTheme.typography.bodySmall, color = Tokens.amberHi)
            }
            val current = e.sha256?.let { sha -> versions.any { it.sha.equals(sha, ignoreCase = true) } } ?: (newest.url == null || newest.url == e.`package`)
            if (e.`package` != null && !current) {
                FilledTonalButton(onClick = { app.harbor.play(e, update = true) }) { Text("Update available: download ${e.version}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onEditNote(newest) }) { Text(if (newest.note.isBlank()) "Add note" else "Edit note") }
                TextButton(onClick = { confirmReset = true }) { Text("Reset flash…") }
                TextButton(onClick = { onRequestRemove(newest) }) { Text("Remove…", color = Tokens.error) }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset ${e.name}?") },
            text = { Text("Everything ${e.name} saved to its flash on this device is removed. The firmware itself stays.") },
            confirmButton = {
                TextButton(onClick = { versions.forEach { app.harbor.resetSave(it) }; confirmReset = false }) { Text("Reset and restart") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
