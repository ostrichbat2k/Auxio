/*
 * Copyright (c) 2026 Auxio Project
 * UsbSettings.kt is part of Auxio.
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
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings

/**
 * User configuration specific to direct USB DAC output.
 *
 * @author Auxio Project
 */
interface UsbSettings : Settings<UsbSettings.Listener> {
    /**
     * Whether direct USB DAC output is enabled. This is an experimental, opt-in feature -- off by
     * default -- since it claims the USB interface away from Android's own audio framework.
     */
    val enabled: Boolean

    /**
     * Whether USB output should actually be used for playback. Distinct from [enabled]: a device
     * can be activated (and its Controls/Capabilities screens usable) without this -- audio only
     * ever routes to it once this is also on. Only meaningful (and only ever shown in the UI as
     * toggleable) when the attached device is bit-perfect capable; see
     * [UsbAudioManager.isBitPerfectCapable].
     */
    val bitPerfectMode: Boolean

    /**
     * The last hardware volume (raw dB*256 Feature Unit units) explicitly set via
     * [UsbAudioManager.setVolume] -- by the physical volume keys or the Settings screen's slider
     * -- or null if never set. Applied as the starting volume the next time a device is
     * activated (see [UsbAudioOutput]'s constructor) instead of a fixed default, so the user's
     * chosen level survives an app restart/DAC reconnect instead of resetting every time.
     */
    var volumeDb: Int?

    interface Listener {
        /** Called when [enabled] has changed. */
        fun onUsbEnabledChanged() {}

        /** Called when [bitPerfectMode] has changed. */
        fun onBitPerfectModeChanged() {}
    }
}

class UsbSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<UsbSettings.Listener>(context), UsbSettings {
    override val enabled: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_usb_enabled), false)

    override val bitPerfectMode: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_usb_bit_perfect), false)

    override var volumeDb: Int?
        get() =
            getString(R.string.set_key_usb_volume).let { key ->
                if (sharedPreferences.contains(key)) sharedPreferences.getInt(key, 0) else null
            }
        set(value) {
            sharedPreferences.edit {
                val key = getString(R.string.set_key_usb_volume)
                if (value != null) putInt(key, value) else remove(key)
            }
        }

    override fun onSettingChanged(key: String, listener: UsbSettings.Listener) {
        when (key) {
            getString(R.string.set_key_usb_enabled) -> listener.onUsbEnabledChanged()
            getString(R.string.set_key_usb_bit_perfect) -> listener.onBitPerfectModeChanged()
        }
    }
}
