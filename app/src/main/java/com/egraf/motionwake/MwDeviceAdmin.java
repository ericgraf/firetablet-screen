/*
 * MwDeviceAdmin — MotionWake device-admin receiver.
 *
 * Activating this admin (dpm set-active-admin, or the ACTION_ADD_DEVICE_ADMIN
 * flow) makes the package uninstall-proof — the same persistence guarantee
 * the tablet's old kiosk app (Fully) used. The admin only requests the
 * force-lock policy; MotionWake removes it itself when it needs to allow
 * uninstall (dpm.removeActiveAdmin / removeActiveAdmin(receiver, null)).
 */
package com.egraf.motionwake;

import android.app.admin.DeviceAdminReceiver;

public class MwDeviceAdmin extends DeviceAdminReceiver {
}
