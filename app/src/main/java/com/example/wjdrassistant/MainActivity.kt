package com.example.wjdrassistant

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wjdrassistant.ui.theme.WJDRAssistantTheme
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
        setContent {
            WJDRAssistantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AccessibilityControlScreen(
                        requestPermissions = { requestStoragePermissions() }
                    )
                }
            }
        }

        if (intent?.getBooleanExtra(EXTRA_REQUEST_MEDIA_PROJECTION, false) == true) {
            ensureMediaProjectionPermission {}
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilityControlScreen(
    requestPermissions: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var isServiceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var screenshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // 点击坐标
    var clickX by remember { mutableStateOf("") }
    var clickY by remember { mutableStateOf("") }
    
    // 滑动坐标
    var swipeStartX by remember { mutableStateOf("") }
    var swipeStartY by remember { mutableStateOf("") }
    var swipeEndX by remember { mutableStateOf("") }
    var swipeEndY by remember { mutableStateOf("") }
    
    // 长按坐标
    var longClickX by remember { mutableStateOf("") }
    var longClickY by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "无障碍控制助手",
            style = MaterialTheme.typography.headlineMedium
        )

        // 服务状态
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceEnabled) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isServiceEnabled) "服务已启用" else "服务未启用",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                ) {
                    Text("前往设置")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        isServiceEnabled = isAccessibilityServiceEnabled(context)
                    }
                ) {
                    Text("刷新状态")
                }
            }
        }

        if (!isServiceEnabled) {
            Text(
                text = "请先启用无障碍服务才能使用以下功能",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 截图功能
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "截图功能",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (isServiceEnabled) {
                            // 检查存储权限
                            if (activity?.hasStoragePermission() != true) {
                                Toast.makeText(context, "需要存储权限才能保存截图，正在请求权限...", Toast.LENGTH_SHORT).show()
                                requestPermissions()
                                return@Button
                            }
                            
                            val service = AccessibilityControlService.getInstance()
                            if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                Toast.makeText(context, "正在截图...", Toast.LENGTH_SHORT).show()
                                activity?.ensureMediaProjectionPermission {
                                    service.takeScreenshot(object : AccessibilityControlService.ScreenshotCallback {
                                        override fun onScreenshotTaken(bitmap: Bitmap?) {
                                            if (bitmap != null) {
                                                screenshotBitmap = bitmap
                                                // 保存截图到应用私有目录（不需要权限）
                                                val file = File(context.getExternalFilesDir(null), "screenshot_${System.currentTimeMillis()}.png")
                                                if (service.saveScreenshotToFile(bitmap, file.absolutePath)) {
                                                    Toast.makeText(context, "截图已保存: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "截图成功但保存失败", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "截图返回为空", Toast.LENGTH_SHORT).show()
                                            }
                                        }

                                        override fun onScreenshotError(error: String) {
                                            Toast.makeText(context, "截图失败: $error", Toast.LENGTH_SHORT).show()
                                        }
                                    })
                                }
                            } else {
                                Toast.makeText(context, "服务不可用或系统版本不支持", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = isServiceEnabled
                ) {
                    Text("截图")
                }
                if (screenshotBitmap != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "截图尺寸: ${screenshotBitmap!!.width} x ${screenshotBitmap!!.height}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // 点击功能
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "点击功能",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = clickX,
                        onValueChange = { clickX = it },
                        label = { Text("X坐标") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = clickY,
                        onValueChange = { clickY = it },
                        label = { Text("Y坐标") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (isServiceEnabled) {
                            val x = clickX.toFloatOrNull()
                            val y = clickY.toFloatOrNull()
                            if (x != null && y != null) {
                                val service = AccessibilityControlService.getInstance()
                                if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    service.click(x, y, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                                        override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                            Toast.makeText(context, "点击完成", Toast.LENGTH_SHORT).show()
                                        }

                                        override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                            Toast.makeText(context, "点击取消", Toast.LENGTH_SHORT).show()
                                        }
                                    })
                                } else {
                                    Toast.makeText(context, "服务不可用或系统版本不支持", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "请输入有效的坐标", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isServiceEnabled
                ) {
                    Text("执行点击")
                }
            }
        }

        // 长按功能
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "长按功能",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = longClickX,
                        onValueChange = { longClickX = it },
                        label = { Text("X坐标") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = longClickY,
                        onValueChange = { longClickY = it },
                        label = { Text("Y坐标") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (isServiceEnabled) {
                            val x = longClickX.toFloatOrNull()
                            val y = longClickY.toFloatOrNull()
                            if (x != null && y != null) {
                                val service = AccessibilityControlService.getInstance()
                                if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    service.longClick(x, y, 500, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                                        override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                            Toast.makeText(context, "长按完成", Toast.LENGTH_SHORT).show()
                                        }

                                        override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                            Toast.makeText(context, "长按取消", Toast.LENGTH_SHORT).show()
                                        }
                                    })
                                } else {
                                    Toast.makeText(context, "服务不可用或系统版本不支持", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "请输入有效的坐标", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isServiceEnabled
                ) {
                    Text("执行长按")
                }
            }
        }

        // 滑动功能
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "滑动功能",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "起始坐标",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = swipeStartX,
                        onValueChange = { swipeStartX = it },
                        label = { Text("X") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = swipeStartY,
                        onValueChange = { swipeStartY = it },
                        label = { Text("Y") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "结束坐标",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = swipeEndX,
                        onValueChange = { swipeEndX = it },
                        label = { Text("X") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = swipeEndY,
                        onValueChange = { swipeEndY = it },
                        label = { Text("Y") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (isServiceEnabled) {
                            val startX = swipeStartX.toFloatOrNull()
                            val startY = swipeStartY.toFloatOrNull()
                            val endX = swipeEndX.toFloatOrNull()
                            val endY = swipeEndY.toFloatOrNull()
                            if (startX != null && startY != null && endX != null && endY != null) {
                                val service = AccessibilityControlService.getInstance()
                                if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    service.swipe(startX, startY, endX, endY, 300, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                                        override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                            Toast.makeText(context, "滑动完成", Toast.LENGTH_SHORT).show()
                                        }

                                        override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                            Toast.makeText(context, "滑动取消", Toast.LENGTH_SHORT).show()
                                        }
                                    })
                                } else {
                                    Toast.makeText(context, "服务不可用或系统版本不支持", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "请输入有效的坐标", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isServiceEnabled
                ) {
                    Text("执行滑动")
                }
            }
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    
    for (service in enabledServices) {
        if (service.resolveInfo.serviceInfo.packageName == context.packageName) {
            return true
        }
    }
    return false
}