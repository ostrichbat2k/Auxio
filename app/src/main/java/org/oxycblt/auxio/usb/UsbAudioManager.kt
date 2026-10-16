/*
 * Copyright (c) 2026 Auxio Project
 * UsbAudioManager.kt is part of Auxio.
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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.oxycblt.auxio.BuildConfig
import timber.log.Timber as L

/**
 * Detects attached USB Audio Class devices (candidate DACs) and manages permission requests for
 * them. This is the first stage toward direct USB audio output: detection and permission only.
 * Actual audio routing is handled separately once a permitted device is available.
 */
@Singleton
class UsbAudioManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _devices = MutableStateFlow<List<UsbAudioDevice>>(emptyList())
    val devices: StateFlow<List<UsbAudioDevice>> = _devices.asStateFlow()

    private var started = false
    private var knownDeviceNames = emptySet<String>()
    private var knownPermissions = emptyMap<String, Boolean>()

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        L.i("USB device attached: ${intentDevice(intent)?.deviceName}")
                        refresh()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        L.i("USB device detached: ${intentDevice(intent)?.deviceName}")
                        refresh()
                    }
                    ACTION_USB_PERMISSION -> {
                        val device = intentDevice(intent)
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        L.i("USB permission result for ${device?.deviceName}: granted=$granted")
                        if (device != null) {
                            val label = device.productName ?: device.deviceName
                            Toast.makeText(
                                    context,
                                    if (granted) "USB DAC permission granted: $label"
                                    else "USB DAC permission denied: $label",
                                    Toast.LENGTH_LONG,
                                )
                                .show()
                        }
                        refresh()
                    }
                }
            }
        }

    private fun intentDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    /** Begins listening for USB device attach/detach/permission events. Call once at startup. */
    fun start() {
        if (started) return
        started = true

        val filter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        }

        refresh()
    }

    /** Requests permission to use [device]. The result arrives asynchronously via broadcast. */
    fun requestPermission(device: UsbDevice) {
        val permissionIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun refresh() {
        val found =
            usbManager.deviceList.values.filter { it.isAudioDevice() }.map {
                UsbAudioDevice(it, usbManager.hasPermission(it))
            }

        val newNames = found.map { it.displayName }.toSet()
        for (name in newNames - knownDeviceNames) {
            Toast.makeText(context, "USB Audio Device detected: $name", Toast.LENGTH_LONG).show()
        }
        knownDeviceNames = newNames

        // Trigger descriptor inspection off the actual permission state transition rather than
        // the ACTION_USB_PERMISSION broadcast's extras, which were observed to be unreliable
        // (null device, granted=false) on at least one real device/OS build even when the
        // permission was in fact granted a moment later.
        for (audioDevice in found) {
            val wasPermitted = knownPermissions[audioDevice.device.deviceName] ?: false
            if (audioDevice.hasPermission && !wasPermitted) {
                L.i("Permission newly granted for ${audioDevice.displayName}, inspecting")
                UsbAudioInspector.inspect(context, audioDevice.device)
            }
        }
        knownPermissions = found.associate { it.device.deviceName to it.hasPermission }

        L.d("USB audio devices: ${found.map { it.displayName to it.hasPermission }}")
        _devices.value = found
    }

    private fun UsbDevice.isAudioDevice(): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    private companion object {
        const val ACTION_USB_PERMISSION = BuildConfig.APPLICATION_ID + ".action.USB_PERMISSION"
    }
}
