/*
 * Copyright (c) 2026 Auxio Project
 * PcmRingBuffer.kt is part of Auxio.
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

import java.nio.ByteBuffer

/**
 * A simple thread-safe byte ring buffer decoupling the decoder's feed rate (via [write], called
 * from the player's playback thread) from the USB stream's real-time pull rate (via
 * [readOrZeroPad], called from the dedicated streaming thread).
 */
class PcmRingBuffer(capacityBytes: Int) {
    private val buffer = ByteArray(capacityBytes)
    private var readPos = 0
    private var writePos = 0
    private var size = 0
    private val lock = Any()

    fun availableToRead(): Int = synchronized(lock) { size }

    fun availableToWrite(): Int = synchronized(lock) { buffer.size - size }

    /** Writes as much of [src] (from its current position to its limit) as there's room for. */
    fun write(src: ByteBuffer): Int =
        synchronized(lock) {
            val toWrite = minOf(src.remaining(), buffer.size - size)
            var written = 0
            while (written < toWrite) {
                val chunk = minOf(toWrite - written, buffer.size - writePos)
                src.get(buffer, writePos, chunk)
                writePos = (writePos + chunk) % buffer.size
                written += chunk
            }
            size += written
            written
        }

    /** Fills [dst] with buffered data, zero-padding (silence) if there isn't enough yet. */
    fun readOrZeroPad(dst: ByteArray) {
        synchronized(lock) {
            val toRead = minOf(dst.size, size)
            var read = 0
            while (read < toRead) {
                val chunk = minOf(toRead - read, buffer.size - readPos)
                System.arraycopy(buffer, readPos, dst, read, chunk)
                readPos = (readPos + chunk) % buffer.size
                read += chunk
            }
            size -= read
            if (read < dst.size) {
                dst.fill(0, read, dst.size)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            readPos = 0
            writePos = 0
            size = 0
        }
    }
}
