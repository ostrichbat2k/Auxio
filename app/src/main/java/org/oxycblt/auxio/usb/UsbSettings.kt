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

    interface Listener {
        /** Called when [enabled] has changed. */
        fun onUsbEnabledChanged() {}
    }
}

class UsbSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<UsbSettings.Listener>(context), UsbSettings {
    override val enabled: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_usb_enabled), false)

    override fun onSettingChanged(key: String, listener: UsbSettings.Listener) {
        if (key == getString(R.string.set_key_usb_enabled)) {
            listener.onUsbEnabledChanged()
        }
    }
}
