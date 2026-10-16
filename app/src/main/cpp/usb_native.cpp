// Copyright (c) 2026 Auxio Project
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.
//
// Android's public UsbDeviceConnection.claimInterface(force=true) evicts a kernel driver
// from an interface via USBDEVFS_IOCTL/USBDEVFS_DISCONNECT, but the native implementation
// (android_hardware_UsbDeviceConnection_claim_interface in AOSP) always immediately claims
// the interface for the caller right after disconnecting it. The kernel's own
// USBDEVFS_SETCONFIGURATION handler (proc_setconfig in drivers/usb/core/devio.c) then
// refuses the configuration switch if *any* interface is claimed by *anyone*, including the
// calling process itself -- so going through claimInterface first guarantees the switch
// will fail.
//
// This shim issues the disconnect ioctl directly, without ever claiming the interface,
// leaving it in the "no driver bound, nothing claimed" state that proc_setconfig requires.

#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_org_oxycblt_auxio_usb_UsbNative_disconnectKernelDriver(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint interfaceNumber) {
    struct usbdevfs_ioctl ctl {};
    ctl.ifno = interfaceNumber;
    ctl.ioctl_code = USBDEVFS_DISCONNECT;
    ctl.data = nullptr;
    int ret = ioctl(fd, USBDEVFS_IOCTL, &ctl);
    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}
