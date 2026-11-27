package com.markwhite.harbor.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Metadata for one firmware build's flash-sector save on disk. */
data class SaveRecord(
    val sha: String,
    val bytes: Long,
    val sectors: Int,
    val savedAt: Long,
)

/**
 * What each firmware build wrote to its flash: `saves/<package sha>.sectors`, Harbor's sector
 * store (4-byte offset + 4096 bytes per changed sector; native savefile.rs). Written atomically:
 * a temp file, synced, then renamed over the old one.
 */
class SaveStore(context: Context) {
    private val dir = File(context.filesDir, "saves").apply { mkdirs() }
    private val _revision = MutableStateFlow(0L)
    /** Increments whenever any save is written or removed so UI summaries refresh. */
    val revision: StateFlow<Long> = _revision

    private fun file(sha: String) = File(dir, "$sha.sectors")

    fun read(sha: String): ByteArray? = file(sha).takeIf { it.exists() }?.readBytes()

    /** When the save was last written, or 0. */
    fun savedAt(sha: String): Long = file(sha).takeIf { it.exists() }?.lastModified() ?: 0L

    fun sizeBytes(sha: String): Long = file(sha).takeIf { it.exists() }?.length() ?: 0L

    fun sectorCount(sha: String): Int = (sizeBytes(sha) / RECORD).toInt()

    fun listAll(): List<SaveRecord> = listInDir(dir)

    fun totalBytes(): Long = listAll().sumOf { it.bytes }

    /** Saves whose firmware `.fwsc` package is no longer in [installedShas]. */
    fun orphaned(installedShas: Set<String>): List<SaveRecord> =
        listAll().filter { it.sha !in installedShas }

    @Synchronized
    fun write(sha: String, sectors: ByteArray) {
        val tmp = File(dir, "$sha.tmp")
        FileOutputStream(tmp).use {
            it.write(sectors)
            it.fd.sync()
        }
        if (!tmp.renameTo(file(sha))) throw IOException("could not save flash")
        _revision.value++
    }

    @Synchronized
    fun reset(sha: String) {
        if (file(sha).delete()) _revision.value++
    }

    companion object {
        const val RECORD = 4 + 4096

        fun listInDir(dir: File): List<SaveRecord> = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".sectors")
        }?.map { f ->
            val len = f.length()
            SaveRecord(
                sha = f.name.removeSuffix(".sectors"),
                bytes = len,
                sectors = (len / RECORD).toInt(),
                savedAt = f.lastModified(),
            )
        }?.sortedByDescending { it.savedAt } ?: emptyList()
    }
}
