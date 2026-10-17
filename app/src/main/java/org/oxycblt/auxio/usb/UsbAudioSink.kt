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
 * Supports 16-bit stereo PCM at 44100 or 48000 Hz -- the two rates this device's clock source was
 * confirmed (by direct SET_CUR/readback) to accept -- rejecting anything else via
 * [supportsFormat]/[getFormatSupport] so the caller can fall back elsewhere. The sample rate is
 * read from each [configure] call and the device's clock is reconfigured to match, so switching
 * between a 44.1kHz and 48kHz track reconfigures the DAC rather than resampling anything.
 *
 * 44100 Hz doesn't divide evenly into USB's one-packet-per-millisecond isochronous schedule (44.1
 * samples/packet), so packet sizes vary slightly call to call via [IsoPacketScheduler] rather than
 * always being the endpoint's declared max -- see that class for how.
 *
 * Rate/pitch changes, tunneling, and offload are all unsupported for the same reason direct USB
 * output exists in the first place: nothing sits between the decoder and the DAC's own clock to
 * do that kind of processing.
 */
class UsbAudioSink(private val session: UsbAudioSession, private val format: UacStreamFormat) :
    AudioSink {

    private companion object {
        const val PACKETS_PER_URB = 8
        const val NUM_URBS = 4
        const val RING_BUFFER_MS = 500
        const val CHANNELS = 2
        val SUPPORTED_SAMPLE_RATES = setOf(44100, 48000)
    }

    private var listener: AudioSink.Listener? = null
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var audioAttributes = AudioAttributes.DEFAULT
    private var skipSilence = false
    private var framesWritten = 0L
    private var ended = false

    private var sampleRateHz = 0
    private var bytesPerFrame = 0
    private var ringBuffer = PcmRingBuffer(1)

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
                format.sampleRate in SUPPORTED_SAMPLE_RATES
        if (!supported) {
            L.i(
                "UsbAudioSink: format not supported (encoding=${format.pcmEncoding} " +
                    "channels=${format.channelCount} rate=${format.sampleRate}, need " +
                    "16-bit/$CHANNELS ch/one of $SUPPORTED_SAMPLE_RATES Hz)")
        }
        return supported
    }

    override fun getFormatSupport(format: Format): Int =
        if (supportsFormat(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        if (sampleRateHz == 0) AudioSink.CURRENT_POSITION_NOT_SET
        else framesWritten * 1_000_000L / sampleRateHz

    @Deprecated("Deprecated in AudioSink interface itself; this is still the signature callers use.")
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (!supportsFormat(inputFormat)) {
            throw AudioSink.ConfigurationException("Unsupported format: $inputFormat", inputFormat)
        }
        L.i("UsbAudioSink: configured for $inputFormat")

        if (streaming && sampleRateHz == inputFormat.sampleRate) {
            // Already streaming at the right rate (e.g. a flush/seek within the same track);
            // nothing to reconfigure.
            framesWritten = 0L
            ended = false
            return
        }

        stopStreamingThread()

        sampleRateHz = inputFormat.sampleRate
        bytesPerFrame = CHANNELS * format.subslotSize
        ringBuffer = PcmRingBuffer(bytesPerFrame * sampleRateHz * RING_BUFFER_MS / 1000)
        framesWritten = 0L
        ended = false

        val clockOk = UsbAudioController.setClockFrequency(session, sampleRateHz)
        if (!clockOk) {
            throw AudioSink.ConfigurationException(
                "Failed to set DAC clock to $sampleRateHz Hz", inputFormat)
        }

        // Re-check mute/volume after every clock change: unknown whether reconfiguring the
        // clock resets the Feature Unit's state on this hardware, but it's cheap to confirm and
        // the first real-playback attempt at this point transferred perfectly (0 USB errors)
        // while producing no audible output, which points at exactly this.
        val muted = UsbAudioController.getMute(session)
        L.i("UsbAudioSink: post-clock-change mute=$muted")
        if (muted != false) {
            UsbAudioController.setMute(session, muted = false)
        }
        for (channel in 1..2) {
            val volume = UsbAudioController.getVolume(session, channel)
            L.i("UsbAudioSink: post-clock-change channel $channel volume=$volume")
            if (volume == null || volume < 0) {
                UsbAudioController.setVolume(session, value = 0, channel = channel)
            }
        }

        // See DAC_SETTLE_DELAY_MS: give the clock change just above time to actually lock
        // before the first packet goes out. Deliberately not calling reassertKernelEviction
        // here -- see UsbAudioManager.activateDac's comment on that call: evicting a driver
        // after the alt-setting is already selected resets the interface back to alt 0 as a
        // side effect, which would undo the activation this sink depends on.
        Thread.sleep(DAC_SETTLE_DELAY_MS)
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
        L.i(
            "UsbAudioSink: opened iso stream fd=$fd endpoint=${format.endpointAddress} " +
                "maxPacketSize=${format.maxPacketSize} handle=$handle")
        isoHandle = handle

        val scheduler = IsoPacketScheduler(sampleRateHz)
        val frameBytes = bytesPerFrame

        fun buildUrb(): Pair<ByteArray, IntArray> {
            val packetLengths = IntArray(PACKETS_PER_URB)
            var total = 0
            for (p in 0 until PACKETS_PER_URB) {
                val bytes = scheduler.nextPacketSampleCount() * frameBytes
                packetLengths[p] = bytes
                total += bytes
            }
            val chunk = ByteArray(total)
            ringBuffer.readOrZeroPad(chunk)
            return chunk to packetLengths
        }

        streaming = true
        streamThread =
            Thread {
                    L.i("UsbAudioSink: streaming thread starting ($sampleRateHz Hz)")
                    for (slot in 0 until NUM_URBS) {
                        val (chunk, lengths) = buildUrb()
                        val ok = UsbNative.submitIso(handle, slot, chunk, lengths)
                        if (!ok) L.w("UsbAudioSink: initial submitIso failed for slot $slot")
                    }
                    var submitted = 0
                    var reapErrors = 0
                    while (streaming) {
                        val slot = UsbNative.reapIso(handle)
                        if (!streaming) break
                        if (slot < 0) {
                            reapErrors++
                            continue
                        }
                        val (chunk, lengths) = buildUrb()
                        if (UsbNative.submitIso(handle, slot, chunk, lengths)) {
                            submitted++
                            if (submitted % 500 == 0) {
                                L.i(
                                    "UsbAudioSink: $submitted URBs submitted, $reapErrors reap " +
                                        "errors, ${ringBuffer.availableToRead()} bytes buffered")
                            }
                        }
                    }
                    L.i("UsbAudioSink: streaming thread exiting ($submitted submitted, $reapErrors reap errors)")
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
