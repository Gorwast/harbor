package com.markwhite.harbor.data

import android.content.Context
import com.markwhite.harbor.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A firmware package kept on this device, identified by the SHA-256 of the whole `.fwsc`. */
@Serializable
data class InstalledPackage(
    val sha: String,
    /** the catalog entry it came from, or "local" for a file the user opened */
    val catalogId: String,
    val name: String,
    val version: String = "",
    val size: Long,
    val addedAt: Long,
    val url: String? = null,
    val lastPlayedAt: Long = 0L,
    val favorite: Boolean = false,
    val note: String = "",
) {
    val title: String get() = if (version.isBlank()) name else "$name $version"
    val lastUsedAt: Long get() = maxOf(lastPlayedAt, addedAt)
}

enum class LocalSort(val label: String) {
    Recent("Recently played"),
    NameAZ("A–Z"),
    Size("Largest"),
    Added("Recently added"),
}

data class DownloadProgress(
    val entryId: String,
    val name: String,
    val got: Long,
    val total: Long,
)

class PackageException(message: String) : Exception(message)

/** Packages in app storage (`packages/<sha>.fwsc`) with a small JSON index. */
class FirmwareStore(context: Context) {
    private val dir = File(context.filesDir, "packages").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _installed = MutableStateFlow(loadIndex())
    val installed: StateFlow<List<InstalledPackage>> = _installed
    private val _downloading = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloading: StateFlow<Map<String, DownloadProgress>> = _downloading

    private fun loadIndex(): List<InstalledPackage> = runCatching {
        json.decodeFromString(ListSerializer(InstalledPackage.serializer()), index.readText())
    }.getOrDefault(emptyList()).filter { File(dir, "${it.sha}.fwsc").exists() }

