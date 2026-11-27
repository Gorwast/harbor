package com.markwhite.harbor.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.markwhite.harbor.engine.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A finished recording saved by Harbor in `Music/Harbor`. */
data class RecordingItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val displayName: String,
    val firmware: String,
    val mimeType: String,
    val ext: String,
    val formatBadge: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSec: Long,
) {
    val isWav: Boolean get() = ext == "wav"
}

enum class RecordingSort(val label: String) {
    Newest("Newest"),
    Oldest("Oldest"),
    Largest("Largest"),
    Longest("Longest"),
}

/** Parsed PCM format facts from a WAV header (pure helper for duration & 24-bit detection). */
internal data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Int,
    val dataBytes: Long,
) {
    val durationMs: Long
        get() {
            val bytesPerSec = sampleRate.toLong() * channels * (bitsPerSample / 8)
            return if (bytesPerSec > 0) (dataBytes * 1000L) / bytesPerSec else 0L
        }
}

/**
 * Recordings, stored in the shared Music/Harbor folder, where music players and file managers
 * find them. MediaStore lets Harbor query, play, rename, and delete its own files there without
 * any storage permission. A recording is written in place, hidden (pending) until it is finished.
 */
class Recordings(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val collection get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val _version = MutableStateFlow(0L)
    /** Increments whenever Harbor creates, renames, converts, recovers, or deletes a recording. */
    val version: StateFlow<Long> = _version

    fun notifyChanged() {
        _version.value++
    }

    /** "Felucca 2026-10-20 14.32.05": sorts by time, and is a valid name on any file system. */
    fun nameFor(firmware: String): String {
        val safe = sanitizeName(firmware).ifEmpty { "Harbor" }
        return "$safe ${SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date())}"
    }

    /** A new, pending recording and its file, opened for writing and seeking. */
    fun create(name: String, firmware: String, ext: String = "wav", mime: String = "audio/x-wav"): Pair<Uri, ParcelFileDescriptor> {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Audio.Media.TITLE, name)
            put(MediaStore.Audio.Media.ARTIST, firmware)
            put(MediaStore.Audio.Media.ALBUM, "Harbor")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw java.io.IOException("the Music folder isn't available")
        val fd = try {
            resolver.openFileDescriptor(uri, "rw") ?: throw java.io.IOException("couldn't open the recording")
        } catch (e: Exception) {
            delete(uri)
            throw e
        }
        return uri to fd
    }

    /** Show a finished recording to other apps. */
    fun publish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
    }

    fun delete(uri: Uri): Boolean {
        val removed = runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        if (removed) notifyChanged()
        return removed
    }

    /** List all finished Harbor-owned recordings in `Music/Harbor/` (no storage permission needed). */
    fun list(): List<RecordingItem> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(FOLDER))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Audio.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        return runCatching {
            resolver.query(collection, projection, args, null)?.use { c ->
                val out = mutableListOf<RecordingItem>()
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val displayName = c.getString(1) ?: "recording"
                    val rawTitle = c.getString(2)?.takeIf { it.isNotBlank() }
                        ?: displayName.substringBeforeLast('.')
                    val rawArtist = c.getString(3)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Harbor"
                    val rawMime = c.getString(4) ?: ""
                    val size = c.getLong(5).coerceAtLeast(0L)
                    var duration = c.getLong(6).coerceAtLeast(0L)
                    val dateAdded = c.getLong(7).coerceAtLeast(0L)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val ext = extensionOf(displayName, rawMime)
                    val mime = rawMime.ifBlank { mimeForExt(ext) }
                    var badge = badgeForExt(ext)
                    if (ext == "wav") {
                        val info = readWavInfo(uri, size)
                        if (info != null) {
                            if (info.bitsPerSample == 24) badge = "WAV 24"
                            else if (info.bitsPerSample == 16) badge = "WAV 16"
                            if (duration <= 0L) duration = info.durationMs
                        } else if (duration <= 0L && size > 44) {
                            duration = ((size - 44) * 1000L) / (44_100L * 4L)
                        }
                    } else if (ext == "aiff" && duration <= 0L && size > 54) {
                        duration = ((size - 54) * 1000L) / (44_100L * 4L)
                    }
                    out += RecordingItem(
                        id = id,
                        uri = uri,
                        title = rawTitle,
                        displayName = displayName,
                        firmware = rawArtist,
                        mimeType = mime,
                        ext = ext,
                        formatBadge = badge,
                        sizeBytes = size,
                        durationMs = duration,
                        dateAddedSec = dateAdded,
                    )
                }
                out
            }
        }.getOrNull().orEmpty()
    }

    /** Rename a Harbor-owned recording in MediaStore, preserving its file extension. */
    fun rename(item: RecordingItem, newTitle: String): Boolean {
        val safe = sanitizeName(newTitle)
        if (safe.isEmpty()) return false
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, safe)
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safe.${item.ext}")
        }
        val ok = runCatching { resolver.update(item.uri, values, null, null) > 0 }.getOrDefault(false)
        if (ok) notifyChanged()
        return ok
    }

    /**
     * Convert an existing 16-bit or 24-bit WAV recording into AAC-LC `.m4a` to reclaim space,
     * optionally deleting the original WAV once the `.m4a` is published.
     */
    suspend fun convertWavToM4a(
        item: RecordingItem,
        bitrateKbps: Int,
        deleteOriginal: Boolean,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        if (!item.isWav) return@withContext Result.failure(IllegalArgumentException("Only WAV recordings can be converted"))
        runCatching {
            val baseName = sanitizeName(item.title).ifEmpty { "Harbor" }
            val (outUri, outFd) = create(baseName, item.firmware, "m4a", "audio/mp4")
            try {
                outFd.close()
                transcodeWavToM4a(item.uri, outUri, bitrateKbps.coerceIn(128, 256) * 1000)
                publish(outUri)
                if (deleteOriginal) runCatching { resolver.delete(item.uri, null, null) }
                notifyChanged()
                outUri
            } catch (e: Exception) {
                runCatching { resolver.delete(outUri, null, null) }
                throw e
            }
        }
    }

    private fun readWavInfo(uri: Uri, fileSize: Long): WavInfo? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val hdr = ByteArray(128)
            val n = input.read(hdr)
            if (n < 44) return@use null
            parseWavHeader(hdr, n, fileSize)
        }
    }.getOrNull()

    /** Encode a finished 16-bit or 24-bit 44.1 kHz stereo WAV at [wavUri] into AAC-LC `.m4a` at [m4aUri]. */
    fun transcodeWavToM4a(wavUri: Uri, m4aUri: Uri, bitrateBps: Int) {
        val wavInfo = readWavInfo(wavUri, 0L)
        val sampleRate = wavInfo?.sampleRate?.takeIf { it > 0 } ?: 44_100
        val channels = wavInfo?.channels?.takeIf { it in 1..2 } ?: 2
        val bitsPerSample = wavInfo?.bitsPerSample?.takeIf { it == 16 || it == 24 } ?: 16
        val dataOffset = wavInfo?.dataOffset ?: 44

        val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        resolver.openFileDescriptor(wavUri, "r")!!.use { inPfd ->
            resolver.openFileDescriptor(m4aUri, "rw")!!.use { outPfd ->
                FileInputStream(inPfd.fileDescriptor).use { input ->
                    val skip = ByteArray(dataOffset)
                    var skipped = 0
                    while (skipped < dataOffset) {
                        val r = input.read(skip, skipped, dataOffset - skipped)
                        if (r <= 0) break
                        skipped += r
                    }
                    codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                    val muxer = android.media.MediaMuxer(outPfd.fileDescriptor, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    var track = -1
                    var muxerStarted = false
                    var eosIn = false
                    var eosOut = false
                    var ptsUs = 0L
                    // 24-bit stereo frame is 6 bytes; 8190 is a multiple of 6 and 4 (for 16-bit too -> 8184).
                    val rawBuf = ByteArray(if (bitsPerSample == 24) 8190 else 8192)
                    val pcm16Buf = ByteArray(8192)
                    val info = android.media.MediaCodec.BufferInfo()
                    try {
                        while (!eosOut) {
                            if (!eosIn) {
                                val inIdx = codec.dequeueInputBuffer(10_000)
                                if (inIdx >= 0) {
                                    val ib = codec.getInputBuffer(inIdx)!!
                                    ib.clear()
                                    val maxPcm16 = minOf(pcm16Buf.size, ib.remaining())
                                    val maxRead = if (bitsPerSample == 24) {
                                        val samples = minOf(rawBuf.size / 3, maxPcm16 / 2)
                                        (samples / channels) * channels * 3
                                    } else {
                                        minOf(rawBuf.size, maxPcm16)
                                    }
                                    val n = readAligned(input, rawBuf, maxRead, if (bitsPerSample == 24) channels * 3 else channels * 2)
                                    if (n <= 0) {
                                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        eosIn = true
                                    } else if (bitsPerSample == 24) {
                                        val outBytes = downscale24LeTo16Le(rawBuf, n, pcm16Buf)
                                        ib.put(pcm16Buf, 0, outBytes)
                                        codec.queueInputBuffer(inIdx, 0, outBytes, ptsUs, 0)
                                        val frames = outBytes / (channels * 2)
                                        ptsUs += frames * 1_000_000L / sampleRate
                                    } else {
                                        ib.put(rawBuf, 0, n)
                                        codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                                        val frames = n / (channels * 2)
                                        ptsUs += frames * 1_000_000L / sampleRate
                                    }
                                }
                            }
                            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                            when {
                                outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    track = muxer.addTrack(codec.outputFormat)
                                    muxer.start()
                                    muxerStarted = true
                                }
                                outIdx >= 0 -> {
                                    val ob = codec.getOutputBuffer(outIdx)!!
                                    if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxerStarted) {
                                        ob.position(info.offset)
                                        ob.limit(info.offset + info.size)
                                        muxer.writeSampleData(track, ob, info)
                                    }
                                    codec.releaseOutputBuffer(outIdx, false)
                                    if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eosOut = true
                                }
                            }
                        }
                    } finally {
                        if (muxerStarted) runCatching { muxer.stop() }
                        runCatching { muxer.release() }
                        runCatching { codec.stop() }
                        runCatching { codec.release() }
                    }
                }
            }
        }
    }

    private fun readAligned(input: FileInputStream, buf: ByteArray, max: Int, align: Int): Int {
        var total = 0
        while (total < max) {
            val r = input.read(buf, total, max - total)
            if (r <= 0) break
            total += r
            if (total >= align && total % align == 0) break
        }
        return (total / align) * align
    }

    /**
     * Recordings left pending when Harbor stopped mid-take (the process was killed): their audio
     * is all there but the WAV header's sizes are still zero. Fill them in and publish, so a crash
     * doesn't cost the take. Call only when no recording is running.
     */
    fun recoverUnfinished() {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(FOLDER))
        }
        val found = runCatching {
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                buildList { while (c.moveToNext()) add(ContentUris.withAppendedId(collection, c.getLong(0))) }
            }
        }.getOrNull().orEmpty()
        var recoveredAny = false
        for (uri in found) {
            runCatching {
                val ok = resolver.openFileDescriptor(uri, "rw")?.use { fd -> FileOutputStream(fd.fileDescriptor).channel.use(::fixSizes) } == true
                if (ok) {
                    publish(uri)
                    recoveredAny = true
                } else {
                    resolver.delete(uri, null, null)
                }
                EventLog.i(if (ok) "recovered an unfinished recording" else "removed an empty unfinished recording")
            }
        }
        if (recoveredAny) notifyChanged()
    }

    /** Write the RIFF and data sizes from the file's length; false if it holds no audio. */
    private fun fixSizes(ch: java.nio.channels.FileChannel): Boolean {
        val data = ((ch.size() - 44).coerceIn(0, 0xFFFF_FFFFL - 36) / 4) * 4
        if (data == 0L) return false
        fun u32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).also { it.flip() }
        ch.write(u32(36 + data), 4)
        ch.write(u32(data), 40)
        ch.force(true)
        return true
    }

    companion object {
        /** MediaStore's form of the folder (trailing slash included). */
        val FOLDER = Environment.DIRECTORY_MUSIC + "/Harbor/"
        /** What the UI calls it. */
        const val FOLDER_NAME = "Music/Harbor"

        fun sanitizeName(raw: String): String =
            raw.replace(Regex("[\\\\/:*?\"<>|]+"), " ").replace(Regex("\\s+"), " ").trim().take(80)

        fun extensionOf(displayName: String, mime: String): String {
            val dot = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (dot in setOf("wav", "aiff", "aif", "m4a")) return if (dot == "aif") "aiff" else dot
            return when {
                mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
                mime.contains("aiff") -> "aiff"
                else -> "wav"
            }
        }

        fun mimeForExt(ext: String): String = when (ext) {
            "m4a" -> "audio/mp4"
            "aiff" -> "audio/aiff"
            else -> "audio/x-wav"
        }

        fun badgeForExt(ext: String): String = when (ext) {
            "m4a" -> "M4A"
            "aiff" -> "AIFF"
            else -> "WAV"
        }

        internal fun parseWavHeader(hdr: ByteArray, len: Int, fileSize: Long): WavInfo? {
            if (len < 44) return null
            if (hdr[0] != 'R'.code.toByte() || hdr[1] != 'I'.code.toByte() || hdr[2] != 'F'.code.toByte() || hdr[3] != 'F'.code.toByte()) return null
            if (hdr[8] != 'W'.code.toByte() || hdr[9] != 'A'.code.toByte() || hdr[10] != 'V'.code.toByte() || hdr[11] != 'E'.code.toByte()) return null
            val bb = ByteBuffer.wrap(hdr, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            var sampleRate = 44_100
            var channels = 2
            var bits = 16
            var dataOffset = 44
            var dataBytes = (fileSize - 44L).coerceAtLeast(0L)
            while (pos + 8 <= len) {
                val id0 = hdr[pos].toInt().toChar()
                val id1 = hdr[pos + 1].toInt().toChar()
                val id2 = hdr[pos + 2].toInt().toChar()
                val id3 = hdr[pos + 3].toInt().toChar()
                val chunkSize = bb.getInt(pos + 4).toLong() and 0xFFFF_FFFFL
                if (id0 == 'f' && id1 == 'm' && id2 == 't' && id3 == ' ' && pos + 24 <= len) {
                    channels = bb.getShort(pos + 10).toInt() and 0xFFFF
                    sampleRate = bb.getInt(pos + 12)
                    bits = bb.getShort(pos + 22).toInt() and 0xFFFF
                } else if (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a') {
                    dataOffset = pos + 8
                    dataBytes = if (chunkSize > 0) chunkSize else (fileSize - dataOffset).coerceAtLeast(0L)
                    break
                }
                val step = 8 + chunkSize + (chunkSize and 1L)
                if (step <= 8 || pos + step > Int.MAX_VALUE) break
                pos += step.toInt()
            }
            return WavInfo(sampleRate, channels, bits, dataOffset, dataBytes)
        }

        internal fun downscale24LeTo16Le(src: ByteArray, srcLen: Int, dst: ByteArray): Int {
            var i = 0
            var o = 0
            while (i + 2 < srcLen && o + 1 < dst.size) {
                // Keep the upper 16 bits of each 24-bit signed little-endian sample.
                dst[o] = src[i + 1]
                dst[o + 1] = src[i + 2]
                i += 3
                o += 2
            }
            return o
        }

        fun filterAndSort(
            items: List<RecordingItem>,
            query: String,
            firmware: String?,
            sort: RecordingSort,
        ): List<RecordingItem> {
            val q = query.trim().lowercase(Locale.ROOT)
            val filtered = items.filter { item ->
                (firmware == null || item.firmware.equals(firmware, ignoreCase = true)) &&
                    (q.isEmpty() || item.title.lowercase(Locale.ROOT).contains(q) ||
                        item.firmware.lowercase(Locale.ROOT).contains(q) ||
                        item.formatBadge.lowercase(Locale.ROOT).contains(q))
            }
            return when (sort) {
                RecordingSort.Newest -> filtered.sortedByDescending { it.dateAddedSec }
                RecordingSort.Oldest -> filtered.sortedBy { it.dateAddedSec }
                RecordingSort.Largest -> filtered.sortedByDescending { it.sizeBytes }
                RecordingSort.Longest -> filtered.sortedByDescending { it.durationMs }
            }
        }
    }
}
