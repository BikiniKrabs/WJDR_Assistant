package com.example.wjdrassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
//import android.accessibilityservice.GestureResultCallback
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream

class AccessibilityControlService : AccessibilityService() {

    // 回调接口
    interface ScreenshotCallback {
        fun onScreenshotTaken(bitmap: Bitmap?)
        fun onScreenshotError(error: String)
    }

    companion object {
        private const val TAG = "AccessibilityControlService"
        private var instance: AccessibilityControlService? = null
        private var mediaProjection: MediaProjection? = null

        fun getInstance(): AccessibilityControlService? = instance

        fun setMediaProjection(projection: MediaProjection?) {
            mediaProjection = projection
        }

        fun hasMediaProjection(): Boolean = mediaProjection != null
    }

    private var screenshotCallback: ScreenshotCallback? = null
    private var displayMetrics: DisplayMetrics? = null

    // 浮窗相关
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var isRunning = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        displayMetrics = resources.displayMetrics

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingControls()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mediaProjection?.stop()
        mediaProjection = null

        removeFloatingControls()
        Log.d(TAG, "无障碍服务已销毁")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可以在这里处理无障碍事件
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
    }

    /**
     * 截图功能
     * 注意：某些系统不允许无障碍服务使用 takeScreenshot API
     * 因此直接使用 ImageReader + VirtualDisplay 方式，兼容性更好
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun takeScreenshot(callback: ScreenshotCallback) {
        this.screenshotCallback = callback
        
        // 直接使用 ImageReader 方式，因为某些系统不允许服务使用 takeScreenshot API
        // 如果需要尝试 takeScreenshot API，可以取消下面的注释
        /*
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                takeScreenshot(
                    getMainDisplayId(),
                    mainExecutor,
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val bitmap = Bitmap.wrapHardwareBuffer(
                                        screenshot.hardwareBuffer,
                                        screenshot.colorSpace
                                    )
                                    screenshot.hardwareBuffer.close()
                                    screenshotCallback?.onScreenshotTaken(bitmap)
                                } else {
                                    takeScreenshotWithImageReader()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "处理截图失败，回退到ImageReader方式", e)
                                takeScreenshotWithImageReader()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "takeScreenshot失败，错误代码: $errorCode，使用ImageReader方式")
                            takeScreenshotWithImageReader()
                        }
                    }
                )
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "takeScreenshot权限不足，使用ImageReader方式", e)
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot异常，使用ImageReader方式", e)
            }
        }
        */
        
        // 使用 ImageReader 方式
        takeScreenshotWithImageReader()
    }

    private fun showFloatingControls() {
        if (overlayView != null || windowManager == null) return

        val layout = LayoutInflater.from(this).inflate(R.layout.overlay_controls, null) as LinearLayout
        val btnStartPause = layout.findViewById<ImageButton>(R.id.btn_start_pause)
        val btnStop = layout.findViewById<ImageButton>(R.id.btn_stop)
        val btnSettings = layout.findViewById<ImageButton>(R.id.btn_settings)
        val btnMove = layout.findViewById<ImageButton>(R.id.btn_move)

        btnStartPause.setOnClickListener {
            startOneShotScreenshotSaveAndOcr(btnStartPause)
        }

        btnStop.setOnClickListener {
            isRunning = false
            btnStartPause.setImageResource(android.R.drawable.ic_media_play)
            removeFloatingControls()
        }

        btnSettings.setOnClickListener {
            try {
                val intent = android.content.Intent(this, MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "启动设置界面失败", e)
            }
        }

        // 第四个按钮：按住拖动浮窗
        var lastX = 0f
        var lastY = 0f
        var paramX = 0
        var paramY = 0
        btnMove.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    paramX = params.x
                    paramY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    params.x = paramX + dx
                    params.y = paramY + dy
                    windowManager?.updateViewLayout(layout, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        overlayParams = params
        overlayView = layout
        windowManager?.addView(layout, params)
    }

    private fun removeFloatingControls() {
        try {
            overlayView?.let { view -> windowManager?.removeView(view) }
        } catch (e: Exception) {
            Log.e(TAG, "移除浮窗失败", e)
        } finally {
            overlayView = null
            overlayParams = null
        }
    }

    /**
     * 点击开始：图标切换为暂停 -> 截图保存 -> 对指定区域 OCR -> 保存 txt
     */
    private fun startOneShotScreenshotSaveAndOcr(btnStartPause: ImageButton) {
        if (isRunning) return
        isRunning = true

        btnStartPause.post {
            btnStartPause.setImageResource(android.R.drawable.ic_media_pause)
            btnStartPause.invalidate()
        }

        if (!hasMediaProjection()) {
            // 引导去主界面授权
            try {
                val intent = android.content.Intent(this, MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_REQUEST_MEDIA_PROJECTION, true)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "启动主界面申请屏幕捕获权限失败", e)
            } finally {
                finishStart(btnStartPause)
            }
            return
        }

        takeScreenshot(object : ScreenshotCallback {
            override fun onScreenshotTaken(bitmap: Bitmap?) {
                if (bitmap == null) {
                    finishStart(btnStartPause)
                    return
                }

                val outDir = getExternalFilesDir(null) ?: filesDir
                val baseName = "overlay_${System.currentTimeMillis()}"
                val imgFile = File(outDir, "$baseName.png")
                saveScreenshotToFile(bitmap, imgFile.absolutePath)

                // OCR：默认识别上方 20% 高度、左右各留 10% 边距的区域
                val ocrTextFile = File(outDir, "$baseName.txt")
                val crop = cropForOcr(bitmap)
                val image = InputImage.fromBitmap(crop, 0)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        try {
                            ocrTextFile.writeText(result.text ?: "")
                        } catch (e: Exception) {
                            Log.e(TAG, "写入 OCR 文本失败", e)
                        } finally {
                            finishStart(btnStartPause)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "OCR 失败", e)
                        try {
                            ocrTextFile.writeText("")
                        } catch (_: Exception) {
                        } finally {
                            finishStart(btnStartPause)
                        }
                    }
            }

            override fun onScreenshotError(error: String) {
                finishStart(btnStartPause)
            }
        })
    }

    private fun finishStart(btnStartPause: ImageButton) {
        isRunning = false
        btnStartPause.postDelayed({
            btnStartPause.setImageResource(android.R.drawable.ic_media_play)
            btnStartPause.invalidate()
        }, 300)
    }

    private fun cropForOcr(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val left = (w * 0.10f).toInt().coerceIn(0, w - 1)
        val top = (h * 0.05f).toInt().coerceIn(0, h - 1)
        val right = (w * 0.90f).toInt().coerceIn(left + 1, w)
        val bottom = (h * 0.25f).toInt().coerceIn(top + 1, h)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun takeScreenshotWithImageReader() {
        val projection = mediaProjection
        if (projection == null) {
            screenshotCallback?.onScreenshotError("需要先授予屏幕捕获权限")
            return
        }
        var imageReader: ImageReader? = null
        var virtualDisplay: android.hardware.display.VirtualDisplay? = null
        
        try {
            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            if (display == null) {
                screenshotCallback?.onScreenshotError("无法获取显示器")
                return
            }
            
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)

            imageReader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                1
            )

            val surface = imageReader.surface

            // 使用 MediaProjection 创建虚拟显示（Android 14+ 必需）
            virtualDisplay = projection.createVirtualDisplay(
                "Screenshot_${System.currentTimeMillis()}",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )

            if (virtualDisplay == null) {
                screenshotCallback?.onScreenshotError("无法创建虚拟显示，可能需要系统权限")
                imageReader.close()
                return
            }

            var imageProcessed = false
            imageReader.setOnImageAvailableListener({ reader ->
                if (imageProcessed) return@setOnImageAvailableListener
                imageProcessed = true
                
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val bitmap = imageToBitmap(image)
                        image.close()
                        screenshotCallback?.onScreenshotTaken(bitmap)
                    } else {
                        screenshotCallback?.onScreenshotError("无法获取图像")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理图像失败", e)
                    screenshotCallback?.onScreenshotError(e.message ?: "处理图像失败")
                } finally {
                    try {
                        virtualDisplay?.release()
                        imageReader?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "清理资源失败", e)
                    }
                }
            }, null)

            // 等待虚拟显示创建并捕获图像
            // 使用 Handler 延迟，避免阻塞
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!imageProcessed) {
                    imageProcessed = true
                    screenshotCallback?.onScreenshotError("截图超时")
                    try {
                        virtualDisplay?.release()
                        imageReader?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "清理资源失败", e)
                    }
                }
            }, 2000) // 2秒超时
            
        } catch (e: SecurityException) {
            Log.e(TAG, "截图权限不足", e)
            screenshotCallback?.onScreenshotError("截图权限不足: ${e.message}")
            try {
                virtualDisplay?.release()
                imageReader?.close()
            } catch (ex: Exception) {
                Log.e(TAG, "清理资源失败", ex)
            }
        } catch (e: Exception) {
            Log.e(TAG, "截图异常", e)
            screenshotCallback?.onScreenshotError("截图失败: ${e.message}")
            try {
                virtualDisplay?.release()
                imageReader?.close()
            } catch (ex: Exception) {
                Log.e(TAG, "清理资源失败", ex)
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    /**
     * 点击功能
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun click(x: Float, y: Float, callback: AccessibilityService.GestureResultCallback? = null) {
        val path = android.graphics.Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, callback, null)
    }

    /**
     * 长按功能
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun longClick(x: Float, y: Float, duration: Long = 500, callback: AccessibilityService.GestureResultCallback? = null) {
        val path = android.graphics.Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        dispatchGesture(gesture, callback, null)
    }

    /**
     * 滑动功能
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 300,
        callback: AccessibilityService.GestureResultCallback? = null
    ) {
        val path = android.graphics.Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        dispatchGesture(gesture, callback, null)
    }

    /**
     * 根据文本查找节点并点击
     */
    fun clickByText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        
        if (nodes.isNotEmpty()) {
            val node = nodes[0]
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            } else {
                // 如果节点不可点击，尝试点击其父节点
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    parent = parent.parent
                }
            }
        }
        return false
    }

    /**
     * 根据ID查找节点并点击
     */
    fun clickById(viewId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        
        if (nodes.isNotEmpty()) {
            val node = nodes[0]
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    /**
     * 保存截图到文件
     */
    fun saveScreenshotToFile(bitmap: Bitmap, filePath: String): Boolean {
        return try {
            val file = File(filePath)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存截图失败", e)
            false
        }
    }

    /**
     * 获取主显示器ID
     * 注意：AccessibilityService 的 Context 不是视觉 Context，不能直接获取 display
     * 使用 DisplayManager 获取主显示器
     */
    private fun getMainDisplayId(): Int {
        return try {
            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && defaultDisplay != null) {
                defaultDisplay.displayId
            } else {
                Display.DEFAULT_DISPLAY
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取显示器ID失败", e)
            Display.DEFAULT_DISPLAY
        }
    }
}
