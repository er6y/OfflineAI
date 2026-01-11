package com.example.offlineai.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.example.offlineai.LogManager

/**
 * Transparent Activity for requesting MediaProjection permission
 * Used by Agent to capture screenshots
 */
class MediaProjectionPermissionActivity : Activity() {
    
    companion object {
        private const val TAG = "MediaProjectionPermission"
        const val REQUEST_CODE = 1001
        
        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, MediaProjectionPermissionActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogManager.logI(TAG, "Requesting MediaProjection permission")
        
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                LogManager.logI(TAG, "MediaProjection permission granted")
                
                // Initialize ScreenshotCapture with permission
                AgentManager.getInstance(applicationContext)?.onMediaProjectionGranted(resultCode, data)
            } else {
                LogManager.logW(TAG, "MediaProjection permission denied")
                AgentManager.getInstance(applicationContext)?.onMediaProjectionDenied()
            }
            
            finish()
        }
    }
}
