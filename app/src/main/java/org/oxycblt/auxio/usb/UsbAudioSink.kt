/*
 * Copyright (c) 2026 Auxio Project
 * UsbAudioSink.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.usb

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import kotlin.math.log10
import timber.log.Timber as L

/**
 * A Media3 [AudioSink] that streams PCM directly to a UAC3 device's isochronous OUT endpoint via
 * raw USBDEVFS URBs (see [UsbNative]), bypassing AudioTrack and Android's mixer entirely.
 *
 * Supports only 16-bit stereo PCM at the fixed [sampleRateHz] this sink was built for; anything
 * else is rejected via [supportsFormat]/[getFormatSupport] so the caller can fall back elsewhere.
 * Rate/pitch changes, tunneling, and offload are all unsupported for the same reason direct USB
 * output exists in the first place: nothing sits between the decoder and the DAC's own clock to
 * do that kind of processing.
 */
class UsbAudioSink(
    private val session: UsbAudioSession,
    private val format: UacStreamFormat,
    private val sampleRateHz: Int,
) : AudioSink {

    private companion object {
        const val PACKETS_PER_URB = 8
        const val NUM_URBS = 4
        const val RING_BUFFER_MS = 500
        const val CHANNELS = 2
    }

    private var listener: AudioSink.Listener? = null
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var audioAttributes = AudioAttributes.DEFAULT
    private var skipSilence = false
    private var framesWritten = 0L
    private var ended = false

    private val bytesPerFrame = CHANNELS * format.subslotSize
    private val ringBuffer = PcmRingBuffer(bytesPerFrame * sampleRateHz * RING_BUFFER_MS / 1000)

    private var isoHandle = 0L
    private var streamThread: Thread? = null
    @Volatile private var streaming = false

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean {
        val supported =
            format.pcmEncoding == C.ENCODING_PCM_16BIT &&
                format.channelCount == CHANNELS &&
                format.sampleRate == sampleRateHz
        if (!supported) {
            L.i(
                "UsbAudioSink: format not supported (encoding=${format.pcmEncoding} " +
                    "channels=${format.channelCount} rate=${format.sampleRate}, need " +
                    "16-bit/$CHANNELS ch/$sampleRateHz Hz)")
        }
        return supported
    }

    override fun getFormatSupport(format: Format): Int =
        if (supportsFormat(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        framesWritten * 1_000_000L / sampleRateHz

    @Deprecated("Deprecated in AudioSink interface itself; this is still the signature callers use.")
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (!supportsFormat(inputFormat)) {
            throw AudioSink.ConfigurationException("Unsupported format: $inputFormat", inputFormat)
        }
        L.i("UsbAudioSink: configured for $inputFormat")
        framesWritten = 0L
        ended = false
        startStreamingThread()
    }

    override fun play() {
        // The streaming thread runs continuously once configured; starting playback just means
        // real data will start showing up in handleBuffer() instead of silence being streamed.
    }

    override fun handleDiscontinuity() {
        // Nothing special needed: timestamps aren't used to pace the USB stream, the ring
        // buffer just carries whatever PCM arrives in order.
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val written = ringBuffer.write(buffer)
        framesWritten += written / bytesPerFrame
        return !buffer.hasRemaining()
    }

    override fun playToEndOfStream() {
        if (ringBuffer.availableToRead() == 0) {
            ended = true
        }
    }

    override fun isEnded(): Boolean = ended

    override fun hasPendingData(): Boolean = ringBuffer.availableToRead() > 0

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // Direct USB output can't resample/pitch-shift on the fly; report back unchanged so the
        // player knows its request wasn't honored.
        this.playbackParameters = PlaybackParameters.DEFAULT
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilence

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {}

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}

    override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET

    override fun enableTunnelingV21() {
        error("UsbAudioSink does not support tunneling")
    }

    override fun disableTunneling() {}

    override fun setVolume(volume: Float) {
        val dbValue = linearToUacVolume(volume)
        UsbAudioController.setVolume(session, dbValue, channel = 1)
        UsbAudioController.setVolume(session, dbValue, channel = 2)
    }

    override fun pause() {
        // Let the ring buffer run dry; the streaming thread zero-pads once it's empty, so the
        // USB isochronous schedule (which must not stop) just carries silence.
    }

    override fun flush() {
        ringBuffer.clear()
        framesWritten = 0L
        ended = false
    }

    override fun reset() {
        stopStreamingThread()
        ringBuffer.clear()
    }

    override fun release() {
        stopStreamingThread()
    }

    private fun startStreamingThread() {
        if (streaming) return
        val fd = session.connection.fileDescriptor
        val handle =
            UsbNative.openIsoStream(fd, format.endpointAddress, format.maxPacketSize, PACKETS_PER_URB, NUM_URBS)
        if (handle == 0L) {
            L.w("UsbAudioSink: failed to open iso stream")
            return
        }
        isoHandle = handle

        val urbBytes = format.maxPacketSize * PACKETS_PER_URB
        streaming = true
        streamThread =
            Thread {
                    val chunk = ByteArray(urbBytes)
                    for (slot in 0 until NUM_URBS) {
                        ringBuffer.readOrZeroPad(chunk)
                        UsbNative.submitIso(handle, slot, chunk, urbBytes)
                    }
                    while (streaming) {
                        val slot = UsbNative.reapIso(handle)
                        if (!streaming) break
                        if (slot < 0) continue
                        ringBuffer.readOrZeroPad(chunk)
                        UsbNative.submitIso(handle, slot, chunk, urbBytes)
                    }
                }
                .apply {
                    isDaemon = true
                    name = "UsbAudioSink-stream"
                    start()
                }
    }

    private fun stopStreamingThread() {
        if (!streaming && isoHandle == 0L) return
        streaming = false
        if (isoHandle != 0L) {
            UsbNative.cancelIso(isoHandle)
        }
        streamThread?.join(2000)
        streamThread = null
        if (isoHandle != 0L) {
            UsbNative.closeIsoStream(isoHandle)
            isoHandle = 0L
        }
    }

    private fun linearToUacVolume(linear: Float): Int {
        val db = if (linear <= 0f) -32768.0 else 20.0 * log10(linear.toDouble())
        return (db * 256).toInt().coerceIn(-32768, 32767)
    }
}