    @Synchronized
    private fun saveIndex(list: List<InstalledPackage>) {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(InstalledPackage.serializer()), list))
        if (!tmp.renameTo(index)) throw IOException("could not update the package index")
        _installed.value = list
    }

    /** The newest package kept for a catalog entry. */
    fun latestFor(catalogId: String): InstalledPackage? =
        _installed.value.filter { it.catalogId == catalogId }.maxByOrNull { it.addedAt }

    fun bySha(sha: String): InstalledPackage? = _installed.value.firstOrNull { it.sha == sha }

    fun read(sha: String): ByteArray = File(dir, "$sha.fwsc").readBytes()

    /** Download [entry]'s package. [progress]: bytes so far and the total (-1 if unknown). */
    suspend fun download(entry: FirmwareEntry, progress: (Long, Long) -> Unit): InstalledPackage =
        withContext(Dispatchers.IO) {
            val url = entry.`package` ?: throw PackageException("${entry.name} has no package to download")
            try {
                fetch(entry, url, entry.sha256, progress)
            } catch (e: PackageException) {
                // the hub's copy failed: fm1-emu's own link may still work (unchecked: its checksum isn't known)
                val listed = entry.listedPackage?.takeIf { it != url } ?: throw e
                com.markwhite.harbor.engine.EventLog.w("${entry.name}: ${e.message}; trying $listed")
                fetch(entry, listed, null, progress)
            }
        }

    /** Download [entry] for offline use without docking or interrupting the running synth. */
    suspend fun downloadForOffline(entry: FirmwareEntry): Result<InstalledPackage> {
        if (_downloading.value.containsKey(entry.id)) {
            return Result.failure(PackageException("${entry.name} is already downloading"))
        }
        _downloading.value = _downloading.value + (entry.id to DownloadProgress(entry.id, entry.title, 0, -1))
        return try {
            val pkg = download(entry) { got, total ->
                _downloading.value = _downloading.value + (entry.id to DownloadProgress(entry.id, entry.title, got, total))
            }
            Result.success(pkg)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _downloading.value = _downloading.value - entry.id
        }
    }

    private fun fetch(entry: FirmwareEntry, url: String, expected: String?, progress: (Long, Long) -> Unit): InstalledPackage {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", Catalog.USER_AGENT)
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        if (code !in 200..299) throw PackageException("couldn't download ${entry.name} from ${URL(url).host} (HTTP $code)")
        val total = conn.contentLengthLong
        val bytes = conn.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream(if (total > 0) total.toInt() else 1 shl 20)
            val buf = ByteArray(64 * 1024)
            var n: Int
            var got = 0L
            while (input.read(buf).also { n = it } >= 0) {
                out.write(buf, 0, n)
                got += n
                if (got > MAX_PACKAGE) throw PackageException("the package is too large")
                progress(got, total)
            }
            out.toByteArray()
        }
        if (expected != null && !sha256(bytes).equals(expected, ignoreCase = true)) {
            throw PackageException("the download of ${entry.name} didn't match its published checksum")
        }
        return add(bytes, entry.id, entry.name, entry.version, url)
    }

    /** Keep a package the user opened (or downloaded), after checking it unpacks for the FM-1. */
    @Synchronized
    fun add(bytes: ByteArray, catalogId: String, name: String, version: String = "", url: String? = null): InstalledPackage {
        if (bytes.size > MAX_PACKAGE) throw PackageException("the package is too large")
        val board = try {
            NativeEngine.packageBoard(bytes)
        } catch (e: IllegalArgumentException) {
            throw PackageException("this isn't a firmware package Harbor can open (${e.message})")
        }
        if (board == "smk37") throw PackageException(SMK_REFUSED)
        val sha = sha256(bytes)
        bySha(sha)?.let { return it }
        val tmp = File(dir, "$sha.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(File(dir, "$sha.fwsc"))) throw IOException("could not store the package")
        val pkg = InstalledPackage(sha, catalogId, name, version, bytes.size.toLong(), System.currentTimeMillis(), url)
        saveIndex(_installed.value + pkg)
        return pkg
    }

    @Synchronized
    fun markPlayed(sha: String, at: Long = System.currentTimeMillis()) {
        val current = _installed.value
        if (current.none { it.sha == sha }) return
        saveIndex(current.map { if (it.sha == sha) it.copy(lastPlayedAt = at) else it })
    }

    @Synchronized
    fun setFavorite(sha: String, favorite: Boolean) {
        val current = _installed.value
        if (current.none { it.sha == sha }) return
        saveIndex(current.map { if (it.sha == sha) it.copy(favorite = favorite) else it })
    }

    @Synchronized
    fun setNote(sha: String, note: String) {
        val current = _installed.value
        if (current.none { it.sha == sha }) return
        val clean = note.trim().take(240)
        saveIndex(current.map { if (it.sha == sha) it.copy(note = clean) else it })
    }

    @Synchronized
    fun remove(sha: String) {
        File(dir, "$sha.fwsc").delete()
        saveIndex(_installed.value.filterNot { it.sha == sha })
    }

    fun totalBytes(): Long = _installed.value.sumOf { it.size }

    /** Older kept versions of catalog firmwares when a newer version is also installed. */
    fun supersededVersions(): List<InstalledPackage> = supersededVersionsOf(_installed.value)

    /** Non-favourite, non-protected packages not played or added since [cutoffMs]. */
    fun unusedSince(cutoffMs: Long, protectedShas: Set<String> = emptySet()): List<InstalledPackage> =
        unusedSinceOf(_installed.value, cutoffMs, protectedShas)

    companion object {
        const val MAX_PACKAGE = 8L shl 20
        const val SMK_REFUSED = "This is an SMK-37 firmware. SMK-37 support is coming in a later version of Harbor."

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun supersededVersionsOf(packages: List<InstalledPackage>): List<InstalledPackage> =
            packages
                .filter { it.catalogId != "local" }
                .groupBy { it.catalogId }
                .values
                .filter { it.size > 1 }
                .flatMap { group -> group.sortedByDescending { it.addedAt }.drop(1) }

        fun unusedSinceOf(
            packages: List<InstalledPackage>,
            cutoffMs: Long,
            protectedShas: Set<String> = emptySet(),
        ): List<InstalledPackage> = packages.filter {
            !it.favorite && it.sha !in protectedShas && it.lastUsedAt < cutoffMs
        }

        fun sortAndFilterOnDevice(
            packages: List<InstalledPackage>,
            sort: LocalSort,
            query: String,
            entryOf: (String) -> FirmwareEntry? = { null },
        ): List<InstalledPackage> {
            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) packages else packages.filter { pkg ->
                val e = entryOf(pkg.catalogId)
                pkg.title.lowercase().contains(q) ||
                    pkg.note.lowercase().contains(q) ||
                    (e?.author?.lowercase()?.contains(q) == true) ||
                    (e?.about?.lowercase()?.contains(q) == true)
            }
            val comparator = when (sort) {
                LocalSort.Recent -> compareByDescending<InstalledPackage> { it.favorite }
                    .thenByDescending { it.lastPlayedAt }
                    .thenByDescending { it.addedAt }
                    .thenBy { it.name.lowercase() }
                LocalSort.NameAZ -> compareByDescending<InstalledPackage> { it.favorite }
                    .thenBy { it.name.lowercase() }
                    .thenByDescending { it.addedAt }
                LocalSort.Size -> compareByDescending<InstalledPackage> { it.favorite }
                    .thenByDescending { it.size }
                    .thenBy { it.name.lowercase() }
                LocalSort.Added -> compareByDescending<InstalledPackage> { it.favorite }
                    .thenByDescending { it.addedAt }
                    .thenBy { it.name.lowercase() }
            }
            return filtered.sortedWith(comparator)
        }
    }
}
