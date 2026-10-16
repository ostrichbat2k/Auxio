/*
 * Copyright (c) 2026 Auxio Project
 * UsbAudioDescriptorParser.kt is part of Auxio.
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

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import timber.log.Timber as L

/**
 * Parses raw USB configuration descriptors to extract the USB Audio Class 3.0
 * control/streaming topology.
 *
 * The public android.hardware.usb Java API only models standard descriptors (Interface,
 * Endpoint). The class-specific AudioControl/AudioStreaming descriptors that describe
 * terminals, clocks, and PCM formats are embedded inside the raw configuration descriptor
 * as a flat sequential list and have to be walked manually, matching exactly what a tool
 * like `lsusb -v` does. Byte offsets below were verified against a real UAC 3.0 device
 * (Apple USB-C EarPods) rather than derived from the spec alone.
 */
object UsbAudioDescriptorParser {

    private const val DESC_TYPE_CONFIGURATION = 0x02
    private const val DESC_TYPE_INTERFACE = 0x04
    private const val DESC_TYPE_ENDPOINT = 0x05
    private const val DESC_TYPE_CS_INTERFACE = 0x24

    private const val AC_SUBCLASS = 1
    private const val AS_SUBCLASS = 2
    private const val UAC3_PROTOCOL = 0x30

    // AudioControl class-specific interface descriptor subtypes (UAC 3.0). These differ from
    // UAC 1.0/2.0's subtype numbers (e.g. FEATURE_UNIT is 6 in UAC2 but 7 in UAC3).
    private const val AC_INPUT_TERMINAL = 0x02
    private const val AC_OUTPUT_TERMINAL = 0x03
    private const val AC_CLOCK_SOURCE = 0x0B

    // AudioStreaming class-specific interface descriptor subtype (UAC 3.0). Unlike UAC 1.0/2.0,
    // UAC 3.0 folds format info directly into AS_GENERAL rather than a separate FORMAT_TYPE
    // descriptor.
    private const val AS_GENERAL = 0x01

    private const val TERMINAL_USB_STREAMING = 0x0101

    // USB Endpoint Descriptor bmAttributes synchronization-type field (bits 3:2).
    private const val SYNC_TYPE_ASYNC = 0x1

    /** Fetches a full configuration descriptor (standard + class-specific) by its 0-based index. */
    fun fetchConfigurationDescriptor(connection: UsbDeviceConnection, configIndex: Int): ByteArray? {
        val header = ByteArray(9)
        val headerLen =
            connection.controlTransfer(
                0x80, 0x06, (DESC_TYPE_CONFIGURATION shl 8) or configIndex, 0, header, header.size, 1000)
        if (headerLen < 4) {
            L.w("Failed to read configuration descriptor header (index $configIndex): $headerLen")
            return null
        }
        val totalLength = header.u16(2)
        val full = ByteArray(totalLength)
        val fullLen =
            connection.controlTransfer(
                0x80, 0x06, (DESC_TYPE_CONFIGURATION shl 8) or configIndex, 0, full, full.size, 2000)
        if (fullLen != totalLength) {
            L.w("Short read on configuration descriptor $configIndex: got $fullLen, wanted $totalLength")
        }
        return if (fullLen > 0) full.copyOf(fullLen) else null
    }

    /** Splits a raw configuration descriptor into its sequential (type, bytes) TLV entries. */
    private fun walk(data: ByteArray): List<Pair<Int, ByteArray>> {
        val entries = mutableListOf<Pair<Int, ByteArray>>()
        var offset = 0
        while (offset + 2 <= data.size) {
            val length = data.u8(offset)
            if (length < 2 || offset + length > data.size) break
            val type = data.u8(offset + 1)
            entries.add(type to data.copyOfRange(offset, offset + length))
            offset += length
        }
        return entries
    }

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

