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
import androidx.media3.exoplayer.audio.AudioSink
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.oxycblt.auxio.BuildConfig
import timber.log.Timber as L

/**
 * Detects attached USB Audio Class devices (candidate DACs), manages permission requests, and
 * once permitted, activates a persistent [UsbAudioSession] configured and ready for playback.
 * [createAudioSink] hands out an [AudioSink] wired to that session for the player to use in place
 * of the normal AudioTrack-backed sink.
 *
 * Direct USB output supports 44100 and 48000 Hz -- the two rates this device's clock source was
 * confirmed to accept -- with [UsbAudioSink] reconfiguring the DAC's clock to match each track as
 * needed (see [IsoPacketScheduler] for how 44100 Hz, which isn't a whole number of samples per
 * USB's 1ms isochronous packet, is handled without a fixed packet size).
 */
@Singleton
class UsbAudioManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _devices = MutableStateFlow<List<UsbAudioDevice>>(emptyList())
    val devices: StateFlow<List<UsbAudioDevice>> = _devices.asStateFlow()

    private val _diagnosticLog = MutableStateFlow<List<String>>(emptyList())
    /** Most-recent-first log of activation events, for the USB settings screen. */
    val diagnosticLog: StateFlow<List<String>> = _diagnosticLog.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Appends a timestamped line to [diagnosticLog] (newest first, capped at [LOG_CAPACITY]). */
    private fun logEvent(message: String) {
        val stamp = timeFormatter.format(Date())
        L.i(message)
        _diagnosticLog.value = (listOf("$stamp  $message") + _diagnosticLog.value).take(LOG_CAPACITY)
    }

    @Volatile private var activeSession: UsbAudioSession? = null
    @Volatile private var activeFormat: UacStreamFormat? = null

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
                        deactivateDac()
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
                L.i("Permission newly granted for ${audioDevice.displayName}, activating DAC session")
                // Session activation blocks on several control transfers; keep it off whatever
                // thread refresh() was called on (the broadcast receiver's, normally main).
                Thread { activateDac(audioDevice.device) }
                    .apply {
                        isDaemon = true
                        start()
                    }
            }
        }
        knownPermissions = found.associate { it.device.deviceName to it.hasPermission }

        L.d("USB audio devices: ${found.map { it.displayName to it.hasPermission }}")
        _devices.value = found
    }

    private fun UsbDevice.isAudioDevice(): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    /** Returns an [AudioSink] wired to the active DAC session, or null if none is ready. */
    fun createAudioSink(): AudioSink? {
        val session = activeSession ?: return null
        val format = activeFormat ?: return null
        return UsbAudioSink(session, format)
    }

    private fun activateDac(device: UsbDevice) {
        logEvent("Activating ${device.productName ?: device.deviceName}")
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            logEvent("Could not open USB connection to ${device.deviceName}")
            return
        }
        var topology: UacPlaybackTopology? = null
        try {
            for (i in 0 until device.configurationCount.coerceAtLeast(1)) {
                val bytes = UsbAudioDescriptorParser.fetchConfigurationDescriptor(connection, i) ?: continue
                UsbAudioDescriptorParser.dumpAcTopology(bytes)
                UsbAudioDescriptorParser.parseUac3Playback(bytes)?.let { topology = it }
            }
        } finally {
            connection.close()
        }

        val found = topology
        if (found == null) {
            logEvent("No UAC3 playback topology found on ${device.deviceName}")
            return
        }
        val format = found.formats.firstOrNull { it.bitResolution == 16 }
        if (format == null) {
            logEvent("No 16-bit format available for ${device.deviceName}")
            return
        }
        logEvent(
            "Topology found: mixer=${found.mixerUnitId} featureUnit=${found.outputFeatureUnitId} " +
                "clock=${found.clockSourceId}")

        val session = UsbAudioController.activate(context, device, found)
        if (session == null) {
            logEvent("Failed to activate UAC3 session for ${device.deviceName}")
            return
        }

        val clockOk = UsbAudioController.setClockFrequency(session, SUPPORTED_SAMPLE_RATE_HZ)
        logEvent("Clock set to $SUPPORTED_SAMPLE_RATE_HZ Hz: ok=$clockOk")
        val muted = UsbAudioController.getMute(session)
        if (muted != false) UsbAudioController.setMute(session, muted = false)
        // Master channel (0) volume: we check master *mute* above, but had never checked
        // master *volume* -- if this Feature Unit has an independent master gain stage
        // separate from the per-channel ones below, it being low/attenuated would silence
        // everything regardless of what channels 1/2 are set to.
        for (channel in 0..2) {
            val volume = UsbAudioController.getVolume(session, channel)
            // 0 (1/256 dB) is 0dB/unity gain by the general UAC convention, but that's only
            // ever been an assumption -- never actually checked against what this specific
            // device itself reports as its real maximum via GET_RANGE. If its true max isn't
            // 0 (firmware quirk, or a device that encodes gain rather than attenuation),
            // writing 0 could be landing well short of audible.
            val range = UsbAudioController.getVolumeRange(session, channel)
            val target = range?.second ?: 0
            if (volume == null || volume != target) {
                UsbAudioController.setVolume(session, value = target, channel = channel)
            }
            logEvent("Volume ch$channel: was=$volume range=$range target=$target")
        }

        // Our USB input doesn't feed the output Feature Unit directly on every device -- on
        // this one it's first summed into a Mixer Unit alongside a second source (the hardware
        // mic path), discovered via UsbAudioDescriptorParser.dumpAcTopology. The Feature Unit
        // being correctly unmuted at unity means nothing if the mixer's own per-channel gain for
        // our input defaults to silence, independent of and upstream from everything above.
        val mixerUnitId = found.mixerUnitId
        val mixerBase = found.mixerInputChannelBase
        if (mixerUnitId >= 0 && mixerBase >= 0) {
            for (inCh in mixerBase until mixerBase + 2) {
                for (outCh in 1..2) {
                    val gain = UsbAudioController.getMixerGain(session, mixerUnitId, inCh, outCh)
                    val gainRange = UsbAudioController.getMixerGainRange(session, mixerUnitId, inCh, outCh)
                    val gainTarget = gainRange?.second ?: 0
                    if (gain == null || gain != gainTarget) {
                        UsbAudioController.setMixerGain(session, mixerUnitId, inCh, outCh, gainTarget)
                    }
                    logEvent("Mixer gain in=$inCh out=$outCh: was=$gain range=$gainRange target=$gainTarget")
                }
            }
        } else {
            logEvent("No mixer unit gating our USB input (mixerUnitId=$mixerUnitId base=$mixerBase)")
        }

        // Android's own UsbAlsaManager independently binds the kernel's snd-usb-audio driver to
        // any USB Audio Class device on attach; that bind was observed completing (ALSA device
        // node creation logged) *during* this very activation sequence, after the one-time
        // disconnectKernelDriver pass in UsbAudioController.activate() had already run -- so it
        // can still be racing us at this point. Evicting it again here, right before the final
        // alt-setting selection, is safe; evicting it *after* selectFormat was tried first and
        // broke every subsequent iso submission (errno=ENOENT), almost certainly because
        // disconnecting a driver resets the interface to alt-setting 0 as a side effect, undoing
        // whatever alt-setting was just selected. So this must stay before selectFormat, not after.
        UsbAudioController.reassertKernelEviction(session)
        val formatOk = UsbAudioController.selectFormat(session, format)
        logEvent("Alt-setting ${format.alternateSetting} (${format.bitResolution}-bit) selected: ok=$formatOk")

        if (!clockOk || !formatOk) {
            logEvent("Failed to configure DAC session (clock=$clockOk format=$formatOk)")
            UsbAudioController.release(session)
            return
        }

        // Diagnostic: confirm the updated (variable-packet-length) native pipeline still
        // produces audible output at 44100 Hz on its own, isolating whether a problem is in that
        // pipeline versus the AudioSink/real-decoded-PCM integration built on top of it.
        //
        // The silence turned out to be intermittent, not a fixed code regression: the same build
        // was confirmed both audible and silent across repeated runs with no code change in
        // between. Hypothesis: the clock/alt-setting switch just above needs some settling time
        // on this hardware before it's actually ready to render, and streaming immediately after
        // sometimes races ahead of that. DAC_SETTLE_DELAY_MS gives it a moment before the first
        // packet goes out. Do NOT call reassertKernelEviction after selectFormat -- see the
        // comment above that call for why.
        Thread.sleep(DAC_SETTLE_DELAY_MS)
        logEvent("Clock valid before streaming: ${UsbAudioController.getClockValid(session)}")
        IsoToneTest.play(session, format, 48000, durationMs = 3000)
        logEvent("Clock valid after streaming: ${UsbAudioController.getClockValid(session)}")

        activeSession = session
        activeFormat = format
        logEvent("DAC session ready: $format at $SUPPORTED_SAMPLE_RATE_HZ Hz")
    }

    private fun deactivateDac() {
        activeSession?.let { UsbAudioController.release(it) }
        activeSession = null
        activeFormat = null
    }

    /**
     * Testing hook: re-runs DAC activation (including the diagnostic tone test) for whatever USB
     * audio device is currently attached. Requests permission if needed, or re-activates
     * directly if already permitted -- Android only re-fires the permission-granted broadcast on
     * an actual grant, not on a request for a permission [knownPermissions] already has recorded
     * as granted, so without this there's no way to re-trigger activation short of unplugging the
     * device or restarting the app.
     */
    fun debugRetryActivation() {
        val device = _devices.value.firstOrNull()
        if (device == null) {
            L.w("debugRetryActivation: no USB audio device attached")
            return
        }
        if (!device.hasPermission) {
            L.i("debugRetryActivation: requesting permission for ${device.displayName}")
            requestPermission(device.device)
            return
        }
        L.i("debugRetryActivation: re-activating DAC session for ${device.displayName}")
        deactivateDac()
        Thread { activateDac(device.device) }
            .apply {
                isDaemon = true
                start()
            }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = BuildConfig.APPLICATION_ID + ".action.USB_PERMISSION"
        const val SUPPORTED_SAMPLE_RATE_HZ = 48000
        const val LOG_CAPACITY = 50
    }
}
