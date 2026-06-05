package lightly.monitor.control

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionController(private val activity: Activity) {
    fun missingRuntimePermissions(): Array<String> = requiredPermissions().filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    fun hasMediaPermissions(): Boolean = missingRuntimePermissions().isEmpty()
    fun requestMediaPermissions(requestCode: Int = REQUEST_MEDIA_PERMISSIONS) { val missing = missingRuntimePermissions(); if (missing.isNotEmpty()) ActivityCompat.requestPermissions(activity, missing, requestCode) }
    private fun requiredPermissions(): List<String> = buildList { add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT) }
    companion object { const val REQUEST_MEDIA_PERMISSIONS = 7301 }
}
