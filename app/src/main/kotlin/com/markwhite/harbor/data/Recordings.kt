package com.markwhite.harbor.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.markwhite.harbor.engine.EventLog
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recordings, as WAV files in the shared Music/Harbor folder, where music players and file
 * managers find them. MediaStore lets an app write its own files there without a permission.
 * A recording is written in place, hidden (pending) until it is finished.
 */
class Recordings(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val collection get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** "Felucca 2026-10-20 14.32.05": sorts by time, and is a valid name on any file system. */
    fun nameFor(firmware: String): String {
        val safe = firmware.replace(Regex("[\\\\/:*?\"<>|]+"), " ").trim().ifEmpty { "Harbor" }
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

    fun delete(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    /** Encode a finished 16-bit 44.1 kHz stereo WAV at [wavUri] into AAC-LC `.m4a` at [m4aUri]. */
    fun transcodeWavToM4a(wavUri: Uri, m4aUri: Uri, bitrateBps: Int) {
        val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 2).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        resolver.openFileDescriptor(wavUri, "r")!!.use { inPfd ->
            resolver.openFileDescriptor(m4aUri, "rw")!!.use { outPfd ->
                java.io.FileInputStream(inPfd.fileDescriptor).use { input ->
                    val skip = ByteArray(44)
                    var skipped = 0
                    while (skipped < 44) {
                        val r = input.read(skip, skipped, 44 - skipped)
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
                    val buf = ByteArray(8192)
                    val info = android.media.MediaCodec.BufferInfo()
                    try {
                        while (!eosOut) {
                            if (!eosIn) {
                                val inIdx = codec.dequeueInputBuffer(10_000)
                                if (inIdx >= 0) {
                                    val ib = codec.getInputBuffer(inIdx)!!
                                    ib.clear()
                                    val maxRead = minOf(buf.size, ib.remaining())
                                    val n = input.read(buf, 0, maxRead)
                                    if (n < 0) {
                                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        eosIn = true
                                    } else {
                                        ib.put(buf, 0, n)
                                        codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                                        ptsUs += (n / 4) * 1_000_000L / 44_100L
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
                buildList { while (c.moveToNext()) add(android.content.ContentUris.withAppendedId(collection, c.getLong(0))) }
            }
        }.getOrNull().orEmpty()
        for (uri in found) {
            runCatching {
                val ok = resolver.openFileDescriptor(uri, "rw")?.use { fd -> FileOutputStream(fd.fileDescriptor).channel.use(::fixSizes) } == true
                if (ok) publish(uri) else delete(uri)
                EventLog.i(if (ok) "recovered an unfinished recording" else "removed an empty unfinished recording")
            }
        }
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
    }
}
