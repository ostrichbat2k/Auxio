/*
 * Copyright (c) 2026 Auxio Project
 * UacPlaybackTopology.kt is part of Auxio.
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

/** One selectable PCM format on a UAC 3.0 streaming interface: one alternate setting. */
data class UacStreamFormat(
    val alternateSetting: Int,
    val bitResolution: Int,
    val subslotSize: Int,
    val clusterDescriptorId: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    val isAsync: Boolean,
)

/** The USB Audio Class 3.0 playback topology discovered on a device: enough to start a stream. */
data class UacPlaybackTopology(
    val configurationValue: Int,
    val controlInterfaceNumber: Int,
    val streamingInterfaceNumber: Int,
    val clockSourceId: Int,
    /** The Feature Unit gating the physical output (mute/volume), or -1 if none was found. */
    val outputFeatureUnitId: Int,
    /**
     * The Mixer Unit sitting between our USB input and [outputFeatureUnitId], if any (-1 if
     * none) -- on at least one real device, the Feature Unit's mute/volume is NOT the only gate:
     * the USB stream is first summed into a Mixer Unit alongside another source (e.g. the
     * hardware mic/line-in), and that mixer's own per-input gain defaults to silence until set,
     * independently of the Feature Unit downstream being correctly unmuted at unity.
     */
    val mixerUnitId: Int,
    /**
     * The 1-based input-channel number our USB terminal's first channel occupies on
     * [mixerUnitId] (e.g. 1 for stereo if we're the first input pin), or -1 if [mixerUnitId] is
     * -1 or we couldn't determine the offset (anything but the first pin requires summing
     * preceding pins' channel counts via their Cluster Descriptors, which aren't parsed here).
     */
    val mixerInputChannelBase: Int,
    /**
     * The UAC 3.0 Power Domain gating our USB input/output path, or -1 if none was found. UAC3
     * introduced per-entity power management (D0/D1/D2); this device's real Linux kernel ALSA
     * driver (sound/usb/power.c) explicitly wakes the relevant domain to D0 and waits out its
     * declared recovery time before ever streaming to it -- something this codebase had never
     * done at all until this was discovered via a full topology dump. Clean, 0-error USB
     * transfers happening while the domain is still mid-wake would be silently discarded by the
     * device's own (still-asleep) analog output stage, with nothing visible at the USB protocol
     * level to show it.
     */
    val powerDomainId: Int,
    /** D1->D0 recovery time in microseconds (waRecoveryTime1 * 50us), or 0 if unknown. */
    val powerDomainD1RecoveryUs: Int,
    /** D2->D0 recovery time in microseconds (waRecoveryTime2 * 50us), or 0 if unknown. */
    val powerDomainD2RecoveryUs: Int,
    val formats: List<UacStreamFormat>,
)
