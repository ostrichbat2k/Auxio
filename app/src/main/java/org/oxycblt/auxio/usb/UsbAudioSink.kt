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
        // DecoderAudioRenderer.isReady() (the actual renderer driving playback) is just
        // `audioSink.hasPendingData()` -- i.e. our hasPendingData() directly gates whether
        // ExoPlayer considers the whole player READY or BUFFERING. DefaultAudioSink's equivalent
        // checks a real AudioTrack's hardware buffer, which typically holds several seconds; our
        // ring buffer at the old 500ms was thin enough that any ordinary decode-timing hiccup
        // could drain it to empty, flipping isReady() to false and dropping the player into
        // BUFFERING -- observed as playback randomly stalling ~20-60s in with zero errors
        // anywhere (a legitimate state transition, not a failure) and never recovering, since a
        // buffer this shallow may never satisfy whatever lookahead ExoPlayer's
        // buffering-recovery logic wants before calling the renderer ready again. Matching
        // DefaultAudioSink's actual depth removes the mismatch instead of guessing at the exact
        // recovery threshold.
        const val RING_BUFFER_MS = 5000
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

    // Diagnostic: tracks whether the decoder has actually stopped calling handleBuffer(),
    // versus the native iso layer legitimately running out of real data to send (both look
    // identical from outside -- the ring buffer just sits at 0 -- but only one is actually our
    // bug to fix).
    @Volatile private var lastHandleBufferAtMs = 0L
    @Volatile private var handleBufferCalls = 0L

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    // UAC 1.0 declares its actual supported rate(s) per-format (e.g. the real device this was
    // confirmed against offers only 8000/48000 Hz, not 44100 at all); UAC 2.0/3.0 negotiate
    // through a shared Clock Source instead, where the hardcoded 44100/48000 pair is what this
    // codebase has actually verified devices accept. Falling back to the hardcoded set when
    // sampleRates is empty (UAC 2.0/3.0) keeps that behavior unchanged.
    private val acceptedSampleRates =
        this.format.sampleRates.toSet().ifEmpty { SUPPORTED_SAMPLE_RATES }

    override fun supportsFormat(format: Format): Boolean {
        val supported =
            format.pcmEncoding == C.ENCODING_PCM_16BIT &&
                format.channelCount == CHANNELS &&
                format.sampleRate in acceptedSampleRates
        if (!supported) {
            L.i(
                "UsbAudioSink: format not supported (encoding=${format.pcmEncoding} " +
                    "channels=${format.channelCount} rate=${format.sampleRate}, need " +
                    "16-bit/$CHANNELS ch/one of $acceptedSampleRates Hz)")
        }
        return supported
    }

    override fun getFormatSupport(format: Format): Int =
        if (supportsFormat(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val result =
            if (sampleRateHz == 0) AudioSink.CURRENT_POSITION_NOT_SET
            else framesWritten * 1_000_000L / sampleRateHz
        L.i(
            "USB DEBUG: getCurrentPositionUs() sink=${System.identityHashCode(this)} " +
                "sourceEnded=$sourceEnded sampleRateHz=$sampleRateHz framesWritten=$framesWritten " +
                "result=$result")
        return result
    }

    @Deprecated("Deprecated in AudioSink interface itself; this is still the signature callers use.")
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (!supportsFormat(inputFormat)) {
            throw AudioSink.ConfigurationException("Unsupported format: $inputFormat", inputFormat)
        }
        L.i("UsbAudioSink: configured for $inputFormat, sink=${System.identityHashCode(this)}")

        if (streaming && sampleRateHz == inputFormat.sampleRate) {
            // Already streaming at the right rate (ExoPlayer calls configure() again with an
            // identical format periodically during otherwise normal playback, roughly every
            // 30-60s observed here, not just on an actual seek/flush) -- genuinely nothing to
            // reconfigure. Previously this branch still zeroed framesWritten/ended despite its
            // own comment saying there was nothing to do, which made getCurrentPositionUs()
            // jump back to 0 on every one of those redundant calls -- a real seek's position
            // reset is already handled by flush(), so this branch doesn't need to duplicate it.
            return
        }

        stopStreamingThread()

        sampleRateHz = inputFormat.sampleRate
        bytesPerFrame = CHANNELS * format.subslotSize
        ringBuffer = PcmRingBuffer(bytesPerFrame * sampleRateHz * RING_BUFFER_MS / 1000)
        framesWritten = 0L
        ended = false

        // UAC 1.0 has no Clock Source entity -- its rate lives on the streaming endpoint itself
        // (see UsbAudioManager.activateDac's comment on the same split). Using
        // setClockFrequency unconditionally here was the actual cause of a real, reproduced
        // infinite playback-error loop against a UAC 1.0 device: every configure() call failed
        // this control transfer (wrong addressing entirely -- clockSourceId is always -1), threw
        // this same ConfigurationException every time, and fed straight into
        // ExoPlaybackStateHolder.onPlayerError's unconditional retry-next-track recovery.
        val clockOk =
            if (session.topology.uacVersion == 1) {
                UsbAudioController.setUac1EndpointSampleRate(session, format.endpointAddress, sampleRateHz)
            } else {
                UsbAudioController.setClockFrequency(session, sampleRateHz)
            }
        if (!clockOk) {
            throw AudioSink.ConfigurationException(
                "Failed to set DAC clock to $sampleRateHz Hz", inputFormat)
        }

        // See UsbAudioManager.activateDac's comment on wakePowerDomain for why: a clean,
        // 0-error transfer into a UAC3 Power Domain that's still mid-wake (or never woken at
        // all) looks identical, at the USB protocol level, to one going to a fully-powered
        // device. Unlike reassertKernelEviction below, this is safe to call again here -- it's
        // a power-state control transfer, not an interface claim, so it can't reset alt-setting.
        val powerDomainId = session.topology.powerDomainId
        if (powerDomainId >= 0) {
            UsbAudioController.wakePowerDomain(
                session,
                powerDomainId,
                session.topology.powerDomainD1RecoveryUs,
                session.topology.powerDomainD2RecoveryUs)
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
        L.i("UsbAudioSink: play()")
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
        lastHandleBufferAtMs = System.currentTimeMillis()
        handleBufferCalls++
        val written = ringBuffer.write(buffer)
        framesWritten += written / bytesPerFrame
        return !buffer.hasRemaining()
    }

    override fun playToEndOfStream() {
        L.i(
            "UsbAudioSink: playToEndOfStream(), ${ringBuffer.availableToRead()} bytes still " +
                "buffered, ${System.currentTimeMillis() - lastHandleBufferAtMs}ms since last handleBuffer()")
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
        L.i("UsbAudioSink: pause()")
    }

    override fun flush() {
        L.i("UsbAudioSink: flush()")
        ringBuffer.clear()
        framesWritten = 0L
        ended = false
    }

    override fun reset() {
        L.i("UsbAudioSink: reset()")
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
                                        "errors, ${ringBuffer.availableToRead()} bytes buffered, " +
                                        "$handleBufferCalls handleBuffer() calls, " +
                                        "${System.currentTimeMillis() - lastHandleBufferAtMs}ms since last one")
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
