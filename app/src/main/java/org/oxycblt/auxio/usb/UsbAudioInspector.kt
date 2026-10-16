/*
 * Copyright (c) 2026 Auxio Project
 * UsbAudioInspector.kt is part of Auxio.
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
import android.hardware.usb.UsbManager
import timber.log.Timber as L

/**
 * Opens a permitted USB device and walks every one of its configurations looking for a UAC 3.0
 * playback topology, logging what it finds. This is read-only enumeration: it does not switch
 * the device's active configuration or claim any interface.
 */
object UsbAudioInspector {
    fun inspect(context: Context, device: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            L.w("Could not open USB connection to ${device.deviceName}")
            return
        }
        var topology: UacPlaybackTopology? = null
        try {
            val configCount = device.configurationCount.coerceAtLeast(1)
            L.i("Inspecting ${device.productName ?: device.deviceName}: $configCount configuration(s)")
            for (i in 0 until configCount) {
                val bytes = UsbAudioDescriptorParser.fetchConfigurationDescriptor(connection, i) ?: continue
                val found = UsbAudioDescriptorParser.parseUac3Playback(bytes)
                if (found != null) {
                    topology = found
                    L.i("Config index $i: UAC3 playback topology = $found")
                }
            }
            if (topology == null) {
                L.i("No UAC3 (full, non-BADD) playback configuration found on this device.")
            }
        } finally {
            connection.close()
        }

        val found = topology ?: return
        val session = UsbAudioController.activate(context, device, found) ?: return
        try {
            val currentHz = UsbAudioController.getClockFrequency(session)
            L.i("Phase 3 probe: clock source reports ${currentHz ?: "unreadable"} Hz before any change")
            if (currentHz != null) {
                val targetHz = if (currentHz == 44100) 48000 else 44100
                val wrote = UsbAudioController.setClockFrequency(session, targetHz)
                L.i("Phase 3 probe: set clock to $targetHz Hz -> $wrote")
                // Restore the original rate so we don't leave the device in an unexpected state.
                UsbAudioController.setClockFrequency(session, currentHz)
            }
            val selected = found.formats.maxByOrNull { it.bitResolution }
            if (selected != null) {
                UsbAudioController.selectFormat(session, selected)
            }
        } finally {
            UsbAudioController.release(session)
        }
    }
}
