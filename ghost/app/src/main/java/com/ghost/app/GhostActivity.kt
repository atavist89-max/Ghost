package com.ghost.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.ghost.app.inference.ModelValidator
import com.ghost.app.notification.NotificationLoggerService
import com.ghost.app.utils.GhostPaths
import com.ghost.app.utils.PermissionChecker
import java.io.File

/**
 * Launcher activity that captures the screen using AccessibilityService and opens the full-screen chat.
 * 
 * Flow:
 * 1. Check MANAGE_EXTERNAL_STORAGE permission
 * 2. Validate model exists
 * 3. Check AccessibilityService is enabled
 * 4. Take silent screenshot via AccessibilityService
 * 5. Launch ChatActivity with screenshot
 * 
 * Note: Replaces MediaProjection (with permission dialog) with AccessibilityService.takeScreenshot() (silent)
 * Reference: Pokeclaw architecture (github.com/agents-io/PokeClaw)
 */
class GhostActivity : Activity() {

    companion object {
        private const val TAG = "GhostActivity"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var redirectedForNotification = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        // Check permissions and proceed
        if (!checkStoragePermission()) {
            return
        }
        
        // Validate model before proceeding
        if (!validateModel()) {
            finish()
            return
        }
        
        // Check AccessibilityService is enabled
        if (!isAccessibilityServiceEnabled()) {
            Log.i(TAG, "AccessibilityService not enabled, redirecting to settings")
            Toast.makeText(
                this, 
                getString(R.string.accessibility_service_not_enabled), 
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
            return
        }
        
        // Check if AccessibilityService is connected
        if (!GhostAccessibilityService.isConnected()) {
            Log.w(TAG, "AccessibilityService enabled but not connected yet")
            Toast.makeText(
                this,
                getString(R.string.accessibility_service_connecting),
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        // Check NotificationListenerService is enabled
        if (!isNotificationListenerEnabled()) {
            Log.i(TAG, "Notification listener not enabled, redirecting to settings")
            Toast.makeText(
                this,
                "Notification access is required for the historian feature",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            redirectedForNotification = true
            return
        }

        // Take silent screenshot
        takeSilentScreenshot()
    }
    
    override fun onResume() {
        super.onResume()
        if (redirectedForNotification && !isNotificationListenerEnabled()) {
            Log.i(TAG, "Returned from settings without enabling notification listener, finishing")
            finish()
        }
        redirectedForNotification = false
    }

    /**
     * Check if NotificationListenerService is enabled in system settings.
     */
    private fun isNotificationListenerEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val myComponent = ComponentName(this, NotificationLoggerService::class.java).flattenToString()
        return enabledServices.contains(myComponent)
    }

    /**
     * Check if AccessibilityService is enabled in system settings.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, 
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }
    
    /**
     * Check required storage permission.
     */
    private fun checkStoragePermission(): Boolean {
        val (hasStorage, _, _) = PermissionChecker.checkAllPermissions(this)
        
        if (!hasStorage) {
            Log.i(TAG, "Requesting MANAGE_EXTERNAL_STORAGE permission")
            Toast.makeText(this, "Please enable 'All files access' for Ghost", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
            return false
        }
        
        return true
    }
    
    /**
     * Validate model file exists.
     */
    private fun validateModel(): Boolean {
        val modelFile = GhostPaths.findModelFile() ?: File(GhostPaths.MODEL_PATH)
        
        if (!modelFile.exists()) {
            Log.e(TAG, "Model not found at ${modelFile.absolutePath}")
            Toast.makeText(
                this,
                "Model not found. Place .litertlm model in ${GhostPaths.getDisplayPath()}",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        
        Log.i(TAG, "Model found: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024} MB)")
        return true
    }
    
    /**
     * Take silent screenshot using AccessibilityService.
     * No dialog, no status bar indicator - completely silent.
     */
    private fun takeSilentScreenshot() {
        Log.i(TAG, "Taking silent screenshot via AccessibilityService")
        
        val service = GhostAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "AccessibilityService instance is null")
            Toast.makeText(this, "Service not ready, please try again", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        service.takeScreenshotForGhost { bitmap ->
            if (bitmap != null) {
                Log.i(TAG, "Screenshot captured successfully: ${bitmap.width}x${bitmap.height}")
                launchChatActivity(bitmap)
            } else {
                Log.e(TAG, "Failed to capture screenshot")
                Toast.makeText(this, "Failed to capture screen", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    /**
     * Launch ChatActivity with the captured screenshot.
     */
    private fun launchChatActivity(bitmap: Bitmap) {
        Log.i(TAG, "Launching ChatActivity")
        try {
            val intent = ChatActivity.createIntent(this, bitmap)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                          Intent.FLAG_ACTIVITY_CLEAR_TOP or
                          Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            Log.i(TAG, "ChatActivity launched successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ChatActivity", e)
            Toast.makeText(this, "Failed to open chat: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }
}
