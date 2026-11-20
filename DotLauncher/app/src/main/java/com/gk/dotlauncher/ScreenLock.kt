package com.gk.dotlauncher

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Device admin with only the "force-lock" policy, used for double-tap to lock. */
class LockAdminReceiver : DeviceAdminReceiver()

object ScreenLock {
    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(c: Context) = ComponentName(c, LockAdminReceiver::class.java)

    fun isEnabled(c: Context) = dpm(c).isAdminActive(admin(c))

    /** Returns false if permission has not been granted yet. */
    fun lock(c: Context): Boolean {
        if (!isEnabled(c)) return false
        return runCatching { dpm(c).lockNow() }.isSuccess
    }

    fun requestIntent(c: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(c))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, c.getString(R.string.lock_admin_desc))

    fun disable(c: Context) = runCatching { dpm(c).removeActiveAdmin(admin(c)) }
}
