package com.example.wjdrassistant

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_REQUEST_MEDIA_PROJECTION = "extra_request_media_projection"
    }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要存储权限才能保存截图", Toast.LENGTH_LONG).show()
        }
    }

    private var pendingMediaProjectionAction: (() -> Unit)? = null
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(result.resultCode, data)
            AccessibilityControlService.setMediaProjection(projection)
            pendingMediaProjectionAction?.invoke()
        } else {
            Toast.makeText(this, "需要授予屏幕捕获权限才能截图/OCR", Toast.LENGTH_LONG).show()
        }
        pendingMediaProjectionAction = null
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val tvResult = findViewById<TextView>(R.id.tv_result)
        val btnGoAccessibility = findViewById<Button>(R.id.btn_go_accessibility)
        val btnStartAssistant = findViewById<Button>(R.id.btn_start_assistant)

        fun refreshStatus() {
            val enabled = isAccessibilityServiceEnabled(this)
            tvStatus.text = if (enabled) "服务已启用" else "服务未启用"
        }
        refreshStatus()

        btnGoAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStartAssistant.setOnClickListener {
            if (!isAccessibilityServiceEnabled(this)) {
                Toast.makeText(this, "请先在无障碍设置中启用助手服务", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            // 助手实际上由无障碍服务 + 浮窗实现，这里只做提示
            tvResult.text = "结果：助手已启动，可通过屏幕浮窗开始/暂停"
            launchApp("com.gof.china")
        }

        if (intent?.getBooleanExtra(EXTRA_REQUEST_MEDIA_PROJECTION, false) == true) {
            ensureMediaProjectionPermission {}
        }
    }

    fun launchApp(packageName: String) {
        val packageManager: PackageManager = this.packageManager
        var intent: Intent? = packageManager.getLaunchIntentForPackage(packageName)

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Application not found or cannot be launched.", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun requestStoragePermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要 READ_MEDIA_IMAGES
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            // Android 10-12 需要 WRITE_EXTERNAL_STORAGE
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun ensureMediaProjectionPermission(onReady: () -> Unit) {
        if (AccessibilityControlService.hasMediaProjection()) {
            onReady()
            return
        }
        // Android 14+ 要求在申请 MediaProjection 前必须有 mediaProjection 类型前台服务
        ScreenCaptureForegroundService.start(this)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        pendingMediaProjectionAction = onReady
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    for (service in enabledServices) {
        if (service.resolveInfo.serviceInfo.packageName == context.packageName) return true
    }
    return false
}