    private fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)

    /**
     * Finds the UAC 3.0 AudioControl interface in [configBytes] (if any) and parses it, together
     * with its linked USB-Streaming-to-output AudioStreaming interface, into a playback topology.
     * Returns null if this configuration isn't a (full, non-BADD) UAC 3.0 one.
     */
    fun parseUac3Playback(configBytes: ByteArray): UacPlaybackTopology? {
        val entries = walk(configBytes)

        var configurationValue = 0
        var currentInterfaceNumber = -1
        var currentAltSetting = -1
        var currentInterfaceClass = -1
        var currentInterfaceSubClass = -1
        var currentInterfaceProtocol = -1

        var controlInterfaceNumber = -1
        var streamingInterfaceNumber = -1
        var clockSourceId = -1
        var usbStreamingTerminalId = -1
        var outputFeatureUnitId = -1

        val formatsByInterface = mutableMapOf<Int, MutableList<UacStreamFormat>>()
        var pendingAltSetting = -1
        var pendingEndpointAddress = -1
        var pendingEndpointSync = -1
        var pendingMaxPacketSize = -1
        var pendingBitResolution = -1
        var pendingSubslotSize = -1
        var pendingClusterId = -1

        fun flushPendingFormat(interfaceNumber: Int) {
            if (pendingAltSetting >= 0 && pendingEndpointAddress >= 0 && pendingBitResolution > 0) {
                formatsByInterface
                    .getOrPut(interfaceNumber) { mutableListOf() }
                    .add(
                        UacStreamFormat(
                            alternateSetting = pendingAltSetting,
                            bitResolution = pendingBitResolution,
                            subslotSize = pendingSubslotSize,
                            clusterDescriptorId = pendingClusterId,
                            endpointAddress = pendingEndpointAddress,
                            maxPacketSize = pendingMaxPacketSize,
                            isAsync = pendingEndpointSync == SYNC_TYPE_ASYNC))
            }
            pendingAltSetting = -1
            pendingEndpointAddress = -1
            pendingEndpointSync = -1
            pendingMaxPacketSize = -1
            pendingBitResolution = -1
            pendingSubslotSize = -1
            pendingClusterId = -1
        }

        for ((type, bytes) in entries) {
            when (type) {
                DESC_TYPE_CONFIGURATION -> {
                    configurationValue = bytes.u8(5)
                }
                DESC_TYPE_INTERFACE -> {
                    if (currentInterfaceNumber == streamingInterfaceNumber) {
                        flushPendingFormat(currentInterfaceNumber)
                    }
                    currentInterfaceNumber = bytes.u8(2)
                    currentAltSetting = bytes.u8(3)
                    currentInterfaceClass = bytes.u8(5)
                    currentInterfaceSubClass = bytes.u8(6)
                    currentInterfaceProtocol = bytes.u8(7)
                    if (currentInterfaceProtocol == UAC3_PROTOCOL && currentInterfaceSubClass == AS_SUBCLASS) {
                        pendingAltSetting = currentAltSetting
                    }
                }
                DESC_TYPE_CS_INTERFACE -> {
                    if (currentInterfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
                    val subtype = bytes.u8(2)
                    if (currentInterfaceSubClass == AC_SUBCLASS && currentInterfaceProtocol == UAC3_PROTOCOL) {
                        controlInterfaceNumber = currentInterfaceNumber
                        when (subtype) {
                            AC_INPUT_TERMINAL -> {
                                val terminalId = bytes.u8(3)
                                val terminalType = bytes.u16(4)
                                if (terminalType == TERMINAL_USB_STREAMING) {
                                    usbStreamingTerminalId = terminalId
                                }
                            }
                            AC_CLOCK_SOURCE -> {
                                if (clockSourceId < 0) clockSourceId = bytes.u8(3)
                            }
                            AC_OUTPUT_TERMINAL -> {
                                val terminalType = bytes.u16(4)
                                // The real-world (non-USB-Streaming) output terminal's source is
                                // the last Feature Unit before the physical jack -- the one whose
                                // mute/volume state actually gates what's audible.
                                if (terminalType != TERMINAL_USB_STREAMING && outputFeatureUnitId < 0) {
                                    outputFeatureUnitId = bytes.u8(7) // bSourceID
                                }
                            }
                        }
                    } else if (currentInterfaceSubClass == AS_SUBCLASS &&
                        currentInterfaceProtocol == UAC3_PROTOCOL &&
                        subtype == AS_GENERAL) {
                        val terminalLink = bytes.u8(3)
                        if (terminalLink == usbStreamingTerminalId) {
                            streamingInterfaceNumber = currentInterfaceNumber
                            pendingClusterId = bytes.u16(8) // wClusterDescrID
                            pendingSubslotSize = bytes.u8(18) // bSubslotSize
                            pendingBitResolution = bytes.u8(19) // bBitResolution
                        }
                    }
                }
                DESC_TYPE_ENDPOINT -> {
                    if (currentInterfaceNumber == streamingInterfaceNumber) {
                        pendingEndpointAddress = bytes.u8(2)
                        val attributes = bytes.u8(3)
                        pendingEndpointSync = (attributes shr 2) and 0x3
                        pendingMaxPacketSize = bytes.u16(4)
                    }
                }
            }
        }
        flushPendingFormat(currentInterfaceNumber)

        if (controlInterfaceNumber < 0 || streamingInterfaceNumber < 0 || clockSourceId < 0) {
            L.d(
                "No UAC3 playback topology in this configuration " +
                    "(control=$controlInterfaceNumber streaming=$streamingInterfaceNumber clock=$clockSourceId)")
            return null
        }

        val formats = formatsByInterface[streamingInterfaceNumber].orEmpty()
        if (formats.isEmpty()) {
            L.w("No streaming formats found for interface $streamingInterfaceNumber")
            return null
        }

        return UacPlaybackTopology(
            configurationValue = configurationValue,
            controlInterfaceNumber = controlInterfaceNumber,
            streamingInterfaceNumber = streamingInterfaceNumber,
            clockSourceId = clockSourceId,
            outputFeatureUnitId = outputFeatureUnitId,
            formats = formats)
    }
}
