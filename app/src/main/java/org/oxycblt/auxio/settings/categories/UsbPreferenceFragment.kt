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
import org.oxycblt.auxio.util.collectImmediately

/**
 * USB DAC status and activation log, for diagnosing direct USB audio output -- shows whatever's
 * currently attached/permitted and a running history of what [UsbAudioManager] did the last time
 * it tried to activate a session, so changes across attempts (settling delays, control-transfer
 * fixes, etc.) can be tracked from the device itself instead of pulling logcat each time.
 */
@AndroidEntryPoint
class UsbPreferenceFragment : BasePreferenceFragment(R.xml.preferences_usb) {
    @Inject lateinit var usbAudioManager: UsbAudioManager

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectImmediately(usbAudioManager.devices, ::updateDevice)
        collectImmediately(usbAudioManager.diagnosticLog, ::updateLog)
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
     * Rebuilds the "Controls" category entirely from what [state] says this device actually
     * supports -- a device with a single format offers no format picker, one with no Mixer Unit
     * in its topology offers no mixer-gain sliders, etc. Nothing here is specific to any one
     * device model.
     */
    private fun updateControls(state: UsbControlState?) {
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
                    value = (state.volume ?: volumeRange.second).coerceIn(min, max)
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

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == getString(R.string.set_key_usb_retry)) {
            usbAudioManager.debugRetryActivation()
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }
}
