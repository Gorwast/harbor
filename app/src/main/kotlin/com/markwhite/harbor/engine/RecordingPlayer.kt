package com.markwhite.harbor.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import com.markwhite.harbor.data.RecordingItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Live state for the Recordings tab's inline audio player. */
data class RecordingPlayback(
    val active: RecordingItem? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
)

/**
 * In-app recording audition player. Uses [MediaPlayer] for WAV/M4A and falls back to an
 * [AudioTrack] 16-bit PCM streamer for AIFF (which Android's MediaPlayer omits on many devices).
 * Automatically pauses the live FM-1 synth while auditioning and resumes it once stopped.
 */
class RecordingPlayer(
    private val context: Context,
    private val harbor: Harbor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(RecordingPlayback())
    val state: StateFlow<RecordingPlayback> = _state

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var aiffJob: Job? = null
    private var aiffPaused = AtomicBoolean(false)
    private var aiffSeekByte = AtomicLong(-1L)
    private var aiffDataBytes = 0L
    private var aiffSampleRate = 44_100
    private var aiffChannels = 2
    private var pausedSynth = false

    fun toggle(item: RecordingItem) {
        val cur = _state.value
        if (cur.active?.id == item.id) {
            if (cur.playing) pausePlayback() else resumePlayback()
            return
        }
        play(item)
    }

    fun play(item: RecordingItem) {
        stopInternal(resumeSynth = false)
        pauseSynthIfNeeded()
        if (item.ext == "aiff") {
            startAiff(item)
            return
        }
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(context, item.uri)
                prepare()
                setOnCompletionListener {
                    stopInternal(resumeSynth = true)
                }
                start()
            }
            mediaPlayer = mp
            val dur = mp.duration.toLong().takeIf { it > 0 } ?: item.durationMs
            _state.value = RecordingPlayback(item, playing = true, positionMs = 0L, durationMs = dur)
            progressJob = scope.launch {
                while (isActive && mediaPlayer === mp) {
                    val pos = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
                    _state.value = _state.value.copy(positionMs = pos, playing = runCatching { mp.isPlaying }.getOrDefault(false))
                    delay(120)
                }
            }
        } catch (e: Exception) {
            stopInternal(resumeSynth = true)
            _state.value = RecordingPlayback(active = item, error = "Couldn't play ${item.displayName}: ${e.message ?: "unsupported format"}")
        }
    }

    fun pausePlayback() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) mp.pause()
            _state.value = _state.value.copy(playing = false)
            return
        }
        if (aiffJob != null) {
            aiffPaused.set(true)
            _state.value = _state.value.copy(playing = false)
        }
    }

    fun resumePlayback() {
        pauseSynthIfNeeded()
        mediaPlayer?.let { mp ->
            mp.start()
            _state.value = _state.value.copy(playing = true)
            return
        }
        if (aiffJob != null) {
            aiffPaused.set(false)
            _state.value = _state.value.copy(playing = true)
            return
        }
        _state.value.active?.let { play(it) }
    }

    fun seekTo(ms: Long) {
        val cur = _state.value
        val clamped = ms.coerceIn(0L, cur.durationMs.coerceAtLeast(1L))
        mediaPlayer?.let { mp ->
            runCatching { mp.seekTo(clamped.toInt()) }
            _state.value = cur.copy(positionMs = clamped)
            return
        }
        if (aiffJob != null && aiffDataBytes > 0) {
            val frameSize = aiffChannels * 2L
            val byteOffset = ((clamped * aiffSampleRate / 1000L) * frameSize).coerceIn(0L, aiffDataBytes)
            aiffSeekByte.set((byteOffset / frameSize) * frameSize)
            _state.value = cur.copy(positionMs = clamped)
        }
    }

    fun stop() {
        stopInternal(resumeSynth = true)
    }

    fun release() {
        stopInternal(resumeSynth = true)
        scope.cancel()
    }

    private fun pauseSynthIfNeeded() {
        if (!pausedSynth && harbor.docked.value != null && !harbor.isPaused) {
            harbor.pause("Paused · listening to recording")
            pausedSynth = true
        }
    }

    private fun resumeSynthIfWePaused() {
        if (pausedSynth) {
            pausedSynth = false
            harbor.resume()
        }
    }

    private fun stopInternal(resumeSynth: Boolean) {
        progressJob?.cancel()
        progressJob = null
        mediaPlayer?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        mediaPlayer = null
        aiffJob?.cancel()
        aiffJob = null
        aiffPaused.set(false)
        aiffSeekByte.set(-1L)
        _state.value = RecordingPlayback()
        if (resumeSynth) resumeSynthIfWePaused()
    }

    private fun startAiff(item: RecordingItem) {
        aiffPaused.set(false)
        aiffSeekByte.set(-1L)
        aiffJob = scope.launch(Dispatchers.IO) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(item.uri, "r")
                    ?: throw IllegalStateException("file unavailable")
                pfd.use { fd ->
                    FileInputStream(fd.fileDescriptor).use { fis ->
                        val ch = fis.channel
                        val header = parseAiffHeader(ch) ?: throw IllegalStateException("invalid AIFF header")
                        aiffSampleRate = header.sampleRate
                        aiffChannels = header.channels
                        aiffDataBytes = header.dataBytes
                        val frameSize = header.channels * 2
                        val durationMs = (header.dataBytes / frameSize) * 1000L / header.sampleRate
                        _state.value = RecordingPlayback(item, playing = true, positionMs = 0L, durationMs = durationMs)

                        val channelMask = if (header.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                        val minBuf = AudioTrack.getMinBufferSize(header.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                            .coerceAtLeast(8192)
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build(),
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(header.sampleRate)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelMask(channelMask)
                                    .build(),
                            )
                            .setBufferSizeInBytes(minBuf)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        try {
                            track.play()
                            val raw = ByteArray(8192)
                            val bb = ByteBuffer.wrap(raw)
                            var offset = 0L
                            while (isActive && offset < header.dataBytes) {
                                val reqSeek = aiffSeekByte.getAndSet(-1L)
                                if (reqSeek >= 0L) {
                                    offset = reqSeek.coerceIn(0L, header.dataBytes)
                                    track.pause()
                                    track.flush()
                                    if (!aiffPaused.get()) track.play()
                                }
                                if (aiffPaused.get()) {
                                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                                    delay(50)
                                    continue
                                } else if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    track.play()
                                }
                                val toRead = minOf(raw.size.toLong(), header.dataBytes - offset).toInt()
                                bb.clear()
                                bb.limit((toRead / frameSize) * frameSize)
                                val read = ch.read(bb, header.dataStart + offset)
                                if (read <= 0) break
                                // AIFF is big-endian 16-bit PCM; swap byte pairs for Android AudioTrack (LE).
                                var i = 0
                                while (i + 1 < read) {
                                    val hi = raw[i]
                                    raw[i] = raw[i + 1]
                                    raw[i + 1] = hi
                                    i += 2
                                }
                                track.write(raw, 0, read)
                                offset += read
                                val posMs = (offset / frameSize) * 1000L / header.sampleRate
                                _state.value = _state.value.copy(positionMs = posMs, playing = true)
                            }
                        } finally {
                            runCatching { track.stop() }
                            runCatching { track.release() }
                        }
                    }
                }
                if (isActive) {
                    scope.launch { stopInternal(resumeSynth = true) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    scope.launch {
                        stopInternal(resumeSynth = true)
                        _state.value = RecordingPlayback(active = item, error = "Couldn't play ${item.displayName}: ${e.message ?: "error"}")
                    }
                }
            }
        }
    }

    private data class AiffHeader(val sampleRate: Int, val channels: Int, val dataStart: Long, val dataBytes: Long)

    private fun parseAiffHeader(ch: java.nio.channels.FileChannel): AiffHeader? {
        val size = ch.size()
        if (size < 54) return null
        val top = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        if (ch.read(top, 0) < 12) return null
        val a = top.array()
        if (String(a, 0, 4) != "FORM" || String(a, 8, 4) != "AIFF") return null
        var pos = 12L
        var channels = 2
        val sampleRate = 44_100
        val hdr = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        while (pos + 8 <= size) {
            hdr.clear()
            if (ch.read(hdr, pos) < 8) break
            val id = String(hdr.array(), 0, 4)
            val chunkSize = hdr.getInt(4).toLong() and 0xFFFF_FFFFL
            if (id == "COMM" && chunkSize >= 18) {
                val comm = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                if (ch.read(comm, pos + 8) >= 8) {
                    channels = (comm.getShort(0).toInt() and 0xFFFF).coerceIn(1, 2)
                }
            } else if (id == "SSND" && chunkSize >= 8) {
                val ssnd = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                if (ch.read(ssnd, pos + 8) >= 8) {
                    val ssndOffset = ssnd.getInt(0).toLong() and 0xFFFF_FFFFL
                    val dataStart = pos + 16 + ssndOffset
                    val dataBytes = (chunkSize - 8 - ssndOffset).coerceAtMost(size - dataStart).coerceAtLeast(0L)
                    return AiffHeader(sampleRate, channels, dataStart, dataBytes)
                }
            }
            pos += 8 + chunkSize + (chunkSize and 1L)
        }
        return null
    }
}
