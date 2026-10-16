/*
 * Copyright (c) 2026 Auxio Project
 * IsoPacketScheduler.kt is part of Auxio.
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

/**
 * Distributes whole-number sample counts across isochronous packets for sample rates that don't
 * divide evenly into USB's one-packet-per-millisecond schedule -- 44100 Hz is 44.1 samples per
 * packet, not a whole number. Uses a running-remainder (Bresenham-style) distribution: exact over
 * any window, and degenerates to a fixed sample count per packet when the rate does divide evenly
 * (e.g. 48000 Hz -> always exactly 48).
 */
class IsoPacketScheduler(sampleRateHz: Int) {
    private val baseSamplesPerPacket = sampleRateHz / 1000
    private val remainderPerPacket = sampleRateHz % 1000
    private var accumulator = 0

    /** Returns the number of samples the next packet should carry. */
    fun nextPacketSampleCount(): Int {
        accumulator += remainderPerPacket
        return if (accumulator >= 1000) {
            accumulator -= 1000
            baseSamplesPerPacket + 1
        } else {
            baseSamplesPerPacket
        }
    }

    fun reset() {
        accumulator = 0
    }
}
