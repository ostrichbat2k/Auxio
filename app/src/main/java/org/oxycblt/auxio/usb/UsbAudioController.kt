/*
 * Copyright (c) 2026 Auxio Project
 * UsbAudioController.kt is part of Auxio.
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

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import timber.log.Timber as L

/** An activated USB connection to a UAC3 device: configuration selected, interfaces claimed. */
data class UsbAudioSession(
    val connection: UsbDeviceConnection,
    val topology: UacPlaybackTopology,
    val controlInterface: UsbInterface,
    /** Streaming interface alternate settings, keyed by bAlternateSetting. */
    val streamingAltSettings: Map<Int, UsbInterface>,
)

/**
 * Switches a UAC3 device into its playback configuration, claims the relevant interfaces, and
 * issues the class-specific control requests needed to pick a sample rate and PCM format.
 *
 * The control-request byte layout here (request type/codes, wValue/wIndex encoding) follows the
 * USB Audio Class 3.0 spec's Clock Source entity requests, carried forward from the same
 * addressing scheme UAC 1.0/2.0 used. Unlike the descriptor parser, this hasn't been cross-
 * verified against a passive capture (control transfers are live, not something `lsusb` records),
 * so [getClockFrequency] is meant to be tried first as a safe, read-only check of the addressing
 * before any SET_CUR write is attempted.
 */
object UsbAudioController {
    private const val REQ_TYPE_CLASS_INTERFACE_GET = 0xA1 // Device-to-host, Class, Interface
    private const val REQ_TYPE_CLASS_INTERFACE_SET = 0x21 // Host-to-device, Class, Interface
    private const val UAC_REQUEST_CUR = 0x01
    private const val CS_SAM_FREQ_CONTROL = 0x01

    /** Opens, configures, and claims the interfaces for [device]'s UAC3 playback topology. */
    fun activate(context: Context, device: UsbDevice, topology: UacPlaybackTopology): UsbAudioSession? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            L.w("Could not open USB connection to ${device.deviceName}")
            return null
        }

        // The kernel refuses SETCONFIGURATION while any interface of the device has a kernel
        // driver bound (e.g. snd-usb-audio auto-binds to the default configuration's audio
        // interface the moment the device is attached, independent of our app). claimInterface
        // (force=true) evicts that driver via USBDEVFS_DISCONNECT. We must NOT release these
        // claims before switching configuration: releaseInterface() explicitly tells the kernel
        // to reconnect its driver (see android_hardware_UsbDeviceConnection_release_interface in
        // AOSP's frameworks/base/core/jni), which would immediately re-arm the exact block we're
        // trying to clear.
        val defaultConfig = device.getConfiguration(0)
        var claimedDefault = 0
        for (i in 0 until defaultConfig.interfaceCount) {
            if (connection.claimInterface(defaultConfig.getInterface(i), true)) claimedDefault++
        }
        L.i("Force-claimed $claimedDefault/${defaultConfig.interfaceCount} default-configuration interfaces")

        val targetConfig =
            (0 until device.configurationCount)
                .map { device.getConfiguration(it) }
                .firstOrNull { it.id == topology.configurationValue }
        if (targetConfig == null) {
            L.w("Could not find UsbConfiguration matching value ${topology.configurationValue}")
            connection.close()
            return null
        }

        if (!connection.setConfiguration(targetConfig)) {
            L.w("setConfiguration(${targetConfig.id}) failed")
            connection.close()
            return null
        }

        var controlInterface: UsbInterface? = null
        val streamingAlts = mutableMapOf<Int, UsbInterface>()
        for (i in 0 until targetConfig.interfaceCount) {
            val iface = targetConfig.getInterface(i)
            if (iface.id == topology.controlInterfaceNumber && iface.alternateSetting == 0) {
                controlInterface = iface
            } else if (iface.id == topology.streamingInterfaceNumber) {
                streamingAlts[iface.alternateSetting] = iface
            }
        }

        val control = controlInterface
        if (control == null || streamingAlts.isEmpty()) {
            L.w("Could not locate control/streaming interfaces in target configuration")
            connection.close()
            return null
        }

        if (!connection.claimInterface(control, true)) {
            L.w("Failed to claim control interface ${control.id}")
            connection.close()
            return null
        }
        val altZero = streamingAlts[0]
        if (altZero != null && !connection.claimInterface(altZero, true)) {
            L.w("Failed to claim streaming interface ${altZero.id}")
            connection.releaseInterface(control)
            connection.close()
            return null
        }

        L.i(
            "Activated UAC3 session: config=${targetConfig.id} " +
                "control=${control.id} streaming=${topology.streamingInterfaceNumber} " +
                "alts=${streamingAlts.keys}")
        return UsbAudioSession(connection, topology, control, streamingAlts)
    }

    fun release(session: UsbAudioSession) {
        session.connection.releaseInterface(session.controlInterface)
        for (iface in session.streamingAltSettings.values) {
            session.connection.releaseInterface(iface)
        }
        session.connection.close()
    }

    /** Reads the clock source's current sample rate in Hz, or null on failure. Safe/read-only. */
    fun getClockFrequency(session: UsbAudioSession): Int? {
        val buffer = ByteArray(4)
        val wValue = CS_SAM_FREQ_CONTROL shl 8
        val wIndex = (session.topology.clockSourceId shl 8) or session.controlInterface.id
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_GET, UAC_REQUEST_CUR, wValue, wIndex, buffer, buffer.size, 1000)
        if (result != 4) {
            L.w("GET_CUR clock frequency failed: result=$result (wValue=$wValue wIndex=$wIndex)")
            return null
        }
        val hz =
            (buffer[0].toInt() and 0xFF) or
                ((buffer[1].toInt() and 0xFF) shl 8) or
                ((buffer[2].toInt() and 0xFF) shl 16) or
                ((buffer[3].toInt() and 0xFF) shl 24)
        L.i("Clock ${session.topology.clockSourceId} current frequency: $hz Hz")
        return hz
    }

    /** Attempts to set the clock source sample rate in Hz, then reads it back to confirm. */
    fun setClockFrequency(session: UsbAudioSession, hz: Int): Boolean {
        val wValue = CS_SAM_FREQ_CONTROL shl 8
        val wIndex = (session.topology.clockSourceId shl 8) or session.controlInterface.id
        val buffer =
            byteArrayOf(
                (hz and 0xFF).toByte(),
                ((hz shr 8) and 0xFF).toByte(),
                ((hz shr 16) and 0xFF).toByte(),
                ((hz shr 24) and 0xFF).toByte())
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_SET, UAC_REQUEST_CUR, wValue, wIndex, buffer, buffer.size, 1000)
        if (result < 0) {
            L.w("SET_CUR clock frequency to $hz Hz failed: result=$result")
            return false
        }
        val readBack = getClockFrequency(session)
        val ok = readBack == hz
        L.i("SET_CUR clock frequency to $hz Hz: write result=$result, readback=$readBack, match=$ok")
        return ok
    }

    /** Switches the streaming interface to the alternate setting for [format]. */
    fun selectFormat(session: UsbAudioSession, format: UacStreamFormat): Boolean {
        val iface = session.streamingAltSettings[format.alternateSetting]
        if (iface == null) {
            L.w("No interface for alternate setting ${format.alternateSetting}")
            return false
        }
        val ok = session.connection.setInterface(iface)
        L.i("setInterface(alt=${format.alternateSetting}, ${format.bitResolution}-bit): $ok")
        return ok
    }
}
