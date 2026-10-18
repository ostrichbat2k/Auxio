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
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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
 * Direct USB output reconfigures the DAC's clock to match each track's actual rate (see
 * [UsbAudioOutput], and [IsoPacketScheduler] for how 44100 Hz, which isn't a whole number of
 * samples per USB's 1ms isochronous packet, is handled without a fixed packet size).
 */
@Singleton
class UsbAudioManager
@Inject
constructor(@ApplicationContext private val context: Context, private val usbSettings: UsbSettings) :
    UsbSettings.Listener {
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

    private val _controlState = MutableStateFlow<UsbControlState?>(null)
    /** Live, read-back-from-the-device state of the active session's adjustable controls. */
    val controlState: StateFlow<UsbControlState?> = _controlState.asStateFlow()

    private val _capabilities = MutableStateFlow<UsbDeviceCapabilities?>(null)
    /** Everything found on the attached device's descriptors -- see [UsbDeviceCapabilities]. */
    val capabilities: StateFlow<UsbDeviceCapabilities?> = _capabilities.asStateFlow()

    // In-memory only: which bit resolution to prefer when multiple formats are offered. Not
    // persisted across process death since changing it re-activates immediately anyway; a
    // settings-screen choice only has user-visible meaning while that screen (or the session it
    // started) is still around to show the effect.
    @Volatile private var preferredBitResolution: Int = 16

    @Volatile private var activeSession: UsbAudioSession? = null
    @Volatile private var activeFormat: UacStreamFormat? = null
    // Only reflects SUPPORTED_SAMPLE_RATE_HZ (UAC 2.0/3.0) or whatever activateDac negotiated for
    // UAC 1.0 until the first track actually plays -- after that, ensureFormatActive keeps this
    // in sync with whatever rate each track genuinely streams at, since different tracks can (and
    // do) use different rates within the same DAC session. Purely for display (UsbControlState,
    // the Settings screen) and the Capabilities probe before any track has played; actual
    // playback never consults this, it uses each track's own real rate directly.
    @Volatile private var activeRateHz: Int = SUPPORTED_SAMPLE_RATE_HZ

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
                        _capabilities.value = null
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
        usbSettings.registerListener(this)

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
            if (usbSettings.enabled && audioDevice.hasPermission && !wasPermitted) {
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

    // Auxio builds two renderers (FFmpeg-preferred, MediaCodec-fallback), each wrapping its own
    // RoutingAudioSink -- and both get queried (supportsFormat/getFormatSupport, and apparently
    // position) by ExoPlayer's renderer machinery, not just whichever one actually ends up
    // streaming. createAudioSink() returning a *fresh* UsbAudioSink per call meant each
    // RoutingAudioSink got its own, separate instance: one got genuinely configured and streamed
    // correctly, but the other sat forever unconfigured (sampleRateHz=0), and its
    // getCurrentPositionUs() dutifully reporting AudioSink.CURRENT_POSITION_NOT_SET
    // (Long.MIN_VALUE) seems to be exactly what corrupted the player's overall position
    // tracking -- observed as it jumping to nonsense values like -999999475 before stalling.
    // Caching a single instance per session, shared across both RoutingAudioSinks, means
    // whichever one actually gets used reports real progress to *both* callers.
    @Volatile private var activeSink: AudioSink? = null

    /**
     * Returns an [AudioSink] wired to the active DAC session, or null if none is ready -- or if
     * [UsbSettings.bitPerfectMode] is off, or the device isn't [isBitPerfectCapable]. A device
     * can be fully activated (Controls/Capabilities usable) without ever being used for actual
     * playback: [RoutingAudioSink] falls back to normal Android audio output whenever this
     * returns null, same mechanism as a genuinely absent/unplugged device, and it already
     * re-checks this on every track (see its own liveness re-validation), so toggling bit-perfect
     * mode takes effect on the very next [RoutingAudioSink.configure] call.
     *
     * Returns a plain [DefaultAudioSink] built with a [UsbAudioOutputProvider] rather than a
     * hand-rolled `AudioSink` -- see [UsbAudioOutput]'s doc comment for why.
     */
    fun createAudioSink(): AudioSink? {
        if (!usbSettings.bitPerfectMode || !isBitPerfectCapable()) return null
        activeSink?.let { return it }
        val session = activeSession ?: return null
        return DefaultAudioSink.Builder(context)
            .setAudioOutputProvider(UsbAudioOutputProvider(this, session, usbSettings))
            .build()
            .also { activeSink = it }
    }

    /**
     * Switches the DAC's active alt-setting to [format] if it isn't already, and records
     * [sampleRateHz] as the real currently-active rate -- called by
     * [UsbAudioOutputProvider.getAudioOutput] for every track, so a library mixing standard
     * 16-bit tracks with 24-bit hi-res ones (or different sample rates) gets a device format
     * that actually matches *each* track, rather than being stuck with a single bit depth for the
     * whole session (either corrupting the other, byte-misaligned -- confirmed, reproduced as
     * loud hissing -- or rejecting it outright).
     *
     * [activeRateHz]/[controlState]'s sample rate used to only ever get set once, by
     * [activateDac] at startup -- harmless before this session's per-track dynamic format
     * switching existed (every track genuinely did stream at the same rate activateDac picked),
     * but once tracks could switch rate on their own, the Settings screen kept showing that
     * stale startup rate forever after, even while actually playing something else entirely.
     */
    fun ensureFormatActive(format: UacStreamFormat, sampleRateHz: Int): Boolean {
        val session = activeSession ?: return false
        val formatChanged = format != activeFormat
        var ok = true
        if (formatChanged) {
            // Same reasoning as the identical call in activateDac(): Android's own ALSA driver
            // can race to re-claim the interface, and evicting it again right before
            // selectFormat is safe/cheap insurance against that.
            UsbAudioController.reassertKernelEviction(session)
            ok = UsbAudioController.selectFormat(session, format)
            if (ok) {
                activeFormat = format
                logEvent("Switched to ${format.bitResolution}-bit (alt=${format.alternateSetting}) for upcoming track")
            } else {
                logEvent("Failed to switch to ${format.bitResolution}-bit (alt=${format.alternateSetting}) for upcoming track")
            }
        }
        if (formatChanged || sampleRateHz != activeRateHz) {
            activeRateHz = sampleRateHz
            refreshControlState(session, activeFormat ?: format, session.device.productName ?: session.device.deviceName)
        }
        return ok
    }

    /**
     * Whether the active device is bit-perfect capable: its output Feature Unit was found *and*
     * its volume range was successfully read back (confirming Auxio actually knows where unity
     * gain is, rather than just assuming it) -- see [UsbControlState.volumeRange]. This is what
     * gates whether the "Bit-perfect mode" toggle is interactable at all; false if nothing is
     * active right now.
     */
    fun isBitPerfectCapable(): Boolean = controlState.value?.volumeRange != null

    /**
     * Reads the active session's Power Domain state right now (one of
     * [UsbAudioController.PD_STATE_D0]/`D1`/`D2`), or null if there's no active session or this
     * device's topology has no Power Domain at all (UAC 1.0/2.0, or a UAC 3.0 device that didn't
     * declare one). A blocking USB control transfer, same as the other on-demand reads this class
     * does in response to a UI query (e.g. [setMuted]'s read-back) -- fine for an occasional
     * settings-screen refresh, not meant to be polled.
     */
    fun getPowerDomainState(): Int? {
        val session = activeSession ?: return null
        val powerDomainId = session.topology.powerDomainId
        if (powerDomainId < 0) return null
        return UsbAudioController.getPowerDomainState(session, powerDomainId)
    }

    /**
     * Reads the active session's Clock Source frequency and valid/locked state right now, or
     * null if there's no active session or this device has no Clock Source at all (UAC 1.0 --
     * see [UacPlaybackTopology.clockSourceId]). Same blocking-read caveat as
     * [getPowerDomainState].
     */
    fun getClockInfo(): Pair<Int?, Boolean?>? {
        val session = activeSession ?: return null
        if (session.topology.clockSourceId < 0) return null
        return UsbAudioController.getClockFrequency(session) to UsbAudioController.getClockValid(session)
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
                if (topology == null) {
                    UsbAudioDescriptorParser.parseUac2Playback(bytes)?.let { topology = it }
                }
                if (topology == null) {
                    UsbAudioDescriptorParser.parseUac1Playback(bytes)?.let { topology = it }
                }
            }
        } finally {
            connection.close()
        }

        val found = topology
        if (found == null) {
            logEvent("No UAC1/UAC2/UAC3 playback topology found on ${device.deviceName}")
            return
        }
        val deviceName = device.productName ?: device.deviceName
        _capabilities.value =
            UsbDeviceCapabilities(
                deviceName = deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                topology = found)
        val format =
            found.formats.firstOrNull { it.bitResolution == preferredBitResolution }
                ?: found.formats.firstOrNull()
        if (format == null) {
            logEvent("No usable format available for ${device.deviceName}")
            return
        }
        logEvent(
            "Topology found: mixer=${found.mixerUnitId} featureUnit=${found.outputFeatureUnitId} " +
                "clock=${found.clockSourceId}")

        var session = UsbAudioController.activate(context, device, found)
        if (session == null) {
            logEvent("Failed to activate UAC${found.uacVersion} session for ${device.deviceName}")
            return
        }

        // Query what sample rates the Clock Source actually supports before ever asking it to
        // change rate -- UAC 1.0 has none, and this was never checked at all before: a real
        // device accepted a SET_CUR to an unsupported rate and echoed it back as a successful
        // readback while the real hardware clock silently stayed where it was (audibly: pitched
        // up and sped up, every sample converted at the wrong rate despite 0 USB errors anywhere
        // in the chain). See UsbAudioSession.clockSupportedRates and where
        // UsbAudioOutputProvider consults it -- an empty result here (couldn't read, not
        // "nothing supported") falls back to its old hardcoded guess rather than refusing every
        // rate.
        if (found.uacVersion != 1) {
            val ranges = UsbAudioController.getClockFrequencyRanges(session) ?: emptyList()
            session = session.copy(clockSupportedRates = ranges)
            logEvent("Clock ${found.clockSourceId} supported rate ranges: $ranges")
        }

        // Do this before anything else: its recovery wait (up to 300ms for a D2->D0 wake, per
        // this device's own declared waRecoveryTime2) should overlap with -- not follow -- the
        // rest of setup below, and nothing else here is safe to skip past a domain that might
        // still be asleep.
        if (found.powerDomainId >= 0) {
            UsbAudioController.wakePowerDomain(
                session, found.powerDomainId, found.powerDomainD1RecoveryUs, found.powerDomainD2RecoveryUs)
            logEvent(
                "Power domain ${found.powerDomainId} woken (d1=${found.powerDomainD1RecoveryUs}us " +
                    "d2=${found.powerDomainD2RecoveryUs}us)")
        } else {
            logEvent("No power domain found covering our USB input")
        }

        // UAC 2.0/3.0: the Clock Source entity is addressed independently of which streaming
        // alternate setting ends up selected, so this can happen up front. UAC 1.0 has no Clock
        // Source at all -- its sample rate is a property of the streaming *endpoint* itself, set
        // further below, after selectFormat has made that endpoint active (see there for why).
        val clockOk =
            if (found.uacVersion != 1) {
                UsbAudioController.setClockFrequency(session, SUPPORTED_SAMPLE_RATE_HZ).also {
                    logEvent("Clock set to $SUPPORTED_SAMPLE_RATE_HZ Hz: ok=$it")
                }
            } else {
                true
            }
        val muted = UsbAudioController.getMute(session)
        if (muted != false) UsbAudioController.setMute(session, muted = false)
        // Was hardcoded 0..2 (master + stereo) -- the Feature Unit has one volume control per
        // logical channel (1..N) plus the shared master (0), so a device with more than 2
        // channels has per-channel controls beyond what that range ever touched. channelCount
        // is read from the device's own Input Terminal descriptor, not assumed; only the loop
        // bound was ever hardcoded to stereo.
        //
        // Master channel (0) volume: we check master *mute* above, but had never checked
        // master *volume* -- if this Feature Unit has an independent master gain stage
        // separate from the per-channel ones below, it being low/attenuated would silence
        // everything regardless of what the per-channel ones are set to.
        for (channel in 0..found.channelCount.coerceAtLeast(2)) {
            val volume = UsbAudioController.getVolume(session, channel)
            // Deliberately the midpoint of the device's own reported range, not its max (unity
            // gain) -- a freshly (re)activated DAC shouldn't jump straight to its loudest
            // setting. See UsbAudioController.defaultVolumeTarget.
            val range = UsbAudioController.getVolumeRange(session, channel)
            val target = UsbAudioController.defaultVolumeTarget(range)
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
        // Was hardcoded to a stereo-in/stereo-out cross-point grid; both sides scale with the
        // device's own real channel count instead (not assumed).
        val mixerChannelCount = found.channelCount.coerceAtLeast(2)
        if (mixerUnitId >= 0 && mixerBase >= 0) {
            for (inCh in mixerBase until mixerBase + mixerChannelCount) {
                for (outCh in 1..mixerChannelCount) {
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

        // UAC 1.0's Sampling Frequency Control lives on the streaming endpoint itself, which
        // doesn't functionally exist until selectFormat (just above) has made its alternate
        // setting active -- so this has to happen after, unlike UAC 2.0/3.0's entity-addressed
        // clock above. Pick whichever of this format's declared rates matches what the rest of
        // the app expects (48kHz, falling back to 44.1kHz), or just its first if neither is
        // offered.
        val rateHz =
            if (found.uacVersion == 1) {
                format.sampleRates.firstOrNull { it == SUPPORTED_SAMPLE_RATE_HZ }
                    ?: format.sampleRates.firstOrNull { it == 44100 }
                    ?: format.sampleRates.firstOrNull()
            } else {
                SUPPORTED_SAMPLE_RATE_HZ
            }
        val rateOk =
            if (found.uacVersion == 1) {
                if (rateHz == null) {
                    logEvent("Format alt=${format.alternateSetting} declares no sample rate")
                    false
                } else {
                    UsbAudioController.setUac1EndpointSampleRate(session, format.endpointAddress, rateHz).also {
                        logEvent("UAC1 endpoint sample rate set to $rateHz Hz: ok=$it")
                    }
                }
            } else {
                clockOk
            }

        if (!rateOk || !formatOk) {
            logEvent("Failed to configure DAC session (rate=$rateOk format=$formatOk)")
            UsbAudioController.release(session)
            return
        }

        // Give the clock/alt-setting/power-domain changes above a moment to actually settle
        // before marking the session ready. Do NOT call reassertKernelEviction after
        // selectFormat -- see the comment above that call for why.
        //
        // Deliberately NOT running IsoToneTest here anymore: it was a useful one-off diagnostic
        // (a blocking 3-second tone test) while chasing the intermittent-silence investigation,
        // but running it on every single activation pushed "DAC session ready" out by 3+ seconds
        // -- which, in real playback, is 3+ seconds RoutingAudioSink's configure() could get
        // called first and commit to the fallback (AudioTrack/speaker) sink before this ever
        // finishes, exactly what was observed (tracks playing through the tablet speaker). The
        // settings screen's "Retry activation" + its live controls now cover manual diagnosis
        // instead.
        Thread.sleep(DAC_SETTLE_DELAY_MS)
        // No Clock Source entity exists to query on UAC 1.0.
        if (found.uacVersion != 1) {
            logEvent("Clock valid: ${UsbAudioController.getClockValid(session)}")
        }

        activeSession = session
        activeFormat = format
        activeRateHz = rateHz ?: SUPPORTED_SAMPLE_RATE_HZ
        refreshControlState(session, format, device.productName ?: device.deviceName)
        logEvent("DAC session ready: $format at ${activeRateHz} Hz")
    }

    /** Re-reads every adjustable control from the device and republishes [controlState]. */
    private fun refreshControlState(session: UsbAudioSession, format: UacStreamFormat, deviceName: String) {
        val mixerUnitId = session.topology.mixerUnitId
        val mixerBase = session.topology.mixerInputChannelBase
        // Was hardcoded to a stereo-in/stereo-out grid -- see activateDac's identical fix for why
        // this scales with the device's own real channel count instead.
        val mixerChannelCount = session.topology.channelCount.coerceAtLeast(2)
        val mixerChannels =
            if (mixerUnitId >= 0 && mixerBase >= 0) {
                (mixerBase until mixerBase + mixerChannelCount).flatMap { inCh ->
                    (1..mixerChannelCount).map { outCh -> inCh to outCh }
                }
            } else {
                emptyList()
            }
        val mixerGains =
            mixerChannels.associateWith { (inCh, outCh) ->
                UsbAudioController.getMixerGain(session, mixerUnitId, inCh, outCh)
            }
        val mixerGainRange =
            mixerChannels.firstOrNull()?.let { (inCh, outCh) ->
                UsbAudioController.getMixerGainRange(session, mixerUnitId, inCh, outCh)
            }
        // Some devices (confirmed: FiiO Snowsky Tiny A) accept every SET_CUR volume write but
        // always fail GET_CUR on readback (result=-1) -- a firmware limitation, not a transient
        // error. Falling back to null here used to mean every single caller of [controlState]
        // (this screen's volume slider, [MediaSessionHolder]'s physical-volume-key routing) saw
        // "volume unknown" after *every* write on such a device, and both of them treat unknown
        // as "assume max" -- so on a device like this, every write was immediately followed by
        // every reader snapping back to full volume. Preserving the last known value (readback or
        // our own just-applied write, whichever is freshest) instead means a GET_CUR failure
        // looks like "nothing changed" rather than "reset to max", which is the correct read on a
        // device that we just told to be at a specific value two lines above.
        val volume = UsbAudioController.getVolume(session, 0) ?: _controlState.value?.volume
        _controlState.value =
            UsbControlState(
                deviceName = deviceName,
                formats = session.topology.formats,
                activeFormat = format,
                sampleRateHz = activeRateHz,
                muted = UsbAudioController.getMute(session),
                volume = volume,
                volumeRange = UsbAudioController.getVolumeRange(session, 0),
                mixerChannels = mixerChannels,
                mixerGains = mixerGains,
                mixerGainRange = mixerGainRange,
                clockSupportedRates = session.clockSupportedRates)
    }

    /** Live-adjusts the active session's mute state, if any; reflected in [controlState]. */
    fun setMuted(muted: Boolean) {
        val session = activeSession ?: return
        val format = activeFormat ?: return
        UsbAudioController.setMute(session, muted)
        logEvent("User set mute=$muted")
        refreshControlState(session, format, session.device.productName ?: session.device.deviceName)
    }

    // Dedicated to serializing [setVolume]'s own control transfers off whichever thread calls it
    // (the physical volume keys dispatch through MediaSessionCompat's callback handler, i.e. the
    // main thread) -- see [setVolume]'s doc comment for why this exists.
    private val volumeExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "UsbAudioControl-Volume").apply { isDaemon = true } }
    @Volatile private var pendingVolumeDb: Int? = null
    @Volatile private var volumeWriteInFlight = false

    /**
     * Live-adjusts the active session's master/channel volume, if any; reflected in
     * [controlState]. Runs the actual control transfers (and the full post-write
     * [refreshControlState] re-read) on a background thread and coalesces rapid successive calls
     * down to just the latest target, rather than running each synchronously on the calling
     * thread in turn.
     *
     * This specifically fixes a real, reported lag: holding the physical volume key fires a
     * burst of calls here (one per key-repeat event) in quick succession on the main thread. Each
     * one used to be a fully synchronous round trip -- 3 `SET_CUR` transfers plus
     * [refreshControlState]'s own handful of `GET_CUR`/`GET_RANGE` reads -- so a burst of them
     * queued up and had to drain serially, and the DAC's volume kept visibly climbing/falling for
     * a noticeable while after the user had already stopped pressing. Since only the *final*
     * target in a burst actually matters, [pendingVolumeDb] tracks just that, and
     * [scheduleVolumeWrite] only starts a new round trip once the previous one has actually
     * finished, skipping straight to whatever the latest requested value is instead of replaying
     * every intermediate step.
     */
    fun setVolume(valueDb: Int) {
        pendingVolumeDb = valueDb
        // Reflect the requested value immediately so callers (the volume key's system UI, the
        // Settings screen slider) don't have to wait on the hardware round-trip just to show
        // where the user asked the slider to go; refreshControlState below corrects this to the
        // real read-back value once the write actually lands.
        _controlState.value = _controlState.value?.copy(volume = valueDb)
        // Persisted so the next activation (new session, app restart, DAC reconnect) starts from
        // here instead of a fixed default -- see [UsbAudioOutput]'s constructor.
        usbSettings.volumeDb = valueDb
        scheduleVolumeWrite()
    }

    private fun scheduleVolumeWrite() {
        if (volumeWriteInFlight) return
        val target = pendingVolumeDb ?: return
        val session = activeSession ?: return
        val format = activeFormat ?: return
        volumeWriteInFlight = true
        volumeExecutor.execute {
            // Was hardcoded 0..2 (master + stereo) -- see activateDac's identical fix.
            for (channel in 0..session.topology.channelCount.coerceAtLeast(2)) {
                UsbAudioController.setVolume(session, target, channel)
            }
            logEvent("User set volume=$target")
            refreshControlState(session, format, session.device.productName ?: session.device.deviceName)
            volumeWriteInFlight = false
            if (pendingVolumeDb != target) {
                // Something more recent arrived while this write was in flight -- go straight to
                // it instead of waiting for the next external setVolume call to notice.
                scheduleVolumeWrite()
            }
        }
    }

    /** Live-adjusts one Mixer Unit (input, output) channel pair's gain, if the device has one. */
    fun setMixerGain(inputChannel: Int, outputChannel: Int, valueDb: Int) {
        val session = activeSession ?: return
        val format = activeFormat ?: return
        val mixerUnitId = session.topology.mixerUnitId
        if (mixerUnitId < 0) return
        UsbAudioController.setMixerGain(session, mixerUnitId, inputChannel, outputChannel, valueDb)
        logEvent("User set mixer gain in=$inputChannel out=$outputChannel to $valueDb")
        refreshControlState(session, format, session.device.productName ?: session.device.deviceName)
    }

    /**
     * Changes which bit resolution to prefer and immediately re-activates with it, if a format
     * at that resolution exists on the current device (falls back to whatever's first otherwise
     * -- see where [preferredBitResolution] is consulted in [activateDac]).
     */
    fun setPreferredBitResolution(bitResolution: Int) {
        preferredBitResolution = bitResolution
        logEvent("Preferred format set to ${bitResolution}-bit, re-activating")
        debugRetryActivation()
    }

    private fun deactivateDac() {
        activeSession?.let { UsbAudioController.release(it) }
        activeSession = null
        activeFormat = null
        activeSink = null
        _controlState.value = null
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
        if (!usbSettings.enabled) {
            L.i("debugRetryActivation: USB DAC output is disabled in settings")
            return
        }
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

    /**
     * [UsbSettings.Listener] callback: direct USB output is an opt-in, experimental feature (it
     * claims the USB interface away from Android's own audio framework), off by default. Turning
     * it on tries to activate whatever's already attached; turning it off releases any active
     * session immediately.
     */
    override fun onUsbEnabledChanged() {
        if (usbSettings.enabled) {
            L.i("USB DAC output enabled by user, attempting activation")
            debugRetryActivation()
        } else {
            L.i("USB DAC output disabled by user, releasing any active session")
            deactivateDac()
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = BuildConfig.APPLICATION_ID + ".action.USB_PERMISSION"
        const val SUPPORTED_SAMPLE_RATE_HZ = 48000
        const val LOG_CAPACITY = 50
    }
}
