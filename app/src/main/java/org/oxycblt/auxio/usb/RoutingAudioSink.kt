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
 *
 * [supportsFormat]/[getFormatSupport] must NOT commit to a delegate via [resolveForConfigure] --
 * ExoPlayer calls these during track/format selection, which happens *before* [configure], and
 * this genuinely did lose the race there even with the above fix: the sink got locked to the
 * fallback the moment a format-support query ran before USB activation finished. They use [peek]
 * instead, which checks USB availability fresh every call without ever caching.
 *
 * Even with that fixed, real playback was still observed going to the fallback sink indefinitely:
 * the very *first* [configure] call for a freshly-launched player -- e.g. auto-resuming saved
 * playback state right at app launch, well before USB permission has even been granted yet, let
 * alone the DAC activated -- has every chance of losing this race too, and unlike the
 * supportsFormat/getFormatSupport case there's no way around resolving *something* real inside
 * configure() (the chosen sink actually needs configuring). The fix is to not treat the fallback
 * as a permanent choice: [resolveForConfigure] re-checks USB availability on *every* call for as
 * long as the current delegate is still the fallback, so losing the race on track 1 just means
 * falling back to the speaker for that one track -- the next [configure] call (next track, or a
 * seek/format change on the same one) gets another chance, instead of being stuck on the
 * fallback for the rest of the player's lifetime. Once actually upgraded to the USB sink, it's
 * never downgraded back -- only [reset] clears that (e.g. the device was unplugged).
 */
class RoutingAudioSink(private val usbAudioManager: UsbAudioManager, private val fallback: AudioSink) :
    AudioSink {
    private var delegate: AudioSink? = null
    private var pendingListener: AudioSink.Listener? = null

    /** Whatever's already been decided, defaulting to [fallback] without caching that default. */
    private fun current(): AudioSink = delegate ?: fallback

    private fun peek(): AudioSink = delegate ?: usbAudioManager.createAudioSink() ?: fallback

    private fun resolveForConfigure(): AudioSink {
        val existing = delegate
        if (existing != null && existing !== fallback) {
            // Already upgraded to USB; never go back down.
            return existing
        }
        val chosen = usbAudioManager.createAudioSink() ?: fallback
        if (chosen !== existing) {
            pendingListener?.let(chosen::setListener)
        }
        delegate = chosen
        return chosen
    }

    override fun setListener(listener: AudioSink.Listener) {
        pendingListener = listener
        delegate?.setListener(listener)
    }

    override fun supportsFormat(format: Format): Boolean = peek().supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = peek().getFormatSupport(format)

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = current().getCurrentPositionUs(sourceEnded)

    @Deprecated("Deprecated in AudioSink interface itself; this is still the signature callers use.")
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        resolveForConfigure().configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun play() = current().play()

    override fun handleDiscontinuity() = current().handleDiscontinuity()

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean = current().handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    override fun playToEndOfStream() = current().playToEndOfStream()

    override fun isEnded(): Boolean = current().isEnded()

    override fun hasPendingData(): Boolean = current().hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        current().setPlaybackParameters(playbackParameters)

    override fun getPlaybackParameters(): PlaybackParameters = current().getPlaybackParameters()

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) =
        current().setSkipSilenceEnabled(skipSilenceEnabled)

    override fun getSkipSilenceEnabled(): Boolean = current().getSkipSilenceEnabled()

    override fun setAudioAttributes(audioAttributes: AudioAttributes) = current().setAudioAttributes(audioAttributes)

    override fun getAudioAttributes(): AudioAttributes? = current().getAudioAttributes()

    override fun setAudioSessionId(audioSessionId: Int) = current().setAudioSessionId(audioSessionId)

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = current().setAuxEffectInfo(auxEffectInfo)

    override fun getAudioTrackBufferSizeUs(): Long = current().audioTrackBufferSizeUs

    override fun enableTunnelingV21() = current().enableTunnelingV21()

    override fun disableTunneling() = current().disableTunneling()

    override fun setVolume(volume: Float) = current().setVolume(volume)

    override fun pause() = current().pause()

    override fun flush() = current().flush()

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
