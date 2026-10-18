/*
 * Copyright (c) 2026 Auxio Project
 * UsbPreferenceFragment.kt is part of Auxio.
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

package org.oxycblt.auxio.settings.categories

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.usb.UsbAudioDevice
import org.oxycblt.auxio.usb.UsbAudioManager
import org.oxycblt.auxio.usb.UsbControlState
import org.oxycblt.auxio.usb.UsbDeviceCapabilities
import org.oxycblt.auxio.usb.UsbSettings
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.navigateSafe

/**
 * USB DAC status and activation log, for diagnosing direct USB audio output -- shows whatever's
 * currently attached/permitted and a running history of what [UsbAudioManager] did the last time
 * it tried to activate a session, so changes across attempts (settling delays, control-transfer
 * fixes, etc.) can be tracked from the device itself instead of pulling logcat each time.
 */
@AndroidEntryPoint
class UsbPreferenceFragment : BasePreferenceFragment(R.xml.preferences_usb) {
    @Inject lateinit var usbAudioManager: UsbAudioManager
    @Inject lateinit var usbSettings: UsbSettings

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectImmediately(usbAudioManager.devices, ::updateDevice)
        collectImmediately(usbAudioManager.diagnosticLog, ::updateLog)
        collectImmediately(usbAudioManager.capabilities, ::updateCapabilities)
        collectImmediately(usbAudioManager.controlState, ::updateControls)
    }

    private fun updateDevice(devices: List<UsbAudioDevice>) {
        val summary =
            devices.joinToString("\n") { "${it.displayName} (permission: ${it.hasPermission})" }
                .ifEmpty { getString(R.string.set_usb_device_none) }
        findPreference<Preference>(getString(R.string.set_key_usb_device))?.summary = summary
    }

    private fun updateLog(lines: List<String>) {
        val category =
            findPreference<PreferenceCategory>(getString(R.string.set_key_usb_log_category))
                ?: return
        category.removeAll()
        if (lines.isEmpty()) {
            category.addPreference(
                Preference(requireContext()).apply {
                    isSelectable = false
                    title = getString(R.string.set_usb_log_empty)
                })
            return
        }
        for (line in lines) {
            category.addPreference(
                Preference(requireContext()).apply {
                    isSelectable = false
                    title = line
                })
        }
    }

    /**
     * Updates the "Capabilities" entry point's summary with a one-line preview of what's been
     * found so far -- the full, explained breakdown lives on [UsbCapabilitiesFragment], navigated
     * to on tap (see [onPreferenceTreeClick]). Independent of whether Auxio went on to
     * successfully configure the device, so this still updates even if [updateControls] ends up
     * showing no active session.
     */
    private fun updateCapabilities(capabilities: UsbDeviceCapabilities?) {
        val summary =
            capabilities?.let { "${it.deviceName} -- UAC ${it.topology.uacVersion}.0" }
                ?: getString(R.string.set_usb_capabilities_none)
        findPreference<Preference>(getString(R.string.set_key_usb_capabilities))?.summary = summary
    }

    /**
     * Rebuilds the "Controls" category entirely from what [state] says this device actually
     * supports -- a device with a single format offers no format picker, one with no Mixer Unit
     * in its topology offers no mixer-gain sliders, etc. Nothing here is specific to any one
     * device model.
     */
    private fun updateControls(state: UsbControlState?) {
        updateBitPerfectAvailability(state)

        val category =
            findPreference<PreferenceCategory>(getString(R.string.set_key_usb_controls_category))
                ?: return
        category.removeAll()

        if (state == null) {
            category.addPreference(
                Preference(requireContext()).apply {
                    isSelectable = false
                    title = getString(R.string.set_usb_controls_none)
                })
            return
        }

        if (state.formats.size > 1) {
            category.addPreference(
                ListPreference(requireContext()).apply {
                    key = getString(R.string.set_key_usb_format)
                    title = getString(R.string.set_usb_format)
                    isPersistent = false
                    entries = state.formats.map { "${it.bitResolution}-bit" }.toTypedArray()
                    entryValues = state.formats.map { it.bitResolution.toString() }.toTypedArray()
                    value = state.activeFormat.bitResolution.toString()
                    summary = "%s"
                    setOnPreferenceChangeListener { _, newValue ->
                        usbAudioManager.setPreferredBitResolution((newValue as String).toInt())
                        true
                    }
                })
        }

        category.addPreference(
            SwitchPreferenceCompat(requireContext()).apply {
                key = getString(R.string.set_key_usb_mute)
                title = getString(R.string.set_usb_mute)
                isPersistent = false
                isChecked = state.muted == true
                setOnPreferenceChangeListener { _, newValue ->
                    usbAudioManager.setMuted(newValue as Boolean)
                    true
                }
            })

        val volumeRange = state.volumeRange
        if (volumeRange != null) {
            category.addPreference(
                SeekBarPreference(requireContext()).apply {
                    key = getString(R.string.set_key_usb_volume)
                    title = getString(R.string.set_usb_volume)
                    isPersistent = false
                    min = volumeRange.first
                    max = volumeRange.second
                    showSeekBarValue = true
                    value = (state.volume ?: usbSettings.volumeDb ?: volumeRange.second).coerceIn(min, max)
                    setOnPreferenceChangeListener { _, newValue ->
                        usbAudioManager.setVolume(newValue as Int)
                        true
                    }
                })
        }

        val mixerRange = state.mixerGainRange
        if (mixerRange != null) {
            for ((inCh, outCh) in state.mixerChannels) {
                category.addPreference(
                    SeekBarPreference(requireContext()).apply {
                        key =
                            getString(R.string.set_key_usb_mixer_gain_prefix) + "${inCh}_$outCh"
                        title = getString(R.string.set_usb_mixer_gain, inCh, outCh)
                        isPersistent = false
                        min = mixerRange.first
                        max = mixerRange.second
                        showSeekBarValue = true
                        value = (state.mixerGains[inCh to outCh] ?: mixerRange.second).coerceIn(min, max)
                        setOnPreferenceChangeListener { _, newValue ->
                            usbAudioManager.setMixerGain(inCh, outCh, newValue as Int)
                            true
                        }
                    })
            }
        }
    }

    /**
     * Grays out the "Bit-perfect mode" switch with an explanation whenever
     * [UsbAudioManager.isBitPerfectCapable] says no -- toggling it would otherwise do nothing
     * observable (createAudioSink already refuses to use USB output in that case), which reads as
     * a silent failure rather than the device genuinely not supporting it.
     */
    private fun updateBitPerfectAvailability(state: UsbControlState?) {
        val preference =
            findPreference<SwitchPreferenceCompat>(getString(R.string.set_key_usb_bit_perfect)) ?: return
        val capable = state?.volumeRange != null
        preference.isEnabled = capable
        preference.summary =
            if (capable) getString(R.string.set_usb_bit_perfect_desc)
            else getString(R.string.set_usb_bit_perfect_not_capable)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == getString(R.string.set_key_usb_retry)) {
            usbAudioManager.debugRetryActivation()
            return true
        }
        if (preference.key == getString(R.string.set_key_usb_capabilities)) {
            findNavController().navigateSafe(UsbPreferenceFragmentDirections.usbCapabilities())
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }
}
