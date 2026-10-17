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
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.usb.UsbAudioDevice
import org.oxycblt.auxio.usb.UsbAudioManager
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

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == getString(R.string.set_key_usb_retry)) {
            usbAudioManager.debugRetryActivation()
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }
}
