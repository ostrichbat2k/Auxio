/*
 * Copyright (c) 2026 Auxio Project
 * UsbNative.kt is part of Auxio.
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

/**
 * Raw USBDEVFS_IOCTL access that android.hardware.usb.UsbDeviceConnection doesn't expose: a
 * driver-disconnect that doesn't also claim the interface. See usb_native.cpp for why this is
 * needed instead of claimInterface(force=true).
 */
object UsbNative {
    init {
        System.loadLibrary("usbnative")
    }

    /** Evicts whatever kernel driver is bound to [interfaceNumber] on [fd], without claiming it. */
    external fun disconnectKernelDriver(fd: Int, interfaceNumber: Int): Boolean
}
