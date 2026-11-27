package com.markwhite.harbor.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import com.markwhite.harbor.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Snapshot of all device storage used by Harbor and its recordings. */
data class StorageSummary(
    val firmwaresBytes: Long = 0L,
    val firmwaresCount: Int = 0,
    val activeSavesBytes: Long = 0L,
    val activeSavesCount: Int = 0,
    val orphanSavesBytes: Long = 0L,
    val orphanSaves: List<SaveRecord> = emptyList(),
    val recordingsBytes: Long = 0L,
    val recordingsCount: Int = 0,
    val uncompressedRecordingsBytes: Long = 0L,
    val uncompressedRecordingsCount: Int = 0,
    val cacheBytes: Long = 0L,
    val catalogBytes: Long = 0L,
    val deviceFreeBytes: Long = 0L,
    val deviceTotalBytes: Long = 0L,
) {
    val orphanSavesCount: Int get() = orphanSaves.size
    val totalSavesBytes: Long get() = activeSavesBytes + orphanSavesBytes
    val appInternalBytes: Long get() = firmwaresBytes + totalSavesBytes + cacheBytes + catalogBytes
    val totalHarborBytes: Long get() = appInternalBytes + recordingsBytes
}

/** Gather sizes across packages, saves, recordings, screenshot/share caches, and StatFs. */
suspend fun computeStorageSummary(
    context: Context,
    store: FirmwareStore,
    saves: SaveStore,
    screenshots: Screenshots,
    recordings: Recordings,
): StorageSummary = withContext(Dispatchers.IO) {
    val installed = store.installed.value
    val installedShas = installed.map { it.sha }.toSet()
    val allSaves = saves.listAll()
    val (active, orphans) = allSaves.partition { it.sha in installedShas }
    val recs = recordings.list()
    val uncompressed = recs.filter { it.ext == "wav" || it.ext == "aiff" }
    val shareCache = File(context.cacheDir, "share").listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    val catalogFiles = listOf("catalog.json", "hub-catalog.json")
        .sumOf { File(context.filesDir, it).takeIf(File::exists)?.length() ?: 0L }
    val stat = runCatching { StatFs(context.filesDir.path) }.getOrNull()
    StorageSummary(
        firmwaresBytes = installed.sumOf { it.size },
        firmwaresCount = installed.size,
        activeSavesBytes = active.sumOf { it.bytes },
        activeSavesCount = active.size,
        orphanSavesBytes = orphans.sumOf { it.bytes },
        orphanSaves = orphans,
        recordingsBytes = recs.sumOf { it.sizeBytes },
        recordingsCount = recs.size,
        uncompressedRecordingsBytes = uncompressed.sumOf { it.sizeBytes },
        uncompressedRecordingsCount = uncompressed.size,
        cacheBytes = screenshots.diskBytes() + shareCache,
        catalogBytes = catalogFiles,
        deviceFreeBytes = stat?.availableBytes ?: 0L,
        deviceTotalBytes = stat?.totalBytes ?: 0L,
    )
}

/** Delete temporary exported files in `cacheDir/share`. */
fun clearShareCache(context: Context) {
    File(context.cacheDir, "share").listFiles()?.forEach { it.delete() }
}

/**
 * Write a `.zip` archive containing a `.fm1save` for every installed firmware that has saved flash.
 * Returns the number of saves written. Bring-your-own (M-VAVE) entries omit the firmware package.
 */
suspend fun exportAllSavesZip(
    store: FirmwareStore,
    saves: SaveStore,
    catalog: Catalog,
    out: OutputStream,
): Int = withContext(Dispatchers.IO) {
    var count = 0
    val usedNames = mutableSetOf<String>()
    ZipOutputStream(out).use { zip ->
        for (pkg in store.installed.value) {
            val sectors = saves.read(pkg.sha) ?: continue
            if (sectors.isEmpty()) continue
            val entry = catalog.byId(pkg.catalogId)
            val includePackage = entry?.byo != true
            val encoded = NativeEngine.saveEncode(pkg.title, store.read(pkg.sha), sectors, includePackage)
            val base = pkg.title.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { pkg.sha.take(8) }
            var name = "$base.fm1save"
            if (!usedNames.add(name)) {
                name = "${base}_${pkg.sha.take(6)}.fm1save"
                usedNames.add(name)
            }
            zip.putNextEntry(ZipEntry(name))
            zip.write(encoded)
            zip.closeEntry()
            count++
        }
    }
    count
}

/** True when Android reports an active network with internet capability. */
fun isOnline(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
    val net = cm.activeNetwork ?: return@runCatching false
    val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}.getOrDefault(true)
