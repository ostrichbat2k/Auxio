/*
 * Copyright (c) 2026 Auxio Project
 * RoutingAudioSink.kt is part of Auxio.
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
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * Picks between a USB-direct sink (via [usbAudioManager]) and [fallback] the first time
 * [configure] is called, rather than when the renderer (and this sink) is constructed.
 *
 * [UsbAudioManager] activates its DAC session asynchronously, off the main thread, as soon as USB
 * permission is granted -- but permission-grant latency itself is unpredictable (observed
 * anywhere from ~100ms to 3+ seconds), and the player is typically constructed well before that
 * resolves. Checking at construction time means losing the race whenever that happens. Checking
 * instead at the first real `configure()` call -- which only happens once the user has navigated
 * the UI and started playback -- gives the background activation a realistic chance to finish
 * first, since that step itself is fast (under a second) once permission actually lands.
 */
class RoutingAudioSink(private val usbAudioManager: UsbAudioManager, private val fallback: AudioSink) :
    AudioSink {
    private var delegate: AudioSink? = null
    private var pendingListener: AudioSink.Listener? = null

    private fun resolve(): AudioSink {
        delegate?.let {
            return it
        }
        val chosen = usbAudioManager.createAudioSink() ?: fallback
        pendingListener?.let(chosen::setListener)
        delegate = chosen
        return chosen
    }

    override fun setListener(listener: AudioSink.Listener) {
        pendingListener = listener
        delegate?.setListener(listener)
    }

    override fun supportsFormat(format: Format): Boolean = resolve().supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = resolve().getFormatSupport(format)

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = resolve().getCurrentPositionUs(sourceEnded)

    @Deprecated("Deprecated in AudioSink interface itself; this is still the signature callers use.")
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        resolve().configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun play() = resolve().play()

    override fun handleDiscontinuity() = resolve().handleDiscontinuity()

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean = resolve().handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    override fun playToEndOfStream() = resolve().playToEndOfStream()

    override fun isEnded(): Boolean = resolve().isEnded()

    override fun hasPendingData(): Boolean = resolve().hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        resolve().setPlaybackParameters(playbackParameters)

    override fun getPlaybackParameters(): PlaybackParameters = resolve().getPlaybackParameters()

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) =
        resolve().setSkipSilenceEnabled(skipSilenceEnabled)

    override fun getSkipSilenceEnabled(): Boolean = resolve().getSkipSilenceEnabled()

    override fun setAudioAttributes(audioAttributes: AudioAttributes) = resolve().setAudioAttributes(audioAttributes)

    override fun getAudioAttributes(): AudioAttributes? = resolve().getAudioAttributes()

    override fun setAudioSessionId(audioSessionId: Int) = resolve().setAudioSessionId(audioSessionId)

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = resolve().setAuxEffectInfo(auxEffectInfo)

    override fun getAudioTrackBufferSizeUs(): Long = resolve().audioTrackBufferSizeUs

    override fun enableTunnelingV21() = resolve().enableTunnelingV21()

    override fun disableTunneling() = resolve().disableTunneling()

    override fun setVolume(volume: Float) = resolve().setVolume(volume)

    override fun pause() = resolve().pause()

    override fun flush() = resolve().flush()

    override fun reset() {
        // Let the next configure() re-evaluate USB availability (e.g. a DAC plugged in or
        // unplugged since the last track) rather than sticking with whatever was chosen before.
        delegate?.reset()
        delegate = null
    }

    override fun release() {
        delegate?.release()
        delegate = null
    }
}
