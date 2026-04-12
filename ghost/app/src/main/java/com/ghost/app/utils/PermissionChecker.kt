package com.ghost.app.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.ghost.app.R

/**
 * Permission checking and requesting utilities.
 * Handles MANAGE_EXTERNAL_STORAGE and SYSTEM_ALERT_WINDOW permissions.
 */
object PermissionChecker {

    private const val TAG = "GhostPermission"

    /**
     * Check if MANAGE_EXTERNAL_STORAGE permission is granted.
     * Required for reading the 2.5GB model file from external storage.
     */
    fun hasManageExternalStorage(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // For older versions, we would need WRITE_EXTERNAL_STORAGE
            // but this app targets Android 16 (API 36) only
            false
        }
    }

    /**
     * Check if SYSTEM_ALERT_WINDOW permission is granted.
     * Required for showing the PiP overlay window.
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Request MANAGE_EXTERNAL_STORAGE permission.
     * Opens system settings since this is a special permission.
     * @param activity Activity context for launching settings
     * @return true if already granted, false if redirected to settings
     */
    fun requestManageExternalStorage(activity: Activity): Boolean {
        if (hasManageExternalStorage(activity)) {
            return true
        }

        Log.i(TAG, "Redirecting to settings for MANAGE_EXTERNAL_STORAGE")

        Toast.makeText(
            activity,
            R.string.permission_settings_redirect,
            Toast.LENGTH_LONG
        ).show()

        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${activity.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general storage settings
            val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(fallbackIntent)
        }

        return false
    }

    /**
     * Request SYSTEM_ALERT_WINDOW permission.
     * Opens system settings for overlay permission.
     * @param activity Activity context for launching settings
     * @return true if already granted, false if redirected to settings
     */
    fun requestOverlayPermission(activity: Activity): Boolean {
        if (hasOverlayPermission(activity)) {
            return true
        }

        Log.i(TAG, "Redirecting to settings for SYSTEM_ALERT_WINDOW")

        Toast.makeText(
            activity,
            R.string.permission_overlay_required,
            Toast.LENGTH_LONG
        ).show()

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        activity.startActivity(intent)
        return false
    }

    /**
     * Check all required permissions.
     * @return Triple of (hasStorage, hasOverlay, hasNotifications)
     */
    fun checkAllPermissions(context: Context): Triple<Boolean, Boolean, Boolean> {
        return Triple(
            hasManageExternalStorage(context),
            hasOverlayPermission(context),
            hasNotificationPermission(context)
        )
    }
    
    /**
     * Check if POST_NOTIFICATIONS permission is granted (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == 
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed on older versions
        }
    }
    
    /**
     * Request notification permission (Android 13+)
     */
    fun requestNotificationPermission(activity: Activity): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission(activity)) {
                activity.requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1003
                )
                return false
            }
        }
        return true
    }

    /**
     * Verify that all critical permissions are granted.
     * If not, shows appropriate error and returns false.
     */
    fun verifyPermissionsOrFinish(activity: Activity): Boolean {
        val (hasStorage, hasOverlay) = checkAllPermissions(activity)

        if (!hasStorage) {
            Toast.makeText(
                activity,
                R.string.permission_storage_required,
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        if (!hasOverlay) {
            Toast.makeText(
                activity,
                R.string.permission_overlay_required,
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        return true
    }
}
